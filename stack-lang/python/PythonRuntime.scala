package python

import sast.*
import sast.Symbols.Symbol
import sast.Symbols.Annotation
import sast.Types.ProcType
import reporting.Reporter

import scala.collection.mutable

object PythonRuntime:
  // True Python reserved keywords — cannot appear as identifiers anywhere,
  // including as attribute/member names.
  val keywords = List(
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
    "yield",
    "match", "case",
    // Special names always taken in generated Python classes
    "self", "__init__"
  )

  def isValidIdentifier(name: String): Boolean =
    name.nonEmpty
    && Character.isUnicodeIdentifierStart(name.head)
    && name.tail.forall(Character.isUnicodeIdentifierPart)
    && !keywords.contains(name)

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

  val py_Value             = py.typeMember("Value")
  val py_Value_selectDynamic = py_Value.termMember("selectDynamic")
  val py_Value_updateDynamic = py_Value.termMember("updateDynamic")
  val py_Value_callDynamic   = py_Value.termMember("callDynamic")
  val py_Value_getDynamic    = py_Value.termMember("getDynamic")
  val py_Value_setDynamic    = py_Value.termMember("setDynamic")
  val py_Value_cast          = py_Value.termMember("cast")
  val py_value               = py.termMember("value")
  val py_module              = py.termMember("module")
  val py_call                = py.termMember("call")
  val py_isNone              = py.termMember("isNone")
  val py_isSame              = py.termMember("isSame")
  val py_isInstance          = py.termMember("isInstance")
  val py_kwarg               = py.termMember("kwarg")
  val py_splice              = py.termMember("splice")
  val py_kwargs              = py.termMember("kwargs")
  val annot_targetName       = py.annotationMember("targetName")
  val annot_property         = py.annotationMember("property")

  val compile_namedArg       = defn.compile_namedArg

  val py_Positional_type     = py.typeMember("Positional")
  val py_Keyword_type        = py.typeMember("Keyword")

  def isPositionalType(tpe: Types.Type): Boolean = tpe match
    case Types.AppliedType(tctor, _) => tctor == py_Positional_type
    case _ => false

  def isKeywordType(tpe: Types.Type): Boolean = tpe match
    case Types.AppliedType(tctor, _) => tctor == py_Keyword_type
    case _ => false

  def pyTargetName(sym: Symbol): Option[String] =
    sym.annotation(annot_targetName).map:
      case Annotation(_, List(Constant.String(name))) => name
      case _ => throw new Exception(s"Unexpected @py.targetName payload on ${sym.fullName}")

  def isPyProperty(sym: Symbol): Boolean =
    sym.annotation(annot_property).isDefined

  def validatePyProperty(sym: Symbol): Unit =
    if isPyProperty(sym) then
      sym.info match
        case proc: ProcType =>
          if proc.params.nonEmpty || proc.autos.nonEmpty then
            Reporter.abort("@py.property is only valid on parameterless methods", sym.sourcePos)
        case _ =>
          Reporter.abort("@py.property is only valid on methods", sym.sourcePos)

  val py_try                 = py.termMember("try")

  val py_abort    = Python.termMember("abort")


  // Result variant class symbols (from jo stdlib)
  val Jo       = defn.resolveContainer("jo")
  val jo_Ok    = Jo.typeMember("Ok")
  val jo_Err   = Jo.typeMember("Err")
