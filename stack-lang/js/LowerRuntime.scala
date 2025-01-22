package js

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

class LowerRuntime(runtime: JSRuntime) extends phases.Phase:

  val defn = Definitions.instance

  val Predef_String = Definitions.instance.Predef_String
  val StringType = TypeRef(Predef_String)

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

    this(fun).strip match
      case TypeApply(Ident(sym), tpt :: Nil) if sym == defn.Predef_array =>
        val fun2 =
          if Subtyping.conforms(tpt.tpe, PrimType.Int) then
            Ident(runtime.JS_arrayCreateInt)(fun.span)

          else if Subtyping.conforms(tpt.tpe, PrimType.Bool) then
            Ident(runtime.JS_arrayCreateBool)(fun.span)

          else
            Ident(runtime.JS_arrayCreateObject)(fun.span)

        Encoded(Apply(fun2, args2)(app.tpe, app.span))(app.tpe)

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
          val fun2 = Ident(runtime.JS_stringLength)(fun.span)
          Apply(fun2, args2)(PrimType.Int, app.span)

        else if name == "apply" then
          val fun2 = Ident(runtime.JS_stringGet)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

        else if name == "substring" then
          // 'substring' semantics change, need rewire
          val fun2 = Ident(runtime.JS_stringSubstring)(fun.span)
          Encoded(Apply(fun2, args2)(AnyType, app.span))(app.tpe)

        else if name == "+" then
          // '+' is supported directly by JavaScript, but backend will rewrite `+` to `_plus_`
          val fun2 = Ident(runtime.JS_stringPlus)(fun.span)
          Encoded(Apply(fun2, args2)(StringType, app.span))(app.tpe)

        else
          throw new Exception("Unexpected method on array: " + name)

      case _ =>
        assert(fun.isIdempotent, fun.show)
        Apply(fun, args2)(app.tpe, app.span)
