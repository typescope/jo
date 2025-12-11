package pickle

import ast.Positions.*
import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import reporting.Reporter

import scala.reflect.ClassTag
import scala.collection.mutable

import common.IO

/** Decode trees, symbols and types
  *
  * This is the counterpart to Encoder.scala, reading back the encoded format
  * to reconstruct SAST trees.
  */
object Decoder:
  class NameRef(val ownerIndex: Int, val nameIndex: Int, val kind: Int)

  private class StringTable(strings: Array[String]):
    def get(index: Int): String = strings(index)

  private class SymTable(nameRefs: Array[NameRef], stringTable: StringTable):
    /** External symbols loaded from the name table */
    private val externalSymbols: Array[Symbol] = new Array(nameRefs.size)

    def getFullNameParts(index: Int): List[String] =
      def recur(tail: List[String], index: Int): List[String] =
        val nameRef = nameRefs(index)
        val name = stringTable.get(nameRef.nameIndex)
        if nameRef.ownerIndex == -1 then
          name :: tail
        else
          recur(name :: tail, nameRef.ownerIndex)
      end recur
      recur(Nil, index)

    def getExternalSymbol(index: Int)(using defn: Definitions): Symbol =
      if index < 0 || index >= externalSymbols.length then
        throw new Exception(s"Invalid external symbol index: $index")

      var sym = externalSymbols(index)
      if sym == null then
        val nameRef = nameRefs(index)
        val fullNameParts = getFullNameParts(index)
        sym = nameRef.kind match
          case Format.Term => defn.resolveStatic(fullNameParts, Universe.Term)
          case Format.Pattern => defn.resolveStatic(fullNameParts, Universe.Pattern)
          case Format.Type => defn.resolveStatic(fullNameParts, Universe.Type)
          case Format.Container => defn.resolveStatic(fullNameParts, Universe.Container)

        externalSymbols(index) = sym
      end if
      sym

  /** De Bruijn encoding of bound type parameters in types */
  private class TypeParamScope:
    private val scope = new mutable.ArrayBuffer[Symbol]

    def withParams[T](params: List[Symbol])(fn: => T): T =
      scope ++= params
      val res = fn
      scope.dropRight(params.size)
      res

    def getParam(index: Int): Symbol =
      val size = scope.size
      val i = size - index - 1

      if i < 0 || i >= size then throw new Exception("index = " + i + ", size = " + size)

      scope(i)

  private class State(
      val root: Symbol,
      val stringTable: StringTable,
      symTable: SymTable):

    export symTable.getExternalSymbol

    /** Map from internal symbol IDs to symbols */
    private val internalSymbols = mutable.Map.empty[Int, Symbol]

    def registerInternalSymbol(id: Int, symbol: Symbol): Unit =
      internalSymbols(id) = symbol

    def getInternalSymbol(id: Int): Symbol =
      internalSymbols.get(id) match
        case Some(sym) => sym
        case None => throw new Exception(s"Unknown internal symbol id: $id, table = " + internalSymbols)

  end State

  def error(message: String): Nothing = Reporter.abortInternal(message)

  def error(message: String, pos: SourcePosition): Nothing = Reporter.abort(message, pos)

  inline def debug(message: String, enable: Boolean): Unit =
    inline if enable then println(message) else ()

  //----------------------------------------------------------------------------
  def loadPackage(dir: String)(using defnLazy: Definitions.Lazy, rp: Reporter): List[Namespace] =
    val files = IO.getSastFiles(dir).toList
    val delayedDefs = files.map(file => Decoder.load(file))

    // Force all delayed definitions
    val defn = defnLazy.value
    delayedDefs.map(_.force()(using defn))


  /** Load a .sast file and decode it */
  def load(file: String)(using defnLazy: Definitions.Lazy, rp: Reporter): DelayedDef[Namespace] =
    // Load the file and decode - owner is now stored in the file
    val bytes = IO.fileAsBytes(file)
    given ReadBuffer = new ReadBuffer(bytes)
    val delayedNS = decode()

    // Register the symbol in the appropriate name table
    val owner = delayedNS.symbol.owner
    if owner == null then
      defnLazy.rootNameTable.define(delayedNS.symbol)
    else
      owner.nameTable.define(delayedNS.symbol)

    delayedNS

  /** Resolve owner symbol from path, creating it if it doesn't exist */
  private def resolveOwner(path: List[String], pos: SourcePosition)(using defnLazy: Definitions.Lazy, rp: Reporter): Symbol =
    def resolve(path: List[String], nameTable: NameTable, owner: Symbol): Symbol =
      path match
        case name :: rest =>
          val sym = nameTable.resolveContainer(name) match
            case Some(sym) => sym

            case None =>
              // Create the symbol if it doesn't exist
              val flags = Flags.NSpace | Flags.Branch
              val sym = ContainerSymbol.create(name, new NameTable, flags, Visibility.Default, owner, pos)
              nameTable.define(sym)
              sym

          resolve(rest, sym.nameTable, sym)

        case Nil =>
          owner

    resolve(path, defnLazy.rootNameTable, owner = null)

  def decode()(using buf: ReadBuffer, defnLazy: Definitions.Lazy, rp: Reporter): DelayedDef[Namespace] =
    // Read and validate file header
    val magic = decodeIntRaw()
    if magic != Format.MAGIC_NUMBER then
      Reporter.abortInternal(f"Invalid SAST file: expected magic number 0x${Format.MAGIC_NUMBER}%08X, got 0x${magic}%08X")

    val majorVersion = decodeByte()
    val minorVersion = decodeByte()

    // Check version compatibility
    // - Major version must match exactly
    // - Minor version can be <= current (backward compatible)
    if majorVersion != Format.MAJOR_VERSION then
      Reporter.abortInternal(
        s"Incompatible SAST file version: file is $majorVersion.$minorVersion, compiler supports ${Format.MAJOR_VERSION}.${Format.MINOR_VERSION}\n" +
        "Please rebuild the library with the current compiler version."
      )

    // Read owner index (-1 if null, otherwise index to name table)
    val ownerIndex = decodeInt()

    // Read string table
    val stringTableAddr: Int = decodeIntRaw()
    val stringTable: StringTable = buf.withPosition(stringTableAddr):
      new StringTable(decodeStringTable())

    // Read external name table
    val nameTableAddr: Int = decodeIntRaw()
    val nameRefs: Array[NameRef] = buf.withPosition(nameTableAddr):
      decodeNameTable()

    // Create symbol table early so we can use getFullNameParts
    val symTable = new SymTable(nameRefs, stringTable)

    val id = decodeNat()
    val name = stringTable.get(buf.readNat())
    val source = decodeSource(stringTable)
    val symSpan = Span(decodeNat(), decodeNat())
    val pos = symSpan.toPos(using source)

    // Resolve owner from name table using getFullNameParts
    val ownerSymbol: Symbol =
      if ownerIndex == -1 then
        null
      else
        val ownerPath = symTable.getFullNameParts(ownerIndex)
        resolveOwner(ownerPath, pos)

    val nameTable = new NameTable
    val rootSymbol = ContainerSymbol.create(name, nameTable, Flags.NSpace, Visibility.Default, ownerSymbol, pos)

    given state: State = new State(rootSymbol, stringTable, symTable)

    // Import/alias may refer to the root symbol
    state.registerInternalSymbol(id, rootSymbol)

    debug("decoding symbols of module " + rootSymbol, enable = false)

    // Decode imports
    val importsLength = decodeIntRaw()
    val importsPos = buf.position
    buf.advance(importsLength)

    val delayedDefs = repeated { decodeDef(rootSymbol) }
    val span = Span(decodeNat(), decodeNat())

    debug("decoding symbols of module " + rootSymbol + " success", enable = false)

    // Add symbols to name table
    for delayedDef <- delayedDefs do nameTable.define(delayedDef.symbol)

    val delayed = () =>
      given Definitions = defnLazy.value

      val imports =
        given ReadBuffer = buf.fresh(importsPos)
        decodeImports(rootSymbol)

      val members = delayedDefs.map: d =>
        d.force()

      debug("module " + rootSymbol + " loaded success", enable = false)

      Namespace(rootSymbol, imports, members)(span)

    DelayedDef(rootSymbol, delayed)

  /** Decode all imports for a namespace */
  private def decodeImports
      (owner: Symbol)
      (using buf: ReadBuffer, defn: Definitions, state: State)
  : List[Symbol] =

    var lastOffset = owner.span.endOffset
    repeated:
      val id = decodeNat()
      val name = decodeString()
      val target = decodeSymbolRef()

      val startDelta = decodeInt()
      val symLength = decodeNat()
      val span = Span(lastOffset + startDelta, symLength)

      lastOffset = span.endOffset

      val flags = target.flags | Flags.Alias
      val sym =
        if target.isTerm then
          TermSymbol.create(name, flags, Visibility.Default, owner, span.toPos(using owner.source))

        else if target.isType then
          val kind = target.asTypeSymbol.kind
          TypeSymbol.create(kind, name, flags, Visibility.Default, owner, span.toPos(using owner.source))

        else
          PatternSymbol.create(name, flags, Visibility.Default, owner, span.toPos(using owner.source))

      state.registerInternalSymbol(id, sym)


      defn.add(sym, StaticRef(target))

      sym

  /** Decode a definition and return delayed definition
    *
    * Invariant:
    *
    * The buffer position for decodeXXX should be at the end of its definition
    * after the call.
    */
  private def decodeDef
      (owner: Symbol)
      (using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State, rp: Reporter)
  : DelayedDef[Def] =

    val defType = decodeByte()

    defType match
      case Format.ParamDef => decodeParamDef(owner)
      case Format.AliasDef => decodeAliasDef(owner)
      case Format.FunDef => decodeFunDef(owner, Flags.empty)
      case Format.ClassDef => decodeClassDef(owner)
      case Format.InterfaceDef => decodeInterfaceDef(owner)
      case Format.TypeDef => decodeTypeDef(owner)
      case Format.PatDef => decodePatDef(owner)
      case Format.Section => decodeSection(owner)
      case _ => throw new Exception(s"Unknown definition type in decodeDef: $defType")

  private def decodeValDef(owner: Symbol, extraFlags: Flags)(using buf: ReadBuffer, defnLazy: Definitions, state: State): ValDef =
    given Source = owner.source

    val absoluteStart = decodeNat()
    val id = decodeNat()
    val name = decodeString()
    val flags = decodeFlags() | extraFlags
    val visibility = decodeVisibility(owner)

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    val info = decodeType()
    val symbol = TermSymbol.create(name, info, flags, visibility, owner, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    val rhs = decodeWord(owner, absoluteStart)
    val endDelta = decodeInt()
    val span = Span(absoluteStart, rhs.span.endOffset + endDelta - absoluteStart)
    ValDef(symbol, rhs)(span)

  private def decodeParamDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[ParamDef] =
    given Source = owner.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()
    val flags = decodeFlags() | Flags.Context
    val visibility = decodeVisibility(owner)

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    val symbol = TermSymbol.create(name, flags, visibility, owner, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    val typeStartPos = buf.position
    lazy val paramDef =
      given Definitions = defnLazy.value
      given ReadBuffer = buf.fresh(typeStartPos)
      val tpt = decodeTypeTree(absoluteStart)
      val endDelta = decodeInt()
      val span = Span(absoluteStart, tpt.span.endOffset + endDelta - absoluteStart)
      ParamDef(symbol, tpt)(span)

    // Supply type for symbol
    defnLazy.infoProvider.addLazy(symbol, () => paramDef.tpt.tpe)

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, () => paramDef)

  private def decodeAliasDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[AliasDef] =
    given Source = owner.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()

    val isPattern = decodeByte() == Format.Pattern

    val flags = decodeFlags() | Flags.Alias
    val visibility = decodeVisibility(owner)

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    val symbol =
      if isPattern then
        PatternSymbol.create(name, flags, visibility, owner, symSpan.toPos)

      else
        TermSymbol.create(name, flags, visibility, owner, symSpan.toPos)

    state.registerInternalSymbol(id, symbol)

    val targetStartPos = buf.position
    lazy val aliasDef =
      given Definitions = defnLazy.value
      given ReadBuffer = buf.fresh(targetStartPos)
      val target = decodeWord(symbol, absoluteStart).asInstanceOf[Ident]
      val endDelta = decodeInt()
      val span = Span(absoluteStart, target.span.endOffset + endDelta - absoluteStart)
      AliasDef(symbol, target)(span)

    // Supply type for symbol
    defnLazy.infoProvider.addLazy(symbol, () => StaticRef(aliasDef.target.symbol))

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, () => aliasDef)

  private def decodeFunDef(owner: Symbol, initFlags: Flags)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[FunDef] =
    given Source = owner.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()
    val flags = decodeFlags() | initFlags | Flags.Fun | Flags.Loaded
    val visibility = decodeVisibility(owner)

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    val symbol = TermSymbol.create(name, flags, visibility, owner, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    given defn: Definitions = defnLazy.value

    // Read signature lazily
    val tparamsStartPos = buf.position
    object sig:
      given sigBuf: ReadBuffer = buf.fresh(tparamsStartPos)

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

        val tparam = TypeSymbol.create(kind, tparamName, tparamInfo, Flags.Param, Visibility.Default, symbol, tparamSpan.toPos)
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

        val param = TermSymbol.create(paramName, paramInfo, Flags.Param, Visibility.Default, symbol, paramSpan.toPos)
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

        val auto = TermSymbol.create(autoName, autoInfo, Flags.Param, Visibility.Default, symbol, autoSpan.toPos)
        state.registerInternalSymbol(autoId, auto)

        auto

      // Decode auto candidates
      val candidatesWithTrees = repeated {
        repeated {
          val tag = decodeByte()
          tag match
            case 0 => // Function candidate
              val candidateSym = decodeSymbolRef()
              val candidateStartDelta = decodeInt()
              val candidateSpanLength = decodeNat()
              val candidateSpan = Span(absoluteStart + candidateStartDelta, candidateSpanLength)
              (AutoCandidate.Value(candidateSym)(candidateSpan), candidateSym)

            case 1 => // Member candidate
              val tpe = decodeType()
              val memberName = decodeString()
              val tptStartDelta = decodeInt()
              val tptSpanLength = decodeNat()
              val tptSpan = Span(absoluteStart + tptStartDelta, tptSpanLength)
              val tpt = TypeTree(tpe)(tptSpan)
              (AutoCandidate.Member(tpt, memberName)(tptSpan), MemberCandidate(tpe, memberName))
        }
      }

      val candidateTrees = candidatesWithTrees.map(_.map(_._1))
      val candidateSymbols = candidatesWithTrees.map(_.map(_._2))

      val resultType = decodeTypeTree(absoluteStart)

      val receives = repeated { decodeSymbolRef() }

      val preParamCount = decodeNat()

      val signatureEndPos = sigBuf.position
    end sig

    // Add symbol info
    lazy val funInfo: ProcType =
      val receives = sig.receives

      ProcType(
        sig.tparams, sig.params.map(_.toNamedInfo), sig.autos.map(_.toNamedInfo),
        sig.candidateSymbols, sig.resultType.tpe, () => receives, sig.preParamCount)

    defnLazy.infoProvider.addLazy(symbol, () => funInfo)


    val delayedFun = () =>
      given ReadBuffer = buf.fresh(sig.signatureEndPos)

      val body = decodeWord(symbol, sig.resultType.span.endOffset)
      val endDelta = decodeInt()
      val span = Span(absoluteStart, body.span.endOffset + endDelta - absoluteStart)
      val policy = Effects.Policy.CheckBound(sig.receives)
      FunDef(symbol, sig.tparams, sig.params, sig.autos, sig.candidateTrees, sig.resultType, policy, body)(span)

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, delayedFun)

  private def decodeClassDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[ClassDef] =
    given Source = owner.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()
    val kind = decodeKind()
    val visibility = decodeVisibility(owner)

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    // Create and register symbol immediately
    val symbol = TypeSymbol.create(kind, name, Flags.Class, visibility, owner, symSpan.toPos)
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
        val tparamSpan = Span(symbol.span.start + tparamStartDelta, tparamLength)

        // TODO: eager reading info excludes F-bounds
        val tparamKind = decodeKind()
        val tparamInfo = decodeType()

        val tparam = TypeSymbol.create(tparamKind, tparamName, tparamInfo, Flags.Param, Visibility.Default, symbol, tparamSpan.toPos)
        state.registerInternalSymbol(tparamId, tparam)
        tparam

      // Decode self symbol
      val selfInfo =
        val classRef = StaticRef(symbol)
        if tparams.isEmpty then classRef
        else AppliedType(symbol, tparams.map(StaticRef.apply))

      val selfId = decodeNat()
      val selfName = decodeString()
      val selfFlags = decodeFlags()
      val self = TermSymbol.create(selfName, selfInfo, selfFlags, Visibility.Default, symbol, symbol.sourcePos)
      state.registerInternalSymbol(selfId, self)

      // Decode val members
      val vals = repeated:
        val valId = decodeNat()
        val valName = decodeString()
        val valFlags = decodeFlags() | Flags.Field
        val visibility = decodeVisibility(symbol)

        val valStartDelta = decodeInt()
        val valLength = decodeNat()
        val valSpan = Span(symbol.span.start + valStartDelta, valLength)

        val valType = decodeType()

        val valSym = TermSymbol.create(valName, valType, valFlags, visibility, symbol, valSpan.toPos)
        state.registerInternalSymbol(valId, valSym)
        valSym

      // Decode function definitions as DelayedDef
      val delayedFuns = repeated:
        assert(decodeByte() == Format.FunDef, "Unexpected tag")
        decodeFunDef(symbol, Flags.Method)

      val endDelta = decodeInt()

      val symInfo =
        val funs = delayedFuns.map(_.symbol)
        val base = ClassInfo(symbol, tparams, tparams.map(StaticRef.apply), self, vals, funs)

        if tparams.isEmpty then base
        else TypeLambda(tparams, base, preParamCount = 0)

    end content

    defnLazy.infoProvider.addLazy(symbol, () => content.symInfo)

    val delayed = () =>
      var lastOffset = absoluteStart
      val funs = content.delayedFuns.map: d =>
        val fun = d.force()
        lastOffset = fun.span.endOffset
        fun

      val span = Span(absoluteStart, lastOffset + content.endDelta - absoluteStart)
      ClassDef(symbol, content.self, content.tparams, content.vals, funs)(span)

    // Set buffer position at end
    buf.setPosition(pos + length)
    DelayedDef(symbol, delayed)

  private def decodeInterfaceDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[InterfaceDef] =
    given Source = owner.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()
    val kind = decodeKind()
    val visibility = decodeVisibility(owner)

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    // Create and register symbol immediately
    val symbol = TypeSymbol.create(kind, name, Flags.Interface, visibility, owner, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    given Definitions = defnLazy.value

    // Read interface content lazily
    val contentStartPos = buf.position
    object content:
      given ReadBuffer = buf.fresh(contentStartPos)

      // Decode type parameters
      val tparams = repeated:
        val tparamId = decodeNat()
        val tparamName = decodeString()

        val tparamStartDelta = decodeInt()
        val tparamLength = decodeNat()
        val tparamSpan = Span(symbol.span.start + tparamStartDelta, tparamLength)

        val tparamKind = decodeKind()
        val tparamInfo = decodeType()

        val tparam = TypeSymbol.create(tparamKind, tparamName, tparamInfo, Flags.Param, Visibility.Default, symbol, tparamSpan.toPos)
        state.registerInternalSymbol(tparamId, tparam)
        tparam

      // Decode self symbol
      val selfInfo =
        val interfaceRef = StaticRef(symbol)
        if tparams.isEmpty then interfaceRef
        else AppliedType(symbol, tparams.map(StaticRef.apply))

      val selfId = decodeNat()
      val selfName = decodeString()
      val selfFlags = decodeFlags()
      val self = TermSymbol.create(selfName, selfInfo, selfFlags, Visibility.Default, symbol, symbol.sourcePos)
      state.registerInternalSymbol(selfId, self)

      // Decode method definitions as DelayedDef
      val delayedMethods = repeated:
        assert(decodeByte() == Format.FunDef, "Unexpected tag")
        decodeFunDef(symbol, Flags.Method)

      val endDelta = decodeInt()

      val symInfo =
        val methods = delayedMethods.map(_.symbol)
        val base = ClassInfo(symbol, tparams, tparams.map(StaticRef.apply), self, Nil, methods)

        if tparams.isEmpty then base
        else TypeLambda(tparams, base, preParamCount = 0)

    end content

    defnLazy.infoProvider.addLazy(symbol, () => content.symInfo)

    val delayed = () =>
      var lastOffset = absoluteStart
      val methods = content.delayedMethods.map: d =>
        val method = d.force()
        lastOffset = method.span.endOffset
        method

      val span = Span(absoluteStart, lastOffset + content.endDelta - absoluteStart)
      InterfaceDef(symbol, content.self, content.tparams, methods)(span)

    // Set buffer position at end
    buf.setPosition(pos + length)
    DelayedDef(symbol, delayed)

  private def decodeTypeDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[TypeDef] =
    given Source = owner.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()
    val id = decodeNat()
    val name = decodeString()
    val kind = decodeKind()
    val visibility = decodeVisibility(owner)

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    // Create symbol immediately but delay type reading
    val symbol = TypeSymbol.create(kind, name, Flags.empty, visibility, owner, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    given Definitions = defnLazy.value

    // Read type and def length lazily
    val typeStartPos = buf.position
    object delayed:
      given ReadBuffer = buf.fresh(typeStartPos)
      val tpe = decodeType()
      val treeLength = decodeNat()

    // Add symbol info lazily
    defnLazy.infoProvider.addLazy(symbol, () => delayed.tpe)

    val typeDefFun = () =>
      val actualSpan = Span(absoluteStart, delayed.treeLength)
      TypeDef(symbol)(actualSpan)

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, typeDefFun)

  private def decodePatDef(owner: Symbol)(using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State): DelayedDef[PatDef] =
    given Source = owner.source
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()
    val visibility = decodeVisibility(owner)

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    val symbol = PatternSymbol.create(name, Flags.Fun, visibility, owner, symSpan.toPos)
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

        val tparamStartDelta = decodeInt()
        val tparamSpanLength = decodeNat()
        val tparamSpan = Span(absoluteStart + tparamStartDelta, tparamSpanLength)

        // TODO: eager reading info excludes F-bounds
        val kind = decodeKind()
        val tparamInfo = decodeType()

        val tparam = TypeSymbol.create(kind, tparamName, tparamInfo, Flags.Param, Visibility.Default, symbol, tparamSpan.toPos)
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

        val param = PatternSymbol.create(paramName, paramInfo, Flags.Param, Visibility.Default, symbol, paramSpan.toPos)
        state.registerInternalSymbol(paramId, param)

        param

      val resultType = decodeTypeTree(absoluteStart)
      val receives = repeated { decodeSymbolRef() }
      val preParamCount = decodeNat()
      val signatureEndPos = sigBuf.position
    end sig

    // Add symbol info
    lazy val patInfo: ProcType =
      val receives = sig.receives
      ProcType(
        sig.tparams, sig.params.map(_.toNamedInfo), Nil, Nil,
        sig.resultType.tpe, () => receives, sig.preParamCount)

    defnLazy.infoProvider.addLazy(symbol, () => patInfo)

    val delayedPatDef = () =>
      given ReadBuffer = buf.fresh(sig.signatureEndPos)
      val body = decodePattern(symbol, sig.resultType.span.endOffset)
      val endDelta = decodeInt()
      val span = Span(absoluteStart, body.span.endOffset + endDelta - absoluteStart)
      PatDef(symbol, sig.tparams, sig.params, sig.resultType, body)(span)

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, delayedPatDef)

  private def decodeSection
      (owner: Symbol)
      (using buf: ReadBuffer, defnLazy: Definitions.Lazy, state: State, rp: Reporter)
  : DelayedDef[Section] =

    given Source = owner.source

    // length is redundant --- keep it for extension and uniformity
    val length = decodeIntRaw()
    val pos = buf.position

    val absoluteStart = decodeNat()

    val id = decodeNat()
    val name = decodeString()
    val visibility = decodeVisibility(owner)

    val symStartDelta = decodeInt()
    val symSpanLength = decodeNat()
    val symSpan = Span(absoluteStart + symStartDelta, symSpanLength)

    // Create and register symbol immediately
    val nameTable = new NameTable()
    val symbol = ContainerSymbol.create(name, nameTable, Flags.Section, visibility, owner, symSpan.toPos)
    state.registerInternalSymbol(id, symbol)

    // Decode nested definitions as DelayedDef
    val delayedDefs = repeated { decodeDef(symbol) }

    val endDelta = decodeInt()

    for delayedDef <- delayedDefs do nameTable.define(delayedDef.symbol)
    nameTable.freeze()

    val delayed = () =>
      given Definitions = defnLazy.value
      var lastOffset = absoluteStart
      val nestedDefs = delayedDefs.map: d =>
        val defn = d.force()
        lastOffset = defn.span.endOffset
        defn

      val span = Span(absoluteStart, lastOffset + endDelta - absoluteStart)
      Section(symbol, nestedDefs)(span)

    // Set buffer position at end
    buf.setPosition(pos + length)

    DelayedDef(symbol, delayed)

  //----------------------------------------------------------------------------

  private def decodeNameTable()(using buf: ReadBuffer): Array[NameRef] =
    val count = decodeNat()
    val nameRefs = new Array[NameRef](count)

    var i = 0
    while i < count do
      val ownerIndex = decodeInt() // ownerIndex can be -1
      val nameIndex = decodeNat()
      val kind = decodeByte()

      nameRefs(i) = new NameRef(ownerIndex, nameIndex, kind)
      i += 1

    nameRefs

  private def decodeStringTable()(using buf: ReadBuffer): Array[String] =
    val count = decodeNat()
    val strings = new Array[String](count)

    var i = 0
    while i < count do
      strings(i) = buf.readUtf8()
      i += 1

    strings

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

  private def decodeKind()(using buf: ReadBuffer): Kind =
    val kindType = decodeByte()
    kindType match
      case Format.SimpleKind => Kind.Simple

      case Format.ArrowKind => // Arrow kind
        val args = repeated { decodeKind() }
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

  private def decodeType
     (tparamScope: TypeParamScope = new TypeParamScope)
     (using buf: ReadBuffer, defn: Definitions, state: State)
  : Type =

    val pos = buf.position
    val typeTag = decodeByte()
    typeTag match
      case Format.VoidType => VoidType
      case Format.ErrorType => ErrorType
      case Format.AnyType => AnyType
      case Format.BottomType => BottomType

      case Format.TypeParamRef =>
        val index = decodeNat()
        StaticRef(tparamScope.getParam(index))

      case Format.StaticRef =>
        val sym = decodeSymbolRef()
        StaticRef(sym)

      case Format.MemberRef =>
        val prefix = decodeType(tparamScope)
        val sym = decodeSymbolRef()
        MemberRef(prefix, sym)

      case Format.ConstantType =>
        val const = decodeConstant()
        ConstantType(const)

      case Format.RecordType =>
        val fields = repeated:
          val name = decodeString()
          val info = decodeType(tparamScope)
          NamedInfo(name, info)
        RecordType(fields)

      case Format.UnionType =>
        val branches = repeated { decodeType(tparamScope) }
        UnionType(branches)

      case Format.ObjectType =>
        val members = new mutable.ArrayBuffer[NamedInfo[Type]]
        val muts = new mutable.ArrayBuffer[String]

        val memberCount = decodeNat()

        var i = 0
        while i < memberCount do
          val name = decodeString()
          val info = decodeType(tparamScope)
          members += NamedInfo(name, info)
          if info.isValueType then
            val isMutable = decodeBool()
            if isMutable then muts += name

          i += 1
        end while

        ObjectType(members.toList, muts.toList)

      case Format.AppliedType =>
        // No first-class type constructors
        val tctor = decodeSymbolRef()
        val targs = repeated { decodeType(tparamScope) }
        AppliedType(tctor, targs)

      case Format.ProcType =>
        val tparams = repeated:
          val name = decodeString()
          // TODO: eager decoding excludes F-bounds
          val kind = decodeKind()
          val info = decodeType(tparamScope)

          val tparam = TypeSymbol.create(kind, name, info, Flags.Param, Visibility.Default, state.root, state.root.sourcePos)

          tparam

        tparamScope.withParams(tparams):
          val params = repeated:
            val name = decodeString()
            val info = decodeType(tparamScope)
            NamedInfo(name, info)

          val autos = repeated:
            val name = decodeString()
            val info = decodeType(tparamScope)
            NamedInfo(name, info)

          // Decode candidates for each auto parameter
          val candidates = repeated {
            repeated {
              val tag = decodeByte()
              tag match
                case 0 => // Function candidate
                  decodeSymbolRef()

                case 1 => // Member candidate
                  val tp = decodeType(tparamScope)
                  val memberName = decodeString()
                  MemberCandidate(tp, memberName)
            }
          }

          val resType = decodeType(tparamScope)
          val receives = repeated { decodeSymbolRef() }
          val preParamCount = decodeNat()

          ProcType(tparams, params, autos, candidates, resType, () => receives, preParamCount)

      case Format.TypeLambda =>
        val tparams = repeated:
          val name = decodeString()

          // TODO: eager decoding excludes F-bounds
          val kind = decodeKind()
          val info = decodeType(tparamScope)

          val tparam = TypeSymbol.create(kind, name, info, Flags.Param, Visibility.Default, state.root, state.root.sourcePos)
          tparam

        tparamScope.withParams(tparams):
          val resType = decodeType(tparamScope)
          val preParamCount = decodeNat()
          TypeLambda(tparams, resType, preParamCount)

      case Format.DuckType =>
        val baseType = decodeType(tparamScope)
        val adapters = repeated:
          val tag = decodeByte()
          tag match
            case 0 => // Function adapter
              val symbol = decodeSymbolRef()
              ParamAdapter.Function(symbol)

            case 1 => // Member adapter
              val name = decodeString()
              ParamAdapter.Member(name)
          end match
        DuckType(baseType)(() => adapters)

      case Format.TypeBound =>
        val lo = decodeType(tparamScope)
        val hi = decodeType(tparamScope)
        TypeBound(lo, hi)

      case _ => throw new Exception(s"Unknown type tag: $typeTag at $pos")

  private def decodeWord(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Word =
    val wordTag = decodeByte()

    wordTag match
      case Format.Literal     => decodeLiteral(prevOffset)
      case Format.Ident       => decodeIdent(prevOffset)
      case Format.New         => decodeNew(prevOffset)
      case Format.Select      => decodeSelect(owner, prevOffset)
      case Format.RecordLit   => decodeRecordLit(owner, prevOffset)
      case Format.Encoded     => decodeEncoded(owner, prevOffset)
      case Format.Apply       => decodeApply(owner, prevOffset)
      case Format.TypeApply   => decodeTypeApply(owner, prevOffset)
      case Format.With        => decodeWith(owner, prevOffset)
      case Format.Allow       => decodeAllow(owner, prevOffset)
      case Format.Assign      => decodeAssign(owner, prevOffset)
      case Format.FieldAssign => decodeFieldAssign(owner, prevOffset)
      case Format.ValDef      => decodeValDef(owner, Flags.empty)
      case Format.FunDef      => decodeFunDef(owner, Flags.empty).force()
      case Format.TypeDef     => decodeTypeDef(owner).force()
      case Format.PatDef      => decodePatDef(owner).force()
      case Format.If          => decodeIf(owner, prevOffset)
      case Format.While       => decodeWhile(owner, prevOffset)
      case Format.Block       => decodeBlock(owner, prevOffset)
      case Format.Match       => decodeMatch(owner, prevOffset)
      case Format.Object      => decodeObject(owner, prevOffset)
      case _ => throw new Exception(s"Unknown word tag: $wordTag")

  private def decodeLiteral(prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Literal =
    val const = decodeConstant()
    val tpe = decodeType()

    val startDelta = decodeInt()
    val length = decodeNat()

    val startOffset = prevOffset + startDelta
    val span = Span(startOffset, length)

    Literal(const)(tpe, span)

  private def decodeIdent(prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Ident =
    val sym = decodeSymbolRef()

    val startDelta = decodeInt()
    val length = decodeNat()

    val startOffset = prevOffset + startDelta
    val span = Span(startOffset, length)

    Ident(sym)(span)

  private def decodeNew(prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): New =
    val startDelta = decodeInt()
    val startOffset = prevOffset + startDelta

    val classType = decodeTypeTree(startOffset)
    val span = Span(startOffset, classType.span.endOffset - startOffset)

    New(classType)(span)

  private def decodeSelect(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Select =
    val startDelta = decodeInt()
    val startOffset = prevOffset + startDelta

    val qual = decodeWord(owner, startOffset)
    val name = decodeString()
    val endDelta = decodeInt()

    val span = Span(startOffset, qual.span.endOffset + endDelta - startOffset)

    Select(qual, name)(span)

  private def decodeRecordLit(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): RecordLit =
    val startDelta = decodeInt()
    val startOffset = prevOffset + startDelta

    var lastOffset = startOffset
    val fields = repeated:
      val fieldName = decodeString()
      val rhs = decodeWord(owner, lastOffset)
      lastOffset = rhs.span.endOffset
      (fieldName, rhs)

    val endDelta = decodeInt()

    val span = Span(startOffset, lastOffset + endDelta - startOffset)
    RecordLit(fields)(span)

  private def decodeEncoded(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Encoded =
    val repr = decodeWord(owner, prevOffset)
    val tpe = decodeType()
    Encoded(repr)(tpe)

  private def decodeApply(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Apply =
    val startDelta = decodeInt()
    val startOffset = prevOffset + startDelta

    val fun = decodeWord(owner, startOffset)

    var lastOffset = fun.span.endOffset
    val args = repeated:
      val arg = decodeWord(owner, lastOffset)
      lastOffset = arg.span.endOffset
      arg

    val autos = repeated:
      val auto = decodeWord(owner, lastOffset)
      lastOffset = auto.span.endOffset
      auto

    val endDelta = decodeInt()
    val span = Span(startOffset, lastOffset + endDelta - startOffset)

    Apply(fun, args, autos)(span)

  private def decodeTypeApply(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): TypeApply =
    val startDelta = decodeInt()
    val startOffset = prevOffset + startDelta

    val fun = decodeWord(owner, startOffset)

    var lastOffset = fun.span.endOffset
    val targs = repeated:
      val targ = decodeTypeTree(lastOffset)
      lastOffset = targ.span.endOffset
      targ

    val endDelta = decodeInt()
    val span = Span(startOffset, lastOffset + endDelta - startOffset)

    val tpe = fun.tpe.asProcType.instantiate(targs.map(_.tpe))

    TypeApply(fun, targs)(tpe, span)

  private def decodeWith(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): With =
    val expr = decodeWord(owner, prevOffset)

    var lastOffset = expr.span.endOffset
    val args = repeated:
      val ident = decodeWord(owner, lastOffset).asInstanceOf[Ident]
      val rhs = decodeWord(owner, ident.span.endOffset)
      lastOffset = rhs.span.endOffset
      Assign(ident, rhs)

    With(expr, args)

  private def decodeAllow(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Allow =
    val expr = decodeWord(owner, prevOffset)

    var lastOffset = expr.span.endOffset
    val params = repeated:
      val param = decodeWord(owner, lastOffset).asInstanceOf[Ident]
      lastOffset = param.span.endOffset
      param

    Allow(expr, params)

  private def decodeAssign(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Assign =
    val ident = decodeWord(owner, prevOffset).asInstanceOf[Ident]
    val rhs = decodeWord(owner, ident.span.endOffset)
    Assign(ident, rhs)

  private def decodeFieldAssign(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): FieldAssign =
    val lhs = decodeWord(owner, prevOffset).asInstanceOf[Select]
    val rhs = decodeWord(owner, lhs.span.endOffset)
    FieldAssign(lhs, rhs)

  private def decodeIf(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): If =
    val startDelta = decodeInt()
    val startOffset = prevOffset + startDelta

    val cond = decodeWord(owner, startOffset)
    val thenp = decodeWord(owner, cond.span.endOffset)
    val elsep = decodeWord(owner, thenp.span.endOffset)
    val tpe = decodeType()

    val endDelta = decodeInt()
    val span = Span(startOffset, elsep.span.endOffset + endDelta - startOffset)

    If(cond, thenp, elsep)(tpe, span)

  private def decodeWhile(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): While =
    val startDelta = decodeInt()
    val startOffset = prevOffset + startDelta

    val cond = decodeWord(owner, startOffset)
    val body = decodeWord(owner, cond.span.endOffset)

    val endDelta = decodeInt()
    val span = Span(startOffset, body.span.endOffset + endDelta - startOffset)

    While(cond, body)(span)

  private def decodeBlock(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Block =
    val startDelta = decodeInt()
    val startOffset = startDelta + prevOffset

    var lastOffset = startOffset
    val words = repeated:
      val word = decodeWord(owner, lastOffset)
      lastOffset = word.span.endOffset
      word

    val endDelta = decodeInt()
    val span = Span(startOffset, lastOffset + endDelta - startOffset)

    Block(words)(span)

  private def decodeMatch(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Match =
    val startDelta = decodeInt()
    val startOffset = startDelta + prevOffset

    val scrutinee = decodeWord(owner, startOffset)

    var lastOffset = scrutinee.span.endOffset
    val cases = repeated:
      val delta = decodeInt()
      val caseStartOffset = delta + lastOffset

      val pat = decodePattern(owner, caseStartOffset)
      val body = decodeWord(owner, pat.span.endOffset)

      val endDelta = decodeInt()

      lastOffset = body.span.endOffset
      val span = Span(caseStartOffset, lastOffset + endDelta - caseStartOffset)

      Case(pat, body)(span)

    val tpe = decodeType()
    val endDelta = decodeInt()
    val span = Span(startOffset, lastOffset + endDelta - startOffset)

    Match(scrutinee, cases)(tpe, span)

  private def decodeObject(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Object =
    given Source = owner.source

    val startDelta = decodeInt()
    val startOffset = startDelta + prevOffset

    val selfId = decodeNat()
    val selfName = decodeString()
    val selfFlags = decodeFlags()
    val selfDelta = decodeInt()
    val selfLength = decodeNat()

    val selfSpan = Span(startOffset + selfDelta, selfLength)
    val self = TermSymbol.create(selfName, selfFlags, Visibility.Default, owner, selfSpan.toPos)
    state.registerInternalSymbol(selfId, self)

    val delayedDefs: List[DelayedDef[ValDef | FunDef]] = repeated:
      val tag = decodeByte()

      tag match
        case Format.ValDef =>
          val vdef = decodeValDef(self, Flags.Field)
          DelayedDef(vdef.symbol, () => vdef)

        case Format.FunDef =>
          decodeFunDef(self, Flags.Method)

        case _ => throw new Exception("Object can only contain val and fun definitions")

    val endDelta = decodeInt()

    val selfRef = StaticRef(self)
    val mutables = delayedDefs.filter(_.symbol.isMutable).map(_.symbol.name).toList

    lazy val selfType =
      val memberTypes = delayedDefs.map: d =>
        NamedInfo(d.symbol.name, MemberRef(selfRef, d.symbol))

      ObjectType(memberTypes.toList, mutables)

    defn.addLazy(self, () => selfType)

    var lastOffset = startOffset
    val members: List[ValDef | FunDef] =
      for delayedDef <- delayedDefs.toList yield
        val defn = delayedDef.force()
        lastOffset = defn.span.endOffset
        defn

    val objectType = ObjectType(members.map(_.symbol.toNamedInfo), mutables)
    val span = Span(startOffset, lastOffset + endDelta - startOffset)

    Object(self, members)(objectType, span)


  private def decodePattern(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): Pattern =
    val patternTag = decodeByte()

    patternTag match
      case Format.AliasPattern =>
        val isDef = decodeBool()
        val startDelta = decodeInt()
        val id = decodeNat()
        val length = decodeNat()

        val span = Span(startDelta + prevOffset, length)

        val nested = decodePattern(owner, span.endOffset)

        val symbol =
          if isDef then
            val name = decodeString()
            val info = nested.valueType

            val symbol = PatternSymbol.create(name, info, Flags.empty, Visibility.Default, owner, span.toPos(using owner.source))
            state.registerInternalSymbol(id, symbol)
            symbol
          else
            state.getInternalSymbol(id)

        val ident = Ident(symbol)(span)
        AliasPattern(ident, nested)(isDef)

      case Format.TypePattern =>
        val scrutineeType = decodeType()
        val tpt = decodeTypeTree(prevOffset)
        TypePattern(tpt)(scrutineeType)

      case Format.ApplyPattern =>
        val startDelta = decodeInt()
        val startOffset = startDelta + prevOffset

        val scrutineeType = decodeType()
        val fun = decodeWord(owner, prevOffset)

        var lastOffset = fun.span.endOffset
        val nested = repeated:
          val pat = decodePattern(owner, lastOffset)
          lastOffset = pat.span.endOffset
          pat

        val endDelta = decodeInt()
        val span = Span(startOffset, lastOffset + endDelta - startOffset)

        ApplyPattern(fun, nested)(scrutineeType, span)

      case Format.OrPattern =>
        val lhs = decodePattern(owner, prevOffset)
        val rhs = decodePattern(owner, lhs.span.endOffset)
        val valueType = decodeType()
        OrPattern(lhs, rhs)(valueType)

      case Format.ValuePattern =>
        val scrutineeType = decodeType()
        val value = decodeWord(owner, prevOffset)
        ValuePattern(value)(scrutineeType)

      case Format.GuardPattern =>
        val pattern = decodePattern(owner, prevOffset)
        val guard = decodeWord(owner, pattern.span.endOffset)
        GuardPattern(pattern, guard)

      case Format.BindPattern =>
        val pattern = decodePattern(owner, prevOffset)

        var lastOffset = pattern.span.endOffset
        val bindings = repeated:
          val binding = decodeWord(owner, lastOffset).asInstanceOf[Assign]
          lastOffset = binding.span.endOffset
          binding

        BindPattern(pattern, bindings)

      case Format.SeqPattern =>
        val scrutineeType = decodeType()
        val startDelta = decodeInt()
        val startOffset = prevOffset + startDelta

        var lastOffset = startOffset
        val pats = repeated:
          val pat = decodeSeqPartPattern(owner, lastOffset)
          lastOffset = pat.span.endOffset
          pat

        val endDelta = decodeInt()
        val span = Span(startOffset, lastOffset + endDelta - startOffset)

        SeqPattern(pats)(scrutineeType, span)

      case Format.WildcardPattern =>
        val scrutineeType = decodeType()
        val startDelta = decodeInt()
        val length = decodeNat()
        val span = Span(prevOffset + startDelta, length)
        WildcardPattern()(scrutineeType, span)

      case _ => throw new Exception(s"Unknown pattern tag: $patternTag")

  private def decodeSeqPartPattern(owner: Symbol, prevOffset: Int)(using buf: ReadBuffer, defn: Definitions, state: State): SeqPartPattern =
    val seqPatTag = decodeByte()
    seqPatTag match
      case Format.AtomPattern =>
        val pattern = decodePattern(owner, prevOffset)
        AtomPattern(pattern)

      case Format.SkipToPattern =>
        val startDelta = decodeInt()
        val startOffset = prevOffset + startDelta

        val nested = decodePattern(owner, startOffset)

        val endDelta = decodeInt()
        val span = Span(startOffset, nested.span.endOffset + endDelta - startOffset)

        SkipToPattern(nested)(span)

      case Format.StarPattern =>
        val startDelta = decodeInt()
        val startOffset = prevOffset + startDelta

        val nested = decodePattern(owner, startOffset)

        val bindings = repeated:
          val sym2 = decodeSymbolRef()

          val id = decodeNat()
          val name = decodeString()
          val info = decodeType()

          val sym1 = PatternSymbol.create(name, info, Flags.empty, Visibility.Default, owner, sym2.sourcePos)
          state.registerInternalSymbol(id, sym1)

          (sym1, sym2)

        val endDelta = decodeInt()
        val span = Span(startOffset, nested.span.endOffset + endDelta - startOffset)

        StarPattern(nested)(span, bindings)

      case Format.RestPattern =>
        val startDelta = decodeInt()
        val startOffset = prevOffset + startDelta

        val nested = decodePattern(owner, startOffset)

        val endDelta = decodeInt()
        val span = Span(startOffset, nested.span.endOffset + endDelta - startOffset)

        RestPattern(nested)(span)

      case _ => throw new Exception(s"Unknown sequence pattern tag: $seqPatTag")

  private def decodeVisibility(owner: Symbol)(using ReadBuffer, State): Visibility =
    decodeByte() match
      case Format.VisibilityDefault => Visibility.Default

      case Format.VisibilityPrivate =>
        val level = decodeNat()
        val within =
          if level == 0 then owner
          else owner.ownersIterator.toList(level - 1)

        Visibility.Private(within)

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

  private def decodeLongNat()(using buf: ReadBuffer): Long =
    buf.readLongNat()

  private def decodeString()(using buf: ReadBuffer, state: State): String =
    val index = decodeNat()
    state.stringTable.get(index)

  private def decodeConstant()(using buf: ReadBuffer, state: State): Constant =
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

  private def decodeSource(stringTable: StringTable)(using buf: ReadBuffer): Source =
    val file = stringTable.get(decodeNat())
    val source = new Source(file)

    val count = decodeNat()

    var offset = 0
    var i = 0
    source.addLineOffset(offset)
    while i < count do
      offset += decodeNat()
      source.addLineOffset(offset)
      i += 1
    end while

    source

  private def repeated[T: ClassTag](decode: => T)(using buf: ReadBuffer): List[T] =
    val count = decodeNat()
    val arr = new Array[T](count)
    var i = 0
    while i < count do
      arr(i) = decode
      i = i + 1
    end while
    arr.toList

end Decoder
