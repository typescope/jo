import Reporter.Positioned


/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/
object Ast:
  enum Word extends Positioned:
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(name: String)
    case Fence(words: List[Word])
    case If(cond: List[Word], thenp: List[Word], elsep: List[Word])

    this match
      case If(cond, thenp, elsep) =>
        assert(cond.forall(_.hasPos))
        assert(thenp.forall(_.hasPos))
        assert(elsep.forall(_.hasPos))

      case Fence(words) =>
        assert(words.forall(_.hasPos))

      case _ =>

  enum Def extends Positioned:
    val ident: Word.Ident
    val name: String = ident.name

    case FunDef(ident: Word.Ident, params: List[Word.Ident], words: List[Word])
    case ValDef(ident: Word.Ident, words: List[Word])

    this match
      case FunDef(ident, params, words) =>
        assert(ident.hasPos)
        assert(params.forall(_.hasPos))
        assert(words.forall(_.hasPos))

      case ValDef(ident, words) =>
        assert(ident.hasPos)
        assert(words.forall(_.hasPos))

  case class Prog(defs: List[Def], main: List[Word]):
    assert(defs.forall(_.hasPos))
    assert(main.forall(_.hasPos))
