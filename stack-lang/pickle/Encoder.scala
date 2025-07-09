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
  *    Positions of trees are represented by an absolute offset to the beginning
  *    of the source file and its length. The representation can be mapped to
  *    line numbers and column numbers based on a lines table.
  *
  *    A lines table stores the length of lines in the source file.
  *
  *    Positions may take a lot of spaces in the file. Optimizations are
  *    possible given that
  *
  *    - line numbers are not useful for tree nodes that span multiple lines
  *    - positions can be reconstructed from child nodes in many cases
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
  *           imports [
  *             #5,
  *             ...
  *           ],
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

    def internalSymbolTable(using State, Definitions): Text =
      "[" ~ internalSymIds.keys.toSeq.map(Encoder.encodeSymbol).join(LINE_SEP) ~ "]"
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

    val symbolRef = Text(symbol)

    val importsData = "imports [" ~ imports.join(",") ~ "]"

    val defsData = "defs [" ~ indent:
        defs.map(encodeDef).join(",")
      ~ "]"

    val source = encodeSource(symbol.sourcePos.source)

    // must comes after defs
    val symsData = state.internalSymbolTable

    // must comes after symbols
    val refsData = state.externalNameTable

    "Namespace [" ~ indent:
        List(source, refsData, symsData, symbolRef, importsData, defsData).join("," ~ Text.BreakLine)
    ~ "]"

  //----------------------------------------------------------------------------

  /** Definition of a symbol */
  private def encodeSymbol(symbol: Symbol)(using defn: Definitions, state: State): Text =
    // TODO: attributes, comments

    val id = state.getInternalSymbolId(symbol)

    val ownerText =
      if symbol.owner == null then Text("NoOwner") else encodeSymbolRef(symbol.owner)

    val flags = encodeFlags(symbol.flags)
    val pos = "[" ~ symbol.sourcePos.start ~ "," ~ symbol.sourcePos.length ~ "]"

    symbol match
      case tsym: TypeSymbol =>
        // TypeSymbol [id, name, flags, kind, owner ref, source pos, info]
        "TypeSymbol [" ~ indent:
            id ~ "," ~
            tsym.name ~ "," ~
            flags ~ "," ~
            encodeKind(tsym.kind) ~ "," ~
            ownerText ~ "," ~
            pos ~ "," ~
            encodeSymbolInfo(tsym)
        ~ "]"

      case _ =>
        // Symbol [id, name, flags, owner ref, source pos, info]
        "Symbol [" ~ indent:
            id ~ LINE_SEP ~
            symbol.name ~ LINE_SEP ~
            flags ~ LINE_SEP ~
            ownerText ~ LINE_SEP ~
            pos ~ LINE_SEP ~
            encodeSymbolInfo(symbol)
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

  private def encodeSymbolInfo(symbol: Symbol)(using defn: Definitions, state: State): Text =
    symbol.info match
      case procType: ProcType =>
        // TODO: add effects
        encodeType(procType)

      case info =>
        encodeType(info)

  private def encodeFlags(flags: Flags)(using Definitions, State): Text =
    "[" ~ flags.toStrings.join(",") ~ "]"

  private def encodeKind(kind: Kind)(using Definitions, State): Text =
    kind match
      case Kind.Simple =>
        Text("*")

      case Kind.Arrow(args, to) =>
        // right-associative
        "[" ~ args.map(encodeKind).join(",") ~ "] -> " ~ encodeKind(to)


  private def encodeDef(defn: Def)(using Definitions, State): Text =
    // TODO: span
    defn match
      case pdef: ParamDef =>
        "ParamDef [" ~ pdef.symbol ~ "," ~ pdef.tpt ~ "]"

      case cdef: ClassDef =>
        "ClassDef [" ~ indent:
            cdef.symbol ~ LINE_SEP ~
            cdef.self ~ LINE_SEP ~
            "[" ~ indent:
                cdef.tparams.join(LINE_SEP)
            ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                cdef.vals.join(LINE_SEP)
            ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                cdef.funs.map(encodeDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

      case fdef: FunDef =>
        // TODO: store local symbol definitions locally?
        "FunDef [" ~ indent:
            fdef.symbol ~ LINE_SEP ~
            "[" ~ indent:
                fdef.tparams.join(LINE_SEP)
            ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                fdef.params.join(LINE_SEP)
            ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                fdef.autos.join(LINE_SEP)
            ~ "]" ~ LINE_SEP ~
            fdef.resultType ~ LINE_SEP ~
            fdef.body
        ~ "]"


      case pdef: PatDef =>
        "PatDef [" ~ indent:
            pdef.symbol ~ LINE_SEP ~
            "[" ~ indent:
                pdef.tparams.join(LINE_SEP)
            ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                pdef.params.join(LINE_SEP)
            ~ "]" ~ LINE_SEP ~
            pdef.resultType ~ LINE_SEP ~
            pdef.body
        ~ "]"

      case tdef: TypeDef =>
        "TypeDef [" ~ tdef.symbol ~ "]"

      case sec: Section =>
        "Section [" ~ indent:
            sec.symbol ~ LINE_SEP ~
            "[" ~ indent:
                sec.defs.map(encodeDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

  private def encodeTypeTree(tpt: TypeTree)(using Definitions, State): Text =
    // TODO: span
    "TypeTree [" ~ tpt.tpe ~ "]"


  private def encodeType(tpe: Type)(using Definitions, State): Text =
    tpe match
      case VoidType => Text("Void")

      case ErrorType => Text("Error")

      case AnyType => Text("Any")

      case BottomType => Text("Bottom")

      case StaticRef(sym) =>
        "StaticRef [" ~ sym ~ "]"

      case MemberRef(prefix, sym) =>
        "MemberRef [" ~ prefix ~ "," ~ sym ~ "]"

      case tvar: TypeVar =>
        assert(tvar.isInstantiated, "uninstantiated type variable: " + tvar)
        encodeType(tvar.instantiated)

      case ConstantType(const) =>
        "ConstantType [" ~ encodeConstant(const) ~ "]"

      case RecordType(fields) =>
        "RecordType [" ~ indent:
          fields.map(f => f.name ~ ": " ~ f.info).join("," ~ Text.BreakLine)
        ~ "]"

      case UnionType(branches) =>
        "UnionType [" ~ branches.map(encodeType).join(",") ~ "]"

      case TagType(tag, params) =>
        val paramText =  params.map(f => f.name ~ ": " ~ f.info).join(",")
        "TagType [" ~ tag ~ "," ~ paramText ~ "]"

      case ObjectType(fields, methods, muts) =>
        val fieldText = "[" ~ fields.map(f => f.name ~ ": " ~ f.info).join(",") ~ "]"
        val methodText = "[" ~ methods.map(m => m.name ~ ": " ~ m.info).join(",") ~ "]"
        val mutableText = "[" ~ muts.join(",") ~ "]"

        "ObjectType [" ~ fieldText ~ "," ~ methodText ~ "," ~ mutableText ~ "]"

      case AppliedType(tctor, targs) =>
        "AppliedType [" ~ tctor ~ ",[" ~ targs.join(",") ~ "]]"

      case ProcType(tparams, params, autos, resType, receives, preParamCount) =>
        // assert(receives.isInstanceOf[Effects.Policy.CheckBound], "Expect Policy.CheckBounds, found = " + receives)

        val effects: List[Symbol] = Nil // receives.asInstanceOf[Effects.Policy.CheckBound].effects

        val tparamText = "tparams [" ~ tparams.join(",") ~ "]"
        val paramText = "params [" ~ params.map(param => "[" ~ param.name ~ "," ~ param.info ~ "]").join(",") ~ "]"
        val autoText = "autos [" ~ autos.map(auto => "[" ~ auto.name ~ "," ~ auto.info ~ "]").join(",") ~ "]"
        val receiveText = "receives [" ~ effects.join(",") ~ "]"

        "ProcType [" ~ indent:
          List(tparamText, paramText, autoText, encodeType(resType), receiveText, Text(preParamCount)).join("," ~ Text.BreakLine)
        ~ "]"

      case TypeLambda(tparams, resType, preParamCount) =>
        val tparamText = "[" ~ tparams.join(",") ~ "]"
        "TypeLambda [" ~ tparamText ~ "," ~ resType ~ "," ~ preParamCount ~ "]"

      case cinfo: ContainerInfo =>
        "Container [" ~ cinfo.members.join("," ~ Text.BreakLine) ~ "]"

      case ClassInfo(classSymbol, tparams, targs, self, fields, methods) =>
        targs.zip(tparams).map: (targ, tparam) =>
          targ match
            case StaticRef(sym) => assert(sym == tparam, "Unexpected class info")
            case tp => throw new Exception("Unexpected targ for classInfo: " + tp)

        "ClassInfo [" ~ indent:
            classSymbol ~ "," ~
            "[" ~ tparams.join(",") ~ "]," ~
            self ~ "," ~
            "[" ~ fields.join(",") ~ "]," ~
            "[" ~ methods.join(",") ~ "],"
        ~ "]"


      case TypeBound(lo, hi) =>
        "TypeBound [" ~ lo ~ "," ~ hi ~ "]"

  private def encodeWord(word: Word)(using Definitions, State): Text =
    // TODO: span and types
    word match
      case Literal(const) =>
        "Lit [" ~ encodeConstant(const) ~ "]"

      case Ident(sym) =>
        "Ident [" ~ sym ~ "]"

      case New(classRef, targs) =>
        "New [" ~ classRef ~ ",[" ~ targs.join(",") ~ "]]"

      case Select(qual, name) =>
        "Select [" ~ qual ~ "," ~ name ~ "]"

      case RecordLit(fields) =>
        val content = fields.map:
          case (f, rhs) => "[" ~ f ~ "," ~ rhs ~ "]"

        "Record [" ~ content.join(",") ~ "]"

      case TaggedLit(tag, args) =>
        "Tag [" ~ tag ~ ", [" ~ args.join(",") ~ "]]"

      case Encoded(repr) =>
        "Encoded [" ~ repr ~ "," ~ word.tpe ~ "]"

      case Apply(fun, args, autos) =>
        "Apply [" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ args.join(",") ~ "]" ~ LINE_SEP ~
          "[" ~ autos.join(",") ~ "]"
        ~ "]"

      case TypeApply(fun, targs) =>
        "TypeApply [" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ targs.join(",") ~ "]"
        ~ "]"

      case With(expr, args) =>
        val bindings = args.map:
          case Assign(ident, rhs) =>
            "[" ~ ident ~ "," ~ rhs ~ "]"

        "With [" ~ expr ~ ",[" ~ indent:
           bindings.join(LINE_SEP)
        ~ "]]"

      case Allow(expr, params) =>
        "Allow [" ~ expr ~ ",[" ~ params.join(",") ~ "]]"

      case Assign(ident, rhs) =>
        "Assign [" ~ ident ~ "," ~ rhs ~ "]"

      case FieldAssign(lhs, rhs) =>
        "FieldAssign [" ~ lhs ~ "," ~ rhs ~ "]"

      case fdef: FunDef => encodeDef(fdef)

      case tdef: TypeDef => encodeDef(tdef)

      case pdef: PatDef => encodeDef(pdef)

      case If(cond, thenp, elsep) =>
        "If [" ~ indent:
          cond ~ LINE_SEP ~
          thenp ~ LINE_SEP ~
          elsep
        ~ "]"

      case While(cond, body) =>
        "While [" ~ indent:
          cond ~ LINE_SEP ~
          body
        ~ "]"

      case Block(words) =>
        "Block [" ~ indent:
          words.join(LINE_SEP)
        ~ "]"

      case Match(scrutinee, cases) =>
        val pairs = cases.map:
          case Case(pat, body) => "[" ~ pat ~ "," ~ body ~ "]"

        "Match [" ~ scrutinee ~ ", [" ~ indent:
           pairs.join(LINE_SEP)
        ~ "]]"

      case Object(self, inits, defs) =>
        "Object [" ~ indent:
            self ~ LINE_SEP ~
            "[" ~ indent:
                inits.join(LINE_SEP)
            ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                defs.map(encodeDef).join(LINE_SEP)
            ~ "]"
        ~ "]"


  private def encodePattern(pattern: Pattern)(using Definitions, State): Text =
    // TODO: span
    pattern match
      case AliasPattern(id, nested) =>
        "AliasPattern [" ~ id ~ "," ~ nested ~ "]"

      case TypePattern(tpt) =>
        "TypePattern [" ~ tpt ~ "]"

      case TagPattern(tagLit, nested) =>
        "TagPattern [" ~ tagLit ~ ",[" ~ nested.join(",") ~ "]]"

      case ApplyPattern(fun, nested) =>
        "ApplyPattern [" ~ fun ~ ",[" ~ nested.join(",") ~ "]]"

      case OrPattern(lhs, rhs) =>
        "OrPattern [" ~ lhs ~ "," ~ rhs ~ "]"

      case ValuePattern(value) =>
        "ValuePattern [" ~ value ~ "]"

      case GuardPattern(pattern, guard) =>
        "GuardPattern [" ~ pattern ~ "," ~ guard ~ "]"

      case BindPattern(pattern, bindings) =>
        "BindPattern [" ~ pattern ~ ", [" ~ indent:
          bindings.join(LINE_SEP)
        ~ "]"

      case SeqPattern(pats) =>
        val nested =
          pats.map:
            case AtomPattern(pattern) => "AtomPattern [" ~ pattern ~ "]"

            case SkipToPattern(pattern) => "SkipToPattern [" ~ pattern ~ "]"

            case star @ StarPattern(pattern) =>
              val bindings = star.bindings.map: (sym1, sym2) =>
                "[" ~ sym1 ~ "," ~ sym2 ~ "]"
              "StarPattern [" ~ pattern ~ ", [" ~ bindings.join(",")  ~ "]]"

            case RestPattern(pattern) => "RestPattern [" ~ pattern ~ "]"

        "SeqPattern [" ~ nested.join(",") ~ "]"

      case WildcardPattern() =>
        Text("Wildcard")

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
    "[" ~ source.file ~ "," ~ source.lineLengths.join("|") ~ "]"
