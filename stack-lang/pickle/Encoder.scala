package pickle

import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Types.*
import sast.Symbols.*

import common.Text
import common.Text.*
import common.StringUtil
import common.Base64

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
  *   A global symbol table is used for all internal symbols.
  *
  *   An alternative design is to use the address of the definition as the ID
  *   of the symbol. That would require backpatch forward symbols which are
  *   referred in the code parts before they are serialized. This design could
  *   reduce file size and remove the need for a global table. This approach
  *   might be pursued in the final design.
  *
  * == External symbols
  *
  *   Extenral symbols are identified by full paths to the symbols. A global
  *   name table is used for all external references.
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
  *    A lines table stores the length of lines in the source file. The line
  *    table is encoded compactly based on base64 encoding of each individual
  *    line length. As lines are mostly less than 64 columns, a single character
  *    suffices for most lines. Taking the obligatory 1 byte separator into
  *    consideration, 2 bytes per line is still cheaper than binary encoding
  *    which takes 4 bytes per line.
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
  *    suffices for each delta based on base64 encoding. With the separator, it
  *    is 4 bytes per tree, which is cheaper than binary encoding which takes
  *    usually 8 bytes per tree.
  *
  * A sample encoding looks like the following:
  *
  *       Namespace [
  *           [stk.Predef.+, stk.Predef.assert],
  *
  *           [
  *             Symbol [
  *               4, List, [NSpace], NoOwner,
  *               [23, 43]
  *             ],
  *             ...
  *           ],
  *
  *           #0,
  *
  *           defs [
  *             FunDef [
  *               #N,
  *               tparams [...],
  *               params [...],
  *               autos [...],
  *               receives [...],
  *               locals [...],
  *               If [
  *                   Apply [...],
  *                   Block [...],
  *                   Block [...],
  *                   StaticRef [...],
  *                   Span [...]
  *               ],
  *             ],
  *             Section [...]
  *           ]
  *       ]
  */
object Encoder:
  val LINE_SEP = "," ~ Text.BreakLine

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

    def getInternalSymbolId(sym: Symbol): Int =
      internalSymIds.get(sym) match
        case Some(id) => id
        case None =>
          val id = internalSymbolCount
          internalSymIds(sym) = id
          internalSymbolCount += 1
          id
      end match

    def externalNameTable(using Definitions): Text =
      // TODO: store type for checking contracts
      "[" ~ externalSymbols.toSeq.map(_.fullName).join(LINE_SEP) ~ "]"

    def topLevelSymbolTable(using State, Definitions): Text =
      val topLevelSyms = internalSymIds.keys.filter(!_.isLocal)
      "[" ~ topLevelSyms.map(Encoder.encodeSymbol).join(LINE_SEP) ~ "]"
  end State

  //----------------------------------------------------------------------------
  //
  // Implicits to cut down boilerplate in encoding
  //
  // We do not want too many implicits here --- it is better to be explicit
  // except for the most common and obvious usage.

  private given (using Definitions, State): Text.Maker[Word] =
    v => encodeWord(v)

  private given (using Definitions, State): Text.Maker[Pattern] =
    v => encodePattern(v)

  private given (using Definitions, State): Text.Maker[Type] =
    v => encodeType(v)

  private given (using Definitions, State): Text.Maker[TypeTree] =
    v => encodeTypeTree(v)

  private given (using Definitions, State): Text.Maker[Symbol] =
    v => encodeSymbolRef(v)

  private given Maker[Int] = (v) => Text.Atom(Base64.intToBase64(v))

  //----------------------------------------------------------------------------

  def encode(ns: Namespace)(using Definitions): Text =
    val Namespace(symbol, imports, defs) = ns

    given state: State = new State(symbol)

    val symbolRef = internalRef(symbol)

    val defsData = "[" ~ indent:
        defs.map(encodeDef).join(",")
      ~ "]"

    val source = encodeSource(symbol.sourcePos.source)

    // must comes after defs
    val symsData = state.topLevelSymbolTable

    // must comes after symbols
    val refsData = state.externalNameTable

    "[" ~ indent:
        List(source, refsData, symsData, symbolRef, defsData).join("," ~ Text.BreakLine)
    ~ "]"

  //----------------------------------------------------------------------------

  /** Definition of a symbol */
  private def encodeSymbol(symbol: Symbol)(using defn: Definitions, state: State): Text =
    // TODO: attributes, comments

    val id = state.getInternalSymbolId(symbol)

    val ownerText =
      if symbol.owner == null then Text("NoOwner") else internalRef(symbol.owner)

    val flags = encodeFlags(symbol.flags)
    val pos = symbol.sourcePos.start ~ "," ~ symbol.sourcePos.length

    symbol match
      case tsym: TypeSymbol =>
        // [id, name, flags, kind, owner ref, source pos, info]
        "[" ~ id ~ "," ~ tsym.name ~ "," ~ flags ~ "," ~ encodeKind(tsym.kind)
           ~ "," ~ ownerText ~ "," ~ pos ~ "," ~ encodeSymbolInfo(tsym)
        ~ "]"

      case _ =>
        // [id, name, flags, owner ref, source pos, info]
        "[" ~ id ~ "," ~ symbol.name ~ "," ~ flags ~ "," ~ ownerText ~ "," ~
            pos ~ "," ~ encodeSymbolInfo(symbol)
        ~ "]"

  /** Reference to a symbol
    *
    *     #3  ==> refers the symbol whose id is 3
    *
    *     @5  ==> refers the name table entry whose index is 5
    */
  private def encodeSymbolRef(symbol: Symbol)(using defn: Definitions, state: State): Text =
    if symbol.containedIn(state.root) then
      "#" ~ state.getInternalSymbolId(symbol)

    else
      assert(!symbol.isLocal, "Cannot reference external local symbol: " + symbol)
      "@" ~ state.getExternalSymbolIndex(symbol)

  private def internalRefs(symbols: List[Symbol])(using state: State): Text =
    symbols.map(state.getInternalSymbolId).join(",")

  private def internalRef(symbol: Symbol)(using state: State): Text =
    Text(state.getInternalSymbolId(symbol))

  private def encodeSymbolInfo(symbol: Symbol)(using defn: Definitions, state: State): Text =
    symbol.info match
      case procType: ProcType =>
        // TODO: add effects
        encodeType(procType)

      case info =>
        encodeType(info)

  private def encodeFlags(flags: Flags)(using Definitions, State): Text =
    Flags.flagIndices(flags).join(Text.Empty)

  private def encodeKind(kind: Kind)(using Definitions, State): Text =
    kind match
      case Kind.Simple =>
        Text("*")

      case Kind.Arrow(args, to) =>
        // right-associative
        "[" ~ args.map(encodeKind).join(",") ~ "] -> " ~ encodeKind(to)


  private def encodeDef(defn: Def)(using definitions: Definitions, state: State): Text = state.withPositioned(defn): startDelta =>
    val res = defn match
      case pdef: ParamDef =>
        Format.ParamDef ~ "[" ~ internalRef(pdef.symbol) ~ "," ~ pdef.tpt ~ "]"

      case vdef: ValDef =>
        Format.ValDef ~ "[" ~ internalRef(vdef.symbol) ~ "," ~ vdef.rhs ~ "]"

      case cdef: ClassDef =>
        Format.ClassDef ~ "[" ~ indent:
            internalRef(cdef.symbol) ~ LINE_SEP ~
            internalRef(cdef.self) ~ LINE_SEP ~
            "[" ~ internalRefs(cdef.tparams) ~ "]" ~ LINE_SEP ~
            "[" ~ internalRefs(cdef.vals) ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                cdef.funs.map(encodeDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

      case fdef: FunDef =>
        // TODO: store local symbol definitions locally?
        Format.FunDef ~ "[" ~ indent:
            internalRef(fdef.symbol) ~ LINE_SEP ~
            "[" ~ internalRefs(fdef.tparams) ~ "]" ~ LINE_SEP ~
            "[" ~ internalRefs(fdef.params) ~ "]" ~ LINE_SEP ~
            "[" ~ internalRefs(fdef.autos) ~ "]" ~ LINE_SEP ~
            fdef.resultType ~ LINE_SEP ~
            fdef.body
        ~ "]"


      case pdef: PatDef =>
        Format.PatDef ~ "[" ~ indent:
            internalRef(pdef.symbol) ~ LINE_SEP ~
            "[" ~ internalRefs(pdef.tparams) ~ "]" ~ LINE_SEP ~
            "[" ~ internalRefs(pdef.params) ~ "]" ~ LINE_SEP ~
            pdef.resultType ~ LINE_SEP ~
            pdef.body
        ~ "]"

      case tdef: TypeDef =>
        Format.TypeDef ~ "[" ~ internalRef(tdef.symbol) ~ "]"

      case sec: Section =>
        Format.Section ~ "[" ~ indent:
            internalRef(sec.symbol) ~ LINE_SEP ~
            "[" ~ indent:
                sec.defs.map(encodeDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

    val lengthDelta = defn.span.length - state.getChildrenLength
    val pos = startDelta ~ "," ~ lengthDelta
    res ~ "@" ~ pos

  private def encodeTypeTree(tpt: TypeTree)(using defn: Definitions, state: State): Text = state.withPositioned(tpt): startDelta =>
    val pos = startDelta ~ "," ~ tpt.span.length
    "[" ~ tpt.tpe ~ "]@" ~ pos

  private def encodeType(tpe: Type)(using Definitions, State): Text =
    tpe match
      case VoidType => Text(Format.VoidType)

      case ErrorType => Text(Format.ErrorType)

      case AnyType => Text(Format.AnyType)

      case BottomType => Text(Format.BottomType)

      case StaticRef(sym) =>
        encodeSymbolRef(sym)

      case MemberRef(prefix, sym) =>
        Format.MemberRef ~ "[" ~ prefix ~ "," ~ sym ~ "]"

      case tvar: TypeVar =>
        assert(tvar.isInstantiated, "uninstantiated type variable: " + tvar)
        encodeType(tvar.instantiated)

      case ConstantType(const) =>
        Format.ConstantType ~ "[" ~ encodeConstant(const) ~ "]"

      case RecordType(fields) =>
        Format.RecordType ~ "[" ~ indent:
          fields.map(f => f.name ~ ": " ~ f.info).join("," ~ Text.BreakLine)
        ~ "]"

      case UnionType(branches) =>
        Format.UnionType ~ "[" ~ branches.map(encodeType).join(",") ~ "]"

      case TagType(tag, params) =>
        val paramText =  params.map(f => f.name ~ ": " ~ f.info).join(",")
        Format.TagType ~ "[" ~ tag ~ "," ~ paramText ~ "]"

      case ObjectType(fields, methods, muts) =>
        val fieldText = "[" ~ fields.map(f => f.name ~ ": " ~ f.info).join(",") ~ "]"
        val methodText = "[" ~ methods.map(m => m.name ~ ": " ~ m.info).join(",") ~ "]"
        val mutableText = "[" ~ muts.join(",") ~ "]"

        Format.ObjectType ~ "[" ~ fieldText ~ "," ~ methodText ~ "," ~ mutableText ~ "]"

      case AppliedType(tctor, targs) =>
        Format.AppliedType ~ "[" ~ tctor ~ ",[" ~ targs.join(",") ~ "]]"

      case ProcType(tparams, params, autos, resType, receives, preParamCount) =>
        // assert(receives.isInstanceOf[Effects.Policy.CheckBound], "Expect Policy.CheckBounds, found = " + receives)

        val effects: List[Symbol] = Nil // receives.asInstanceOf[Effects.Policy.CheckBound].effects

        val tparamText = "[" ~ tparams.join(",") ~ "]"
        val paramText = "[" ~ params.map(param => "[" ~ param.name ~ "," ~ param.info ~ "]").join(",") ~ "]"
        val autoText = "[" ~ autos.map(auto => "[" ~ auto.name ~ "," ~ auto.info ~ "]").join(",") ~ "]"
        val receiveText = "[" ~ effects.join(",") ~ "]"

        Format.ProcType ~ "[" ~ indent:
          List(tparamText, paramText, autoText, encodeType(resType), receiveText, Text(preParamCount)).join("," ~ Text.BreakLine)
        ~ "]"

      case TypeLambda(tparams, resType, preParamCount) =>
        val tparamText = "[" ~ tparams.join(",") ~ "]"
        Format.TypeLambda ~ "[" ~ tparamText ~ "," ~ resType ~ "," ~ preParamCount ~ "]"

      case cinfo: ContainerInfo =>
        Format.ContainerInfo ~ "[" ~ internalRefs(cinfo.members) ~ "]"

      case ClassInfo(classSymbol, tparams, targs, self, fields, methods) =>
        targs.zip(tparams).map: (targ, tparam) =>
          targ match
            case StaticRef(sym) => assert(sym == tparam, "Unexpected class info")
            case tp => throw new Exception("Unexpected targ for classInfo: " + tp)

        Format.ClassInfo ~ "[" ~ indent:
            classSymbol ~ "," ~
            "[" ~ internalRefs(tparams) ~ "]," ~
            self ~ "," ~
            "[" ~ internalRefs(fields) ~ "]," ~
            "[" ~ internalRefs(methods) ~ "],"
        ~ "]"


      case TypeBound(lo, hi) =>
        Format.TypeBound ~ "[" ~ lo ~ "," ~ hi ~ "]"

  private def encodeWord(word: Word)(using defn: Definitions, state: State): Text = state.withPositioned(word): startDelta =>
    // TODO: types
    val res = word match
      case Literal(const) =>
        Format.Literal ~ "[" ~ encodeConstant(const) ~ "]"

      case Ident(sym) =>
        Format.Ident ~ "[" ~ sym ~ "]"

      case New(classRef, targs) =>
        Format.New ~ "[" ~ classRef ~ ",[" ~ targs.join(",") ~ "]]"

      case Select(qual, name) =>
        Format.Select ~ "[" ~ qual ~ "," ~ name ~ "]"

      case RecordLit(fields) =>
        val content = fields.map:
          case (f, rhs) => "[" ~ f ~ "," ~ rhs ~ "]"

        Format.RecordLit ~ "[" ~ content.join(",") ~ "]"

      case TaggedLit(tag, args) =>
        Format.TaggedLit ~ "[" ~ tag ~ ", [" ~ args.join(",") ~ "]]"

      case Encoded(repr) =>
        Format.Encoded ~ "[" ~ repr ~ "," ~ word.tpe ~ "]"

      case Apply(fun, args, autos) =>
        Format.Apply ~ "[" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ args.join(",") ~ "]" ~ LINE_SEP ~
          "[" ~ autos.join(",") ~ "]"
        ~ "]"

      case TypeApply(fun, targs) =>
        Format.TypeApply ~ "[" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ targs.join(",") ~ "]"
        ~ "]"

      case With(expr, args) =>
        val bindings = args.map:
          case Assign(ident, rhs) =>
            "[" ~ ident ~ "," ~ rhs ~ "]"

        Format.With ~ "[" ~ expr ~ ",[" ~ indent:
           bindings.join(LINE_SEP)
        ~ "]]"

      case Allow(expr, params) =>
        Format.Allow ~ "[" ~ expr ~ ",[" ~ params.join(",") ~ "]]"

      case Assign(ident, rhs) =>
        Format.Assign ~ "[" ~ ident ~ "," ~ rhs ~ "]"

      case FieldAssign(lhs, rhs) =>
        Format.FieldAssign ~ "[" ~ lhs ~ "," ~ rhs ~ "]"

      case vdef: ValDef => encodeDef(vdef)

      case fdef: FunDef => encodeDef(fdef)

      case tdef: TypeDef => encodeDef(tdef)

      case pdef: PatDef => encodeDef(pdef)

      case If(cond, thenp, elsep) =>
        Format.If ~ "[" ~ indent:
          cond ~ LINE_SEP ~
          thenp ~ LINE_SEP ~
          elsep
        ~ "]"

      case While(cond, body) =>
        Format.While ~ "[" ~ indent:
          cond ~ LINE_SEP ~
          body
        ~ "]"

      case Block(words) =>
        Format.Block ~ "[" ~ indent:
          words.join(LINE_SEP)
        ~ "]"

      case Match(scrutinee, cases) =>
        Format.Match ~ "[" ~ scrutinee ~ ", [" ~ indent:
           val pairs = cases.map:
             case Case(pat, body) => "[" ~ pat ~ "," ~ body ~ "]"

           pairs.join(LINE_SEP)
        ~ "]]"

      case Object(self, inits, defs) =>
        Format.Object ~ "[" ~ indent:
            internalRef(self) ~ LINE_SEP ~
            "[" ~ inits.join(",") ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                defs.map(encodeDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

    val lengthDelta = word.span.length - state.getChildrenLength
    val pos = startDelta ~ "," ~ lengthDelta
    res ~ "@" ~ pos

  private def encodePattern(pattern: Pattern)(using defn: Definitions, state: State): Text = state.withPositioned(pattern): startDelta =>
    val res = pattern match
      case AliasPattern(id, nested) =>
        Format.AliasPattern ~ "[" ~ id ~ "," ~ nested ~ "]"

      case TypePattern(tpt) =>
        Format.TypePattern ~ "[" ~ tpt ~ "]"

      case TagPattern(tagLit, nested) =>
        Format.TagPattern ~ "[" ~ tagLit ~ ",[" ~ nested.join(",") ~ "]]"

      case ApplyPattern(fun, nested) =>
        Format.ApplyPattern ~ "[" ~ fun ~ ",[" ~ nested.join(",") ~ "]]"

      case OrPattern(lhs, rhs) =>
        Format.OrPattern ~ "[" ~ lhs ~ "," ~ rhs ~ "]"

      case ValuePattern(value) =>
        Format.ValuePattern ~ "[" ~ value ~ "]"

      case GuardPattern(pattern, guard) =>
        Format.GuardPattern ~ "[" ~ pattern ~ "," ~ guard ~ "]"

      case BindPattern(pattern, bindings) =>
        Format.BindPattern ~ "[" ~ pattern ~ ", [" ~ indent:
          bindings.join(LINE_SEP)
        ~ "]"

      case SeqPattern(pats) =>
        val nested =
          pats.map:
            case AtomPattern(pattern) =>
              Format.AtomPattern ~ "[" ~ pattern ~ "]"

            case SkipToPattern(pattern) =>
              Format.SkipToPattern ~ "[" ~ pattern ~ "]"

            case star @ StarPattern(pattern) =>
              val bindings = star.bindings.map: (sym1, sym2) =>
                "[" ~ sym1 ~ "," ~ sym2 ~ "]"
              Format.StarPattern ~ "[" ~ pattern ~ ", [" ~ bindings.join(",")  ~ "]]"

            case RestPattern(pattern) =>
              Format.RestPattern ~ "[" ~ pattern ~ "]"

        Format.SeqPattern ~ "[" ~ nested.join(",") ~ "]"

      case WildcardPattern() =>
        Text(Format.WildcardPattern)


    val lengthDelta = pattern.span.length - state.getChildrenLength
    val pos = startDelta ~ "," ~ lengthDelta
    res ~ "@" ~ pos

  private def encodeConstant(const: Constant): Text =
    const match
      case Constant.Bool(value) =>
        "Bool [" ~ value.toString ~ "]"

      case Constant.Int(value) =>
        "Int [" ~ value.toString ~ "]"

      case Constant.String(value) =>
        val byteSize = StringUtil.utf8Length(value)
        "String [" ~ byteSize.toString ~ ":"  ~ value ~ "]"

  /** Encode line lengths as comma-separated hexadecimal */
  private def encodeSource(source: Source): Text =
    "[" ~ source.file ~ "," ~ source.lineLengths.map(Base64.intToBase64).join("|") ~ "]"
