package native
package runtime

import sast.Definitions
import sast.Flags
import sast.Symbols.Symbol
import sast.Types.ClassInfo

import native.Assembly.Label
import native.Assembler.PatchableBuffer

import scala.collection.mutable

class InterfaceTable(runtime: NativeRuntime):
  private val methodToLiftedMap = mutable.Map.empty[Symbol, Symbol]
  private val classIds = mutable.Map.empty[Symbol, Int]
  private val interfaceIds = mutable.Map.empty[Symbol, Int]

  /** Map from a class to its interface table address */
  private val interfaceTableAddr = mutable.Map.empty[Symbol, Label]

  def getInterfaceTable(cls: Symbol): Label =
    assert(cls.isClass, "not a class: " + cls)

    interfaceTableAddr.get(cls) match
      case Some(label) => label
      case None =>
        val label = Label(cls.name)
        interfaceTableAddr(cls) = label
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
        id

  def getInterfaceId(interface: Symbol): Int =
    interfaceIds.get(interface) match
      case Some(id) => id
      case None =>
        val id = interfaceIds.size
        interfaceIds(interface) = id
        id

  /** Return lifted implementation function of the given interface method in the given class */
  def getLiftedImplementation(classInfo: ClassInfo, meth: Symbol): Symbol =
    classInfo.getMemberSymbol(meth.name) match
      case Some(sym) => methodToLiftedMap(sym)
      case None => throw new Exception(s"Implementation missing for ${meth} in class ${classInfo.classSymbol}")

  /** Return lifted implementation functions of interface methods in the given class */
  def getInterfaceImplementations(classInfo: ClassInfo)(using Definitions): List[Symbol] =
    val result = new mutable.ArrayBuffer[Symbol]

    val directViews = classInfo.directViews
    for viewType <- directViews do
      val interfaceInfo = viewType.asClassInfo

      for method <- interfaceInfo.methods if method.is(Flags.Defer) do
        result += getLiftedImplementation(classInfo, method)
      end for
    end for

    result.toList

  def lowerInterfaceTable()(using pb: PatchableBuffer, defn: Definitions): Unit =
    for (cls, label) <- interfaceTableAddr do
      val classInfo = cls.info.asClassInfo
      val directViews = classInfo.directViews

      if directViews.size == 0 then
        pb.defineLabel(label)

      else
        // First, generate vtables
        val vtableMap = mutable.Map.empty[Symbol, Int]
        for viewType <- directViews do
          val interfaceInfo = viewType.asClassInfo
          val interfaceSym = interfaceInfo.classSymbol

          pb.align(4)
          vtableMap(interfaceSym) = pb.currentAddr()

          for method <- interfaceInfo.methods if method.is(Flags.Defer) do
            val implMethod = getLiftedImplementation(classInfo, method)
            val label = runtime.funLabelMap(implMethod)
            pb.resolve(label) match
              case Some(addr) => pb.addInt(addr)
              case None => throw new Exception("Lifted function address unkonwn: " + method + ", lifted = " + implMethod)
          end for
        end for

        // Second, generate itable
        pb.align(4)
        pb.defineLabel(label)

        pb.addInt(directViews.size) // Count of interfaces

        // For each interface, add (iid, vtable_ptr) pair
        for (isym, vtableAddr) <- vtableMap do
          val interfaceId = getInterfaceId(isym)

          pb.addInt(interfaceId) // iid
          pb.addInt(vtableAddr)  // vtable_ptr
        end for
