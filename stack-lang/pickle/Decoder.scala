package pickle

import ast.Positions.*
import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import scala.collection.mutable

/** Decode trees, symbols and types
  *
  * This is the counterpart to Encoder.scala, reading back the encoded format
  * to reconstruct SAST trees.
  */
object Decoder:
  private class State(val root: Symbol):
    /** External symbols loaded from the name table */
    private var externalSymbols: Array[Symbol] = _

    /** Map from internal symbol IDs to symbols */
    private val internalSymbols = mutable.Map.empty[Int, Symbol]

    /** Current position tracking for rebuilding spans */
    private var currentOffset = 0

    def setExternalSymbols(symbols: Array[Symbol]): Unit =
      externalSymbols = symbols

    def getExternalSymbol(index: Int): Symbol =
      if index < 0 || index >= externalSymbols.length then
        throw new Exception(s"Invalid external symbol index: $index")
      externalSymbols(index)

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

  //----------------------------------------------------------------------------

  def decode(buf: ReadBuffer)(using defn: Definitions): Namespace =
    val rootName = decodeString(buf)
    val rootSymbol = defn.lookupStaticName(rootName).getOrElse:
      throw new Exception(s"Cannot find root symbol: $rootName")

    // Read external name table
    val nameTableAddr = buf.readInt2Complement()
    val externalSymbols = buf.withPosition(nameTableAddr):
      decodeExternalNameTable(buf)

    given state: State = new State(rootSymbol)

    val source = decodeSource(buf)

    val defs = decodeRepeated(buf)(decodeDef)

    val span = Span(0, source.content.length)
    Namespace(rootSymbol, List.empty, defs)(span)

  //----------------------------------------------------------------------------

  private def decodeExternalNameTable(buf: ReadBuffer)(using defn: Definitions): Array[Symbol] =
    val count = decodeNat(buf)
    val symbols = new Array[Symbol](count)

    var i = 0
    while i < count do
      val fullName = decodeString(buf)
      val kind = decodeByte(buf)

      val symbol = defn.lookupStaticName(fullName).getOrElse:
        throw new Exception(s"Cannot find external symbol: $fullName")

      val symbol =
        if kind == 0 then defn.resolveTypeByPath(fullName)
        else kind == 1 then defn.resolvePatternByPath(fullName)
        else defn.resolveTermByPath(fullName)

      symbols(i) = symbol
      i += 1

    symbols

  private def decodeSymbol(buf: ReadBuffer)(using defn: Definitions, state: State): Symbol =
    val id = decodeNat(buf)
    val name = decodeString(buf)
    val flags = decodeFlags(buf)

    // Create symbol - this is a simplified version
    // In practice, you'd need to create the appropriate symbol type
    val symbol = new Symbol(name, flags)

    // Decode kind if type symbol
    if symbol.isType then
      val kind = decodeKind(buf)
      // Set kind on type symbol

    // Decode owner
    val ownerId = decodeInt(buf)
    val owner = if ownerId == -1 then null else state.getInternalSymbol(ownerId)

    val sourceStart = decodeNat(buf)
    val sourceLength = decodeNat(buf)
    val symbolType = decodeType(buf)

    // Set symbol properties
    symbol.setOwner(owner)
    symbol.setInfo(symbolType)

    state.registerInternalSymbol(id, symbol)
    symbol

  private def decodeSymbolRef(buf: ReadBuffer)(using defn: Definitions, state: State): Symbol =
    val refType = decodeByte(buf)
    refType match
      case 0 => // Internal symbol
        val id = decodeNat(buf)
        state.getInternalSymbol(id)

      case 1 => // External symbol
        val index = decodeNat(buf)
        state.getExternalSymbol(index)

      case _ => throw new Exception(s"Invalid symbol reference type: $refType")

  private def decodeFlags(buf: ReadBuffer): Flags =
    val count = decodeByte(buf)
    var flags = Flags.Empty
    for _ <- 0 until count do
      val flagIndex = decodeByte(buf)
      flags |= Flags.fromIndex(flagIndex)
    flags

  private def decodeKind(buf: ReadBuffer)(using defn: Definitions, state: State): Kind =
    val kindType = decodeByte(buf)
    kindType match
      case 0 => Kind.Simple

      case 1 => // Arrow kind
        val args = decodeRepeated(buf)(decodeKind)
        val to = decodeKind(buf)
        Kind.Arrow(args, to)

      case _ => throw new Exception(s"Invalid kind type: $kindType")

  private def decodeDef(buf: ReadBuffer)(using defn: Definitions, state: State): Def =
    val defType = decodeByte(buf)
    val startDelta = decodeInt(buf)
    val lengthDelta = decodeInt(buf)

    state.withPosition(startDelta, lengthDelta): span =>
      defType match
        case Format.ParamDef =>
          val flags = decodeFlags(buf)
          val name = decodeString(buf)
          val id = decodeNat(buf)
          val tpt = decodeTypeTree(buf)

          val symbol = new Symbol(name, flags)
          state.registerInternalSymbol(id, symbol)
          ParamDef(symbol, tpt)(span)

        case Format.ValDef =>
          val id = decodeNat(buf)
          val flags = decodeFlags(buf)
          val name = decodeString(buf)
          val symbolType = decodeType(buf)
          val rhs = decodeWord(buf)

          val symbol = new Symbol(name, flags)
          symbol.setInfo(symbolType)
          state.registerInternalSymbol(id, symbol)
          ValDef(symbol, rhs)(span)

        case Format.ClassDef =>
          val id = decodeNat(buf)
          val name = decodeString(buf)

          val symbol = new Symbol(name, Flags.Empty)
          state.registerInternalSymbol(id, symbol)

          val tparams = decodeRepeated(buf): _ =>
            val tparamId = decodeNat(buf)
            val tparamName = decodeString(buf)
            val tparamInfo = decodeType(buf)
            val tparamStartDelta = decodeInt(buf)
            val tparamLength = decodeInt(buf)

            val tparam = new TypeSymbol(tparamName, Kind.Simple)
            tparam.setInfo(tparamInfo)
            state.registerInternalSymbol(tparamId, tparam)
            tparam

          val selfId = decodeNat(buf)
          val selfFlags = decodeFlags(buf)
          val selfName = decodeString(buf)
          val self = new Symbol(selfName, selfFlags)
          state.registerInternalSymbol(selfId, self)

          val vals = decodeRepeated(buf): _ =>
            val valId = decodeNat(buf)
            val valFlags = decodeFlags(buf)
            val valName = decodeString(buf)
            val valType = decodeType(buf)
            val valStartDelta = decodeInt(buf)
            val valLength = decodeInt(buf)

            val valSym = new Symbol(valName, valFlags)
            valSym.setInfo(valType)
            state.registerInternalSymbol(valId, valSym)
            valSym

          val funs = decodeRepeated(buf)(decodeDef).map(_.asInstanceOf[FunDef])

          ClassDef(symbol, tparams, self, vals, funs)(span)

        case Format.FunDef =>
          val id = decodeNat(buf)
          val flags = decodeFlags(buf)
          val name = decodeString(buf)

          val symbol = new Symbol(name, flags)
          state.registerInternalSymbol(id, symbol)

          val tparams = decodeRepeated(buf): _ =>
            val tparamId = decodeNat(buf)
            val tparamName = decodeString(buf)
            val tparamInfo = decodeType(buf)
            val tparamStartDelta = decodeInt(buf)
            val tparamLength = decodeInt(buf)

            val tparam = new TypeSymbol(tparamName, Kind.Simple)
            tparam.setInfo(tparamInfo)
            state.registerInternalSymbol(tparamId, tparam)
            tparam

          val params = decodeRepeated(buf): _ =>
            val paramId = decodeNat(buf)
            val paramName = decodeString(buf)
            val paramInfo = decodeType(buf)
            val paramStartDelta = decodeInt(buf)
            val paramLength = decodeInt(buf)

            val param = new Symbol(paramName, Flags.Empty)
            param.setInfo(paramInfo)
            state.registerInternalSymbol(paramId, param)
            param

          val autos = decodeRepeated(buf): _ =>
            val autoId = decodeNat(buf)
            val autoName = decodeString(buf)
            val autoInfo = decodeType(buf)
            val autoStartDelta = decodeInt(buf)
            val autoLength = decodeInt(buf)

            val auto = new Symbol(autoName, Flags.Auto)
            auto.setInfo(autoInfo)
            state.registerInternalSymbol(autoId, auto)
            auto

          val resultType = decodeTypeTree(buf)
          val receives = decodeRepeated(buf)(decodeSymbolRef)
          val body = decodeWord(buf)

          // Create proper ProcType
          val procType = ProcType(tparams, params.map(p => NamedInfo(p.name, p.info)),
                                 autos.map(a => NamedInfo(a.name, a.info)), resultType.tpe, receives, 0)
          symbol.setInfo(procType)

          FunDef(symbol, tparams, params, autos, resultType, body)(span)

        case Format.PatDef =>
          val id = decodeNat(buf)
          val flags = decodeFlags(buf)
          val name = decodeString(buf)

          val symbol = new Symbol(name, flags)
          state.registerInternalSymbol(id, symbol)

          val tparams = decodeRepeated(buf): _ =>
            val tparamId = decodeNat(buf)
            val tparamName = decodeString(buf)
            val tparamInfo = decodeType(buf)
            val tparamStartDelta = decodeInt(buf)
            val tparamLength = decodeInt(buf)

            val tparam = new TypeSymbol(tparamName, Kind.Simple)
            tparam.setInfo(tparamInfo)
            state.registerInternalSymbol(tparamId, tparam)
            tparam

          val params = decodeRepeated(buf): _ =>
            val paramId = decodeNat(buf)
            val paramName = decodeString(buf)
            val paramInfo = decodeType(buf)
            val paramStartDelta = decodeInt(buf)
            val paramLength = decodeInt(buf)

            val param = new Symbol(paramName, Flags.Empty)
            param.setInfo(paramInfo)
            state.registerInternalSymbol(paramId, param)
            param

          val resultType = decodeTypeTree(buf)
          val receives = decodeRepeated(buf)(decodeSymbolRef)
          val body = decodePattern(buf)

          val procType = ProcType(tparams, params.map(p => NamedInfo(p.name, p.info)),
                                 List.empty, resultType.tpe, receives, 0)
          symbol.setInfo(procType)

          PatDef(symbol, tparams, params, resultType, body)(span)

        case Format.TypeDef =>
          val id = decodeNat(buf)
          val name = decodeString(buf)
          val symbolType = decodeType(buf)

          val symbol = new TypeSymbol(name, Kind.Simple)
          symbol.setInfo(symbolType)
          state.registerInternalSymbol(id, symbol)
          TypeDef(symbol)(span)

        case Format.Section =>
          val name = decodeString(buf)
          val symbol = new Symbol(name, Flags.Empty)
          val defs = decodeRepeated(buf)(decodeDef)
          Section(symbol, defs)(span)

        case _ => throw new Exception(s"Unknown definition type: $defType")

  private def decodeTypeTree(buf: ReadBuffer)(using defn: Definitions, state: State): TypeTree =
    val startDelta = decodeInt(buf)
    val length = decodeInt(buf)
    val tpe = decodeType(buf)

    state.withPosition(startDelta, length): span =>
      TypeTree(tpe)(span)

  private def decodeType(buf: ReadBuffer)(using defn: Definitions, state: State): Type =
    val typeTag = decodeByte(buf)
    typeTag match
      case Format.VoidType => VoidType
      case Format.ErrorType => ErrorType
      case Format.AnyType => AnyType
      case Format.BottomType => BottomType

      case Format.StaticRef =>
        val sym = decodeSymbolRef(buf)
        StaticRef(sym)

      case Format.MemberRef =>
        val prefix = decodeType(buf)
        val sym = decodeSymbolRef(buf)
        MemberRef(prefix, sym)

      case Format.ConstantType =>
        val const = decodeConstant(buf)
        ConstantType(const)

      case Format.RecordType =>
        val fields = decodeRepeated(buf): _ =>
          val name = decodeString(buf)
          val info = decodeType(buf)
          NamedInfo(name, info)
        RecordType(fields)

      case Format.UnionType =>
        val branches = decodeRepeated(buf)(decodeType)
        UnionType(branches)

      case Format.TagType =>
        val tag = decodeString(buf)
        val params = decodeRepeated(buf): _ =>
          val name = decodeString(buf)
          val info = decodeType(buf)
          NamedInfo(name, info)
        TagType(tag, params)

      case Format.ObjectType =>
        val memberCount = decodeNat(buf)
        val members = mutable.ListBuffer.empty[NamedInfo[Type]]
        val muts = mutable.Set.empty[String]

        for _ <- 0 until memberCount do
          val name = decodeString(buf)
          val info = decodeType(buf)
          members += NamedInfo(name, info)
          if info.isValueType then
            val isMutable = decodeBool(buf)
            if isMutable then muts += name

        ObjectType(members.toList, muts.toSet)

      case Format.AppliedType =>
        val tctor = decodeType(buf)
        val targs = decodeRepeated(buf)(decodeType)
        AppliedType(tctor, targs)

      case Format.ProcType =>
        val tparams = decodeRepeated(buf): _ =>
          val id = decodeNat(buf)
          val name = decodeString(buf)
          val info = decodeType(buf)
          val tparam = new TypeSymbol(name, Kind.Simple)
          tparam.setInfo(info)
          state.registerInternalSymbol(id, tparam)
          tparam

        val params = decodeRepeated(buf): _ =>
          val name = decodeString(buf)
          val info = decodeType(buf)
          NamedInfo(name, info)

        val autos = decodeRepeated(buf): _ =>
          val name = decodeString(buf)
          val info = decodeType(buf)
          NamedInfo(name, info)

        val resType = decodeType(buf)
        val receives = decodeRepeated(buf)(decodeSymbolRef)
        val preParamCount = decodeNat(buf)

        ProcType(tparams, params, autos, resType, receives, preParamCount)

      case Format.TypeLambda =>
        val tparams = decodeRepeated(buf): _ =>
          val id = decodeNat(buf)
          val name = decodeString(buf)
          val info = decodeType(buf)
          val tparam = new TypeSymbol(name, Kind.Simple)
          tparam.setInfo(info)
          state.registerInternalSymbol(id, tparam)
          tparam

        val resType = decodeType(buf)
        val preParamCount = decodeNat(buf)
        TypeLambda(tparams, resType, preParamCount)

      case Format.TypeBound =>
        val lo = decodeType(buf)
        val hi = decodeType(buf)
        TypeBound(lo, hi)

      case _ => throw new Exception(s"Unknown type tag: $typeTag")

  private def decodeWord(buf: ReadBuffer)(using defn: Definitions, state: State): Word =
    val wordTag = decodeByte(buf)
    val startDelta = decodeInt(buf)
    val lengthDelta = decodeInt(buf)

    state.withPosition(startDelta, lengthDelta): span =>
      wordTag match
        case Format.Literal =>
          val const = decodeConstant(buf)
          val tpe = decodeType(buf)
          Literal(const)(tpe, span)

        case Format.Ident =>
          val sym = decodeSymbolRef(buf)
          Ident(sym)(span)

        case Format.New =>
          val classRef = decodeWord(buf)
          val targs = decodeRepeated(buf)(decodeTypeTree)
          New(classRef, targs)(span)

        case Format.Select =>
          val qual = decodeWord(buf)
          val name = decodeString(buf)
          val tpe = qual.tpe // This would need proper type reconstruction
          Select(qual, name)(tpe, span)

        case Format.RecordLit =>
          val fields = decodeRepeated(buf): _ =>
            val fieldName = decodeString(buf)
            val rhs = decodeWord(buf)
            (fieldName, rhs)
          val tpe = RecordType(fields.map((n, w) => NamedInfo(n, w.tpe)))
          RecordLit(fields)(tpe, span)

        case Format.TaggedLit =>
          val tag = decodeWord(buf)
          val args = decodeRepeated(buf)(decodeWord)
          val tagName = tag match
            case Literal(Constant.String(name)) => name
            case _ => throw new Exception("Expected string literal for tag")
          val tpe = TagType(tagName, args.map(a => NamedInfo("", a.tpe)))
          TaggedLit(tag.asInstanceOf[Literal], args)(tpe, span)

        case Format.Encoded =>
          val repr = decodeWord(buf)
          val tpe = decodeType(buf)
          Encoded(repr)(tpe, span)

        case Format.Apply =>
          val fun = decodeWord(buf)
          val args = decodeRepeated(buf)(decodeWord)
          val autos = decodeRepeated(buf)(decodeWord)
          // Type would need to be computed from function type
          val tpe = VoidType // Placeholder
          Apply(fun, args, autos)(tpe, span)

        case Format.TypeApply =>
          val fun = decodeWord(buf)
          val targs = decodeRepeated(buf)(decodeTypeTree)
          TypeApply(fun, targs)(span)

        case Format.With =>
          val expr = decodeWord(buf)
          val args = decodeRepeated(buf): _ =>
            val ident = decodeWord(buf)
            val rhs = decodeWord(buf)
            Assign(ident, rhs)(VoidType, span)
          With(expr, args)(expr.tpe, span)

        case Format.Allow =>
          val expr = decodeWord(buf)
          val params = decodeRepeated(buf)(decodeWord)
          Allow(expr, params)(expr.tpe, span)

        case Format.Assign =>
          val ident = decodeWord(buf)
          val rhs = decodeWord(buf)
          Assign(ident, rhs)(VoidType, span)

        case Format.FieldAssign =>
          val lhs = decodeWord(buf)
          val rhs = decodeWord(buf)
          FieldAssign(lhs, rhs)(VoidType, span)

        case Format.ValDef =>
          decodeDef(buf).asInstanceOf[ValDef]

        case Format.FunDef =>
          decodeDef(buf).asInstanceOf[FunDef]

        case Format.TypeDef =>
          decodeDef(buf).asInstanceOf[TypeDef]

        case Format.PatDef =>
          decodeDef(buf).asInstanceOf[PatDef]

        case Format.If =>
          val cond = decodeWord(buf)
          val thenp = decodeWord(buf)
          val elsep = decodeWord(buf)
          val tpe = decodeType(buf)
          If(cond, thenp, elsep)(tpe, span)

        case Format.While =>
          val cond = decodeWord(buf)
          val body = decodeWord(buf)
          While(cond, body)(VoidType, span)

        case Format.Block =>
          val words = decodeRepeated(buf)(decodeWord)
          val tpe = if words.nonEmpty then words.last.tpe else VoidType
          Block(words)(tpe, span)

        case Format.Match =>
          val scrutinee = decodeWord(buf)
          val cases = decodeRepeated(buf): _ =>
            val pat = decodePattern(buf)
            val body = decodeWord(buf)
            Case(pat, body)
          val tpe = decodeType(buf)
          Match(scrutinee, cases)(tpe, span)

        case Format.Object =>
          val selfId = decodeNat(buf)
          val selfName = decodeString(buf)
          val self = new Symbol(selfName, Flags.Empty)
          state.registerInternalSymbol(selfId, self)

          val members = decodeRepeated(buf)(decodeDef).map:
            case v: ValDef => v
            case f: FunDef => f
            case _ => throw new Exception("Object can only contain val and fun definitions")

          Object(self, members)(span)

        case _ => throw new Exception(s"Unknown word tag: $wordTag")

  private def decodePattern(buf: ReadBuffer)(using defn: Definitions, state: State): Pattern =
    val patternTag = decodeByte(buf)
    val startDelta = decodeInt(buf)
    val lengthDelta = decodeInt(buf)

    state.withPosition(startDelta, lengthDelta): span =>
      patternTag match
        case Format.AliasPattern =>
          val id = decodeWord(buf)
          val nested = decodePattern(buf)
          AliasPattern(id, nested)(span)

        case Format.TypePattern =>
          val scrutineeType = decodeType(buf)
          val tpt = decodeTypeTree(buf)
          TypePattern(tpt)(scrutineeType, span)

        case Format.TagPattern =>
          val tagLit = decodeWord(buf)
          val nested = decodeRepeated(buf)(decodePattern)
          TagPattern(tagLit, nested)(span)

        case Format.ApplyPattern =>
          val scrutineeType = decodeType(buf)
          val fun = decodeWord(buf)
          val nested = decodeRepeated(buf)(decodePattern)
          ApplyPattern(fun, nested)(scrutineeType, span)

        case Format.OrPattern =>
          val lhs = decodePattern(buf)
          val rhs = decodePattern(buf)
          OrPattern(lhs, rhs)(span)

        case Format.ValuePattern =>
          val scrutineeType = decodeType(buf)
          val value = decodeWord(buf)
          ValuePattern(value)(scrutineeType, span)

        case Format.GuardPattern =>
          val pattern = decodePattern(buf)
          val guard = decodeWord(buf)
          GuardPattern(pattern, guard)(span)

        case Format.BindPattern =>
          val pattern = decodePattern(buf)
          val bindings = decodeRepeated(buf)(decodeWord)
          BindPattern(pattern, bindings)(span)

        case Format.SeqPattern =>
          val scrutineeType = decodeType(buf)
          val pats = decodeRepeated(buf): _ =>
            val seqPatTag = decodeByte(buf)
            seqPatTag match
              case Format.AtomPattern =>
                val pattern = decodePattern(buf)
                AtomPattern(pattern)

              case Format.SkipToPattern =>
                val pattern = decodePattern(buf)
                SkipToPattern(pattern)

              case Format.StarPattern =>
                val pattern = decodePattern(buf)
                val bindings = decodeRepeated(buf): _ =>
                  val sym1 = decodeSymbol(buf)
                  val sym2 = decodeSymbolRef(buf)
                  (sym1, sym2)
                StarPattern(pattern, bindings)

              case Format.RestPattern =>
                val pattern = decodePattern(buf)
                RestPattern(pattern)

              case _ => throw new Exception(s"Unknown sequence pattern tag: $seqPatTag")

          SeqPattern(pats)(scrutineeType, span)

        case Format.WildcardPattern =>
          val scrutineeType = decodeType(buf)
          WildcardPattern()(scrutineeType, span)

        case _ => throw new Exception(s"Unknown pattern tag: $patternTag")

  private def decodeBool(buf: ReadBuffer): Boolean =
    buf.readBool()

  private def decodeByte(buf: ReadBuffer): Byte =
    buf.readByte()

  private def decodeInt(buf: ReadBuffer): Int =
    buf.readInt()

  private def decodeNat(buf: ReadBuffer): Int =
    buf.readNat()

  private def decodeString(buf: ReadBuffer): String =
    buf.readUtf8()

  private def decodeConstant(buf: ReadBuffer): Constant =
    val constType = decodeByte(buf)
    constType match
      case Format.BoolConst =>
        val value = decodeBool(buf)
        Constant.Bool(value)

      case Format.IntConst =>
        val value = decodeInt(buf)
        Constant.Int(value)

      case Format.StringConst =>
        val value = decodeString(buf)
        Constant.String(value)

      case _ => throw new Exception(s"Unknown constant type: $constType")

  private def decodeSource(buf: ReadBuffer): Source =
    val file = decodeString(buf)
    val lineLengths = decodeRepeated(buf)(decodeNat)
    Source.fromLineLengths(file, lineLengths.toArray)

  private def decodeRepeated[T](buf: ReadBuffer)(decode: ReadBuffer => T): List[T] =
    val count = decodeNat(buf)
    (0 until count).map(_ => decode(buf)).toList
