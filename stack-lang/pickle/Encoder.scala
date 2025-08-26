package pickle

import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import scala.collection.mutable

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
  *   A global symbol table is used for top-level internal symbols.
  *
  *   An alternative design is to use the address of the definition as the ID
  *   of the symbol. That would require backpatch forward symbols which are
  *   referred in the code parts before they are serialized. This design could
  *   reduce file size and remove the need for a global table. This approach
  *   might be pursued in the final design.
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
  *   reducing duplication in names.
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
  *    suffices for each delta based on base128 encoding.
  *
  */
object Encoder:
  private class State(val root: Symbol):
    /** Name reference to externally defined symbols */
    private val externalSymbols = new mutable.ArrayBuffer[Symbol]

    /** Map a symbol to a unique ID
      *
      * The mapping is defined for all internally defined symbols (top-level and
      * local).
      *
      * The unique ID is only valid within the scope of the namespace for
      * writing/reading.
      */
    private val internalSymIds = mutable.Map.empty[Symbol, Int]

    private var internalSymbolCount = 0

    /** Ending offset of the last sast node */
    private var lastEndingOffset = 0

    /** The length of direct children of a sast node */
    private var childrenLength = 0

    def getChildrenLength = childrenLength

    /** Maintaining last ending offset and children length
      *
      * The method should be called for each positioned node and the actual
      * encoding should happen in the supplied function `fn`.
      */
    def withPositioned[T](node: Positioned)(fn: Int => T): T =
      val startDelta = node.span.start - lastEndingOffset
      // For the first child
      lastEndingOffset = node.span.start

      val oldLength = childrenLength
      childrenLength = 0

      val res = fn(startDelta)
      lastEndingOffset = node.span.endOffset
      childrenLength = oldLength + node.span.length
      res

    def getExternalSymbolIndex(sym: Symbol): Int =
      val index = externalSymbols.indexOf(sym)
      if index < 0 then
        val index = externalSymbols.size
        externalSymbols += sym
        index

      else
        index

    def internalId(sym: Symbol)(using Definitions): Int =
      assert(sym.containedIn(root) || sym.isTypeParameter, sym.fullName)
      internalSymIds.get(sym) match
        case Some(id) => id
        case None =>
          val id = internalSymbolCount
          internalSymIds(sym) = id
          internalSymbolCount += 1
          id
      end match

    def encodeExternalNameTable()(using defn: Definitions, buf: WriteBuffer) =
      Encoder.encodeNat(externalSymbols.size)
      for sym <- externalSymbols do encodeString(sym.fullName)

  end State

  //----------------------------------------------------------------------------

  def encode(ns: Namespace)(using Definitions): WriteBuffer =
    val Namespace(symbol, imports, defs) = ns

    given state: State = new State(symbol)
    given buf: WriteBuffer = new WriteBuffer(1 << 12)

    encodeString(symbol.fullName)

    val addrNameTable = buf.reserveInt()

    encodeSource(symbol.sourcePos.source)

    repeated(defs) { defn => encodeDef(defn) }

    // must comes after last
    buf.patchInt(addrNameTable, buf.length)
    state.encodeExternalNameTable()

    buf

  //----------------------------------------------------------------------------

  /** Definition of a symbol */
  private def encodeSymbol(symbol: Symbol)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    // TODO: attributes, comments

    val id = state.internalId(symbol)
    encodeNat(id)
    encodeString(symbol.name)
    encodeFlags(symbol.flags)

    symbol match
      case tsym: TypeSymbol => encodeKind(tsym.kind)
      case _ =>

    if symbol.owner == null then
      encodeInt(-1)

    else
      val ownerId = state.internalId(symbol.owner)
      encodeNat(ownerId)

    encodeNat(symbol.sourcePos.start)
    encodeNat(symbol.sourcePos.length)
    encodeType(symbol.info)

  /** Reference to an internal or external symbol
    *
    * - Internal symbols are identified by unique ids
    * - External symbols are identified by full name and kind
    */
  private def encodeSymbolRef(symbol: Symbol)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    if symbol.containedIn(state.root) then
      encodeByte(0)
      encodeNat(state.internalId(symbol))

    else
      assert(!symbol.isLocal, "Cannot reference external local symbol: " + symbol)
      encodeByte(1)
      encodeNat(state.getExternalSymbolIndex(symbol))

  private def encodeFlags(flags: Flags)(using buf: WriteBuffer): Unit =
    // Not all flags need serialization, handled by caller
    val indices = Flags.flagIndices(flags)
    assert(indices.size < 128)
    // TODO: use negative value to signify end of flags
    encodeByte(indices.size.toByte)
    for i <- indices do encodeByte(i)

  private def encodeKind(kind: Kind)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    kind match
      case Kind.Simple =>
        encodeByte(0)

      case Kind.Arrow(args, to) =>
        assert(args.size < 128, args.size)
        encodeByte(1)
        repeated(args) { arg => encodeKind(arg) }
        encodeKind(to)

  private def encodeTypeParams(defn: Def, tparams: List[Symbol])(using definitions: Definitions, state: State, buf: WriteBuffer): Unit =
    repeated(tparams): tparam =>
      encodeNat(state.internalId(tparam))
      encodeString(tparam.name)
      encodeType(tparam.info)

      val symSpan = tparam.sourcePos.span
      val startDelta = symSpan.start - defn.span.start
      encodeInt(startDelta)
      encodeInt(symSpan.length)

  private def encodeDef(defn: Def)(using definitions: Definitions, state: State, buf: WriteBuffer): Unit = state.withPositioned(defn): startDelta =>
    defn match
      case pdef: ParamDef =>
        encodeByte(Format.ParamDef)
        encodeFlags(pdef.symbol.flags & Flags.Default)
        encodeString(pdef.symbol.name)
        encodeNat(state.internalId(pdef.symbol))
        encodeTypeTree(pdef.tpt)

      case vdef: ValDef =>
        encodeByte(Format.ValDef)
        encodeNat(state.internalId(vdef.symbol))
        encodeFlags(vdef.symbol.flags & (Flags.Auto | Flags.Mutable))
        encodeString(vdef.symbol.name)
        encodeType(vdef.symbol.info)
        encodeWord(vdef.rhs)

      case cdef: ClassDef =>
        encodeByte(Format.ClassDef)

        encodeNat(state.internalId(cdef.symbol))
        encodeString(cdef.symbol.name)

        encodeTypeParams(cdef, cdef.tparams)

        encodeNat(state.internalId(cdef.self))
        encodeFlags(cdef.self.flags & Flags.Auto)
        encodeString(cdef.self.name)

        repeated(cdef.vals): sym =>
          encodeNat(state.internalId(sym))
          encodeFlags(sym.flags & (Flags.Auto | Flags.Mutable))
          encodeString(sym.name)
          encodeType(sym.info)

          val symSpan = sym.sourcePos.span
          val startDelta = symSpan.start - cdef.span.start
          encodeInt(startDelta)
          encodeInt(symSpan.length)

        repeated(cdef.funs): fdef =>
          encodeDef(fdef)

      case fdef: FunDef =>
        encodeByte(Format.FunDef)

        encodeNat(state.internalId(fdef.symbol))
        encodeFlags(fdef.symbol.flags & (Flags.Auto))
        encodeString(fdef.symbol.name)

        encodeTypeParams(fdef, fdef.tparams)

        repeated(fdef.params): param =>
          encodeNat(state.internalId(param))
          encodeString(param.name)
          encodeType(param.info)

          val symSpan = param.sourcePos.span
          val startDelta = symSpan.start - fdef.span.start
          encodeInt(startDelta)
          encodeInt(symSpan.length)

        repeated(fdef.autos): auto =>
          encodeNat(state.internalId(auto))
          encodeString(auto.name)
          encodeType(auto.info)

          val symSpan = auto.sourcePos.span
          val startDelta = symSpan.start - fdef.span.start
          encodeInt(startDelta)
          encodeInt(symSpan.length)

        encodeTypeTree(fdef.resultType)

        repeated(fdef.procType.receives): eff =>
          encodeSymbolRef(eff)

        encodeWord(fdef.body)

      case pdef: PatDef =>
        encodeByte(Format.PatDef)

        encodeNat(state.internalId(pdef.symbol))
        encodeFlags(pdef.symbol.flags)
        encodeString(pdef.symbol.name)

        encodeTypeParams(pdef, pdef.tparams)

        repeated(pdef.params): param =>
          encodeNat(state.internalId(param))
          encodeString(param.name)
          encodeType(param.info)

          val symSpan = param.sourcePos.span
          val startDelta = symSpan.start - pdef.span.start
          encodeInt(startDelta)
          encodeInt(symSpan.length)

        encodeTypeTree(pdef.resultType)

        repeated(pdef.procType.receives): eff =>
          encodeSymbolRef(eff)

        encodePattern(pdef.body)

      case tdef: TypeDef =>
        encodeByte(Format.TypeDef)
        encodeNat(state.internalId(tdef.symbol))
        encodeString(tdef.symbol.name)
        encodeType(tdef.symbol.info)

      case sec: Section =>
        encodeByte(Format.Section)
        encodeString(sec.symbol.name)

        repeated(sec.defs): defn =>
          encodeDef(defn)

    val lengthDelta = defn.span.length - state.getChildrenLength
    encodeInt(startDelta)
    encodeInt(lengthDelta)

  private def encodeTypeTree(tpt: TypeTree)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    state.withPositioned(tpt): startDelta =>
      encodeType(tpt.tpe)
      encodeInt(startDelta)
      encodeInt(tpt.span.length)

  private def encodeType(tpe: Type)(using defn: Definitions, state: State, buf: WriteBuffer): Unit =
    tpe match
      case VoidType => encodeByte(Format.VoidType)

      case ErrorType => encodeByte(Format.ErrorType)

      case AnyType => encodeByte(Format.AnyType)

      case BottomType => encodeByte(Format.BottomType)

      case StaticRef(sym) =>
        encodeByte(Format.StaticRef)
        encodeSymbolRef(sym)

      case MemberRef(prefix, sym) =>
        encodeByte(Format.MemberRef)
        encodeType(prefix)
        encodeSymbolRef(sym)

      case tvar: TypeVar =>
        assert(tvar.isInstantiated, "uninstantiated type variable: " + tvar)
        encodeType(tvar.instantiated)

      case ConstantType(const) =>
        encodeByte(Format.ConstantType)
        encodeConstant(const)

      case RecordType(fields) =>
        encodeByte(Format.RecordType)
        repeated(fields): f =>
          encodeString(f.name)
          encodeType(f.info)

      case UnionType(branches) =>
        encodeByte(Format.UnionType)

        repeated(branches): branch =>
          encodeType(branch)

      case TagType(tag, params) =>
        encodeByte(Format.TagType)

        repeated(params): f =>
          encodeString(f.name)
          encodeType(f.info)

      case ObjectType(members, muts) =>
        encodeByte(Format.ObjectType)

        encodeNat(members.size)
        for NamedInfo(name, info) <- members do
          encodeString(name)
          encodeType(info)
          if info.isValueType then
            encodeBool(muts.contains(name))

      case AppliedType(tctor, targs) =>
        encodeByte(Format.AppliedType)
        encodeType(tctor)
        repeated(targs): targ =>
          encodeType(targ)

      case procType @ ProcType(tparams, params, autos, resType, _, preParamCount) =>
        encodeByte(Format.ProcType)

        // Local type symbols in types only need to store id, bound and name.
        //
        // The position information is irrelevant.
        repeated(tparams): tparam =>
          // The type param can be external
          encodeNat(state.internalId(tparam))
          encodeString(tparam.name)
          encodeType(tparam.info)

        repeated(params): param =>
          encodeString(param.name)
          encodeType(param.info)

        repeated(autos): auto =>
          encodeString(auto.name)
          encodeType(auto.info)

        encodeType(resType)

        repeated(procType.receives): eff =>
          encodeSymbolRef(eff)

        encodeNat(preParamCount)

      case TypeLambda(tparams, resType, preParamCount) =>
        encodeByte(Format.TypeLambda)

        // Local type symbols in types only need to store id, bound and name.
        //
        // The position information is irrelevant.
        repeated(tparams): tparam =>
          // The type param can be external
          encodeNat(state.internalId(tparam))
          encodeString(tparam.name)
          encodeType(tparam.info)

        encodeType(resType)
        encodeNat(preParamCount)

      case TypeBound(lo, hi) =>
        encodeByte(Format.TypeBound)
        encodeType(lo)
        encodeType(hi)

      case _: ContainerInfo | _: ClassInfo =>
        throw new Exception("Unexpected type " + tpe)

  private def encodeWord(word: Word)(using defn: Definitions, state: State, buf: WriteBuffer): Unit = state.withPositioned(word): startDelta =>
    word match
      case Literal(const) =>
        encodeByte(Format.Literal)
        encodeConstant(const)
        encodeType(word.tpe)

      case Ident(sym) =>
        encodeByte(Format.Ident)
        encodeSymbolRef(sym)

      case New(classRef, targs) =>
        encodeByte(Format.New)
        encodeWord(classRef)
        repeated(targs): targ =>
          encodeTypeTree(targ)

      case Select(qual, name) =>
        encodeByte(Format.Select)
        encodeWord(qual)
        encodeString(name)

      case RecordLit(fields) =>
        encodeByte(Format.RecordLit)
        repeated(fields):
          case (f, rhs) =>
            encodeString(f)
            encodeWord(rhs)

      case TaggedLit(tag, args) =>
        encodeByte(Format.TaggedLit)
        encodeWord(tag)
        repeated(args) { arg => encodeWord(arg) }

      case Encoded(repr) =>
        encodeByte(Format.Encoded)
        encodeWord(repr)
        encodeType(word.tpe)

      case Apply(fun, args, autos) =>
        encodeByte(Format.Apply)
        encodeWord(fun)
        repeated(args) { arg => encodeWord(arg) }
        repeated(autos) { auto => encodeWord(auto) }

      case TypeApply(fun, targs) =>
        encodeByte(Format.TypeApply)
        encodeWord(fun)
        repeated(targs) { targ => encodeTypeTree(targ) }

      case With(expr, args) =>
        encodeByte(Format.With)
        encodeWord(expr)
        repeated(args):
          case Assign(ident, rhs) =>
            encodeWord(ident)
            encodeWord(rhs)

      case Allow(expr, params) =>
        encodeByte(Format.Allow)
        encodeWord(expr)
        repeated(params) { param => encodeWord(param) }

      case Assign(ident, rhs) =>
        encodeByte(Format.Assign)
        encodeWord(ident)
        encodeWord(rhs)

      case FieldAssign(lhs, rhs) =>
        encodeByte(Format.FieldAssign)
        encodeWord(lhs)
        encodeWord(rhs)

      case vdef: ValDef => encodeDef(vdef)

      case fdef: FunDef => encodeDef(fdef)

      case tdef: TypeDef => encodeDef(tdef)

      case pdef: PatDef => encodeDef(pdef)

      case If(cond, thenp, elsep) =>
        encodeByte(Format.If)
        encodeWord(cond)
        encodeWord(thenp)
        encodeWord(elsep)
        encodeType(word.tpe)

      case While(cond, body) =>
        encodeByte(Format.While)
        encodeWord(cond)
        encodeWord(body)

      case Block(words) =>
        encodeByte(Format.Block)
        repeated(words) { word => encodeWord(word) }

      case Match(scrutinee, cases) =>
        encodeByte(Format.Match)
        encodeWord(scrutinee)
        repeated(cases):
          case Case(pat, body) =>
            encodePattern(pat)
            encodeWord(body)

        encodeType(word.tpe)

      case Object(self, members) =>
        encodeByte(Format.Object)

        encodeNat(state.internalId(self))
        encodeString(self.name)

        repeated(members) { m => encodeDef(m) }

    val lengthDelta = word.span.length - state.getChildrenLength
    encodeInt(startDelta)
    encodeInt(lengthDelta)

  private def encodePattern(pattern: Pattern)(using defn: Definitions, state: State, buf: WriteBuffer): Unit = state.withPositioned(pattern): startDelta =>
    pattern match
      case AliasPattern(id, nested) =>
        encodeByte(Format.AliasPattern)
        encodeWord(id)
        encodePattern(nested)

      case TypePattern(tpt) =>
        encodeByte(Format.TypePattern)
        encodeType(pattern.scrutineeType)
        encodeTypeTree(tpt)

      case TagPattern(tagLit, nested) =>
        encodeByte(Format.TagPattern)
        encodeWord(tagLit)
        repeated(nested) { pat => encodePattern(pat) }

      case ApplyPattern(fun, nested) =>
        encodeByte(Format.ApplyPattern)
        encodeType(pattern.scrutineeType)
        encodeWord(fun)
        repeated(nested) { pat => encodePattern(pat) }

      case OrPattern(lhs, rhs) =>
        encodeByte(Format.OrPattern)
        encodePattern(lhs)
        encodePattern(rhs)

      case ValuePattern(value) =>
        encodeByte(Format.ValuePattern)
        encodeType(pattern.scrutineeType)
        encodeWord(value)

      case GuardPattern(pattern, guard) =>
        encodeByte(Format.GuardPattern)
        encodePattern(pattern)
        encodeWord(guard)

      case BindPattern(pattern, bindings) =>
        encodeByte(Format.BindPattern)
        encodePattern(pattern)
        repeated(bindings) { binding => encodeWord(binding) }

      case SeqPattern(pats) =>
        encodeByte(Format.SeqPattern)
        encodeType(pattern.scrutineeType)

        repeated(pats):
          case AtomPattern(pattern) =>
            encodeByte(Format.AtomPattern)
            encodePattern(pattern)

          case SkipToPattern(pattern) =>
            encodeByte(Format.SkipToPattern)
            encodePattern(pattern)

          case star @ StarPattern(pattern) =>
            encodeByte(Format.StarPattern)
            encodePattern(pattern)
            repeated(star.bindings): (sym1, sym2) =>
              encodeSymbol(sym1)
              encodeSymbolRef(sym2)

          case RestPattern(pattern) =>
            encodeByte(Format.RestPattern)
            encodePattern(pattern)

      case WildcardPattern() =>
        encodeByte(Format.WildcardPattern)
        encodeType(pattern.scrutineeType)

    val lengthDelta = pattern.span.length - state.getChildrenLength
    encodeInt(startDelta)
    encodeInt(lengthDelta)

  private def encodeBool(b: Boolean)(using buf: WriteBuffer): Unit =
    buf.addBool(b)

  private def encodeByte(b: Byte)(using buf: WriteBuffer): Unit =
    buf.addByte(b)

  private def encodeInt(n: Int)(using buf: WriteBuffer): Unit =
    buf.addInt(n)

  private def encodeNat(n: Int)(using buf: WriteBuffer): Unit =
    buf.addNat(n)

  private def encodeString(s: String)(using buf: WriteBuffer): Unit =
    buf.addUtf8(s)

  private def encodeConstant(const: Constant)(using buf: WriteBuffer): Unit =
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
  private def encodeSource(source: Source)(using buf: WriteBuffer): Unit =
    encodeString(source.file)
    val lineLengths = source.lineLengths
    repeated(lineLengths) { len => encodeNat(len) }

  private def repeated[T](items: Iterable[T])(encode: T => Unit)(using buf: WriteBuffer): Unit =
    encodeNat(items.size)
    for item <- items do encode(item)
