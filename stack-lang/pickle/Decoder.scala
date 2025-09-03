package pickle

import ast.Positions.*
import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import typing.Namer.DelayedDef
import reporting.Reporter

import scala.collection.mutable

/** Decode trees, symbols and types
  *
  * This is the counterpart to Encoder.scala, reading back the encoded format
  * to reconstruct SAST trees.
  */
object Decoder:
  class NameRef(fullName: String, kind: Int)

  private class State(val root: Symbol, nameRefs: Array[NameRef]):
    /** External symbols loaded from the name table */
    private var externalSymbols: Array[Symbol] = new Array(nameRefs.size)

    /** Map from internal symbol IDs to symbols */
    private val internalSymbols = mutable.Map.empty[Int, Symbol]

    /** Current position tracking for rebuilding spans */
    private var currentOffset = 0

    def getExternalSymbol(index: Int)(using defn: Definitions): Symbol =
      if index < 0 || index >= externalSymbols.length then
        throw new Exception(s"Invalid external symbol index: $index")
      var sym = externalSymbols(index)
      if sym == null then
        val nameRef = nameRefs(index)
        sym = nameRef.kind match
          case Format.Term => defn.resolveTermByPath(nameRef.fullName)
          case Format.Pattern => defn.resolvePatternByPath(nameRef.fullName)
          case Format.Type => defn.resolveTypeByPath(nameRef.fullName)

        externalSymbols(index) = sym
      end
      sym

    def registerInternalSymbol(id: Int, symbol: Symbol): Unit =
      internalSymbols(id) = symbol

    def getInternalSymbol(id: Int): Symbol =
      internalSymbols.get(id) match
        case Some(sym) => sym
        case None => throw new Exception(s"Unknown internal symbol id: $id")

    def withPosition[T](startDelta: Int, lengthDelta: Int)(fn: Span => T): T =
      val startOffset = currentOffset + startDelta
      currentOffset = startOffset
      val span = Span(startOffset, lengthDelta)
      val result = fn(span)
      currentOffset = startOffset + lengthDelta
      result

  end State

  def error(message: String): Nothing = Reporter.abortInternal(message)

  def error(message: String, pos: SourcePosition): Nothing = Reporter.abort(message, pos)

  //----------------------------------------------------------------------------

  def decode()(using buf: ReadBuffer, defnLazy: Definitions.Lazy): DelayedDef[Namespace] =
    val rootName = decodeString()
    val source = decodeSource()
    val symSpan = decodeSpan()

    // Read external name table
    val nameTableAddr: Int = decodeIntRaw()
    val nameRefs: Array[NameRef] = buf.withPosition(nameTableAddr):
      decodeExternalNameTable()

    given Source = source

    val rootSymbol: Symbol = resolveNamespace(
      rootName.split('.'),
      symSpan.toPos,
      isBranch = false
    )

    given state: State = new State(rootSymbol, nameRefs)

    val delayedMembers: Array[DelayedDef[Def]] = index(rootSymbol)

    val span = Span(eecodeNat(), decodeNat())

    // Add members
    val nameTable = infoProvider.info(nsSym).as[ContainerInfo].nameTable
    for d <- delayedMembers do nameTable.define(d.symbol)

    val delayd = () =>
      val members = for d <- delayedMembers yield d.force()
      Namespace(rootSymbol, List.empty, members)(span)

    DelayedDef(rootSymbol, delayed)


  /** Resolve namespace and create intermediate namespace on demand
    *
    * It also checks redefinition of namespace.
    */
  def resolveNamespace
      (parts: List[String], pos: SourcePosition, isBranch: Boolean)
      (using defnLazy: Definitions.Lazy)
  : Symbol =

    def check(sym: Symbol): Symbol =
      val name = sym.name
      val pos = sym.sourcePos
      val context = s"Context: loading ${pos.source.file}"

      if sym.isNamespace && !sym.isAlias then
        if isBranch && !sym.is(Flags.Branch) then
          error(s"The $name is already defined as a namespace at $pos. $context")

        else if !isBranch then
          // leaf namespace should not exist
          if sym.is(Flags.Branch) then
            error(s"The namespace $name is already defined as a branch name at $pos. $context")

          else
            error(s"The namespace $name is already defined at $pos. $context")

        else
          sym

      else
        error(s"The $name is already defined as a member at $pos. $context")

    val rootNameTable = defnLazy.rootNameTable
    val infoProvider = defnLazy.infoProvider

    parts match
      case name :: Nil =>
        rootNameTable.resolveTerm(name) match
          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = Symbol.createSymbol(name, flags, pos)
            rootNameTable.define(sym)
            infoProvider.add(sym, owner = null, new ContainerInfo(new NameTable))
            sym

          case Some(sym) => check(sym)

      case prefix :+ name =>
        val nsSym = resolveNamespace(prefix, pos, isBranch = true)

        assert(nsSym.isNamespace, "Not a namespace " + nsSym)
        val nameTable = infoProvider.info(nsSym).as[ContainerInfo].nameTable

        nameTable.resolveTerm(name) match
          case Some(sym) => check(sym)

          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = Symbol.createSymbol(name, flags, pos)
            infoProvider.add(sym, nsSym, new ContainerInfo(new NameTable))
            nameTable.define(sym)
            sym

  /** Index definitions without loading trees and symbol infos */
  private def index(owner: Symbol)(using buf: ReadBuffer, defn: Definitions, state: State): Array[DelayedDef[Def]] =
    val count = decodeNat()
    val delayedDefs = new Array[DelayedDef[Def]](count)

    given Source = owner.sourcePos.source

    var i = 0
    while i < count do
      // Store the current buffer position for this definition
      val defPosition = buf.position
      val defType = decodeByte()

      defType match
        case Format.ParamDef =>
          val id = decodeNat()
          val name = decodeString()
          val flags = decodeFlags()
          val span = decodeSpan()

          val tptPosition = buf.position

          val sym = Symbol.createSymbol(name, flags, span.toPos)

          val paramDefTree = () =>
            given ReadBufer = buf.fresh(tptPosition)
            val tpt = decodeTypeTree()
            // TODO: synchronize encoder
            val span = decodeSpan()
            ParamDef(sym, tpt)(span)

          val delayedParamDef = DelayedInfo(sym, paramDefTree)
          delayedDefs(i) = delayedParamDef

          defn.addLazy(sym, owner, () => delayedParamDef.force().tpt.tpe)
          state.registerInternalSymbol(id, sym)

        case Format.FunDef =>
          val length = decodeIntRaw()

          val id = decodeNat()
          val name = decodeString()
          val flags = decodeFlags()
          val span = decodeSpan()

          val sym = new Symbol(name, flags)
          state.registerInternalSymbol(id, sym)
          // Skip to the end of this definition using the encoded length
          buf.setPosition(defPosition + length)

        case Format.ClassDef =>
          // ClassDef has length encoding, read the length and skip
          val length = decodeIntRaw()
          val id = decodeNat()
          val name = decodeString()

          val sym = new Symbol(name, Flags.Class)
          state.registerInternalSymbol(id, sym)
          // Skip to the end of this definition using the encoded length
          buf.setPosition(defPosition + length)
          sym

        case Format.TypeDef =>
          // TypeDef has no length encoding, decode normally
          val id = decodeNat()
          val name = decodeString()
          val symbolType = decodeType()

          val sym = new TypeSymbol(name, Kind.Simple)
          sym.setInfo(symbolType)
          state.registerInternalSymbol(id, sym)
          sym

        case Format.PatDef =>
          // PatDef has length encoding, read the length and skip
          val length = decodeIntRaw()
          val id = decodeNat()
          val flags = decodeFlags()
          val name = decodeString()

          val sym = new Symbol(name, flags)
          state.registerInternalSymbol(id, sym)
          // Skip to the end of this definition using the encoded length
          buf.setPosition(defPosition + length)
          sym

        case Format.Section =>
          // Section has length encoding, read the length and skip
          val length = decodeIntRaw()
          val name = decodeString()
          val sym = new Symbol(name, Flags.Empty)
          // Skip to the end of this definition using the encoded length
          buf.setPosition(defPosition + length)
          sym

        case _ =>
          throw new Exception(s"Unknown definition type: $defType")

      i += 1
    end while

    delayedDefs

  //----------------------------------------------------------------------------

  private def decodeExternalNameTable()(using buf: ReadBuffer, defn: Definitions): Array[NameRef] =
    val count = decodeNat()
    val nameRefs = new Array[NameRef](count)

    var i = 0
    while i < count do
      val fullName = decodeString()
      val kind = decodeByte()

      nameRefs(i) = new NameRef(fullName, kind)
      i += 1

    nameRefs

  private def decodeSymbolRef()(using buf: ReadBuffer, defn: Definitions, state: State): Symbol =
    val refType = decodeByte()
    refType match
      case 0 => // Internal symbol
        val id = decodeNat()
        state.getInternalSymbol(id)

      case 1 => // External symbol
        val index = decodeNat()
        state.getExternalSymbol(index)

      case _ => throw new Exception(s"Invalid symbol reference type: $refType")

  private def decodeFlags()(using buf: ReadBuffer): Flags =
    val count = decodeByte()
    var flags = Flags.Empty
    for _ <- 0 until count do
      val flagIndex = decodeByte()
      flags |= Flags.fromIndex(flagIndex)
    flags

  private def decodeKind()(using buf: ReadBuffer, defn: Definitions, state: State): Kind =
    val kindType = decodeByte()
    kindType match
      case 0 => Kind.Simple

      case 1 => // Arrow kind
        val args = decodeRepeated(decodeKind)
        val to = decodeKind()
        Kind.Arrow(args, to)

      case _ => throw new Exception(s"Invalid kind type: $kindType")

  private def decodeDef()(using buf: ReadBuffer, defn: Definitions, state: State): Def =
    val defType = decodeByte()
    val startDelta = decodeInt()
    val lengthDelta = decodeInt()

    state.withPosition(startDelta, lengthDelta): span =>
      defType match
        case Format.ParamDef =>
          val flags = decodeFlags()
          val name = decodeString()
          val id = decodeNat()
          val tpt = decodeTypeTree

          val symbol = new Symbol(name, flags)
          state.registerInternalSymbol(id, symbol)
          ParamDef(symbol, tpt)(span)

        case Format.ValDef =>
          val id = decodeNat()
          val flags = decodeFlags()
          val name = decodeString()
          val symbolType = decodeType()
          val rhs = decodeWord()

          val symbol = new Symbol(name, flags)
          symbol.setInfo(symbolType)
          state.registerInternalSymbol(id, symbol)
          ValDef(symbol, rhs)(span)

        case Format.ClassDef =>
          val id = decodeNat()
          val name = decodeString()

          val symbol = new Symbol(name, Flags.Empty)
          state.registerInternalSymbol(id, symbol)

          val tparams = decodeRepeated(buf): _ =>
            val tparamId = decodeNat()
            val tparamName = decodeString()
            val tparamInfo = decodeType()
            val tparamStartDelta = decodeInt()
            val tparamLength = decodeInt()

            val tparam = new TypeSymbol(tparamName, Kind.Simple)
            tparam.setInfo(tparamInfo)
            state.registerInternalSymbol(tparamId, tparam)
            tparam

          val selfId = decodeNat()
          val selfFlags = decodeFlags()
          val selfName = decodeString()
          val self = new Symbol(selfName, selfFlags)
          state.registerInternalSymbol(selfId, self)

          val vals = decodeRepeated(buf): _ =>
            val valId = decodeNat()
            val valFlags = decodeFlags()
            val valName = decodeString()
            val valType = decodeType()
            val valStartDelta = decodeInt()
            val valLength = decodeInt()

            val valSym = new Symbol(valName, valFlags)
            valSym.setInfo(valType)
            state.registerInternalSymbol(valId, valSym)
            valSym

          val funs = decodeRepeated(decodeDef).map(_.asInstanceOf[FunDef])

          ClassDef(symbol, tparams, self, vals, funs)(span)

        case Format.FunDef =>
          val id = decodeNat()
          val flags = decodeFlags()
          val name = decodeString()

          val symbol = new Symbol(name, flags)
          state.registerInternalSymbol(id, symbol)

          val tparams = decodeRepeated(buf): _ =>
            val tparamId = decodeNat()
            val tparamName = decodeString()
            val tparamInfo = decodeType()
            val tparamStartDelta = decodeInt()
            val tparamLength = decodeInt()

            val tparam = new TypeSymbol(tparamName, Kind.Simple)
            tparam.setInfo(tparamInfo)
            state.registerInternalSymbol(tparamId, tparam)
            tparam

          val params = decodeRepeated(buf): _ =>
            val paramId = decodeNat()
            val paramName = decodeString()
            val paramInfo = decodeType()
            val paramStartDelta = decodeInt()
            val paramLength = decodeInt()

            val param = new Symbol(paramName, Flags.Empty)
            param.setInfo(paramInfo)
            state.registerInternalSymbol(paramId, param)
            param

          val autos = decodeRepeated(buf): _ =>
            val autoId = decodeNat()
            val autoName = decodeString()
            val autoInfo = decodeType()
            val autoStartDelta = decodeInt()
            val autoLength = decodeInt()

            val auto = new Symbol(autoName, Flags.Auto)
            auto.setInfo(autoInfo)
            state.registerInternalSymbol(autoId, auto)
            auto

          val resultType = decodeTypeTree
          val receives = decodeRepeated(decodeSymbolRef)
          val body = decodeWord()

          // Create proper ProcType
          val procType = ProcType(tparams, params.map(p => NamedInfo(p.name, p.info)),
                                 autos.map(a => NamedInfo(a.name, a.info)), resultType.tpe, receives, 0)
          symbol.setInfo(procType)

          FunDef(symbol, tparams, params, autos, resultType, body)(span)

        case Format.PatDef =>
          val id = decodeNat()
          val flags = decodeFlags()
          val name = decodeString()

          val symbol = new Symbol(name, flags)
          state.registerInternalSymbol(id, symbol)

          val tparams = decodeRepeated(buf): _ =>
            val tparamId = decodeNat()
            val tparamName = decodeString()
            val tparamInfo = decodeType()
            val tparamStartDelta = decodeInt()
            val tparamLength = decodeInt()

            val tparam = new TypeSymbol(tparamName, Kind.Simple)
            tparam.setInfo(tparamInfo)
            state.registerInternalSymbol(tparamId, tparam)
            tparam

          val params = decodeRepeated(buf): _ =>
            val paramId = decodeNat()
            val paramName = decodeString()
            val paramInfo = decodeType()
            val paramStartDelta = decodeInt()
            val paramLength = decodeInt()

            val param = new Symbol(paramName, Flags.Empty)
            param.setInfo(paramInfo)
            state.registerInternalSymbol(paramId, param)
            param

          val resultType = decodeTypeTree
          val receives = decodeRepeated(decodeSymbolRef)
          val body = decodePattern()

          val procType = ProcType(tparams, params.map(p => NamedInfo(p.name, p.info)),
                                 List.empty, resultType.tpe, receives, 0)
          symbol.setInfo(procType)

          PatDef(symbol, tparams, params, resultType, body)(span)

        case Format.TypeDef =>
          val id = decodeNat()
          val name = decodeString()
          val symbolType = decodeType()

          val symbol = new TypeSymbol(name, Kind.Simple)
          symbol.setInfo(symbolType)
          state.registerInternalSymbol(id, symbol)
          TypeDef(symbol)(span)

        case Format.Section =>
          val name = decodeString()
          val symbol = new Symbol(name, Flags.Empty)
          val defs = decodeRepeated(decodeDef)
          Section(symbol, defs)(span)

        case _ => throw new Exception(s"Unknown definition type: $defType")

  private def decodeTypeTree()(using buf: ReadBuffer, defn: Definitions, state: State): TypeTree =
    val startDelta = decodeInt()
    val length = decodeInt()
    val tpe = decodeType()

    state.withPosition(startDelta, length): span =>
      TypeTree(tpe)(span)

  private def decodeType()(using buf: ReadBuffer, defn: Definitions, state: State): Type =
    val typeTag = decodeByte()
    typeTag match
      case Format.VoidType => VoidType
      case Format.ErrorType => ErrorType
      case Format.AnyType => AnyType
      case Format.BottomType => BottomType

      case Format.StaticRef =>
        val sym = decodeSymbolRef
        StaticRef(sym)

      case Format.MemberRef =>
        val prefix = decodeType()
        val sym = decodeSymbolRef
        MemberRef(prefix, sym)

      case Format.ConstantType =>
        val const = decodeConstant()
        ConstantType(const)

      case Format.RecordType =>
        val fields = decodeRepeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)
        RecordType(fields)

      case Format.UnionType =>
        val branches = decodeRepeated(decodeType)
        UnionType(branches)

      case Format.TagType =>
        val tag = decodeString()
        val params = decodeRepeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)
        TagType(tag, params)

      case Format.ObjectType =>
        val memberCount = decodeNat()
        val members = mutable.ListBuffer.empty[NamedInfo[Type]]
        val muts = mutable.Set.empty[String]

        for _ <- 0 until memberCount do
          val name = decodeString()
          val info = decodeType()
          members += NamedInfo(name, info)
          if info.isValueType then
            val isMutable = decodeBool()
            if isMutable then muts += name

        ObjectType(members.toList, muts.toSet)

      case Format.AppliedType =>
        val tctor = decodeType()
        val targs = decodeRepeated(decodeType)
        AppliedType(tctor, targs)

      case Format.ProcType =>
        val tparams = decodeRepeated(buf): _ =>
          val id = decodeNat()
          val name = decodeString()
          val info = decodeType()
          val tparam = new TypeSymbol(name, Kind.Simple)
          tparam.setInfo(info)
          state.registerInternalSymbol(id, tparam)
          tparam

        val params = decodeRepeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)

        val autos = decodeRepeated(buf): _ =>
          val name = decodeString()
          val info = decodeType()
          NamedInfo(name, info)

        val resType = decodeType()
        val receives = decodeRepeated(decodeSymbolRef)
        val preParamCount = decodeNat()

        ProcType(tparams, params, autos, resType, receives, preParamCount)

      case Format.TypeLambda =>
        val tparams = decodeRepeated(buf): _ =>
          val id = decodeNat()
          val name = decodeString()
          val info = decodeType()
          val tparam = new TypeSymbol(name, Kind.Simple)
          tparam.setInfo(info)
          state.registerInternalSymbol(id, tparam)
          tparam

        val resType = decodeType()
        val preParamCount = decodeNat()
        TypeLambda(tparams, resType, preParamCount)

      case Format.TypeBound =>
        val lo = decodeType()
        val hi = decodeType()
        TypeBound(lo, hi)

      case _ => throw new Exception(s"Unknown type tag: $typeTag")

  private def decodeWord()(using buf: ReadBuffer, defn: Definitions, state: State): Word =
    val wordTag = decodeByte()
    val startDelta = decodeInt()
    val lengthDelta = decodeInt()

    state.withPosition(startDelta, lengthDelta): span =>
      wordTag match
        case Format.Literal =>
          val const = decodeConstant()
          val tpe = decodeType()
          Literal(const)(tpe, span)

        case Format.Ident =>
          val sym = decodeSymbolRef
          Ident(sym)(span)

        case Format.New =>
          val classRef = decodeWord()
          val targs = decodeRepeated(decodeTypeTree)
          New(classRef, targs)(span)

        case Format.Select =>
          val qual = decodeWord()
          val name = decodeString()
          val tpe = qual.tpe // This would need proper type reconstruction
          Select(qual, name)(tpe, span)

        case Format.RecordLit =>
          val fields = decodeRepeated(buf): _ =>
            val fieldName = decodeString()
            val rhs = decodeWord()
            (fieldName, rhs)
          val tpe = RecordType(fields.map((n, w) => NamedInfo(n, w.tpe)))
          RecordLit(fields)(tpe, span)

        case Format.TaggedLit =>
          val tag = decodeWord()
          val args = decodeRepeated(decodeWord)
          val tagName = tag match
            case Literal(Constant.String(name)) => name
            case _ => throw new Exception("Expected string literal for tag")
          val tpe = TagType(tagName, args.map(a => NamedInfo("", a.tpe)))
          TaggedLit(tag.asInstanceOf[Literal], args)(tpe, span)

        case Format.Encoded =>
          val repr = decodeWord()
          val tpe = decodeType()
          Encoded(repr)(tpe, span)

        case Format.Apply =>
          val fun = decodeWord()
          val args = decodeRepeated(decodeWord)
          val autos = decodeRepeated(decodeWord)
          // Type would need to be computed from function type
          val tpe = VoidType // Placeholder
          Apply(fun, args, autos)(tpe, span)

        case Format.TypeApply =>
          val fun = decodeWord()
          val targs = decodeRepeated(decodeTypeTree)
          TypeApply(fun, targs)(span)

        case Format.With =>
          val expr = decodeWord()
          val args = decodeRepeated(buf): _ =>
            val ident = decodeWord()
            val rhs = decodeWord()
            Assign(ident, rhs)(VoidType, span)
          With(expr, args)(expr.tpe, span)

        case Format.Allow =>
          val expr = decodeWord()
          val params = decodeRepeated(decodeWord)
          Allow(expr, params)(expr.tpe, span)

        case Format.Assign =>
          val ident = decodeWord()
          val rhs = decodeWord()
          Assign(ident, rhs)(VoidType, span)

        case Format.FieldAssign =>
          val lhs = decodeWord()
          val rhs = decodeWord()
          FieldAssign(lhs, rhs)(VoidType, span)

        case Format.ValDef =>
          decodeDef().asInstanceOf[ValDef]

        case Format.FunDef =>
          decodeDef().asInstanceOf[FunDef]

        case Format.TypeDef =>
          decodeDef().asInstanceOf[TypeDef]

        case Format.PatDef =>
          decodeDef().asInstanceOf[PatDef]

        case Format.If =>
          val cond = decodeWord()
          val thenp = decodeWord()
          val elsep = decodeWord()
          val tpe = decodeType()
          If(cond, thenp, elsep)(tpe, span)

        case Format.While =>
          val cond = decodeWord()
          val body = decodeWord()
          While(cond, body)(VoidType, span)

        case Format.Block =>
          val words = decodeRepeated(decodeWord)
          val tpe = if words.nonEmpty then words.last.tpe else VoidType
          Block(words)(tpe, span)

        case Format.Match =>
          val scrutinee = decodeWord()
          val cases = decodeRepeated(buf): _ =>
            val pat = decodePattern()
            val body = decodeWord()
            Case(pat, body)
          val tpe = decodeType()
          Match(scrutinee, cases)(tpe, span)

        case Format.Object =>
          val selfId = decodeNat()
          val selfName = decodeString()
          val self = new Symbol(selfName, Flags.Empty)
          state.registerInternalSymbol(selfId, self)

          val members = decodeRepeated(decodeDef).map:
            case v: ValDef => v
            case f: FunDef => f
            case _ => throw new Exception("Object can only contain val and fun definitions")

          Object(self, members)(span)

        case _ => throw new Exception(s"Unknown word tag: $wordTag")

  private def decodePattern()(using buf: ReadBuffer, defn: Definitions, state: State): Pattern =
    val patternTag = decodeByte()
    val startDelta = decodeInt()
    val lengthDelta = decodeInt()

    state.withPosition(startDelta, lengthDelta): span =>
      patternTag match
        case Format.AliasPattern =>
          val id = decodeWord()
          val nested = decodePattern()
          AliasPattern(id, nested)(span)

        case Format.TypePattern =>
          val scrutineeType = decodeType()
          val tpt = decodeTypeTree
          TypePattern(tpt)(scrutineeType, span)

        case Format.TagPattern =>
          val tagLit = decodeWord()
          val nested = decodeRepeated(decodePattern)
          TagPattern(tagLit, nested)(span)

        case Format.ApplyPattern =>
          val scrutineeType = decodeType()
          val fun = decodeWord()
          val nested = decodeRepeated(decodePattern)
          ApplyPattern(fun, nested)(scrutineeType, span)

        case Format.OrPattern =>
          val lhs = decodePattern()
          val rhs = decodePattern()
          OrPattern(lhs, rhs)(span)

        case Format.ValuePattern =>
          val scrutineeType = decodeType()
          val value = decodeWord()
          ValuePattern(value)(scrutineeType, span)

        case Format.GuardPattern =>
          val pattern = decodePattern()
          val guard = decodeWord()
          GuardPattern(pattern, guard)(span)

        case Format.BindPattern =>
          val pattern = decodePattern()
          val bindings = decodeRepeated(decodeWord)
          BindPattern(pattern, bindings)(span)

        case Format.SeqPattern =>
          val scrutineeType = decodeType()
          val pats = decodeRepeated(buf): _ =>
            val seqPatTag = decodeByte()
            seqPatTag match
              case Format.AtomPattern =>
                val pattern = decodePattern()
                AtomPattern(pattern)

              case Format.SkipToPattern =>
                val pattern = decodePattern()
                SkipToPattern(pattern)

              case Format.StarPattern =>
                val pattern = decodePattern()
                val bindings = decodeRepeated(buf): _ =>
                  val sym1 = decodeSymbol()
                  val sym2 = decodeSymbolRef
                  (sym1, sym2)
                StarPattern(pattern, bindings)

              case Format.RestPattern =>
                val pattern = decodePattern()
                RestPattern(pattern)

              case _ => throw new Exception(s"Unknown sequence pattern tag: $seqPatTag")

          SeqPattern(pats)(scrutineeType, span)

        case Format.WildcardPattern =>
          val scrutineeType = decodeType()
          WildcardPattern()(scrutineeType, span)

        case _ => throw new Exception(s"Unknown pattern tag: $patternTag")

  private def decodeBool()(using buf: ReadBuffer): Boolean =
    buf.readBool()

  private def decodeByte()(using buf: ReadBuffer): Byte =
    buf.readByte()

  private def decodeInt()(using buf: ReadBuffer): Int =
    buf.readInt()

  private def decodeIntRaw()(using buf: ReadBuffer): Int =
    buf.readIntRaw()

  private def decodeNat()(using buf: ReadBuffer): Int =
    buf.readNat()

  private def decodeString()(using buf: ReadBuffer): String =
    buf.readUtf8()

  private def decodeSpan(using ReadBuffer): Span =
    Span(decodeNat(), decodeNat())

  private def decodeConstant()(using buf: ReadBuffer): Constant =
    val constType = decodeByte()
    constType match
      case Format.BoolConst =>
        val value = decodeBool()
        Constant.Bool(value)

      case Format.IntConst =>
        val value = decodeInt()
        Constant.Int(value)

      case Format.StringConst =>
        val value = decodeString()
        Constant.String(value)

      case _ => throw new Exception(s"Unknown constant type: $constType")

  private def decodeSource()(using buf: ReadBuffer): Source =
    val file = decodeString()
    val source = new Source(file)
    for lineLen <- decodeRepeated(decodeNat) do
      source.addLineOffset(lineLen)
    source

  private def decodeRepeated[T](decode: => T)(using buf: ReadBuffer): Array[T] =
    val count = decodeNat()
    val arr = new Array[T](count)
    var i = 0
    while i < count do
      arr(i) = decode
      i = i + 1
    end while
    arr

end Decoder
