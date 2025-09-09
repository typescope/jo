package pickle

import ast.Positions.*
import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import typing.Namer.DelayedDef
import reporting.Reporter

import scala.collection.mutable

/** Decode trees, symbols and types
  *
  * This is the counterpart to Encoder.scala, reading back the encoded format
  * to reconstruct SAST trees.
  */
object Decoder:
  class NameRef(val ownerIndex: Int, val name: String, val kind: Int)

  private class State(val root: Symbol, nameRefs: Array[NameRef]):
    /** External symbols loaded from the name table */
    private var externalSymbols: Array[Symbol] = new Array(nameRefs.size)

    /** Map from internal symbol IDs to symbols */
    private val internalSymbols = mutable.Map.empty[Int, Symbol]

    def getExternalSymbol(index: Int)(using defn: Definitions): Symbol =
      if index < 0 || index >= externalSymbols.length then
        throw new Exception(s"Invalid external symbol index: $index")

      var sym = externalSymbols(index)
      if sym == null then
        val nameRef = nameRefs(index)
        val fullNameParts = getFullNameParts(index)
        sym = nameRef.kind match
          case Format.Term => defn.resolveTermByPathParts(fullNameParts)
          case Format.Pattern => defn.resolvePatternByPathParts(fullNameParts)
          case Format.Type => defn.resolveTypeByPathParts(fullNameParts)

        externalSymbols(index) = sym
      end
      sym

    def getFullNameParts(index: Int): List[String] =
      val nameRef = nameRefs(index)
      if nameRef.ownerIndex == -1 then
        nameRef.name :: Nil
      else
        getFullNameParts(nameRef.ownerIndex) :: nameRef.name

    def registerInternalSymbol(id: Int, symbol: Symbol): Unit =
      internalSymbols(id) = symbol

    def getInternalSymbol(id: Int): Symbol =
      internalSymbols.get(id) match
        case Some(sym) => sym
        case None => throw new Exception(s"Unknown internal symbol id: $id")

  end State

  def error(message: String): Nothing = Reporter.abortInternal(message)

  def error(message: String, pos: SourcePosition): Nothing = Reporter.abort(message, pos)

  //----------------------------------------------------------------------------

  def decode()(using buf: ReadBuffer, defnLazy: Definitions.Lazy): DelayedDef[Namespace] =
    val nameIndex = decodeNat()
    val source = decodeSource()
    val symSpan = Span(decodeNat(), decodeNat())

    // Read external name table
    val nameTableAddr: Int = decodeIntRaw()
    val nameRefs: Array[NameRef] = buf.withPosition(nameTableAddr):
      decodeExternalNameTable()

    given Source = source

    val rootSymbol: Symbol = resolveNamespace(
      state.getFullNameParts(nameIndex),
      symSpan.toPos,
      isBranch = false
    )

    given state: State = new State(rootSymbol, nameRefs)

    val delayedDefs: Array[DelayedDef[Def]] = repeated(decodeDef(rootSymbol))

    val span = Span(decodeNat(), decodeNat())

    // Add symbols to name table
    val nameTable = defnLazy.infoProvider.info(rootSymbol).as[ContainerInfo].nameTable
    for delayedDef <- delayedDefs do nameTable.define(delayedDef.symbol)

    val delayed = () =>
      val members = new Array[Def](delayedDefs.length)
      var i = 0

      while i < delayedDefs.length do
        val member = delayedDefs(i).force()
        members(i) = member
        i += 1
      end while

      Namespace(rootSymbol, List.empty, members)(span)

    DelayedDef(rootSymbol, delayed)


  /** Resolve namespace and create intermediate namespace on demand
    *
    * It also checks redefinition of namespace.
    */
  def resolveNamespace(
      parts: List[String], pos: SourcePosition, rootNameTable: NameTable,
      infoProvider: InfoProvider, isBranch: Boolean)
  : Symbol =

    def check(sym: Symbol): Symbol =
      val name = sym.name
      val pos = sym.sourcePos
      val context = s"Context: loading ${pos.source.file}"

      if sym.isNamespace && !sym.isAlias then
        if isBranch && !sym.is(Flags.Branch) then
          error(s"The $name is already defined as a namespace at $pos. $context")

        else if !isBranch then
          // leaf namespace should not exist
          if sym.is(Flags.Branch) then
            error(s"The namespace $name is already defined as a branch name at $pos. $context")

          else
            error(s"The namespace $name is already defined at $pos. $context")

        else
          sym

      else
        error(s"The $name is already defined as a member at $pos. $context")

    parts match
      case name :: Nil =>
        rootNameTable.resolveTerm(name) match
          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = Symbol.createSymbol(name, flags, pos)
            rootNameTable.define(sym)
            infoProvider.add(sym, owner = null, new ContainerInfo(new NameTable))
            sym

          case Some(sym) => check(sym)

      case prefix :+ name =>
        val nsSym = resolveNamespace(prefix, pos, rootNameTable, infoProvider, isBranch = true)

        assert(nsSym.isNamespace, "Not a namespace " + nsSym)
        val nameTable = infoProvider.info(nsSym).as[ContainerInfo].nameTable

        nameTable.resolveTerm(name) match
          case Some(sym) => check(sym)

          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = Symbol.createSymbol(name, flags, pos)
            infoProvider.add(sym, nsSym, new ContainerInfo(new NameTable))
            nameTable.define(sym)
            sym

  /** Decode a definition and return delayed definition
    *
    * Invariant:
    *
    * The buffer position for decodeXXX should be at the end of its definition
    * after the call.
    */
  private def decodeDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[Def] =
    val defType = decodeByte()

    defType match
      case Format.ParamDef => decodeParamDef(owner)
      case Format.FunDef => decodeFunDef(owner, Flags.Empty)
      case Format.ClassDef => decodeClassDef(owner)
      case Format.TypeDef => decodeTypeDef(owner)
      case Format.PatDef => decodePatDef(owner)
      case Format.Section => decodeSection(owner)
      case _ => throw new Exception(s"Unknown definition type in decodeDef: $defType")

  private def decodeValDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions, state: State): ValDef =
    given Source = owner.sourcePos.source

    val absoluteStart = decodeNat()
    val id = decodeNat()
    val name = decodeString()
    val flags = decodeFlags()

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    val info = decodeType()
    val symbol = Symbol.createSymbol(name, info, flags, owner, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    val rhs = decodeWord(owner, absoluteStart)
    val span = Span(absoluteStart, rhs.span.endOffset - absoluteStart)
    ValDef(symbol, rhs)(span)

  private def decodeParamDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[ParamDef] =
    given Source = owner.sourcePos.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()
    val flags = decodeFlags() | Flags.Context

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    val symbol = Symbol.createSymbol(name, flags, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    val typeStartPos = buf.position
    val delayed = () =>
      given ReadBuffer = buf.fresh(typeStartPos)
      val tpt = decodeTypeTree(absoluteStart)
      val span = Span(absoluteStart, tpt.span.endOffset - absoluteStart)
      ParamDef(symbol, tpt)(span)

    val paramDef = DelayedDef(symbol, delayed)

    // Supply type for symbol
    defnLazy.infoProvider.addLazy(symbol, owner, () => paramDef.force().tpt.tpe)

    // Set buffer position at end
    buf.setPosition(pos + length)

    paramDef

  private def decodeFunDef(owner: Symbol, initFlags: Flags)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[FunDef] =
    given Source = owner.sourcePos.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()
    val flags = decodeFlags() | initFlags | Flags.Fun

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    val symbol = Symbol.createSymbol(name, flags, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    given Definitions = defnLazy.value

    // Read signature lazily
    val tparamsStartPos = buf.position
    object sig:
      given sigBuf: ReadBuffer = buf.fresh(tparamStartPos)

      // Decode type parameters
      val tparams = repeated:
        val tparamId = decodeNat()
        val tparamName = decodeString()

        val tparamStartDelta = decodeInt()
        val tparamSpanLength = decodeNat()
        val tparamSpan = Span(absoluteStart + tparamStartDelta, tparamSpanLength)

        // TODO: eager decoding excludes F-bounds
        val kind = decodeKind()
        val tparamInfo = decodeType()

        val tparam = TypeSymbol.createSymbol(kind, tparamName, tparamInfo, Flags.Param, symbol, tparamSpan.toPos)
        state.registerInternalSymbol(tparamId, tparam)
        tparam

      // Decode regular term parameters
      val params = repeated:
        val paramId = decodeNat()
        val paramName = decodeString()

        val paramStartDelta = decodeInt()
        val paramSpanLength = decodeNat()
        val paramSpan = Span(absoluteStart + paramStartDelta, paramSpanLength)

        val paramInfo = decodeType()

        val param = Symbol.createSymbol(paramName, paramInfo, Flags.Param, symbol, paramSpan.toPos)
        state.registerInternalSymbol(paramId, param)
        param

      // Decode auto parameters
      val autos = repeated:
        val autoId = decodeNat()
        val autoName = decodeString()

        val autoStartDelta = decodeInt()
        val autoSpanLength = decodeNat()
        val autoSpan = Span(absoluteStart + autoStartDelta, autoSpanLength)

        val autoInfo = decodeType()

        val auto = Symbol.createSymbol(autoName, autoInfo, Flags.Param | Flags.Auto, symbol, autoSpan.toPos)
        state.registerInternalSymbol(autoId, auto)
        auto


      val resultType = decodeTypeTree(absoluteStart)

      val receives = repeated(decodeSymbolRef())

      val preParamCount = decodeNat()

      val signatureEndPos = sigBuf.position
    end sig

    // Add symbol info
    lazy val funInfo: ProcType =
      val receives = sig.receives
      ProcType(
        sig.tparams, sig.params.map(_.toNamedInfo), sig.autos.map(_.toNamedInfo),
        sig.resultType.tpe, () => receives, sig.preParamCount)

    ip.addLazy(symbol, owner,  () => funInfo)


    val delayedFun = () =>
      given ReadBuffer = buf.fresh(sig.signatureEndPos)

      val body = decodeWord(symbol, absoluteStart)
      val span = Span(absoluteStart, body.span.endOffset - absoluteStart)
      FunDef(symbol, sig.tparams, sig.params, sig.autos, sig.resultType, body)(span)

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, delayedFun)

  private def decodeClassDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[ClassDef] =
    given Source = owner.sourcePos.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()
    val treeLength = decodeNat()

    val id = decodeNat()
    val name = decodeString()

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    // Create and register symbol immediately
    val symbol = Symbol.createSymbol(name, Flags.Class, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    given Definitions = defnLazy.value

    // Read class content lazily
    val contentStartPos = buf.position
    object content:
      given ReadBuffer = buf.fresh(contentStartPos)

      // Decode type parameters
      val tparams = repeated:
        val tparamId = decodeNat()
        val tparamName = decodeString()


        val tparamStartDelta = decodeInt()
        val tparamLength = decodeNat()
        val tparamSpan = Span(absoluteStart + tparamStartDelta, tparamLength)

        // TODO: eager reading info excludes F-bounds
        val tparamKind = decodeKind()
        val tparamInfo = decodeType()

        val tparam = TypeSymbol.createSymbol(tparamKind, tparamName, tparamInfo, Flags.Param, symbol, tparamSpan.toPos)
        state.registerInternalSymbol(tparamId, tparam)
        tparam

      // Decode self symbol
      val selfId = decodeNat()
      val selfName = decodeString()
      val selfFlags = decodeFlags()
      val self = Symbol.createSymbol(selfName, selfFlags, owner.sourcePos)
      state.registerInternalSymbol(selfId, self)

      // Decode val members
      val vals = repeated:
        val valId = decodeNat()
        val valName = decodeString()
        val valFlags = decodeFlags() | Flags.Field

        val valStartDelta = decodeInt()
        val valLength = decodeNat()
        val valSpan = Span(absoluteStart + valStartDelta, valLength)

        val valType = decodeType()

        val valSym = Symbol.createSymbol(valName, valType, valFlags, symbol, valSpan.toPos)
        state.registerInternalSymbol(valId, valSym)
        valSym

      // Decode function definitions as DelayedDef
      val delayedFuns = repeated(decodeFunDef(symbol, Flags.Method))

      // Add type for self
      val selfInfo =
        val classRef = StaticRef(symbol)
        if tparams.isEmpty then classRef
        else AppliedType(classRef, tparamSyms.map(StaticRef.apply))

      ip.add(self, symbol, selfInfo)

      val classInfo = ClassInfo(
        symbol,
        tparams,
        tparams.map(StaticRef.apply),
        self,
        vals,
        delayedFuns.map(_.symbol)
      )
    end content

    defnLazy.infoProvider.addLazy(symbol, owner, () => content.classInfo)

    val delayed = () =>
      val forcedFuns = content.funs.map(_.force())
      val span = Span(absoluteStart, treeLength)
      ClassDef(symbol, content.tparams, content.self, content.vals, forcedFuns)(span)

    // Set buffer position at end
    buf.setPosition(pos + length)
    DelayedDef(symbol, delayed)

  private def decodeTypeDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[TypeDef] =
    given Source = owner.sourcePos.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()
    val treeLength = decodeNat()
    val id = decodeNat()
    val name = decodeString()

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    // Create symbol immediately but delay type reading
    val symbol = new TypeSymbol(Kind.Simple, name, Flags.Type, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    given Definitions = defnLazy.value

    // Read type and def length lazily
    val typeStartPos = buf.position
    lazy val info =
      given ReadBuffer = buf.fresh(typeStartPos)
      decodeType()

    // Add symbol info lazily
    defnLazy.infoProvider.addLazy(symbol, owner, () => typeInfo.symbolType)

    val delayed = () =>
      val actualSpan = Span(absoluteStart, treeLength)
      TypeDef(symbol)(actualSpan)

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, delayed)

  private def decodePatDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[PatDef] =
    given Source = owner.sourcePos.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    val symbol = Symbol.createSymbol(name, Flags.Pattern, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    given Definitions = defnLazy.value

    // Read signature lazily
    val tparamsStartPos = buf.position
    object sig:
      given sigBuf: ReadBuffer = buf.fresh(tparamsStartPos)

      // Decode type parameters
      val tparams = repeated:
        val tparamId = decodeNat()
        val tparamName = decodeString()
        val kind = decodeKind()

        val tparamStartDelta = decodeInt()
        val tparamSpanLength = decodeNat()
        val tparamSpan = Span(absoluteStart + tparamStartDelta, tparamSpanLength)

        // TODO: eager reading info excludes F-bounds
        val tparamInfo = decodeType()

        val tparam = TypeSymbol.createSymbol(kind, tparamName, tparamInfo, Flags.Param, symbol, tparamSpan.toPos)
        state.registerInternalSymbol(tparamId, tparam)
        tparam

      // Decode regular term parameters
      val params = repeated:
        val paramId = decodeNat()
        val paramName = decodeString()

        val paramStartDelta = decodeInt()
        val paramSpanLength = decodeNat()
        val paramSpan = Span(absoluteStart + paramStartDelta, paramSpanLength)

        val paramInfo = decodeType()

        val param = Symbol.createSymbol(paramName, paramInfo, Flags.Param | Flags.Pattern, symbol, paramSpan.toPos)
        state.registerInternalSymbol(paramId, param)
        param

      val resultType = decodeTypeTree(absoluteStart)
      val receives = repeated(decodeSymbolRef())
      val preParamCount = decodeNat()
      val signatureEndPos = sigBuf.position
    end sig

    // Add symbol info
    lazy val patInfo: ProcType =
      val receives = sig.receives
      ProcType(
        sig.tparams, sig.params.map(_.toNamedInfo), List.empty,
        sig.resultType.tpe, () => receives, sig.preParamCount)

    defnLazy.infoProvider.addLazy(symbol, owner, () => patInfo)

    val delayedPatDf = () =>
      given ReadBuffer = buf.fresh(sig.signatureEndPos)
      val body = decodePattern(symbol, sig.resultType.span.endOffset)
      val span = Span(absoluteStart, body.span.endOffset - absoluteStart)
      PatDef(symbol, sig.tparams, sig.params, sig.resultType, body)(span)

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, delayedPatDef)

  private def decodeSection(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[Section] =
    given Source = owner.sourcePos.source

    // length is redundant --- keep it for extension and uniformity
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()
    val treeLength = decodeNat()

    val id = decodeNat()
    val name = decodeString()

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    // Create and register symbol immediately
    val symbol = Symbol.createSymbol(name, Flags.Section, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    // Decode nested definitions as DelayedDef
    val nestedDelayedDefs = repeated(decodeDef(symbol))

    // Provide symbol info
    val nameTable = new NameTable()
    val info = new ContainerInfo(nameTable)
    for delayedDef <- delayedDefs do nameTable.define(delayedDef.symbol)
    defnLazy.infoProvider.add(symbol, owner, info)

    val delayed = () =>
      val nestedDefs = nestedDelayedDefs.map(_.force())
      val span = Span(absoluteStart, treeLength)
      Section(symbol, nestedDefs)(span)

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, delayed)

  //----------------------------------------------------------------------------

  private def decodeExternalNameTable()(using buf: ReadBuffer, defn: Definitions): Array[NameRef] =
    val count = decodeNat()
    val nameRefs = new Array[NameRef](count)

    var i = 0
    while i < count do
      val ownerIndex = decodeInt()
      val name = decodeString()
      val kind = decodeByte()

      nameRefs(i) = new NameRef(ownerIndex, name, kind)
      i += 1

    nameRefs

  private def decodeSymbolRef()(using buf: ReadBuffer, defn: Definitions, state: State): Symbol =
    val refType = decodeByte()
    refType match
      case 0 => // Internal symbol
        val id = decodeNat()
        state.getInternalSymbol(id)

      case 1 => // External symbol
        val index = decodeNat()
        state.getExternalSymbol(index)

      case _ => throw new Exception(s"Invalid symbol reference type: $refType")

  private def decodeFlags()(using buf: ReadBuffer): Flags =
    val bits = decodeLongNat()
    Flags.fromBits(bits)

  private def decodeKind()(using buf: ReadBuffer, defn: Definitions, state: State): Kind =
    val kindType = decodeByte()
    kindType match
      case Format.SimpleKind => Kind.Simple

      case Format.ArrowKind => // Arrow kind
        val args = repeated(decodeKind())
        val to = decodeKind()
        Kind.Arrow(args, to)

      case _ => throw new Exception(s"Invalid kind type: $kindType")


  private def decodeTypeTree(prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): TypeTree =
    val tpe = decodeType()
    val startDelta = decodeInt()
    val length = decodeNat()
    val currentOffset = prevOffset + startDelta
    val span = Span(currentOffset, length)

    TypeTree(tpe)(span)

  private def decodeType()(using buf: ReadBuffer, defn: Definitions, state: State): Type =
    val typeTag = decodeByte()
    typeTag match
      case Format.VoidType => VoidType
      case Format.ErrorType => ErrorType
      case Format.AnyType => AnyType
      case Format.BottomType => BottomType

      case Format.StaticRef =>
        val sym = decodeSymbolRef
        StaticRef(sym)

      case Format.MemberRef =>
        val prefix = decodeType()
        val sym = decodeSymbolRef
        MemberRef(prefix, sym)

      case Format.ConstantType =>
        val const = decodeConstant()
        ConstantType(const)

      case Format.RecordType =>
        val fields = repeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)
        RecordType(fields)

      case Format.UnionType =>
        val branches = repeated(decodeType)
        UnionType(branches)

      case Format.TagType =>
        val tag = decodeString()
        val params = repeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)
        TagType(tag, params)

      case Format.ObjectType =>
        val memberCount = decodeNat()
        val members = mutable.ListBuffer.empty[NamedInfo[Type]]
        val muts = mutable.Set.empty[String]

        for _ <- 0 until memberCount do
          val name = decodeString()
          val info = decodeType()
          members += NamedInfo(name, info)
          if info.isValueType then
            val isMutable = decodeBool()
            if isMutable then muts += name

        ObjectType(members.toList, muts.toSet)

      case Format.AppliedType =>
        val tctor = decodeType()
        val targs = repeated(decodeType)
        AppliedType(tctor, targs)

      case Format.ProcType =>
        val tparams = repeated(buf): _ =>
          val id = decodeNat()
          val name = decodeString()
          val info = decodeType()
          val tparam = new TypeSymbol(name, Kind.Simple)
          tparam.setInfo(info)
          state.registerInternalSymbol(id, tparam)
          tparam

        val params = repeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)

        val autos = repeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)

        val resType = decodeType()
        val receives = repeated(decodeSymbolRef)
        val preParamCount = decodeNat()

        ProcType(tparams, params, autos, resType, receives, preParamCount)

      case Format.TypeLambda =>
        val tparams = repeated(buf): _ =>
          val id = decodeNat()
          val name = decodeString()
          val info = decodeType()
          val tparam = new TypeSymbol(name, Kind.Simple)
          tparam.setInfo(info)
          state.registerInternalSymbol(id, tparam)
          tparam

        val resType = decodeType()
        val preParamCount = decodeNat()
        TypeLambda(tparams, resType, preParamCount)

      case Format.TypeBound =>
        val lo = decodeType()
        val hi = decodeType()
        TypeBound(lo, hi)

      case _ => throw new Exception(s"Unknown type tag: $typeTag")

  private def decodeWord(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Word =
    given Source = owner.sourcePos.source
    val wordTag = decodeByte()

    wordTag match
        case Format.Literal =>
          val const = decodeConstant()
          val tpe = decodeType()
          val startDelta = decodeInt()
          val length = decodeNat()
          val currentOffset = prevOffset + startDelta
          val span = Span(currentOffset, length)
          Literal(const)(tpe, span)

        case Format.Ident =>
          val sym = decodeSymbolRef
          val startDelta = decodeInt()
          val length = decodeNat()
          val currentOffset = prevOffset + startDelta
          val span = Span(currentOffset, length)
          Ident(sym)(span)

        case Format.New =>
          val classRef = decodeWord(owner, prevOffset)
          val targs = repeated(decodeTypeTree(prevOffset))
          // Use classRef span as approximation - encoder doesn't store position for New
          New(classRef, targs)(classRef.span)

        case Format.Select =>
          val qual = decodeWord(owner, prevOffset)
          val name = decodeString()
          val suffixLength = decodeNat() // encoder: encodeNat(word.span.length - qual.span.length)
          val tpe = qual.tpe // This would need proper type reconstruction
          val span = Span(qual.span.start, qual.span.length + suffixLength)
          Select(qual, name)(tpe, span)

        case Format.RecordLit =>
          val startDelta = decodeInt()
          val currentOffset = prevOffset + startDelta
          var lastOffset = prevOffset
          val fields = repeated(buf): _ =>
            val fieldName = decodeString()
            val rhs = decodeWord(owner, lastOffset)
            lastOffset = rhs.span.endOffset
            (fieldName, rhs)
          val tpe = RecordType(fields.map((n, w) => NamedInfo(n, w.tpe)))
          val span = Span(currentOffset, lastOffset - currentOffset)
          RecordLit(fields)(tpe, span)

        case Format.TaggedLit =>
          val startDelta = decodeInt()
          val currentOffset = prevOffset + startDelta
          val tag = decodeWord(owner, prevOffset)
          var lastOffset = tag.span.endOffset
          val args = repeated: _ =>
            val arg = decodeWord(owner, lastOffset)
            lastOffset = arg.span.endOffset
            arg
          val tagName = tag match
            case Literal(Constant.String(name)) => name
            case _ => throw new Exception("Expected string literal for tag")
          val tpe = TagType(tagName, args.map(a => NamedInfo("", a.tpe)))
          val span = Span(currentOffset, lastOffset - currentOffset)
          TaggedLit(tag.asInstanceOf[Literal], args)(tpe, span)

        case Format.Encoded =>
          val repr = decodeWord(owner, prevOffset)
          val tpe = decodeType()
          // Use repr span as approximation - encoder doesn't store position for Encoded
          Encoded(repr)(tpe, repr.span)

        case Format.Apply =>
          val fun = decodeWord(owner, prevOffset)
          var lastOffset = fun.span.endOffset
          val args = repeated: _ =>
            val arg = decodeWord(owner, lastOffset)
            lastOffset = arg.span.endOffset
            arg
          val autos = repeated: _ =>
            val auto = decodeWord(owner, lastOffset)
            lastOffset = auto.span.endOffset
            auto
          // Type would need to be computed from function type
          val tpe = VoidType // Placeholder
          val span = Span(fun.span.start, lastOffset - fun.span.start)
          Apply(fun, args, autos)(tpe, span)

        case Format.TypeApply =>
          val fun = decodeWord(owner, prevOffset)
          val targs = repeated(decodeTypeTree(prevOffset))
          // Use fun span as approximation - encoder doesn't store position for TypeApply
          TypeApply(fun, targs)(fun.span)

        case Format.With =>
          val expr = decodeWord(owner, absoluteStart)
          val args = repeated(buf): _ =>
            val ident = decodeWord(owner, absoluteStart)
            val rhs = decodeWord(owner, absoluteStart)
            Assign(ident, rhs)(VoidType, span)
          With(expr, args)(expr.tpe, span)

        case Format.Allow =>
          val expr = decodeWord(owner, absoluteStart)
          val params = repeated(decodeWord(owner, absoluteStart))
          Allow(expr, params)(expr.tpe, span)

        case Format.Assign =>
          val ident = decodeWord(owner, absoluteStart)
          val rhs = decodeWord(owner, absoluteStart)
          Assign(ident, rhs)(VoidType, span)

        case Format.FieldAssign =>
          val lhs = decodeWord(owner, absoluteStart)
          val rhs = decodeWord(owner, absoluteStart)
          FieldAssign(lhs, rhs)(VoidType, span)

        case Format.ValDef =>
          decodeValDef(owner)

        case Format.FunDef =>
          decodeFunDef(owner, Flags.Empty).force()

        case Format.TypeDef =>
          decodeTypeDef(owner).force()

        case Format.PatDef =>
          decodePatDef(owner).force()

        case Format.If =>
          val startDelta = decodeInt()
          val currentOffset = prevOffset + startDelta
          val cond = decodeWord(owner, prevOffset)
          val thenp = decodeWord(owner, cond.span.endOffset)
          val elsep = decodeWord(owner, thenp.span.endOffset)
          val tpe = decodeType()
          val span = Span(currentOffset, elsep.span.endOffset - currentOffset)
          If(cond, thenp, elsep)(tpe, span)

        case Format.While =>
          val startDelta = decodeInt()
          val currentOffset = prevOffset + startDelta
          val cond = decodeWord(owner, prevOffset)
          val body = decodeWord(owner, cond.span.endOffset)
          val span = Span(currentOffset, body.span.endOffset - currentOffset)
          While(cond, body)(VoidType, span)

        case Format.Block =>
          val words = repeated(decodeWord)
          val tpe = if words.nonEmpty then words.last.tpe else VoidType
          Block(words)(tpe, span)

        case Format.Match =>
          val scrutinee = decodeWord(owner, absoluteStart)
          val cases = repeated(buf): _ =>
            val pat = decodePattern(owner, absoluteStart)
            val body = decodeWord(owner, absoluteStart)
            Case(pat, body)
          val tpe = decodeType()
          Match(scrutinee, cases)(tpe, span)

        case Format.Object =>
          val selfId = decodeNat()
          val selfName = decodeString()
          val self = new Symbol(selfName, Flags.Empty)
          state.registerInternalSymbol(selfId, self)

          val members = repeated(decodeDef(self)).map:
            case v: ValDef => v
            case f: FunDef => f
            case _ => throw new Exception("Object can only contain val and fun definitions")

          Object(self, members)(span)

        case _ => throw new Exception(s"Unknown word tag: $wordTag")

  private def decodePattern(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Pattern =
    given Source = owner.sourcePos.source
    val patternTag = decodeByte()

    patternTag match
        case Format.AliasPattern =>
          val id = decodeWord(owner, prevOffset)
          val nested = decodePattern(owner, id.span.endOffset)
          val span = Span(id.span.start, nested.span.endOffset - id.span.start)
          AliasPattern(id, nested)(span)

        case Format.TypePattern =>
          val scrutineeType = decodeType()
          val startDelta = decodeInt()
          val currentOffset = prevOffset + startDelta
          val tpt = decodeTypeTree(prevOffset)
          val span = Span(currentOffset, tpt.span.endOffset - currentOffset)
          TypePattern(tpt)(scrutineeType, span)

        case Format.TagPattern =>
          val tagLit = decodeWord(owner, prevOffset)
          var lastOffset = tagLit.span.endOffset
          val nested = repeated: _ =>
            val pat = decodePattern(owner, lastOffset)
            lastOffset = pat.span.endOffset
            pat
          val span = Span(tagLit.span.start, lastOffset - tagLit.span.start)
          TagPattern(tagLit, nested)(span)

        case Format.ApplyPattern =>
          val scrutineeType = decodeType()
          val fun = decodeWord(owner, prevOffset)
          var lastOffset = fun.span.endOffset
          val nested = repeated: _ =>
            val pat = decodePattern(owner, lastOffset)
            lastOffset = pat.span.endOffset
            pat
          val span = Span(fun.span.start, lastOffset - fun.span.start)
          ApplyPattern(fun, nested)(scrutineeType, span)

        case Format.OrPattern =>
          val lhs = decodePattern(owner, prevOffset)
          val rhs = decodePattern(owner, lhs.span.endOffset)
          val span = Span(lhs.span.start, rhs.span.endOffset - lhs.span.start)
          OrPattern(lhs, rhs)(span)

        case Format.ValuePattern =>
          val scrutineeType = decodeType()
          val startDelta = decodeInt()
          val currentOffset = prevOffset + startDelta
          val value = decodeWord(owner, prevOffset)
          val span = Span(currentOffset, value.span.endOffset - currentOffset)
          ValuePattern(value)(scrutineeType, span)

        case Format.GuardPattern =>
          val pattern = decodePattern(owner, prevOffset)
          val guard = decodeWord(owner, pattern.span.endOffset)
          val span = Span(pattern.span.start, guard.span.endOffset - pattern.span.start)
          GuardPattern(pattern, guard)(span)

        case Format.BindPattern =>
          val pattern = decodePattern(owner, prevOffset)
          var lastOffset = pattern.span.endOffset
          val bindings = repeated: _ =>
            val binding = decodeWord(owner, lastOffset)
            lastOffset = binding.span.endOffset
            binding
          val span = Span(pattern.span.start, lastOffset - pattern.span.start)
          BindPattern(pattern, bindings)(span)

        case Format.SeqPattern =>
          val scrutineeType = decodeType()
          val startDelta = decodeInt()
          val currentOffset = prevOffset + startDelta
          var lastOffset = prevOffset
          val pats = repeated(buf): _ =>
            val seqPatTag = decodeByte()
            seqPatTag match
              case Format.AtomPattern =>
                val pattern = decodePattern(owner, lastOffset)
                lastOffset = pattern.span.endOffset
                AtomPattern(pattern)

              case Format.SkipToPattern =>
                val pattern = decodePattern(owner, lastOffset)
                lastOffset = pattern.span.endOffset
                SkipToPattern(pattern)

              case Format.StarPattern =>
                val pattern = decodePattern(owner, lastOffset)
                val bindings = repeated(buf): _ =>
                  val id = decodeNat()
                  val name = decodeString()
                  val info = decodeType()
                  val sym1 = Symbol.createSymbol(name, Flags.Empty, owner.sourcePos)
                  sym1.setInfo(info)
                  state.registerInternalSymbol(id, sym1)
                  val sym2 = decodeSymbolRef
                  (sym1, sym2)
                lastOffset = pattern.span.endOffset
                StarPattern(pattern, bindings)

              case Format.RestPattern =>
                val pattern = decodePattern(owner, lastOffset)
                lastOffset = pattern.span.endOffset
                RestPattern(pattern)

              case _ => throw new Exception(s"Unknown sequence pattern tag: $seqPatTag")

          val span = Span(currentOffset, lastOffset - currentOffset)
          SeqPattern(pats)(scrutineeType, span)

        case Format.WildcardPattern =>
          val scrutineeType = decodeType()
          val startDelta = decodeInt()
          val length = decodeNat()
          val currentOffset = prevOffset + startDelta
          val span = Span(currentOffset, length)
          WildcardPattern()(scrutineeType, span)

        case _ => throw new Exception(s"Unknown pattern tag: $patternTag")

  private def decodeBool()(using buf: ReadBuffer): Boolean =
    buf.readBool()

  private def decodeByte()(using buf: ReadBuffer): Byte =
    buf.readByte()

  private def decodeInt()(using buf: ReadBuffer): Int =
    buf.readInt()

  private def decodeIntRaw()(using buf: ReadBuffer): Int =
    buf.readIntRaw()

  private def decodeNat()(using buf: ReadBuffer): Int =
    buf.readNat()

  private def decodeLongNat()(using buf: ReadBuffer): Int =
    buf.readLongNat()

  private def decodeString()(using buf: ReadBuffer): String =
    buf.readUtf8()

  private def skipString()(using buf: ReadBuffer): Unit =
    buf.skipUtf8()

  private def decodeConstant()(using buf: ReadBuffer): Constant =
    val constType = decodeByte()
    constType match
      case Format.BoolConst =>
        val value = decodeBool()
        Constant.Bool(value)

      case Format.IntConst =>
        val value = decodeInt()
        Constant.Int(value)

      case Format.StringConst =>
        val value = decodeString()
        Constant.String(value)

      case _ => throw new Exception(s"Unknown constant type: $constType")

  private def decodeSource()(using buf: ReadBuffer): Source =
    val file = decodeString()
    val source = new Source(file)
    for lineLen <- repeated(decodeNat) do
      source.addLineOffset(lineLen)
    source

  private def repeated[T](decode: => T)(using buf: ReadBuffer): Array[T] =
    val count = decodeNat()
    val arr = new Array[T](count)
    var i = 0
    while i < count do
      arr(i) = decode
      i = i + 1
    end while
    arr

end Decoder
