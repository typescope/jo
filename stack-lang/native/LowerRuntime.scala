package native

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import native.runtime.NativeRuntime

/** This phase lowers Array and String to native runtime calls
  *
  * This phase assumes the following support functions defined in
  * runtime/native/Core.stk:
  *
  *     fun arrayCreate(size: Int): Any =
  *     fun arrayLength(arr: Any): Int = ...
  *     fun arrayGet(arr: Any, i: Int): Any = ...
  *     fun arraySet(arr: Any, i: Int, value: Any): void = ...
  *
  * This phase should happen before ExplicitAlloc so that arrays are not treated
  * as objects.
  *
  * String literals are handled in the backend for flexibility: Some string
  * literals do not have the type String. For example, the context parameter
  * runtime expects raw byte string as input.
  */
class LowerRuntime(runtime: NativeRuntime) extends phases.Phase:

  type Context = Unit
  def createContext(fdef: FunDef): Context = ()

  val defn = Definitions.instance

  val Predef_array = defn.Predef_array
  val Predef_Array = defn.Predef_Array

  val StringType = defn.StringType
  val IntType = defn.IntType

  override def transformApply(app: Apply)(using ctx: Context): Word =
    val Apply(fun, args) = app
     val args2 = args.map(this.apply)

    fun.strip match
      case TypeApply(fun @ Ident(sym), tpt :: Nil) if sym == Predef_array  =>
        val fun2 = Ident(runtime.Core_Array_create)(fun.span)
        Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

      case Select(qual, name) if qual.tpe.refersTo(Predef_Array) =>
        // After lambda lift, `qual` is stable thus can be thrown away
        assert(qual.isIdempotent, fun.show)

        if name == "length" then
          val fun2 = Ident(runtime.Core_Array_length)(fun.span)
          Apply(fun2, args2)(IntType, app.span)

        else if name == "apply" then
          val fun2 = Ident(runtime.Core_Array_apply)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

        else if name == "set" then
          val fun2 = Ident(runtime.Core_Array_set)(fun.span)
          Apply(fun2, args2)(VoidType, app.span)

        else
          throw new Exception("Unexpected method on array: " + name)

      case Select(qual, name) if qual.tpe.refersTo(Predef_String) =>
        // After lambda lift, `qual` is stable thus can be thrown away
        assert(qual.isIdempotent, fun.show)

        if name == "length" then
          val fun2 = Ident(runtime.Core_String_length)(fun.span)
          Apply(fun2, args2)(IntType, app.span)

        else if name == "apply" then
          val fun2 = Ident(runtime.Core_String_apply)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

        else if name == "substring" then
          val fun2 = Ident(runtime.Core_String_substring)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

        else if name == "+" then
          val fun2 = Ident(runtime.Core_String_plus)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

        else
          throw new Exception("Unexpected method on array: " + name)

      case _ =>
        Apply(this(fun), args2)(app.tpe, app.span)
