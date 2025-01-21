package native

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import native.runtime.NativeRuntime

import scala.collection.mutable

/** The compiler phase translate array operations to runtime calls
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
  */
class LowerArray(runtime: NativeRuntime) extends SastOps.TreeMap:

  class FunContext(val funSymbol: Symbol, val locals: mutable.ArrayBuffer[Symbol])
  type Context = FunContext

  val Predef_array = Definitions.instance.Predef_array
  val Predef_Array = Definitions.instance.Predef_Array

  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss yield transformNamespace(ns)

  def transformNamespace(ns: Namespace): Namespace =
    val defs = ns.defs.map:
      case fdef: FunDef =>
        val locals2 = mutable.ArrayBuffer.from(fdef.locals)
        given Context = FunContext(fdef.symbol, locals2)
        val body2 = this(fdef.body)
        fdef.copy(body = body2)(locals2.toList, fdef.span)

      case defn => defn

    Namespace(ns.symbol, ns.imports, defs)(ns.span)

  override def apply(word: Word)(using ctx: Context): Word =
    word match

      case Apply(TypeApply(fun @ Ident(sym), tpt :: Nil), arg :: Nil) if sym == Predef_array  =>
        val fun2 = Ident(runtime.Core_arrayCreate)(fun.span)
        Encoded(Apply(fun2, this(arg) :: Nil)(AnyType, word.span))(word.tpe)

      case Apply(fun, args)  =>
        fun.strip match
          case Select(qual, name) if qual.tpe.refersTo(Predef_Array) =>
            // After lambda lift, `qual` is stable thus can be thrown away
            assert(fun.isIdempotent, fun.show)
            val args2 = args.map(this.apply)

            val encoding =
              if name == "length" then
                val fun2 = Ident(runtime.Core_arrayLength)(fun.span)
                Apply(fun2, args2)(IntType, word.span)

              else if name == "apply" then
                val fun2 = Ident(runtime.Core_arrayGet)(fun.span)
                Apply(fun2, args2)(AnyType, word.span)

              else if name == "set" then
                val fun2 = Ident(runtime.Core_arraySet)(fun.span)
                Apply(fun2, args2)(VoidType, word.span)

              else
                throw new Exception("Unexpected method on array: " + name)

            Encoded(encoding)(word.tpe)

          case _ => recur(word)

      case _ => recur(word)
