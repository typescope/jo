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

    val delayedDefs: Array[DelayedDef[Def]] = decodeRepeated(decodeDef(rootSymbol))

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
      case Format.FunDef => decodeFunDef(owner)
      case Format.ClassDef => decodeClassDef(owner)
      case Format.TypeDef => decodeTypeDef(owner)
      case Format.PatDef => decodePatDef(owner)
      case Format.Section => decodeSection(owner)
      case _ => throw new Exception(s"Unknown definition type in decodeDef: $defType")

  private def decodeValDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions, state: State): ValDef =
    given Source = owner.sourcePos.source

    val absoluteStart = decodeInt()
    val id = decodeNat()
    val name = decodeString()
    val flags = decodeFlags()
    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val info = decodeType()

    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)
    val symbol = Symbol.createSymbol(name, info, flags, owner, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    val rhs = decodeWord(owner, absoluteStart)
    val span = Span(absoluteStart, rhs.span.endOffset - absoluteStart)
    ValDef(symbol, rhs)(span)

  private def decodeParamDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[ParamDef] =
    given Source = owner.sourcePos.source
    val absoluteStart = decodeInt()
    val id = decodeNat()
    val name = decodeString()
    val flags = decodeFlags()
    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()

    // Create and register symbol immediately
    val actualSpan = Span(absoluteStart, symSpanLength)
    val symbol = Symbol.createSymbol(name, flags, actualSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    // Return delayed definition
    val delayed = () =>
      val tpt = decodeTypeTree(absoluteStart)
      symbol.setInfo(tpt.tpe) // Set info when tree is loaded
      ParamDef(symbol, tpt)(actualSpan)

    DelayedDef(symbol, delayed)

  private def decodeFunDef()(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): Def =
    val absoluteStart = decodeInt()
    val length = decodeIntRaw()
    val id = decodeNat()
    val symbol = state.getInternalSymbol(id)
    skipString() // name - already in symbol
    decodeFlags() // flags - already in symbol
    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()

    // Decode type parameters
    val tparams = decodeRepeated: _ =>
      val tparamId = decodeNat()
      val tparamName = decodeString()
      val tparamInfo = decodeType()
      val tparam = new TypeSymbol(tparamName, Kind.Simple)
      tparam.setInfo(tparamInfo)
      state.registerInternalSymbol(tparamId, tparam)
      tparam

    // Decode regular parameters
    val params = decodeRepeated: _ =>
      val paramId = decodeNat()
      val paramName = decodeString()
      val paramInfo = decodeType()
      val param = new Symbol(paramName, Flags.Empty)
      param.setInfo(paramInfo)
      state.registerInternalSymbol(paramId, param)
      param

    // Decode auto parameters
    val autos = decodeRepeated: _ =>
      val autoId = decodeNat()
      val autoName = decodeString()
      val autoInfo = decodeType()
      val auto = new Symbol(autoName, Flags.Auto)
      auto.setInfo(autoInfo)
      state.registerInternalSymbol(autoId, auto)
      auto

    val resultType = decodeTypeTree(absoluteStart)
    val receives = decodeRepeated(decodeSymbolRef())
    val body = decodeWord(symbol, absoluteStart)

    FunDef(symbol, tparams, params, autos, resultType, body)(Span(absoluteStart, symSpanLength))

  private def decodeClassDef()(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): Def =
    val absoluteStart = decodeInt()
    val length = decodeIntRaw()
    val id = decodeNat()
    val symbol = state.getInternalSymbol(id)
    skipString() // name - already in symbol
    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()

    // Decode type parameters
    val tparams = decodeRepeated: _ =>
      val tparamId = decodeNat()
      val tparamName = decodeString()
      val tparamKind = decodeKind()
      val tparamInfo = decodeType()
      val tparamStartDelta = decodeInt()
      val tparamLength = decodeInt()
      val tparam = new TypeSymbol(tparamName, tparamKind)
      tparam.setInfo(tparamInfo)
      state.registerInternalSymbol(tparamId, tparam)
      tparam

    // Decode self symbol
    val selfId = decodeNat()
    val selfFlags = decodeFlags()
    val selfName = decodeString()
    val self = new Symbol(selfName, selfFlags)
    state.registerInternalSymbol(selfId, self)

    // Decode val members
    val vals = decodeRepeated: _ =>
      val valId = decodeNat()
      val valFlags = decodeFlags()
      val valName = decodeString()
      val valType = decodeType()
      val valStartDelta = decodeInt()
      val valLength = decodeInt()
      val valSym = Symbol.createSymbol(valName, valFlags, symbol.sourcePos)
      valSym.setInfo(valType)
      state.registerInternalSymbol(valId, valSym)
      valSym

    // Decode function definitions
    val funs = decodeRepeated: _ =>
      decodeDef(symbol)

    ClassDef(symbol, tparams, self, vals, funs)(Span(absoluteStart, symSpanLength))

  private def decodeTypeDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[TypeDef] =
    given Source = owner.sourcePos.source
    val absoluteStart = decodeInt()
    val id = decodeNat()
    val name = decodeString()
    val symbolType = decodeType()
    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val defLength = decodeNat()

    // Create and register symbol immediately
    val actualSpan = Span(absoluteStart, defLength)
    val symbol = new TypeSymbol(name, Kind.Simple)
    symbol.sourcePos = actualSpan.toPos
    symbol.setInfo(symbolType)
    state.registerInternalSymbol(id, symbol)

    // Return delayed definition
    val delayed = () =>
      TypeDef(symbol)(actualSpan)

    DelayedDef(symbol, delayed)

  private def decodePatDef()(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): Def =
    val absoluteStart = decodeInt()
    val length = decodeIntRaw()
    // Implementation similar to FunDef but for PatDef structure
    throw new Exception("PatDef decoding not yet implemented")

  private def decodeSection()(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): Def =
    val absoluteStart = decodeInt()
    val length = decodeIntRaw()
    val id = decodeNat()
    val symbol = state.getInternalSymbol(id)
    skipString() // name - already in symbol
    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    // For Section, we need to decode nested definitions differently
    // This is a placeholder - actual implementation would need proper handling
    val nestedDefs = Array.empty[Def]

    Section(symbol, nestedDefs)(Span(absoluteStart, symSpanLength))

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
    val count = decodeByte()
    var flags = Flags.Empty
    for _ <- 0 until count do
      val flagIndex = decodeByte()
      flags |= Flags.fromIndex(flagIndex)
    flags

  private def decodeKind()(using buf: ReadBuffer, defn: Definitions, state: State): Kind =
    val kindType = decodeByte()
    kindType match
      case 0 => Kind.Simple

      case 1 => // Arrow kind
        val args = decodeRepeated(decodeKind)
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
        val fields = decodeRepeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)
        RecordType(fields)

      case Format.UnionType =>
        val branches = decodeRepeated(decodeType)
        UnionType(branches)

      case Format.TagType =>
        val tag = decodeString()
        val params = decodeRepeated(buf): _ =>
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
        val targs = decodeRepeated(decodeType)
        AppliedType(tctor, targs)

      case Format.ProcType =>
        val tparams = decodeRepeated(buf): _ =>
          val id = decodeNat()
          val name = decodeString()
          val info = decodeType()
          val tparam = new TypeSymbol(name, Kind.Simple)
          tparam.setInfo(info)
          state.registerInternalSymbol(id, tparam)
          tparam

        val params = decodeRepeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)

        val autos = decodeRepeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)

        val resType = decodeType()
        val receives = decodeRepeated(decodeSymbolRef)
        val preParamCount = decodeNat()

        ProcType(tparams, params, autos, resType, receives, preParamCount)

      case Format.TypeLambda =>
        val tparams = decodeRepeated(buf): _ =>
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
          val targs = decodeRepeated(decodeTypeTree(prevOffset))
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
          val fields = decodeRepeated(buf): _ =>
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
          val args = decodeRepeated: _ =>
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
          val args = decodeRepeated: _ =>
            val arg = decodeWord(owner, lastOffset)
            lastOffset = arg.span.endOffset
            arg
          val autos = decodeRepeated: _ =>
            val auto = decodeWord(owner, lastOffset)
            lastOffset = auto.span.endOffset
            auto
          // Type would need to be computed from function type
          val tpe = VoidType // Placeholder
          val span = Span(fun.span.start, lastOffset - fun.span.start)
          Apply(fun, args, autos)(tpe, span)

        case Format.TypeApply =>
          val fun = decodeWord(owner, prevOffset)
          val targs = decodeRepeated(decodeTypeTree(prevOffset))
          // Use fun span as approximation - encoder doesn't store position for TypeApply
          TypeApply(fun, targs)(fun.span)

        case Format.With =>
          val expr = decodeWord(owner, absoluteStart)
          val args = decodeRepeated(buf): _ =>
            val ident = decodeWord(owner, absoluteStart)
            val rhs = decodeWord(owner, absoluteStart)
            Assign(ident, rhs)(VoidType, span)
          With(expr, args)(expr.tpe, span)

        case Format.Allow =>
          val expr = decodeWord(owner, absoluteStart)
          val params = decodeRepeated(decodeWord(owner, absoluteStart))
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
          decodeFunDef(owner).force()

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
          val words = decodeRepeated(decodeWord)
          val tpe = if words.nonEmpty then words.last.tpe else VoidType
          Block(words)(tpe, span)

        case Format.Match =>
          val scrutinee = decodeWord(owner, absoluteStart)
          val cases = decodeRepeated(buf): _ =>
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

          val members = decodeRepeated(decodeDef(self)).map:
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
          val nested = decodeRepeated: _ =>
            val pat = decodePattern(owner, lastOffset)
            lastOffset = pat.span.endOffset
            pat
          val span = Span(tagLit.span.start, lastOffset - tagLit.span.start)
          TagPattern(tagLit, nested)(span)

        case Format.ApplyPattern =>
          val scrutineeType = decodeType()
          val fun = decodeWord(owner, prevOffset)
          var lastOffset = fun.span.endOffset
          val nested = decodeRepeated: _ =>
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
          val bindings = decodeRepeated: _ =>
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
          val pats = decodeRepeated(buf): _ =>
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
                val bindings = decodeRepeated(buf): _ =>
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
    for lineLen <- decodeRepeated(decodeNat) do
      source.addLineOffset(lineLen)
    source

  private def decodeRepeated[T](decode: => T)(using buf: ReadBuffer): Array[T] =
    val count = decodeNat()
    val arr = new Array[T](count)
    var i = 0
    while i < count do
      arr(i) = decode
      i = i + 1
    end while
    arr

end Decoder
