package ruby

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types
import sast.Types.{NamedInfo, Type}

import ruby.Trees as R

import common.UniqueName

import reporting.Reporter

import scala.collection.mutable

/** Code generator that translates Jo SAST to Ruby AST
  *
  * This replaces the CPS-based string generation with direct AST construction,
  * enabling better optimization and cleaner code generation.
  */
class RubyCodeGen(runtime: RubyRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):

  //----------------------------------------------------------------------------
  // Name management
  //----------------------------------------------------------------------------

  val SingletonFieldName = "__instance"

  private val reservedNames = new UniqueName(separator = "")

  val keywords = List(
    "for", "while", "def", "class", "if", "else", "elsif", "end",
    "begin", "rescue", "ensure", "case", "when", "unless", "until",
    "loop", "do", "break", "next", "return", "yield", "super", "self",
    "module", "include", "extend", "require", "p",
    "true", "false", "nil", "and", "or", "not", "in", "then"
  )

  // Make keywords unavailable
  for word <- keywords do reservedNames.freshName(word)

  // Make runtime symbols unavailable
  for name <- runtime.runtimeNames do reservedNames.freshName(name)

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map.empty

  val globalScope = reservedNames.newScope(separator = "")

  private def rubyInteropMemberName(sym: Symbol): String =
    runtime.rbTargetName(sym).getOrElse(rubyMemberName(sym))


  private def localExitTag(label: Symbol)(using UniqueName): R.Tree =
    // Use Symbol tag to match Ruby catch/throw semantics reliably.
    R.RawCode(s""""jo_local_exit:${rubyName(label)}".to_sym""")

  private final case class LoopFrame(
    breakLabel: Option[Symbol],
    continueLabel: Option[Symbol]
  )

  private final case class LoopContext(active: List[LoopFrame]):
    def enterLoop(frame: LoopFrame): LoopContext =
      copy(active = frame :: active)

  private enum LocalLoopJump:
    case Break
    case Continue

  private case class Context(currentFunction: Symbol, loopTargets: LoopContext)

  private def localLoopJump(label: Symbol)(using ctx: Context): Option[LocalLoopJump] =
    ctx.loopTargets.active.headOption.flatMap: frame =>
      if frame.continueLabel.contains(label) then Some(LocalLoopJump.Continue)
      else if frame.breakLabel.contains(label) then Some(LocalLoopJump.Break)
      else None

  private def isLoopControlLabel(label: Symbol)(using ctx: Context): Boolean =
    ctx.loopTargets.active.exists: frame =>
      frame.breakLabel.contains(label) || frame.continueLabel.contains(label)

  def rubyMemberName(sym: Symbol): String =
    assert(sym.isOneOf(Flags.Method | Flags.Field), "Not a method, sym = " + sym)

    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case _ =>
        val rawName = RubyCodeGen.encodeSymbolic(sym.name)
        val scope = reservedNames.newScope("_")
        val name = scope.freshName(rawName)
        symbol2UniqueName(sym) = name
        name

  def rubyName(sym: Symbol)(using scope: UniqueName): String =
    assert(!sym.isOneOf(Flags.Method | Flags.Field), "Member name should call rubyMemberName, sym = " + sym)

    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case None =>
        rewire.get(sym) match
          case Some(target) => rubyName(target)

          case None =>
            val uniqueName =
              if sym.isLocal then
                scope.freshName(RubyCodeGen.encodeSymbolic(sym.name))

              else
                val rawName = sym.fullName.replace(".", "_")
                val baseName = globalScope.freshName(RubyCodeGen.encodeSymbolic(rawName))
                // Ruby requires class names to start with uppercase letter
                if sym.isClass && baseName.headOption.exists(_.isLower) then
                  baseName.capitalize
                else
                  baseName

            symbol2UniqueName(sym) = uniqueName
            uniqueName

  //----------------------------------------------------------------------------
  // Compilation
  //----------------------------------------------------------------------------

  /** Compile a complete set of file units to a Ruby program */
  def compile(units: List[FileUnit]): R.Program =
    val defs = mutable.ArrayBuffer.empty[R.Def]

    given UniqueName = globalScope

    for unit <- units; defn <- unit do
      defn match
        case fdef: FunDef if !fdef.symbol.is(Flags.Object) => defs += compileFunction(fdef)
        case cdef: ClassDef => defs += compileClass(cdef)
        case _ =>

    // Build the program
    val requireStats: List[R.Tree] =
      runtime.requiredLibs.toList.map: name =>
        R.Call(None, "require", List(R.StringLit(name)))

    val paramInits: List[R.Tree] =
      runtime.paramIds.toList.map: (fullName, globalName) =>
        // $param_jo_IO_stdout = "jo.IO.stdout".to_sym
        R.Assign(globalName, R.RawCode(s""""$fullName".to_sym"""))

    R.Program(
      globalInit = requireStats ++ paramInits,
      defs = defs.toList,
      mainCall = R.Call(None, rubyName(runtime.start), Nil)
    )

  /** Compile a function definition */
  private def compileFunction(fdef: FunDef): R.FunDef = try
    val sym = fdef.symbol

    // Regular function - create new scope for local variables
    given UniqueName = reservedNames.newScope(separator = "")
    given Context = Context(sym, LoopContext(Nil))

    val name =
      if sym.is(Flags.Constructor) then
        // Ruby constructor is always named "initialize"
        "initialize"
      else if sym.is(Flags.Method) then
        rubyInteropMemberName(sym)
      else
        rubyName(sym)

    val params = fdef.params.map(rubyName) ++ fdef.autos.map(rubyName)
    val body0 = compileExpr(fdef.body)
    val localDecls = fdef.locals.map(sym => R.Assign(rubyName(sym), R.Nil))
    val body =
      if localDecls.isEmpty then body0
      else body0 match
        case R.Block(stats) => R.Block(localDecls ++ stats)
        case _ => R.Block(localDecls :+ body0)

    R.FunDef(name, params, body)
  catch case ex: Exception => throw ex

  /** Compile a class definition */
  private def compileClass(cdef: ClassDef)(using scope: UniqueName): R.ClassDef =
    val classSym = cdef.symbol
    val rubyClassName = rubyName(classSym)

    symbol2UniqueName(cdef.self) = "self"

    // Get all fields from the class definition
    val fieldNames = cdef.vals.map(_.symbol).map(rubyMemberName)

    // Compile methods - each method gets compiled with its own scope
    val methods = cdef.funs.map(compileFunction)

    // Add static field if this is a singleton object
    val staticFields =
      if classSym.is(Flags.Object) then
        R.Assign(SingletonFieldName, R.New(rubyClassName, Nil)) :: Nil
      else
        Nil

    R.ClassDef(
      name = rubyClassName,
      fields = fieldNames,
      methods = methods,
      staticFields = staticFields
    )

  /** Compile an expression */
  private def compileExpr(word: Word)(using scope: UniqueName, ctx: Context): R.Tree = word match
    case Literal(c) =>
      c match
        case Constant.Bool(b) => R.BoolLit(b)
        case Constant.String(s) => R.StringLit(s)
        case Constant.Int(n) => R.IntLit(n.toLong)
        case Constant.Float(d) => R.FloatLit(d)

    case Ident(sym) =>
      assert(!sym.is(Flags.Context), "Unexpected context parameter")
      R.Ident(rubyName(sym))

    case Select(qual, name) =>
      word.tpe match
        case Types.MemberRef(_, sym) =>
          val qualExpr = compileExpr(qual)
          val memberName = rubyInteropMemberName(sym)
          R.Select(qualExpr, memberName)

        case _ => throw new Exception("Unexpected select: " + word.show)

    case Block(words) =>
       val stats = words.map(compileExpr)
       if stats.size == 1 then stats.head
       else R.Block(stats)

    case encoded @ Encoded(repr) =>
      if encoded.isValueDrop then
        // Value dropped - execute for side effects, result is nil
        // We still need to execute repr, just discard its value
        compileExpr(repr)

      else if encoded.tpe.isLambdaType && repr.tpe.isClassType then
        // Wrap class instance as lambda
        val obj = compileExpr(repr)
        val objName = summon[UniqueName].freshName("instance")
        R.Block(
          R.Assign(objName, obj) ::
          R.Lambda(List("*args"), R.Call(Some(R.Ident(objName)), "apply", List(R.RawCode("*args")))) ::
          Nil
        )

      else
        compileExpr(repr)

    case Apply(Select(New(classType), _), args, autos) =>
      // Object construction
      val classSym = classType.tpe.classSymbol
      val rubyArgs = (args ++ autos).map(compileExpr)
      R.New(rubyName(classSym), rubyArgs)

    case ClassTest(arg, cls) =>
      // Type test for union types
      val value = compileExpr(arg)

      if cls == defn.Bool_type then
        R.BinOp(R.InstanceOf(value, "TrueClass"), "||", R.InstanceOf(value, "FalseClass"))
      else
        val className =
          if cls == defn.String_type then "String"
          else if cls == defn.Float_type then "Float"
          else if cls == defn.Int_type || cls == defn.Byte_type || cls == defn.Char_type then "Integer"
          else if cls == defn.Array_class then "Array"
          else rubyName(cls)

        R.InstanceOf(value, className)

    case Apply(fun, args, autos) =>
      compileCall(fun, args ++ autos)

    case Assign(Ident(sym), rhs, _) =>
      val rhsExpr = compileExpr(rhs)
      val name = rubyName(sym)
      // Assignment as expression - wrap in block that returns nil
      R.Assign(name, rhsExpr)

    case FieldAssign(lhs @ Select(qual, _), rhs) =>
      val memberName = lhs.tpe match
        case Types.MemberRef(_, sym) => rubyMemberName(sym)
        case _ => throw new Exception("Unexpected lhs of assign: " + lhs.show)

      val rhsExpr = compileExpr(rhs)

      qual match
        case Ident(sym) if sym.owner.isType && sym == sym.owner.classInfo.self =>
          // Field assignment on self - use instance variable syntax
          R.FieldAssign(None, memberName, rhsExpr)

        case _ =>
          // Field assignment on other object - use setter syntax
          val qualExpr = compileExpr(qual)
          R.FieldAssign(Some(qualExpr), memberName, rhsExpr)

    case If(cond, thenp, elsep) =>
      val condExpr = compileExpr(cond)
      val thenExpr = compileExpr(thenp)
      val elseExpr =
        if elsep.isEmpty then R.Block(Nil)
        else compileExpr(elsep)
      R.If(condExpr, thenExpr, elseExpr)

    case While(cond, body) =>
      val condExpr = compileExpr(cond)
      val (continueLabelOpt, loopBody) =
        body match
          case Labeled(continueLabel, continueType, body2) if continueType.isVoidType =>
            (Some(continueLabel), body2)
          case _ =>
            (None, body)
      val body2 =
        given Context = ctx.copy(loopTargets = ctx.loopTargets.enterLoop(LoopFrame(None, continueLabelOpt)))
        compileExpr(loopBody)
      R.While(condExpr, body2)

    case Labeled(label, resultType, While(cond, rawBody)) =>
      assert(resultType.isVoidType, s"Ruby backend only supports VoidType labeled blocks for now, found ${resultType.show}")
      val (continueLabelOpt, loopBody) =
        rawBody match
          case Labeled(continueLabel, continueType, body2) if continueType.isVoidType =>
            (Some(continueLabel), body2)
          case _ =>
            (None, rawBody)
      val bodyExpr =
        given Context = ctx.copy(loopTargets = ctx.loopTargets.enterLoop(LoopFrame(Some(label), continueLabelOpt)))
        compileExpr(loopBody)
      R.While(compileExpr(cond), bodyExpr)

    case Labeled(label, resultType, body) =>
      assert(resultType.isVoidType, s"Ruby backend only supports VoidType labeled blocks for now, found ${resultType.show}")
      R.Catch(localExitTag(label), compileExpr(body))

    case Return(label, value) =>
      if label.is(Flags.Fun) then
        R.Return(compileExpr(value))
      else
        assert(value.tpe.isVoidType && value.isEmpty,
          s"Ruby backend expects empty VoidType payload for local labeled return, found ${value.show}: ${value.tpe.show}")
        localLoopJump(label) match
          case Some(LocalLoopJump.Break) =>
            R.Break
          case Some(LocalLoopJump.Continue) =>
            R.Next
          case None =>
            assert(
              !isLoopControlLabel(label),
              s"Ruby backend would emit throw for active loop-control label `${label.name}`; expected native break/next")
            R.Throw(localExitTag(label), Some(R.Nil))

    case _: Def | _: With | _: Allow | _: Match | _: TypeApply |
         _: New | _: IsExpr | _: PatValDef | _: Lambda | _: RecordLit =>
      throw new Exception("Unexpected: " + word)

  /** Whether `name` is a valid Ruby method name.
    * Allows letters, digits, underscores, with optional `?` or `!` suffix.
    */
  /** Whether `name` is a valid Ruby writer name (plain identifier, no `?`/`!` suffix). */
  private def isValidRubyWriterName(name: String): Boolean =
    if name.isEmpty then return false
    val first = name.charAt(0)
    (first.isLetter || first == '_') &&
      name.forall(c => c.isLetterOrDigit || c == '_')

  /** Abort with a compile error for a bad dynamic method/attribute name. */
  private def abortBadRubyName(nameWord: Word, op: String)(using ctx: Context): Nothing =
    Reporter.abort(
      s"$op requires a string literal name that is a valid Ruby method name",
      nameWord.pos(using ctx.currentFunction.source)
    )

  /** Abort with a compile error when a non-literal is passed where a literal name is required. */
  private def abortRequiresLiteral(nameWord: Word, api: String)(using ctx: Context): Nothing =
    Reporter.abort(
      s"$api requires a string literal name",
      nameWord.pos(using ctx.currentFunction.source)
    )

  /** Compile a packed vararg list for a `@rb.interop` abstract method call.
    *
    * Self-recursive and type-agnostic: reads the SAST structure directly.
    * The typer has already enforced the calling convention for each vararg type.
    *
    * - `+` item with namedArg wrapper → `key: value` (`R.KwArg`)
    * - `+` plain item                 → positional arg
    * - `++` splice                    → `*xs` (`R.Starred`); Jo List[T] converted via rb.array
    */
  private def compileVarargItems(word: Word)(using scope: UniqueName, ctx: Context): List[R.Tree] =
    word match
      case Apply(Ident(sym), Nil, _) if sym == defn.List_empty => Nil

      case Apply(Select(prev, "+"), List(item), _) =>
        val compiled = item match
          case Apply(fun, List(Literal(Constant.String(name)), value), _)
              if fun.refers(defn.compile_namedArg) =>
            R.KwArg(name, compileExpr(value))
          case other =>
            compileExpr(other)
        compileVarargItems(prev) :+ compiled

      case Apply(Select(prev, "++"), List(xs), _) =>
        // Jo List[T] is not a native Ruby Array; convert via rb.array before splatting
        val rubyArray = R.Call(None, rubyName(runtime.rb_array), List(compileExpr(xs)))
        compileVarargItems(prev) :+ R.Starred(rubyArray)

      case _ =>
        throw new Exception("unexpected vararg list shape in @rb.interop call: " + word.show)

  /** Compile one call argument with awareness of the declared parameter type.
    *
    * - `@rb.positional`: strip any namedArg wrapper; emit positionally.
    * - `@rb.keyword`:    emit as `key: value`, using rename if specified.
    * - Otherwise:        strip any namedArg wrapper; emit positionally.
    */
  private def compileCallArgWithType(word: Word, paramName: String, paramType: Types.Type)
      (using scope: UniqueName, ctx: Context): R.Tree =
    val compile_namedArg = defn.compile_namedArg
    if runtime.isKeywordType(paramType) then
      val kwName = runtime.keywordRename(paramType).getOrElse(paramName)
      val valueWord = word match
        case Apply(fun, List(_, value), _) if fun.refers(compile_namedArg) => value
        case other => other
      R.KwArg(kwName, compileExpr(valueWord))
    else
      val valueWord = word match
        case Apply(fun, List(_, value), _) if fun.refers(compile_namedArg) => value
        case other => other
      compileExpr(valueWord)

  /** Compile call args paired with their declared parameter types.
    * Keyword args (`@rb.keyword`) are emitted as `key: value` and collected
    * at the end so positional args always precede them.
    */
  private def compileCallArgListWithTypes(args: List[Word], params: List[NamedInfo[Type]])
      (using scope: UniqueName, ctx: Context): List[R.Tree] =
    val positional = scala.collection.mutable.ListBuffer[R.Tree]()
    val keyword    = scala.collection.mutable.ListBuffer[R.Tree]()
    for (word, param) <- args.zip(params) do
      val compiled = compileCallArgWithType(word, param.name, param.info)
      compiled match
        case _: R.KwArg => keyword += compiled
        case _          => positional += compiled
    positional.toList ++ keyword.toList

  /** Compile a function/method call */
  private def compileCall(fun: Word, args: List[Word])(using scope: UniqueName, ctx: Context): R.Tree =
    fun match
      case Ident(sym) if sym.isFunction =>
        if sym == runtime.paramKey then
          val paramSym = args.head match
            case Ident(paramSym) => paramSym
            case word => throw new Exception("Unsupported argument to paramKey: " + word)

          val globalName = runtime.getOrCreateParamId(paramSym)
          R.Ident(globalName)

        else if sym == defn.jo_pass then
          R.Nil

        else if sym.is(Flags.Object) then
          // Object accessor: replace call with direct access
          val funType = sym.tpe.asProcType
          val classSym = funType.resultType.classSymbol

          // Mark the class as reachable - it will get a static instance field
          val className = rubyName(classSym)
          R.Select(R.Ident(className), SingletonFieldName)

        // --- rb.* FFI intrinsics ---

        else if sym == runtime.rb_nil then
          // rb.nil → nil
          R.Nil

        else if sym == runtime.rb_dynamic then
          // rb.dynamic(x) → x  (no-op; all Ruby values are already Ruby objects)
          val receiver :: Nil = args: @unchecked
          compileExpr(receiver)

        else if sym == runtime.rb_const then
          // rb.const("Name") → Name  (emit the constant name as raw Ruby code)
          val nameWord :: Nil = args: @unchecked
          nameWord match
            case Literal(Constant.String(name)) => R.RawCode(name)
            case _ => abortRequiresLiteral(nameWord, "rb.const")

        else if sym == runtime.rb_require then
          // rb.require("name") → require("name")
          val nameWord :: Nil = args: @unchecked
          nameWord match
            case Literal(Constant.String(name)) =>
              runtime.requiredLibs += name
              R.Nil
            case _ => abortRequiresLiteral(nameWord, "rb.require")

        else if sym == runtime.rb_isNil then
          // rb.isNil(obj) → obj.nil?
          val receiver :: Nil = args: @unchecked
          R.Call(Some(compileExpr(receiver)), "nil?", Nil)

        else if sym == runtime.rb_isIdentical then
          // rb.isIdentical(a, b) → a.equal?(b)
          val a :: b :: Nil = args: @unchecked
          R.Call(Some(compileExpr(a)), "equal?", List(compileExpr(b)))

        else if sym == runtime.rb_raw then
          // rbRaw("code") → emit code as a raw Ruby expression
          val Literal(Constant.String(code)) = args.head: @unchecked
          R.RawCode(code)

        else if sym == runtime.rb_try then
          // rb.try(action): Result[T, Error]
          // Intrinsified: wrap the call site in a begin/rescue block.
          // Error is an interface — the Ruby exception object IS the Error value.
          val action :: Nil = args: @unchecked
          val tempResult = summon[UniqueName].freshName("rbresult")
          val tempExc    = summon[UniqueName].freshName("rbexc")
          val okExpr     = R.New(rubyName(runtime.jo_Ok), List(compileExpr(action)))
          val errExpr    = R.New(rubyName(runtime.jo_Err), List(R.Ident(tempExc)))
          R.Block(
            R.Assign(tempResult, R.Nil) ::
            R.BeginRescue(R.Assign(tempResult, okExpr), tempExc, R.Assign(tempResult, errExpr)) ::
            R.Ident(tempResult) ::
            Nil
          )

        else
          val procType = sym.tpe.asProcType
          val rubyArgs = compileCallArgListWithTypes(args, procType.params ++ procType.autos)
          R.Call(None, rubyName(sym), rubyArgs)

      case Select(qual, name) if qual.tpe.isSubtype(defn.BoolType) =>
        compileBoolPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.IntType) =>
        compileIntPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.LongType) =>
        compileLongPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.ByteType) =>
        compileIntPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.CharType) =>
        compileCharPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.FloatType) =>
        compileFloatPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.StringType) =>
        compileStringPrimitive(name, qual, args)

      case f if f.tpe.isLambdaType =>
        // Lambda call - use .call() syntax
        val funExpr = compileExpr(f)
        val rubyArgs = args.map(compileExpr)
        R.LambdaCall(funExpr, rubyArgs)

      case Select(qual, name) =>
        // Intrinsify rb.Dynamic dynamic operations when they appear as member calls
        val methodSym = fun.tpe match
          case Types.MemberRef(_, sym) => sym
          case _ => throw new Exception("Unexpected select: " + fun.show)

        if methodSym == runtime.rb_Dynamic_selectDynamic then
          // x.selectDynamic("a") → x.a
          val nameWord :: Nil = args: @unchecked
          nameWord match
            case Literal(Constant.String(attrName)) if RubyRuntime.isValidMethodName(attrName) =>
              R.Select(compileExpr(qual), attrName)
            case Literal(Constant.String(_)) =>
              abortBadRubyName(nameWord, "selectDynamic")
            case _ =>
              abortRequiresLiteral(nameWord, "selectDynamic")

        else if methodSym == runtime.rb_Dynamic_updateDynamic then
          // x.updateDynamic("a", v) → x.a = v
          val nameWord :: value :: Nil = args: @unchecked
          nameWord match
            case Literal(Constant.String(attrName)) if isValidRubyWriterName(attrName) =>
              R.FieldAssign(Some(compileExpr(qual)), attrName, compileExpr(value))
            case Literal(Constant.String(_)) =>
              abortBadRubyName(nameWord, "updateDynamic")
            case _ =>
              abortRequiresLiteral(nameWord, "updateDynamic")

        else if methodSym == runtime.rb_Dynamic_callDynamic then
          // x.callDynamic("foo", args...) → x.foo(args...)
          val nameWord :: packedArgs :: Nil = args: @unchecked
          val compiledArgs = compileVarargItems(packedArgs)
          nameWord match
            case Literal(Constant.String(methodName)) if RubyRuntime.isValidMethodName(methodName) =>
              R.Call(Some(compileExpr(qual)), methodName, compiledArgs)
            case Literal(Constant.String(_)) =>
              abortBadRubyName(nameWord, "callDynamic")
            case _ =>
              abortRequiresLiteral(nameWord, "callDynamic")

        else if methodSym == runtime.rb_Dynamic_init then
          // x.init(args...) → x.new(args...)
          val packedArgs :: Nil = args: @unchecked
          val compiledArgs = compileVarargItems(packedArgs)
          R.Call(Some(compileExpr(qual)), "new", compiledArgs)

        else if methodSym == runtime.rb_Dynamic_getDynamic then
          // x.getDynamic(k) → x[k]
          val key :: Nil = args: @unchecked
          R.Index(compileExpr(qual), List(compileExpr(key)))

        else if methodSym == runtime.rb_Dynamic_setDynamic then
          // x.setDynamic(k, v) → x[k] = v
          val key :: value :: Nil = args: @unchecked
          R.IndexAssign(compileExpr(qual), List(compileExpr(key)), compileExpr(value))

        else if methodSym == runtime.rb_Dynamic_cast then
          // x.cast[T] → x  (no-op at runtime)
          compileExpr(qual)

        else
          // Regular method/function call on an object
          val memberName = rubyInteropMemberName(methodSym)
          val procType = methodSym.tpe.asProcType
          val isInteropVararg =
            methodSym.owner.hasAnnotation(runtime.annot_interop) && procType.hasVararg
          val rubyArgs =
            if isInteropVararg then
              val prefixArgs   = args.init
              val varargPack   = args.last
              val prefixParams = (procType.params ++ procType.autos).init
              compileCallArgListWithTypes(prefixArgs, prefixParams) ++
                compileVarargItems(varargPack)
            else
              compileCallArgListWithTypes(args, procType.params ++ procType.autos)
          R.Call(Some(compileExpr(qual)), memberName, rubyArgs)

      case Encoded(repr) =>
        // Strip encoding and recurse
        compileCall(repr, args)

      case _ =>
        throw new Exception("Unexpected function in call: " + fun)

  /** Compile Bool class method operations (&&, ||, ==, !=, ~!, toString) */
  private def compileBoolPrimitive(name: String, qual: Word, args: List[Word])(using scope: UniqueName, ctx: Context): R.Tree =
    name match
      case "&&" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "&&", compileExpr(arg))

      case "||" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "||", compileExpr(arg))

      case "==" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "==", compileExpr(arg))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "!=", compileExpr(arg))

      case "~!" =>
        R.UnaryOp("!", compileExpr(qual))

      case "toString" =>
        R.Call(Some(compileExpr(qual)), "to_s", Nil)

      case _ =>
        throw new Exception(s"Unknown Bool method: $name")

  /** Reduce an integer expression to signed 32-bit (two's-complement wrap):
    * `((e + 0x80000000) & 0xFFFFFFFF) - 0x80000000`. Ruby Integers are
    * arbitrary precision, so overflow-capable arithmetic is masked to keep
    * `Int` 32-bit and consistent with the other backends.
    */
  private def wrapInt32(e: R.Tree): R.Tree =
    val off  = R.IntLit(0x80000000L)
    val mask = R.IntLit(0xFFFFFFFFL)
    R.BinOp(R.BinOp(R.BinOp(e, "+", off), "&", mask), "-", off)

  /** Reduce an integer expression to signed 64-bit (two's-complement wrap). */
  private def wrapInt64(e: R.Tree): R.Tree =
    val off  = R.BinOp(R.IntLit(1), "<<", R.IntLit(63))
    val mask = R.BinOp(R.BinOp(R.IntLit(1), "<<", R.IntLit(64)), "-", R.IntLit(1))
    R.BinOp(R.BinOp(R.BinOp(e, "+", off), "&", mask), "-", off)

  private def compileIntPrimitive(name: String, qual: Word, args: List[Word])(using scope: UniqueName, ctx: Context): R.Tree =
    name match
      case "<<" =>
        // Left shift is a bit operation with defined 32-bit semantics
        val arg :: Nil = args: @unchecked
        wrapInt32(R.BinOp(compileExpr(qual), name, compileExpr(arg)))

      case "/" =>
        // Truncate toward zero: a.fdiv(b).truncate. The only overflowing
        // division, INT_MIN / -1, is runtime-dependent.
        val arg :: Nil = args: @unchecked
        val f = R.Call(Some(compileExpr(qual)), "fdiv", List(compileExpr(arg)))
        R.Call(Some(f), "truncate", Nil)

      case "%" =>
        // Truncated remainder (sign of dividend): Integer#remainder
        val arg :: Nil = args: @unchecked
        R.Call(Some(compileExpr(qual)), "remainder", List(compileExpr(arg)))

      case "==" | "!=" | "<" | ">" | "<=" | ">=" | "&" | "|" | "^" | ">>" | "+" | "-" | "*" =>
        // Arithmetic overflow is undefined
        // comparisons and the other bit ops stay within range for in-range inputs
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), name, compileExpr(arg))

      case "toFloat" =>
        R.Select(compileExpr(qual), "to_f")

      case "toLong" =>
        // Int/Byte/Char -> Long: same value in Ruby (all are Integer)
        compileExpr(qual)

      case "toByte" =>
        R.BinOp(compileExpr(qual), "&", R.IntLit(0xFF))

      case "toChar" =>
        // Char is represented as Int (Unicode code point) in Ruby, so this is a no-op
        compileExpr(qual)

      case "toInt" =>  // called from Byte
        compileExpr(qual)

      case "~-" =>
        wrapInt32(R.UnaryOp("-", compileExpr(qual)))

      case "toString" =>
        R.Select(compileExpr(qual), "to_s")

      case _ =>
        throw new Exception(s"Unknown Int method: $name")

  /** Compile Long primitive operations (signed 64-bit). */
  private def compileLongPrimitive(name: String, qual: Word, args: List[Word])(using scope: UniqueName, ctx: Context): R.Tree =
    name match
      case "<<" =>
        // Left shift is a bit operation with defined 64-bit semantics
        val arg :: Nil = args: @unchecked
        wrapInt64(R.BinOp(compileExpr(qual), "<<", compileExpr(arg)))

      case "/" =>
        // Truncate toward zero; Ruby Integer#/ floors for negative operands.
        val arg :: Nil = args: @unchecked
        R.Call(Some(R.Call(Some(compileExpr(qual)), "fdiv", List(compileExpr(arg)))), "truncate", Nil)

      case "%" =>
        // Truncated remainder (sign of dividend): Integer#remainder
        val arg :: Nil = args: @unchecked
        R.Call(Some(compileExpr(qual)), "remainder", List(compileExpr(arg)))

      case "+" | "-" | "*" | "==" | "!=" | "<" | ">" | "<=" | ">=" | "&" | "|" | "^" | ">>" =>
        // Arithmetic overflow is undefined; comparisons and the other bit ops
        // stay within range for in-range inputs.
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), name, compileExpr(arg))

      case "~-" =>
        R.UnaryOp("-", compileExpr(qual))

      case "toInt" =>
        // Long -> Int: low 32 bits, signed
        wrapInt32(compileExpr(qual))

      case "toFloat" =>
        R.Select(compileExpr(qual), "to_f")

      case "toString" =>
        R.Select(compileExpr(qual), "to_s")

      case _ =>
        throw new Exception(s"Unknown Long method: $name")

  /** Compile Float primitive operations */
  private def compileFloatPrimitive(name: String, qual: Word, args: List[Word])(using scope: UniqueName, ctx: Context): R.Tree =
    name match
      case "+" | "-" | "*" | "/" | ">" | "<" | ">=" | "<=" | "==" | "!=" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), name, compileExpr(arg))

      case "toInt" =>
        R.Select(compileExpr(qual), "to_i")

      case "~-" =>
        R.UnaryOp("-", compileExpr(qual))

      case "toString" =>
        R.Select(compileExpr(qual), "to_s")

      case _ =>
        throw new Exception(s"Unknown Float method: $name")

  /** Compile Char primitive operations */
  private def compileCharPrimitive(name: String, qual: Word, args: List[Word])(using scope: UniqueName, ctx: Context): R.Tree =
    name match
      case "==" | "!=" | "<" | ">" | "<=" | ">=" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), name, compileExpr(arg))

      case "toByte" =>
        // Char is already represented as Int in Ruby
        R.BinOp(compileExpr(qual), "&", R.IntLit(0xFF))

      case "toInt" =>
        // Char is already represented as Int (Unicode code point) in Ruby
        compileExpr(qual)

      case "toLong" =>
        // Char -> Long: same Unicode code point value in Ruby
        compileExpr(qual)

      case "toString" =>
        // Use chr with UTF-8 encoding to support Unicode code points > 255
        R.Call(Some(compileExpr(qual)), "chr", List(R.RawCode("Encoding::UTF_8")))

      case _ =>
        throw new Exception(s"Unknown Char method: $name")

  /** Compile String primitive operations */
  private def compileStringPrimitive(name: String, qual: Word, args: List[Word])(using scope: UniqueName, ctx: Context): R.Tree =
    name match
      case "+" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "+", compileExpr(arg))

      case "==" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "==", compileExpr(arg))

      case "size" =>
        R.Select(compileExpr(qual), "length")

      case "get" =>
        val index :: Nil = args: @unchecked
        // str[index].ord - get character code point at index
        val charSelect = R.Index(compileExpr(qual), List(compileExpr(index)))
        R.Select(charSelect, "ord")

      case "substring" =>
        val index :: len :: Nil = args: @unchecked
        // str[index, len] - Ruby slice syntax
        R.Index(compileExpr(qual), List(compileExpr(index), compileExpr(len)))

      case "indexOf" =>
        val other :: from :: Nil = args: @unchecked
        val idxName = summon[UniqueName].freshName("idx")
        val indexCall = R.Call(Some(compileExpr(qual)), "index", List(compileExpr(other), compileExpr(from)))
        R.Block(
          R.Assign(idxName, indexCall) ::
          R.If(
            R.BinOp(R.Ident(idxName), "==", R.Nil),
            R.IntLit(-1),
            R.Ident(idxName)) ::
          Nil)

      case "iterator" =>
        R.Call(None, rubyName(runtime.String_iterator), List(compileExpr(qual)))

      case "toLower" =>
        R.Call(Some(compileExpr(qual)), "downcase", Nil)

      case "toUpper" =>
        R.Call(Some(compileExpr(qual)), "upcase", Nil)

      case _ =>
        throw new Exception(s"Unknown String method: $name")

  /** Generate Ruby code from file units and write to output file */
  def generate(units: List[FileUnit], outFile: String): Unit =
    val program = compile(units)

    val pw = new java.io.PrintWriter(outFile)
    Printer.print(program, pw)
    pw.flush()
    pw.close()

end RubyCodeGen

object RubyCodeGen:
  private val symbolEncoding = Map(
    '$' -> "_dollar",
    '.' -> "_",
    '+' -> "_plus",
    '-' -> "_minus",
    '*' -> "_times",
    '/' -> "_div",
    '%' -> "_mod",
    '=' -> "_eq",
    '<' -> "_less",
    '>' -> "_greater",
    '!' -> "_bang",
    '&' -> "_amp",
    '|' -> "_bar",
    '^' -> "_hat",
    '~' -> "_tilde",
    '?' -> "_qmark",
    ':' -> "_colon"
  )

  def encodeSymbolic(name: String): String =
    val sb = new StringBuilder(name.length * 2)
    name.foreach { ch =>
      symbolEncoding.get(ch) match
        case Some(replacement) => sb.append(replacement)
        case None => sb.append(ch)
    }
    sb.toString
