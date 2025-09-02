package pickle

import ast.Positions.*
import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import typing.Namer.DelayedDef

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
          case 2 => defn.resolveTermByPath(nameRef.fullName)
          case 1 => defn.resolvePatternByPath(nameRef.fullName)
          case 0 => defn.resolveTypeByPath(nameRef.fullName)

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

  //----------------------------------------------------------------------------

  def decode()(using buf: ReadBuffer, defn: Definitions): DelayedDef[Namespace] =
    val rootName: String = decodeString()
    val source: Source = decodeSource()
    val start: Int = decodeNat()
    val len: Int = decodeNat()

    // Read external name table
    val nameTableAddr: Int = buf.readInt2Complement()
    val nameRefs: Array[NameRef] = buf.withPosition(nameTableAddr):
      decodeExternalNameTable()

    // TODO: root symbol and info
    val rootSymbol: Symbol = ???

    given state: State = new State(rootSymbol, nameRefs)

    val delayedMembers: Array[DelayedDef[Def]] = index()

    val span = Span(eecodeNat(), decodeNat())

    val delayd = () =>
      val members = for d <- delayedMembers yield d.force()
      Namespace(rootSymbol, List.empty, members)(span)

    DelayedDef(rootSymbol, delayed)

  /** Index method that lazily loads definitions from the buffer.
    * Similar to the index method in Namer, this creates DelayedDef instances
    * that defer actual decoding until the definitions are needed.
    */
  private def index()(using buf: ReadBuffer, defn: Definitions, state: State): Array[DelayedDef[Def]] =
    val count = decodeNat()
    val delayedDefs = new Array[DelayedDef[Def]](count)

    var i = 0
    while i < count do
      // Store the current buffer position for this definition
      val defPosition = buf.position

      // Peek at the definition to get its symbol information
      val defType = decodeByte()
      val startDelta = decodeInt()
      val lengthDelta = decodeInt()

      // Create symbol based on definition type without fully decoding
      val symbol = defType match
        case Format.ValDef =>
          val id = decodeNat()
          val flags = decodeFlags()
          val name = decodeString()
          val symbolType = decodeType()
          // Skip the rhs without decoding it
          skipWord()

          val sym = new Symbol(name, flags)
          sym.setInfo(symbolType)
          state.registerInternalSymbol(id, sym)
          sym

        case Format.FunDef =>
          val id = decodeNat()
          val flags = decodeFlags()
          val name = decodeString()

          val sym = new Symbol(name, flags)
          state.registerInternalSymbol(id, sym)
          // Skip the rest of the function definition
          skipFunDefBody()
          sym

        case Format.ClassDef =>
          val id = decodeNat()
          val name = decodeString()

          val sym = new Symbol(name, Flags.Class)
          state.registerInternalSymbol(id, sym)
          // Skip the rest of the class definition
          skipClassDefBody()
          sym

        case Format.TypeDef =>
          val id = decodeNat()
          val name = decodeString()
          val symbolType = decodeType()

          val sym = new TypeSymbol(name, Kind.Simple)
          sym.setInfo(symbolType)
          state.registerInternalSymbol(id, sym)
          sym

        case Format.PatDef =>
          val id = decodeNat()
          val flags = decodeFlags()
          val name = decodeString()

          val sym = new Symbol(name, flags)
          state.registerInternalSymbol(id, sym)
          // Skip the rest of the pattern definition
          skipPatDefBody()
          sym

        case Format.Section =>
          val name = decodeString()
          val sym = new Symbol(name, Flags.Empty)
          // Skip the section body
          skipSectionBody()
          sym

        case _ =>
          throw new Exception(s"Unknown definition type in index: $defType")

      // Create delayed definition that will decode the full definition when forced
      val delayed = () => {
        val savedPosition = buf.position
        buf.setPosition(defPosition)
        val fullDef = decodeDef()
        buf.setPosition(savedPosition)
        fullDef
      }

      delayedDefs(i) = DelayedDef(symbol, delayed)
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

  private def decodeNat()(using buf: ReadBuffer): Int =
    buf.readNat()

  private def decodeString()(using buf: ReadBuffer): String =
    buf.readUtf8()

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

  // Helper methods for skipping parts of definitions during indexing
  private def skipWord()(using buf: ReadBuffer): Unit =
    val wordTag = decodeByte()
    val startDelta = decodeInt()
    val lengthDelta = decodeInt()

    // Skip the word content based on its type
    wordTag match
      case Format.Literal =>
        skipConstant()
        skipType()
      case Format.Ident =>
        skipSymbolRef()
      case Format.Select =>
        skipWord()
        decodeString() // field name
      case Format.Apply =>
        skipWord() // function
        skipRepeated(skipWord) // args
        skipRepeated(skipWord) // autos
      case Format.Block =>
        skipRepeated(skipWord)
      case _ =>
        // For other word types, we'd need to implement specific skipping
        // For now, throw an exception to indicate incomplete implementation
        throw new Exception(s"Skip not implemented for word tag: $wordTag")

  private def skipFunDefBody()(using buf: ReadBuffer): Unit =
    // Skip type parameters
    skipRepeated(() => {
      decodeNat() // id
      decodeString() // name
      skipType() // info
      decodeInt() // start delta
      decodeInt() // length
    })

    // Skip regular parameters
    skipRepeated(() => {
      decodeNat() // id
      decodeString() // name
      skipType() // info
      decodeInt() // start delta
      decodeInt() // length
    })

    // Skip auto parameters
    skipRepeated(() => {
      decodeNat() // id
      decodeString() // name
      skipType() // info
      decodeInt() // start delta
      decodeInt() // length
    })

    skipTypeTree() // result type
    skipRepeated(skipSymbolRef) // receives
    skipWord() // body

  private def skipClassDefBody()(using buf: ReadBuffer): Unit =
    // Skip type parameters
    skipRepeated(() => {
      decodeNat() // id
      decodeString() // name
      skipType() // info
      decodeInt() // start delta
      decodeInt() // length
    })

    // Skip self
    decodeNat() // self id
    skipFlags()
    decodeString() // self name

    // Skip vals
    skipRepeated(() => {
      decodeNat() // id
      skipFlags()
      decodeString() // name
      skipType() // type
      decodeInt() // start delta
      decodeInt() // length
    })

    // Skip funs
    skipRepeated(() => skipDef())

  private def skipPatDefBody()(using buf: ReadBuffer): Unit =
    // Skip type parameters
    skipRepeated(() => {
      decodeNat() // id
      decodeString() // name
      skipType() // info
      decodeInt() // start delta
      decodeInt() // length
    })

    // Skip parameters
    skipRepeated(() => {
      decodeNat() // id
      decodeString() // name
      skipType() // info
      decodeInt() // start delta
      decodeInt() // length
    })

    skipTypeTree() // result type
    skipRepeated(skipSymbolRef) // receives
    skipPattern() // body

  private def skipSectionBody()(using buf: ReadBuffer): Unit =
    skipRepeated(() => skipDef())

  private def skipDef()(using buf: ReadBuffer): Unit =
    val defType = decodeByte()
    decodeInt() // start delta
    decodeInt() // length delta

    defType match
      case Format.ValDef =>
        decodeNat() // id
        skipFlags()
        decodeString() // name
        skipType() // type
        skipWord() // rhs
      case Format.FunDef =>
        decodeNat() // id
        skipFlags()
        decodeString() // name
        skipFunDefBody()
      case _ =>
        throw new Exception(s"Skip not implemented for def type: $defType")

  private def skipType()(using buf: ReadBuffer): Unit =
    val typeTag = decodeByte()
    typeTag match
      case Format.VoidType | Format.ErrorType | Format.AnyType | Format.BottomType =>
        // No additional data
      case Format.StaticRef | Format.MemberRef =>
        skipSymbolRef()
        if typeTag == Format.MemberRef then skipType() // prefix
      case Format.ConstantType =>
        skipConstant()
      case Format.RecordType =>
        skipRepeated(() => {
          decodeString() // field name
          skipType() // field type
        })
      case _ =>
        throw new Exception(s"Skip not implemented for type tag: $typeTag")

  private def skipTypeTree()(using buf: ReadBuffer): Unit =
    decodeInt() // start delta
    decodeInt() // length
    skipType()

  private def skipPattern()(using buf: ReadBuffer): Unit =
    val patternTag = decodeByte()
    decodeInt() // start delta
    decodeInt() // length delta

    patternTag match
      case Format.WildcardPattern =>
        skipType() // scrutinee type
      case Format.ValuePattern =>
        skipType() // scrutinee type
        skipWord() // value
      case _ =>
        throw new Exception(s"Skip not implemented for pattern tag: $patternTag")

  private def skipSymbolRef()(using buf: ReadBuffer): Unit =
    val refType = decodeByte()
    refType match
      case 0 | 1 => decodeNat() // internal or external symbol index
      case _ => throw new Exception(s"Invalid symbol reference type: $refType")

  private def skipConstant()(using buf: ReadBuffer): Unit =
    val constType = decodeByte()
    constType match
      case Format.BoolConst => decodeBool()
      case Format.IntConst => decodeInt()
      case Format.StringConst => decodeString()
      case _ => throw new Exception(s"Unknown constant type: $constType")

  private def skipFlags()(using buf: ReadBuffer): Unit =
    val count = decodeByte()
    for _ <- 0 until count do
      decodeByte() // flag index

  private def skipRepeated(skip: () => Unit)(using buf: ReadBuffer): Unit =
    val count = decodeNat()
    for _ <- 0 until count do
      skip()

end Decoder
