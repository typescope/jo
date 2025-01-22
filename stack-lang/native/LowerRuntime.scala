package native

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import native.runtime.NativeRuntime

/** This phase lowers array to native runtime calls
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
  */
class LowerRuntime(runtime: NativeRuntime) extends phases.Phase:

  type Context = Unit
  def createContext(fdef: FunDef): Context = ()

  val Predef_array = Definitions.instance.Predef_array
  val Predef_Array = Definitions.instance.Predef_Array

  override def transformApply(word: Apply)(using ctx: Context): Word =
    val Apply(fun, args) = word
     val args2 = args.map(this.apply)

    fun.strip match
      case TypeApply(fun @ Ident(sym), tpt :: Nil) if sym == Predef_array  =>
        val fun2 = Ident(runtime.Core_arrayCreate)(fun.span)
        Encoded(Apply(fun2, args2)(AnyType, word.span))(word.tpe)

      case Select(qual, name) if qual.tpe.refersTo(Predef_Array) =>
        // After lambda lift, `qual` is stable thus can be thrown away
        assert(qual.isIdempotent, fun.show)

        if name == "length" then
          val fun2 = Ident(runtime.Core_arrayLength)(fun.span)
          Apply(fun2, args2)(PrimType.Int, word.span)

        else if name == "apply" then
          val fun2 = Ident(runtime.Core_arrayGet)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, word.span))(word.tpe)

        else if name == "set" then
          val fun2 = Ident(runtime.Core_arraySet)(fun.span)
          Apply(fun2, args2)(VoidType, word.span)

        else
          throw new Exception("Unexpected method on array: " + name)

      case _ =>
        Apply(this(fun), args2)(word.tpe, word.span)
