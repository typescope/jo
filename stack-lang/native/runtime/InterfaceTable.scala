package native
package runtime

import sast.Definitions
import sast.Flags
import sast.Names
import sast.Symbols.Symbol
import sast.Denotations.*

import native.Assembly.Label
import native.Assembler.PatchableBuffer

import scala.collection.mutable

class InterfaceTable(runtime: NativeRuntime):
  private val methodToLiftedMap = mutable.Map.empty[Symbol, Symbol]
  private val classIds = mutable.Map.empty[Symbol, Int]
  private val reverseClassIds = mutable.Map.empty[Int, Symbol]
  private val interfaceIds = mutable.Map.empty[Symbol, Int]

  /** Map from a class to its interface table address */
  private val interfaceTableAddr = mutable.Map.empty[ClassInfo, Label]

  def getInterfaceTable(classInfo: ClassInfo): Label =
    interfaceTableAddr.get(classInfo) match
      case Some(label) => label
      case None =>
        val label = Label(classInfo.name)
        interfaceTableAddr(classInfo) = label
        label

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
        reverseClassIds(id) = cls
        id

  def getClassSymbol(classId: Int): Symbol = reverseClassIds(classId)

  def getInterfaceId(interface: Symbol): Int =
    interfaceIds.get(interface) match
      case Some(id) => id
      case None =>
        val id = interfaceIds.size
        interfaceIds(interface) = id
        id

  /** Return lifted implementation function of the given interface method in the given class
    *
    * It handles bridge methods.
    */
  def getImplementation(classInfo: ClassInfo, name: String)(using Definitions): Symbol =
    classInfo.getMemberSymbol(name + Names.BridgeSuffix) match
      case Some(sym) => methodToLiftedMap(sym)
      case None =>
        classInfo.getMemberSymbol(name) match
          case Some(sym) => methodToLiftedMap(sym)
          case None =>
            throw new Exception(s"Implementation missing for $name in class ${classInfo.classSymbol}")

  /** Return lifted implementation functions of interface methods in the given class */
  def getInterfaceImplementations(classInfo: ClassInfo)(using Definitions): List[Symbol] =
    val result = new mutable.ArrayBuffer[Symbol]

    val viewList = classInfo.views
    for viewType <- viewList do
      val interfaceInfo = viewType.classInfo

      for method <- interfaceInfo.methods if method.is(Flags.Defer) do
        result += getImplementation(classInfo, method.name)
      end for
    end for

    result.toList

  def lowerInterfaceTable()(using pb: PatchableBuffer, defn: Definitions): Unit =
    for (classInfo, label) <- interfaceTableAddr do
      val viewList = classInfo.views

      if viewList.size == 0 then
        pb.defineLabel(label)

      else
        // First, generate vtables
        val vtableMap = mutable.Map.empty[Symbol, Int]
        for viewType <- viewList do
          val interfaceInfo = viewType.classInfo
          val interfaceSym = interfaceInfo.classSymbol

          pb.align(4)
          vtableMap(interfaceSym) = pb.currentAddr()

          for method <- interfaceInfo.methods if method.is(Flags.Defer) do
            val implMethod = getImplementation(classInfo, method.name)
            val label = runtime.funLabelMap(implMethod)

            // The code segment can be lowered later or before data segment
            Assembler.withPatch(label, 4): (bb, addr) =>
              bb.addInt(addr)

          end for
        end for

        // Second, generate itable
        pb.align(4)
        pb.defineLabel(label)

        pb.addInt(viewList.size) // Count of interfaces

        // For each interface, add (iid, vtable_ptr) pair
        for (isym, vtableAddr) <- vtableMap do
          val interfaceId = getInterfaceId(isym)

          pb.addInt(interfaceId) // iid
          pb.addInt(vtableAddr)  // vtable_ptr
        end for
