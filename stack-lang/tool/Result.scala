package tool

enum Result[+A]:
  case Ok(value: A)
  case Err(output: String)

  def map[B](f: A => B): Result[B] = this match
    case Ok(v)  => Ok(f(v))
    case Err(o) => Err(o)

  def flatMap[B](f: A => Result[B]): Result[B] = this match
    case Ok(v)  => f(v)
    case Err(o) => Err(o)

  def mapError(f: String => String): Result[A] = this match
    case Ok(v)  => Ok(v)
    case Err(o) => Err(f(o))

  def orExit: A = this match
    case Ok(v)  => v
    case Err(o) =>
      System.err.print(o)
      sys.exit(1)

object Result:
  val unit: Result[Unit] = Ok(())
