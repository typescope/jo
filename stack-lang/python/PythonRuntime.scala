package python

import sast.*
import sast.Symbols.Symbol
import sast.Symbols.Annotation

import scala.collection.mutable

object PythonRuntime:
  // Hard Python reserved keywords. These cannot be used in generated dot
  // member syntax such as `obj.class`.
  val hardKeywords = List(
    "False", "None", "True",
    "and", "as", "assert", "async", "await",
    "break", "class", "continue", "def", "del",
    "elif", "else", "except",
    "finally", "for", "from",
    "global",
    "if", "import", "in", "is",
    "lambda",
    "nonlocal", "not",
    "or",
    "pass",
    "raise", "return",
    "try",
    "while", "with",
    "yield"
  )

  // Keywords/names avoided for generated local names. `match` and `case` are
  // soft keywords: valid in `obj.match`, but best avoided as local names.
  val keywords = hardKeywords ++ List(
    "match", "case",
    // Special names always taken in generated Python classes
    "self", "__init__"
  )

  private def isPyIdentStart(c: Char): Boolean =
    c == '_' || Character.isUnicodeIdentifierStart(c)

  private def isPyIdentPart(c: Char): Boolean =
    c == '_' || Character.isUnicodeIdentifierPart(c)

  private def hasIdentifierShape(name: String): Boolean =
    name.nonEmpty
    && isPyIdentStart(name.head)
    && name.tail.forall(isPyIdentPart)

  def isValidMemberName(name: String): Boolean =
    hasIdentifierShape(name) && !hardKeywords.contains(name)

  def isValidIdentifier(name: String): Boolean =
    hasIdentifierShape(name) && !keywords.contains(name)

/** Functions to support Python platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class PythonRuntime(using defn: Definitions):
  // Map from context parameter fullName to unique global variable name
  val paramIds: mutable.Map[String, String] = mutable.Map.empty

  // Map from singleton object symbol to unique global variable name
  val singletonIds: mutable.Map[Symbol, String] = mutable.Map.empty

  val runtimeNames = List("print", "sys")

  /** Get or create a unique global name for a context parameter */
  def getOrCreateParamId(sym: Symbol): String =
    paramIds.getOrElseUpdate(sym.fullName, {
      // Generate unique global name: _param_jo_IO_stdout
      val safeName = sym.fullName.replace('.', '_')
      s"_param_$safeName"
    })

  /** Get or create a unique global name for a singleton object */
  def getOrCreateSingletonId(sym: Symbol): String =
    singletonIds.getOrElseUpdate(sym, {
      // Generate unique global name: _singleton_jo_Predef_Unit
      val safeName = sym.fullName.replace('.', '_').replace("$", "D")
      s"_singleton_$safeName"
    })

  val Python = defn.resolveContainer("jo.py.runtime")

  val ParamSupport = Python.containerMember("ParamSupport")
  val emptyCtx = ParamSupport.termMember("emptyCtx")
  val getParam = ParamSupport.termMember("getParam")
  val startBatch = ParamSupport.termMember("startBatch")
  val addBinding = ParamSupport.termMember("addBinding")
  val finishBatch = ParamSupport.termMember("finish")
  val paramKey = ParamSupport.termMember("paramKey")

  val start = Python.termMember("start")

  val StringOps = Python.containerMember("StringOps")
  val String_iterator = StringOps.termMember("iterator")

  val py = defn.resolveContainer("jo.py")
  val py_none = py.termMember("none")

  val py_Dynamic              = py.typeMember("Dynamic")
  val py_Dynamic_selectDynamic = py_Dynamic.termMember("selectDynamic")
  val py_Dynamic_updateDynamic = py_Dynamic.termMember("updateDynamic")
  val py_Dynamic_callDynamic   = py_Dynamic.termMember("callDynamic")
  val py_Dynamic_getDynamic    = py_Dynamic.termMember("getDynamic")
  val py_Dynamic_setDynamic    = py_Dynamic.termMember("setDynamic")
  val py_Dynamic_cast          = py_Dynamic.termMember("cast")
  val py_dynamic               = py.termMember("dynamic")
  val py_module              = py.termMember("module")
  val py_call                = py.termMember("call")
  val py_isNone              = py.termMember("isNone")
  val py_isIdentical         = py.termMember("isIdentical")
  val py_isInstance          = py.termMember("isInstance")
  val py_list                = py.termMember("list")
  val annot_interop          = py.annotationMember("interop")
  val annot_targetName       = py.annotationMember("targetName")
  val annot_property         = py.annotationMember("property")

  val compile_namedArg       = defn.compile_namedArg

  val annot_positional       = py.annotationMember("positional")
  val annot_keyword          = py.annotationMember("keyword")

  def isPositionalType(tpe: Types.Type): Boolean =
    tpe.getAnnotation(annot_positional).isDefined

  def isKeywordType(tpe: Types.Type): Boolean =
    tpe.getAnnotation(annot_keyword).isDefined

  def keywordRename(tpe: Types.Type): Option[String] =
    tpe.getAnnotation(annot_keyword).flatMap:
      case Annotation(_, List(Constant.String(name))) if name.nonEmpty => Some(name)
      case _ => None

  def pyTargetName(sym: Symbol): Option[String] =
    sym.annotation(annot_targetName).map:
      case Annotation(_, List(Constant.String(name))) => name
      case _ => throw new Exception(s"Unexpected @py.targetName payload on ${sym.fullName}")

  val py_try                 = py.termMember("try")

  val py_abort    = Python.termMember("abort")


  // Result variant class symbols (from jo stdlib)
  val Jo       = defn.resolveContainer("jo")
  val jo_Ok    = Jo.typeMember("Ok")
  val jo_Err   = Jo.typeMember("Err")

  /** Extra symbols that become reachable when a given SAST symbol is reached.
   *
   *  - String.iterator: replaced by StringOps.iterator at emit time.
   *  - List.++: over-approximation of @py.interop vararg splice sites; any
   *    use of List.++ pulls in py_list (acceptable because List is already
   *    reachable at that point so py_list adds negligible size).
   *  - py.try: codegen constructs Ok/Err directly; no SAST New node exists.
   */
  def intrinsicDeps: Map[Symbols.Symbol, List[Symbols.Symbol]] =
    val strSym  = defn.String_type
    val listSym = defn.List_type
    Map(
      strSym.termMember("iterator") -> List(String_iterator),
      listSym.termMember("++")      -> List(py_list),
      py_try -> List(jo_Ok, jo_Ok.termMember(Names.Constructor),
                     jo_Err, jo_Err.termMember(Names.Constructor)),
    )
