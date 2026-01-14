package native
package runtime

import sast.Symbols.Symbol
import sast.Types.Type

import scala.collection.mutable

class InterfaceTable(runtime: NativeRuntime):
  val methodToLiftedMap = mutable.Map.empty[Symbol, Symbol]
  val classIds = mutable.Map.empty[Symbol, Int]
  val interfaceIds = mutable.Map.empty[Symbol, Int]

  def getLiftedMethodOrUpdate(meth: Symbol, update: => Symbol): Symbol =
    methodToLiftedMap.get(meth) match
      case Some(liftedSym) =>
        liftedSym

      case None =>
        val liftedSym = update
        methodToLiftedMap(meth) = liftedSym
        liftedSym

  def getClassId(cls: Symbol): Int =
    classIds.get(cls) match
      case Some(id) => id
      case None =>
        val id = classIds.size
        classIds(cls) = id
        id

  def getInterfaceId(interface: Symbol): Int =
    interfaceIds.get(interface) match
      case Some(id) => id
      case None =>
        val id = interfaceIds.size
        interfaceIds(interface) = id
        id
