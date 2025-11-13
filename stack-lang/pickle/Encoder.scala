package pickle

import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import reporting.Reporter
import common.IO

import scala.collection.mutable

import java.nio.charset.StandardCharsets.UTF_8

/** Encode trees, symbols and types
  *
  * The format is based on the following design:
  *
  * == Internal Symbols
  *
  *   Internal symbols (local/top-level symbols) are uniquely identified by IDs
  *   between definition sites and usage sites. The IDs are only valid within
  *   the namespace during encoding and decoding.
  *
  * == External symbols
  *
  *   Extenral symbols are identified by full paths to the symbols and its
  *   kind. A global name table is used for all external references.
  *
  *   For linking safety, an external name reference should also store its type.
  *   However, that may bloat file sizes.
  *
  *   ZIP is very good at reducing duplicate strings, so no effort is made in
  *   reducing duplication in names except for shared owners in the prefix.
  *
  *   See https://en.wikipedia.org/wiki/Deflate
  *
  * == Information of symbols
  *
  *   Symbol infos (owner and type) are reconstructed from trees. Flags and
  *   kinds are stored directly.
  *
  *   Duplicated copies are envisioned for language tools (e.g. doc/IDE) in the
  *   future.
  *
  * == Types of trees
  *
  *   Types of trees are reconstructed from leaf nodes. The type is represented
  *   explicitly if reconstruction is not possible.
  *
  * == Positions of trees
  *
  *    Positions of trees in the compiler are represented by an absolute offset
  *    to the beginning of the source file and its length. The representation
  *    can be mapped to line numbers and column numbers based on a lines table.
  *
  *    A lines table stores the length of lines in the source file. The lines
  *    table is encoded compactly based on base128 encoding of each individual
  *    line length. As lines are mostly less than 128 columns, a single byte
  *    suffices for most lines.
  *
  *    Positions of trees can take a lot of spaces in the file. Given that
  *
  *    - the offset of a tree is usually within a small delta compared to that
  *      of its previous tree (starting offset of parent for the first children,
  *      ending offset of the preceding sibling tree otherwise), and
  *
  *    - the length of a tree is usually within a small delta compared to the
  *      total length of its children,
  *
  *    we represent the offset and length of a tree as two deltas, where 1 byte
  *    suffices for most trees based on base128 encoding.
  *
  */
object Encoder:
  private class StringTable:
    private var frozen: Boolean = false
    private val strings = new mutable.ArrayBuffer[String]

    /** For quickly loop up index of existing strings for string interning */
    private val map = new mutable.HashMap[String, Int]

    def getIndex(str: String): Int =
      map.get(str) match
        case Some(index) => index

        case None =>
          if frozen then throw new Exception("String table is frozen")

          val index = strings.size
          strings += str
          map(str) = index
          index

    def encode()(using buf: WriteBuffer) =
      this.frozen = true

      Encoder.encodeNat(strings.size)
      for str <- strings do buf.addUtf8(str)

  /** De Bruijn encoding of bound type parameters in types */
  private class TypeParamScope:
    private val scope = new mutable.ArrayBuffer[Symbol]

    def withParams[T](params: List[Symbol])(fn: => T): T =
      scope ++= params
      val res = fn
      scope.dropRight(params.size)
      res

    def paramIndex(param: Symbol): Int =
      val size = scope.size

      var i = 0
      var found = false
      while !found && i < size do
        val sym = scope(size - i - 1)
        found = sym == param
        i += 1

      if found then i - 1 else -1


  /** A name table maps external symbols to full name and its kind */
  private class NameTable:
    /** Name reference to externally defined symbols */
    private val externalSymbols = new mutable.ArrayBuffer[Symbol]

    def getIndex(sym: Symbol)(using Definitions): Int =
      val index = externalSymbols.indexOf(sym)
      if index < 0 then
        val index = externalSymbols.size
        externalSymbols += sym

        // Ensure owners are in the table
        if sym.owner != null then getIndex(sym.owner)

        index

      else
        index

    def encode()(using Definitions, WriteBuffer, State) =
      Encoder.encodeNat(externalSymbols.size)

      for sym <- externalSymbols do
        val ownerIndex =
          if sym.owner == null then
            -1
          else
            val index = externalSymbols.indexOf(sym.owner)
            assert(index >= 0, "owner not in table: " + sym.fullName)
            index

        encodeInt(ownerIndex)

        encodeString(sym.name)

        if sym.isType then encodeByte(Format.Type)
        else if sym.isPattern then encodeByte(Format.Pattern)
        else encodeByte(Format.Term)

  /** Symbol table map internal symbols to unique ids */
  private class SymbolTable(root: Symbol):
    /** Map a symbol to a unique ID
      *
      * The mapping is defined for all internally defined symbols (top-level and
      * local).
      *
      * The unique ID is only valid within the scope of the namespace for
      * writing/reading.
      *
      * An array is faster than a map.
      */
    private val internalSymbols  = new mutable.ArrayBuffer[Symbol]

    def getId(sym: Symbol)(using Definitions): Int =
      // Type parameter in ProcType and TypeLambda can be external symbols
      //
      // However, the source of those symbols are irrelevant as in essense they
      // are bound names in types.
      assert(sym.containedIn(root) || sym.isTypeParameter, sym.fullName)

      val index = internalSymbols.indexOf(sym)

      if index < 0 then
        val index = internalSymbols.size
        internalSymbols += sym
        index

      else
        index

    def show(): String =
      val sb = new StringBuilder
      sb ++= "[" + System.lineSeparator()
      internalSymbols.zipWithIndex.foreach: (sym, i) =>
        sb ++= i.toString + " -> " + sym + System.lineSeparator()
      sb ++= "]"
      sb.toString

  private class State(val root: Symbol):
    val stringTable = new StringTable
    val nameTable = new NameTable
    val symbolTable = new SymbolTable(root)

    def getId(sym: Symbol)(using Definitions): Int =
      symbolTable.getId(sym)
  end State

  //----------------------------------------------------------------------------

  extension (inline work: Unit)
    inline def <[T](message: => String, enable: Boolean)(using buf: WriteBuffer) =
      inline if enable then
        val start = buf.length
        work
        val delta = buf.length - start
        println(message + ": " + delta)
      else
        work

  inline def checkSubtype[S, T >: S]: Unit = ()

  //----------------------------------------------------------------------------

  def store(ns: Namespace, targetDir: String, testPickling: Boolean, verbose: Boolean = false)(using Definitions, Reporter): Unit =
    val fullName = ns.symbol.fullName
    val fileName = fullName + ".sast"
    val path = java.nio.file.Paths.get(targetDir, fileName).toString

    if verbose then println(s"Generated: $path")

    val buf = Encoder.encode(ns)
    IO.writeFile(path, buf.getBytes, 0, buf.length)

    if testPickling then
      val bytes = IO.fileAsBytes(path)
      given ReadBuffer = new ReadBuffer(bytes)
      val ns2 = Decoder.decode().force()

      val contentBefore = RawPrinter.print(ns).toString
      val contentAfter = RawPrinter.print(ns2).toString

      if contentBefore != contentAfter then
        val before = fullName + "-before.txt"
        val after = fullName + "-after.txt"
        println(s"Test pickling failed, please run `icdiff $before $after`.")

        IO.writeFile(before, contentBefore.getBytes(UTF_8))
        IO.writeFile(after, contentAfter.getBytes(UTF_8))
      end if
    end if


  def encode(ns: Namespace)(using Definitions): WriteBuffer =
    val Namespace(symbol, imports, defs) = ns

    given state: State = new State(symbol)
    given buf: WriteBuffer = new WriteBuffer(1 << 12)

    // Write file header: magic number + version
    encodeIntRaw(Format.MAGIC_NUMBER)
    encodeByte(Format.MAJOR_VERSION)
    encodeByte(Format.MINOR_VERSION)

    // Write owner information (as index to name table, or -1 if null)
    // getIndex automatically adds owner and its ancestors to name table
    val ownerIndex =
      if symbol.owner == null then -1
      else state.nameTable.getIndex(symbol.owner)
    encodeInt(ownerIndex)

    // start of encoding
    val addrStringTable = buf.reserveInt()
    val addrNameTable = buf.reserveInt()

    // Import/alias may refer to the root symbol
    encodeNat(state.getId(symbol))
    encodeString(symbol.name)
    encodeSource(symbol.sourcePos.source)
    encodeNat(symbol.span.start)
    encodeNat(symbol.span.length)

    encodeImports(imports, symbol.span.endOffset)

    repeated(defs): defn =>
      encodeDef(defn)

    // must comes after last
    buf.patchInt(addrNameTable, buf.length)
    state.nameTable.encode() < ("Name table for " + symbol.fullName, enable = false)

    // must comes after last
    buf.patchInt(addrStringTable, buf.length)
    state.stringTable.encode() < ("String table for " + symbol.fullName, enable = false)

    // println(symbol.fullName + " = " + state.symbolTable.show())

    buf

  //----------------------------------------------------------------------------

  /** Encode all imports for a namespace */
  private def encodeImports(imports: List[Symbol], prevOffset: Int)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    var lastOffset = prevOffset
    buf.withLength:
      repeated(imports): sym =>
        // import may rename
        encodeNat(state.getId(sym))
        encodeString(sym.name)

        val target = sym.info.as[StaticRef].symbol
        encodeSymbolRef(target)

        val span = sym.sourcePos.span
        val startDelta = span.start - lastOffset
        lastOffset = span.endOffset

        encodeInt(startDelta)
        encodeNat(span.length)

  /** Reference to an internal or external symbol
    *
    * - Internal symbols are identified by unique ids
    * - External symbols are identified by full name and kind
    */
  private def encodeSymbolRef(symbol: Symbol)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    if symbol.containedIn(state.root) || symbol.isTypeParameter then
      // A type parameter used as a bound name in TypeLambda and ProcType can be
      // externally defined. However, in semantics, we treat them as internally
      // defined.
      encodeByte(0)
      encodeNat(state.getId(symbol))

    else
      assert(!symbol.isLocal, "Cannot reference external local symbol: " + symbol)
      encodeByte(1)
      encodeNat(state.nameTable.getIndex(symbol))

  /** Not all flags need serialization, handled by caller */
  private def encodeFlags(flags: Flags)(using buf: WriteBuffer): Unit =
    val bits = Flags.toBits(flags)
    encodeLongNat(bits)

  private def encodeKind(kind: Kind)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    kind match
      case Kind.Simple =>
        encodeByte(Format.SimpleKind)

      case Kind.Arrow(args, to) =>
        encodeByte(Format.ArrowKind)
        repeated(args) { arg => encodeKind(arg) }
        encodeKind(to)

  private def encodeTypeParams(tparams: List[Symbol], prevOffset: Int)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    // Assume type param section is small such that the delta is small even for the same base offset
    repeated(tparams): tparam =>
      encodeNat(state.getId(tparam))
      encodeString(tparam.name)

      val symSpan = tparam.sourcePos.span
      val startDelta = symSpan.start - prevOffset
      encodeInt(startDelta)
      encodeNat(symSpan.length)

      // TODO: should we have KindInfo to merge the two?
      encodeKind(tparam.asTypeSymbol.kind)
      encodeType(tparam.info)


  private def encodeParams(params: List[Symbol], adapters: List[List[Trees.ParamAdapter]], prevOffset: Int)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    // Assume param section is small such that the delta is small even for the same base offset
    repeated(params.zip(adapters)): (param, paramAdapters) =>
      encodeNat(state.getId(param))
      encodeString(param.name)

      val symSpan = param.sourcePos.span
      val startDelta = symSpan.start - prevOffset
      encodeInt(startDelta)
      encodeNat(symSpan.length)

      encodeType(param.info)

      repeated(paramAdapters): adapter =>
        adapter match
          case func @ Trees.ParamAdapter.Function(symbol) =>
            encodeByte(0) // Tag for function adapter
            encodeSymbolRef(symbol)

            val adapterStartDelta = func.span.start - prevOffset
            encodeInt(adapterStartDelta)
            encodeNat(func.span.length)

          case member @ Trees.ParamAdapter.Member(name) =>
            encodeByte(1) // Tag for member adapter
            encodeString(name)

            val adapterStartDelta = member.span.start - prevOffset
            encodeInt(adapterStartDelta)
            encodeNat(member.span.length)


  private def encodeDef(defn: Def)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    defn match
      case vdef: ValDef => encodeValDef(vdef)
      case pdef: ParamDef => encodeParamDef(pdef)
      case adef: AliasDef => encodeAliasDef(adef)
      case cdef: ClassDef => encodeClassDef(cdef)
      case fdef: FunDef => encodeFunDef(fdef)
      case pdef: PatDef => encodePatDef(pdef)
      case tdef: TypeDef => encodeTypeDef(tdef)
      case sec: Section => encodeSection(sec)

  private def encodeValDef(vdef: ValDef)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    val defSym = vdef.symbol
    val absoluteStart = vdef.span.start

    encodeByte(Format.ValDef)
    encodeNat(absoluteStart)

    encodeNat(state.getId(defSym))
    encodeString(defSym.name)
    encodeFlags(defSym.flags & (Flags.Auto | Flags.Mutable))

    encodeInt(defSym.span.start - absoluteStart)
    encodeNat(defSym.span.length)

    encodeType(defSym.info)
    encodeWord(vdef.rhs, absoluteStart)
    encodeInt(vdef.span.endOffset - vdef.rhs.span.endOffset)

  private def encodeParamDef(pdef: ParamDef)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    val defSym = pdef.symbol
    val absoluteStart = pdef.span.start

    encodeByte(Format.ParamDef)

    buf.withLength:
      encodeNat(absoluteStart)

      encodeNat(state.getId(defSym))
      encodeString(defSym.name)
      encodeFlags(defSym.flags & (Flags.Default | Flags.Auto))
      encodeInt(defSym.span.start - absoluteStart)
      encodeNat(defSym.span.length)
      encodeTypeTree(pdef.tpt, absoluteStart)

      encodeInt(pdef.span.endOffset - pdef.tpt.span.endOffset)

  private def encodeAliasDef(adef: AliasDef)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    val defSym = adef.symbol
    val absoluteStart = adef.span.start

    encodeByte(Format.AliasDef)

    buf.withLength:
      encodeNat(absoluteStart)

      encodeNat(state.getId(defSym))
      encodeString(defSym.name)
      encodeFlags(defSym.flags & (Flags.Pattern | Flags.Fun | Flags.Context | Flags.Auto))
      encodeInt(defSym.span.start - absoluteStart)
      encodeNat(defSym.span.length)
      encodeWord(adef.target, absoluteStart)

      encodeInt(adef.span.endOffset - adef.target.span.endOffset)

  private def encodeClassDef(cdef: ClassDef)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    val defSym = cdef.symbol
    val absoluteStart = cdef.span.start

    encodeByte(Format.ClassDef)

    buf.withLength:
      encodeNat(absoluteStart)

      encodeNat(state.getId(defSym))
      encodeString(defSym.name)
      encodeKind(defSym.asTypeSymbol.kind)

      encodeInt(defSym.span.start - absoluteStart)
      encodeNat(defSym.span.length)

      encodeTypeParams(cdef.tparams, defSym.span.start)

      encodeNat(state.getId(cdef.self))
      encodeString(cdef.self.name)
      encodeFlags(cdef.self.flags & (Flags.Auto | Flags.Synthetic))

      // TODO: maintain members in original order
      repeated(cdef.vals): sym =>
        encodeNat(state.getId(sym))
        encodeString(sym.name)
        encodeFlags(sym.flags & (Flags.Auto | Flags.Mutable))

        val symSpan = sym.sourcePos.span
        val symStartDelta = symSpan.start - defSym.span.start
        encodeInt(symStartDelta)
        encodeNat(symSpan.length)

        encodeType(sym.info)

      var lastOffset = absoluteStart
      repeated(cdef.funs): fdef =>
        encodeDef(fdef)
        lastOffset = fdef.span.endOffset

      encodeNat(cdef.span.endOffset - lastOffset)

  private def encodeFunDef(fdef: FunDef)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    val defSym = fdef.symbol
    val absoluteStart = fdef.span.start

    encodeByte(Format.FunDef)

    buf.withLength:
      encodeNat(absoluteStart)

      encodeNat(state.getId(defSym))
      encodeString(defSym.name)
      encodeFlags(defSym.flags & (Flags.Auto | Flags.Synthetic | Flags.Defer | Flags.Default))

      encodeInt(defSym.span.start - absoluteStart)
      encodeNat(defSym.span.length)

      encodeTypeParams(fdef.tparams, absoluteStart)

      encodeParams(fdef.params, fdef.adapters, absoluteStart)

      repeated(fdef.autos): auto =>
        encodeNat(state.getId(auto))
        encodeString(auto.name)

        val symSpan = auto.sourcePos.span
        val startDelta = symSpan.start - absoluteStart
        encodeInt(startDelta)
        encodeNat(symSpan.length)

        encodeType(auto.info)

      encodeTypeTree(fdef.resultType, absoluteStart)

      repeated(fdef.procType.receives): eff =>
        encodeSymbolRef(eff)

      encodeNat(defSym.info.as[ProcType].preParamCount)

      encodeWord(fdef.body, fdef.resultType.span.endOffset)

      encodeInt(fdef.span.endOffset - fdef.body.span.endOffset)

  private def encodePatDef(pdef: PatDef)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    val defSym = pdef.symbol
    val absoluteStart = pdef.span.start

    encodeByte(Format.PatDef)

    buf.withLength:
      encodeNat(absoluteStart)

      encodeNat(state.getId(defSym))
      encodeString(defSym.name)
      encodeInt(defSym.span.start - absoluteStart)
      encodeNat(defSym.span.length)

      encodeTypeParams(pdef.tparams, absoluteStart)

      repeated(pdef.params): param =>
        encodeNat(state.getId(param))
        encodeString(param.name)

        val symSpan = param.sourcePos.span
        val startDelta = symSpan.start - absoluteStart
        encodeInt(startDelta)
        encodeNat(symSpan.length)

        encodeType(param.info)

      encodeTypeTree(pdef.resultType, absoluteStart)

      repeated(pdef.procType.receives): eff =>
        encodeSymbolRef(eff)

      encodeNat(defSym.info.as[ProcType].preParamCount)

      encodePattern(pdef.body, pdef.resultType.span.endOffset)

      encodeInt(pdef.span.endOffset - pdef.body.span.endOffset)

  private def encodeTypeDef(tdef: TypeDef)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    val defSym = tdef.symbol
    val absoluteStart = tdef.span.start

    encodeByte(Format.TypeDef)

    buf.withLength:
      encodeNat(absoluteStart)

      encodeNat(state.getId(defSym))
      encodeString(defSym.name)
      encodeKind(defSym.asTypeSymbol.kind)

      encodeInt(defSym.span.start - absoluteStart)
      encodeNat(defSym.span.length)

      encodeType(defSym.info)
      encodeNat(tdef.span.length)

  private def encodeSection(sec: Section)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    val defSym = sec.symbol
    val absoluteStart = sec.span.start

    encodeByte(Format.Section)

    // length is redundant --- keep it for extension and uniformity
    buf.withLength:
      encodeNat(absoluteStart)

      encodeNat(state.getId(defSym))
      encodeString(defSym.name)
      encodeInt(defSym.span.start - absoluteStart)
      encodeNat(defSym.span.length)

      var lastOffset = absoluteStart
      repeated(sec.defs): defn =>
        encodeDef(defn)
        lastOffset = defn.span.endOffset

      encodeNat(sec.span.endOffset - lastOffset)

  private def encodeTypeTree(tpt: TypeTree, prevOffset: Int)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    val startDelta = tpt.span.start - prevOffset

    encodeType(tpt.tpe)
    encodeInt(startDelta)
    encodeNat(tpt.span.length)

  private def encodeType
      (tpe: Type, tparamScope: TypeParamScope = new TypeParamScope)
      (using defn: Definitions, state: State, buf: WriteBuffer)
  : Unit =

    tpe match
      case VoidType => encodeByte(Format.VoidType)

      case ErrorType => encodeByte(Format.ErrorType)

      case AnyType => encodeByte(Format.AnyType)

      case BottomType => encodeByte(Format.BottomType)

      case StaticRef(sym) =>
        // It can be either TypeParamRef or StaticRef
        val index = tparamScope.paramIndex(sym)
        if index == -1 then
          encodeByte(Format.StaticRef)
          encodeSymbolRef(sym)
        else
          encodeByte(Format.TypeParamRef)
          encodeNat(index)

      case MemberRef(prefix, sym) =>
        encodeByte(Format.MemberRef)
        encodeType(prefix, tparamScope)
        encodeSymbolRef(sym)

      case tvar: TypeVar =>
        assert(tvar.isInstantiated, "uninstantiated type variable: " + tvar)
        encodeType(tvar.instantiated, tparamScope)

      case ConstantType(const) =>
        encodeByte(Format.ConstantType)
        encodeConstant(const)

      case RecordType(fields) =>
        encodeByte(Format.RecordType)
        repeated(fields): f =>
          encodeString(f.name)
          encodeType(f.info, tparamScope)

      case UnionType(branches) =>
        encodeByte(Format.UnionType)

        repeated(branches): branch =>
          encodeType(branch, tparamScope)

      case TagType(tag, params) =>
        encodeByte(Format.TagType)

        encodeString(tag)
        repeated(params): f =>
          encodeString(f.name)
          encodeType(f.info, tparamScope)

      case ObjectType(members, muts) =>
        encodeByte(Format.ObjectType)

        encodeNat(members.size)
        for NamedInfo(name, info) <- members do
          encodeString(name)
          encodeType(info, tparamScope)
          if info.isValueType then
            encodeBool(muts.contains(name))

      case AppliedType(tctor, targs) =>
        encodeByte(Format.AppliedType)
        encodeType(tctor, tparamScope)
        repeated(targs): targ =>
          encodeType(targ, tparamScope)

      case procType @ ProcType(tparams, params, adapters, autos, resType, _, preParamCount) =>
        encodeByte(Format.ProcType)

        // Local type symbols in types only need to store bound and name.
        //
        // The position information is irrelevant.
        tparamScope.withParams(tparams):
          repeated(tparams): tparam =>
            encodeString(tparam.name)
            encodeKind(tparam.asTypeSymbol.kind)
            encodeType(tparam.info, tparamScope)

          repeated(params.zip(adapters)): (param, paramAdapters) =>
            encodeString(param.name)
            encodeType(param.info, tparamScope)
            repeated(paramAdapters): adapter =>
              adapter match
                case sym: Symbol =>
                  encodeByte(0) // Tag for function adapter
                  encodeSymbolRef(sym)

                case name: String =>
                  encodeByte(1) // Tag for member adapter
                  encodeString(name)

          repeated(autos): auto =>
            encodeString(auto.name)
            encodeType(auto.info, tparamScope)

          encodeType(resType, tparamScope)

          repeated(procType.receives): eff =>
            encodeSymbolRef(eff)

          encodeNat(preParamCount)

      case TypeLambda(tparams, resType, preParamCount) =>
        encodeByte(Format.TypeLambda)

        // Local type symbols in types only need to store id, bound and name.
        //
        // The position information is irrelevant.
        tparamScope.withParams(tparams):
          repeated(tparams): tparam =>
            encodeString(tparam.name)
            encodeKind(tparam.asTypeSymbol.kind)
            encodeType(tparam.info, tparamScope)

          encodeType(resType, tparamScope)
          encodeNat(preParamCount)

      case TypeBound(lo, hi) =>
        encodeByte(Format.TypeBound)
        encodeType(lo, tparamScope)
        encodeType(hi, tparamScope)

      case _: ContainerInfo | _: ClassInfo =>
        throw new Exception("Unexpected type " + tpe)

  private def encodeWord(word: Word, prevOffset: Int)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    val startDelta = word.span.start - prevOffset

    word match
      case Literal(const) =>
        encodeByte(Format.Literal)
        encodeConstant(const)
        encodeType(word.tpe)
        encodeInt(startDelta)
        encodeNat(word.span.length)

      case Ident(sym) =>
        encodeByte(Format.Ident)
        encodeSymbolRef(sym)
        encodeInt(startDelta)
        encodeNat(word.span.length)

      case New(classRef, targs) =>
        encodeByte(Format.New)
        encodeInt(startDelta)
        encodeWord(classRef, word.span.start)

        var lastOffset = classRef.span.endOffset
        repeated(targs): targ =>
          encodeTypeTree(targ, lastOffset)
          lastOffset = targ.span.endOffset

        encodeInt(word.span.endOffset - lastOffset)

      case Select(qual, name) =>
        encodeByte(Format.Select)
        encodeInt(startDelta)
        encodeWord(qual, word.span.start)
        encodeString(name)
        encodeInt(word.span.endOffset - qual.span.endOffset)

      case RecordLit(fields) =>
        encodeByte(Format.RecordLit)
        encodeInt(startDelta)

        var lastOffset = word.span.start
        repeated(fields):
          case (f, rhs) =>
            encodeString(f)
            encodeWord(rhs, lastOffset)
            lastOffset = rhs.span.endOffset

        encodeInt(word.span.endOffset - lastOffset)

      case TaggedLit(tag, args) =>
        encodeByte(Format.TaggedLit)
        encodeInt(startDelta)
        encodeWord(tag, word.span.start)

        var lastOffset = tag.span.endOffset
        repeated(args): arg =>
          encodeWord(arg, lastOffset)
          lastOffset = arg.span.endOffset

        encodeType(word.tpe)
        encodeInt(word.span.endOffset - lastOffset)

      case Encoded(repr) =>
        checkSubtype[Encoded, DerivedSpan]

        encodeByte(Format.Encoded)
        encodeWord(repr, prevOffset)
        encodeType(word.tpe)

      case Apply(fun, args, autos) =>
        encodeByte(Format.Apply)

        encodeInt(startDelta)
        encodeWord(fun, word.span.start)

        var lastOffset = fun.span.endOffset
        repeated(args): arg =>
          encodeWord(arg, lastOffset)
          lastOffset = arg.span.endOffset

        repeated(autos): auto =>
          encodeWord(auto, lastOffset)
          lastOffset = auto.span.endOffset

        encodeInt(word.span.endOffset - lastOffset)

      case TypeApply(fun, targs) =>
        encodeByte(Format.TypeApply)

        encodeInt(startDelta)
        encodeWord(fun, word.span.start)

        var lastOffset = fun.span.endOffset
        repeated(targs): targ =>
          encodeTypeTree(targ, lastOffset)
          lastOffset = targ.span.endOffset

        encodeInt(word.span.endOffset - lastOffset)

      case With(expr, args) =>
        checkSubtype[With, DerivedSpan]

        encodeByte(Format.With)

        encodeWord(expr, prevOffset)

        var lastOffset = expr.span.endOffset
        repeated(args):
          case Assign(ident, rhs) =>
            encodeWord(ident, lastOffset)
            encodeWord(rhs, ident.span.endOffset)
            lastOffset = rhs.span.endOffset

      case Allow(expr, params) =>
        checkSubtype[Allow, DerivedSpan]

        encodeByte(Format.Allow)

        encodeWord(expr, prevOffset)

        var lastOffset = expr.span.endOffset
        repeated(params): param =>
          encodeWord(param, prevOffset)
          lastOffset = param.span.endOffset

      case Assign(ident, rhs) =>
        checkSubtype[Assign, DerivedSpan]

        encodeByte(Format.Assign)
        encodeWord(ident, prevOffset)
        encodeWord(rhs, ident.span.endOffset)

      case FieldAssign(lhs, rhs) =>
        checkSubtype[FieldAssign, DerivedSpan]

        encodeByte(Format.FieldAssign)
        encodeWord(lhs, prevOffset)
        encodeWord(rhs, lhs.span.endOffset)

      case vdef: ValDef => encodeDef(vdef)

      case fdef: FunDef => encodeDef(fdef)

      case tdef: TypeDef => encodeDef(tdef)

      case pdef: PatDef => encodeDef(pdef)

      case If(cond, thenp, elsep) =>
        encodeByte(Format.If)
        encodeInt(startDelta)

        encodeWord(cond, word.span.start)
        encodeWord(thenp, cond.span.endOffset)
        encodeWord(elsep, thenp.span.endOffset)
        encodeType(word.tpe)

        encodeInt(word.span.endOffset - elsep.span.endOffset)

      case While(cond, body) =>
        encodeByte(Format.While)
        encodeInt(startDelta)
        encodeWord(cond, word.span.start)
        encodeWord(body, cond.span.endOffset)
        encodeInt(word.span.endOffset - body.span.endOffset)

      case Block(words) =>
        encodeByte(Format.Block)
        encodeInt(startDelta)

        var lastOffset = word.span.start
        repeated(words): word =>
          encodeWord(word, lastOffset)
          lastOffset = word.span.endOffset

        encodeInt(word.span.endOffset - lastOffset)

      case Match(scrutinee, cases) =>
        encodeByte(Format.Match)

        encodeInt(startDelta)

        encodeWord(scrutinee, word.span.start)

        var lastOffset = scrutinee.span.endOffset
        repeated(cases):
          case cas @ Case(pat, body) =>

            encodeInt(cas.span.start - lastOffset)
            encodePattern(pat, cas.span.start)
            encodeWord(body, pat.span.endOffset)
            encodeInt(cas.span.endOffset - body.span.endOffset)

            lastOffset = body.span.endOffset

        encodeType(word.tpe)
        encodeInt(word.span.endOffset - lastOffset)

      case Object(self, members) =>
        encodeByte(Format.Object)

        encodeInt(startDelta)

        encodeNat(state.getId(self))
        encodeString(self.name)
        encodeFlags(self.flags)
        encodeInt(self.sourcePos.span.start - word.span.start)
        encodeNat(self.sourcePos.span.length)

        var lastOffset = word.span.start
        repeated(members): m =>
          encodeDef(m)
          lastOffset = m.span.endOffset

        encodeInt(word.span.endOffset - lastOffset)

  private def encodePattern(pattern: Pattern, prevOffset: Int)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    val startDelta = pattern.span.start - prevOffset

    pattern match
      case apat @ AliasPattern(ident, nested) =>
        checkSubtype[AliasPattern, DerivedSpan]

        encodeByte(Format.AliasPattern)

        val sym = ident.symbol

        val isDef = apat.isDefinition
        encodeBool(isDef)
        encodeInt(startDelta)
        encodeNat(state.getId(sym))
        encodeNat(ident.span.length)

        encodePattern(nested, ident.span.endOffset)

        if isDef then
          encodeString(sym.name)

      case TypePattern(tpt) =>
        checkSubtype[TypePattern, DerivedSpan]

        encodeByte(Format.TypePattern)
        encodeType(pattern.scrutineeType)
        encodeTypeTree(tpt, prevOffset)

      case TagPattern(tagLit, nested) =>
        encodeByte(Format.TagPattern)
        encodeInt(startDelta)
        encodeType(pattern.scrutineeType)
        encodeType(pattern.valueType)
        encodeWord(tagLit, pattern.span.start)

        var lastOffset = tagLit.span.endOffset
        repeated(nested): pat =>
          encodePattern(pat, lastOffset)
          lastOffset = pat.span.endOffset

        encodeInt(pattern.span.endOffset - lastOffset)

      case ApplyPattern(fun, nested) =>
        encodeByte(Format.ApplyPattern)
        encodeInt(startDelta)
        encodeType(pattern.scrutineeType)
        encodeWord(fun, prevOffset)

        var lastOffset = fun.span.endOffset
        repeated(nested): pat =>
          encodePattern(pat, lastOffset)
          lastOffset = pat.span.endOffset

        encodeInt(pattern.span.endOffset - lastOffset)

      case OrPattern(lhs, rhs) =>
        checkSubtype[OrPattern, DerivedSpan]

        encodeByte(Format.OrPattern)
        encodePattern(lhs, prevOffset)
        encodePattern(rhs, lhs.span.endOffset)
        encodeType(pattern.valueType)

      case ValuePattern(value) =>
        checkSubtype[ValuePattern, DerivedSpan]

        encodeByte(Format.ValuePattern)
        encodeType(pattern.scrutineeType)
        encodeWord(value, prevOffset)

      case GuardPattern(pattern, guard) =>
        checkSubtype[GuardPattern, DerivedSpan]

        encodeByte(Format.GuardPattern)
        encodePattern(pattern, prevOffset)
        encodeWord(guard, pattern.span.endOffset)

      case BindPattern(pattern, bindings) =>
        checkSubtype[BindPattern, DerivedSpan]

        encodeByte(Format.BindPattern)
        encodePattern(pattern, prevOffset)

        var lastOffset = pattern.span.endOffset
        repeated(bindings): binding =>
          encodeWord(binding, lastOffset)
          lastOffset = binding.span.endOffset

      case SeqPattern(pats) =>
        encodeByte(Format.SeqPattern)
        encodeType(pattern.scrutineeType)
        encodeInt(startDelta)

        var lastOffset = pattern.span.start
        repeated(pats): pat =>
          encodeSeqPartPattern(pat, lastOffset)
          lastOffset = pat.span.endOffset

        encodeInt(pattern.span.endOffset - lastOffset)

      case WildcardPattern() =>
        encodeByte(Format.WildcardPattern)
        encodeType(pattern.scrutineeType)
        encodeInt(startDelta)
        encodeNat(pattern.span.length)

  private def encodeSeqPartPattern(pattern: SeqPartPattern, prevOffset: Int)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    val startDelta = pattern.span.start - prevOffset

    pattern match
      case AtomPattern(nested) =>
        checkSubtype[AtomPattern, DerivedSpan]

        encodeByte(Format.AtomPattern)
        encodePattern(nested, prevOffset)

      case SkipToPattern(nested) =>
        encodeByte(Format.SkipToPattern)

        encodeInt(startDelta)
        encodePattern(nested, pattern.span.start)
        encodeInt(pattern.span.endOffset - nested.span.endOffset)

      case star @ StarPattern(nested) =>
        encodeByte(Format.StarPattern)

        encodeInt(startDelta)

        encodePattern(nested, pattern.span.start)

        repeated(star.bindings): (sym1, sym2) =>
          encodeSymbolRef(sym2)

          val id = state.getId(sym1)
          encodeNat(id)
          encodeString(sym1.name)
          encodeType(sym1.info)

        encodeInt(pattern.span.endOffset - nested.span.endOffset)

      case RestPattern(nested) =>
        encodeByte(Format.RestPattern)

        encodeInt(startDelta)
        encodePattern(nested, pattern.span.start)
        encodeInt(pattern.span.endOffset - nested.span.endOffset)

    end match

  private def encodeBool(b: Boolean)(using buf: WriteBuffer): Unit =
    buf.addBool(b)

  private def encodeByte(b: Byte)(using buf: WriteBuffer): Unit =
    buf.addByte(b)

  private def encodeInt(n: Int)(using buf: WriteBuffer): Unit =
    buf.addInt(n)

  private def encodeIntRaw(n: Int)(using buf: WriteBuffer): Unit =
    buf.addIntRaw(n)

  private def encodeNat(n: Int)(using buf: WriteBuffer): Unit =
    buf.addNat(n)

  private def encodeLongNat(n: Long)(using buf: WriteBuffer): Unit =
    buf.addLongNat(n)

  private def encodeString(s: String)(using buf: WriteBuffer, state: State): Unit =
    val index = state.stringTable.getIndex(s)
    encodeNat(index)

  private def encodeConstant(const: Constant)(using WriteBuffer, State): Unit =
    const match
      case Constant.Bool(value) =>
        encodeByte(Format.BoolConst)
        encodeBool(value)

      case Constant.Int(value) =>
        encodeByte(Format.IntConst)
        encodeInt(value)

      case Constant.String(value) =>
        encodeByte(Format.StringConst)
        encodeString(value)

  /** Encode line lengths as comma-separated hexadecimal */
  private def encodeSource(source: Source)(using WriteBuffer, State): Unit =
    encodeString(source.file)
    val lineLengths = source.lineLengths
    repeated(lineLengths) { len => encodeNat(len) }

  private def repeated[T](items: Iterable[T])(encode: T => Unit)(using buf: WriteBuffer): Unit =
    encodeNat(items.size)
    for item <- items do encode(item)
