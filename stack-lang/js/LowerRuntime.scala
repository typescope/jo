package js

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable


class LowerRuntime(runtime: JSRuntime) extends SastOps.TreeMap:
  val defn = Definitions.instance

  val rewiring = Map(
    defn.Predef_p -> runtime.JS_p,
    defn.Predef_print -> runtime.JS_print,
    defn.Predef_abort -> runtime.JS_abort
  )

  class FunContext(val funSymbol: Symbol, val locals: mutable.ArrayBuffer[Symbol])

  type Context = FunContext

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

  def transform(app: Apply)(using ctx: Context): Word =
    val Apply(fun, args) = app

    val args2 = args.map(this.apply)

    val fun2 =
      this(fun) match
        case TypeApply(Ident(sym), tpt :: Nil) if sym == defn.Predef_array =>
          if Subtyping.conforms(tpt.tpe, IntType) then
            Ident(runtime.JS_arrayCreateInt)(fun.span)

          else if Subtyping.conforms(tpt.tpe, BoolType) then
            Ident(runtime.JS_arrayCreateBool)(fun.span)

          else
            Ident(runtime.JS_arrayCreateObject)(fun.span)

        case ref @ Ident(sym) =>
          // global function call
          rewiring.get(sym) match
            case Some(subst) => Ident(subst)(fun.span)
            case _ => ref

        case fun2 => fun2

    Apply(fun2, args2)(app.tpe, app.span)

  override def apply(word: Word)(using ctx: Context): Word =
      word match
        case apply: Apply =>
          transform(apply)

        case _ => recur(word)
