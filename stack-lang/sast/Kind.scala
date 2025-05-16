package sast

/** The kind of a type symbol */
enum Kind:
  case Simple
  case Arrow(args: List[Kind], to: Kind)

  def show: String =
    this match
      case Simple => "*"
      case Arrow(args, to) =>
        args.map(_.show).mkString("(", ", ", ")") + " => " + to.show

object Kind:
  def simpleKinded(args: Int): Kind =
    if args == 0 then Simple
    else Arrow((1 to args).map(_ => Simple).toList, Simple)
