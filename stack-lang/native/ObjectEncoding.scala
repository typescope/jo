package native

import sast.Types.*
import sast.Trees.*
import sast.Definitions

import scala.collection.mutable

/** Native representation of objects
  *
  * Class instances are encoded as follows:
  *
  *     {
  *         a = ... ,
  *         b = ... ,
  *
  *         foo = ... ,
  *         bar = ... ,
  *     }
  *
  * An object is encoded as follows:
  *
  *     {
  *         vtable = { foo = ..., bar = ... },
  *         ftable = { a = ..., b = ... }
  *     }
  *
  * The encoding is implementation details and is subject to change.
  */
object ObjectEncoding:
  val VTABLE = "vtable"
  val FTABLE = "ftable"

  def encodeObjectType(objType: ObjectType): RecordType =
    val ftable = RecordType(objType.fields)
    val vtable = RecordType(objType.methods)
    RecordType(NamedInfo(VTABLE, vtable) :: NamedInfo(FTABLE, ftable) :: Nil)

  def encodeClassType(classInfo: ClassInfo)(using Definitions): RecordType =
    val memberTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
    for field <- classInfo.fields do
      memberTypes += field.toNamedInfo

    for fun <- classInfo.allMethods do
      memberTypes += fun.toNamedInfo

    RecordType(memberTypes.toList)

  def encodeObject(obj: RecordLit)(using Definitions): Word =
    val fields = obj.args.filter { case (name, rhs) => rhs.tpe.isValueType }
    val ftable = RecordLit(fields)(obj.span)

    val methods = obj.args.filter { case (name, rhs) => rhs.tpe.isProcType }
    val vtable = RecordLit(methods)(obj.span)

    RecordLit(List(VTABLE -> vtable, FTABLE -> ftable))(obj.span)
