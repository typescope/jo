package js

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

class LowerRuntime(runtime: JSRuntime) extends phases.Phase:

  val defn = Definitions.instance
  val rewiring = Map(
    defn.Predef_p -> runtime.JS_p,
    defn.Predef_print -> runtime.JS_print,
    defn.Predef_abort -> runtime.JS_abort
  )

  type Context = Unit
  def createContext(fdef: FunDef): Context = ()

  override def transformApply(app: Apply)(using ctx: Context): Word =
    val Apply(fun, args) = app

    val args2 = args.map(this.apply)

    val fun2 =
      this(fun) match
        case TypeApply(Ident(sym), tpt :: Nil) if sym == defn.Predef_array =>
          if Subtyping.conforms(tpt.tpe, PrimType.Int) then
            Ident(runtime.JS_arrayCreateInt)(fun.span)

          else if Subtyping.conforms(tpt.tpe, PrimType.Bool) then
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
