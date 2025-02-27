package js

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

/** Lower String and Array to JS runtime calls.
  *
  */
class LowerRuntime(runtime: JSRuntime) extends phases.Phase[Unit]:
  val contextObject = phases.Phase.DummyContext

  val defn = Definitions.instance

  val Predef_String = defn.Predef_String
  val StringType = defn.StringType
  val BoolType = defn.BoolType
  val IntType = defn.IntType

  val rewiring = Map(
    defn.Predef_abort      -> runtime.JS_abort,
    defn.Predef_byteToChar -> runtime.JS_byteToChar,
    defn.Predef_byteToInt  -> runtime.JS_byteToInt,
    defn.Predef_charToByte -> runtime.JS_charToByte,
    defn.Predef_charToInt  -> runtime.JS_charToInt,
    defn.Predef_charToStr  -> runtime.JS_charToStr,
    defn.Predef_intToByte  -> runtime.JS_intToByte,
    defn.Predef_intToChar  -> runtime.JS_intToChar,
    defn.Predef_intToStr   -> runtime.JS_intToStr,

    defn.Predef_open$default   -> runtime.JS_openFile,
    defn.Predef_stdin$default  -> runtime.JS_createStdIn,
    defn.Predef_stdout$default -> runtime.JS_createStdOut,
    defn.Predef_stderr$default -> runtime.JS_createStdErr,
  )

  override def transformApply(app: Apply)(using ctx: Context): Word =
    val Apply(fun, args) = app

    val args2 = args.map(this.apply)
    val fun2 = this(fun)

    fun2.strip match
      case TypeApply(Ident(sym), tpt :: Nil) if sym == defn.Predef_array =>
        val fun2 =
          if Subtyping.conforms(tpt.tpe, IntType) then
            Ident(runtime.JS_Array_createInt)(fun.span)

          else if Subtyping.conforms(tpt.tpe, BoolType) then
            Ident(runtime.JS_Array_createBool)(fun.span)

          else
            Ident(runtime.JS_Array_createObject)(fun.span)

        Encoded(Apply(fun2, args2)(app.tpe, app.span))(app.tpe)

      case TypeApply(Ident(sym), tpt :: Nil) if sym == runtime.JS_cast =>
        assert(args2.size == 1, args2)
        Encoded(args2.head)(tpt.tpe)

      case ref @ Ident(sym) =>
        // global function call
        val fun2 = rewiring.get(sym) match
            case Some(subst) => Ident(subst)(fun.span)
            case _ => ref

        // TODO: need encoding if result type does not agree
        Apply(fun2, args2)(app.tpe, app.span)

      case Select(qual, name) if qual.tpe.refersTo(Predef_String) =>
        // After lambda lift, `qual` is stable thus can be thrown away
        assert(qual.isIdempotent, fun.show)

        if name == "length" then
          val fun2 = Ident(runtime.JS_String_length)(fun.span)
          Apply(fun2, args2)(IntType, app.span)

        else if name == "apply" then
          val fun2 = Ident(runtime.JS_String_apply)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

        else if name == "substring" then
          // 'substring' semantics change, need rewire
          val fun2 = Ident(runtime.JS_String_substring)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

        else if name == "+" then
          // '+' is supported directly by JavaScript, but backend will rewrite `+` to `_plus_`
          val fun2 = Ident(runtime.JS_String_plus)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

        else
          throw new Exception("Unexpected method on array: " + name)

      case _ =>
        Apply(fun2, args2)(app.tpe, app.span)
