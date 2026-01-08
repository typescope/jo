package native

import native.Assembly.*
import native.runtime.NativeRuntime

import sast.*
import sast.Trees.*
import sast.Symbols.*

import common.WorkList

import scala.collection.mutable

/** The common code shared between StackMachine and RegisterMachine */
abstract class Backend(val runtime: NativeRuntime):

  /** Maps function symbols to addresses -- only reachable functions are compiled */
  private val funLabelMap: mutable.Map[Symbol, Label] = mutable.Map.empty

  /** Worklist to add all reachable functions */
  private val workList = new WorkList[Symbol]

  /** Maps string constants to labels */
  private val stringTable: mutable.Map[String, Label] = mutable.Map.empty

  def getFunAddress(sym: Symbol): Label =
    assert(sym.isFunction, "Not a function, sym = " + sym)

    funLabelMap.get(sym) match
      case Some(addr) => addr

      case None =>
        runtime.rewire.get(sym) match
          case Some(target) => getFunAddress(target)

          case None =>
            runtime.locate(sym) match
              case Some(label) =>
                // cache result
                funLabelMap(sym) = label
                label

              case None =>
                val label = Label(sym.name)
                funLabelMap(sym) = label

                // Add function to work list
                if sym != runtime.objectInitProcSym then
                  // `objectInitProcSym` only have a body at the end and is
                  // handled specially.
                  workList.add(sym)

                label

  def compileFunDef(fdef: FunDef)(using cb: CodeBuffer): Unit

  def compile(nss: List[Namespace]): Prog =
    // Buffer to hold the generated assembly code
    val entryLabel = Label("_entry")
    given cb: CodeBuffer = new CodeBuffer(entryLabel)

    // Hand-coded calls must be registered explicitly
    workList.add(runtime.Core_start)

    val symbolDefMap = mutable.Map.empty[Symbol, FunDef]

    for
      ns <- nss
      defn <- ns
    do
      defn match
        case fdef: FunDef =>
          symbolDefMap(fdef.symbol) = fdef

        case _ =>

    workList.run: sym =>
      val fdef = symbolDefMap(sym)
      compileFunDef(fdef)

    // must comes last after traversing whole universe
    compileFunDef(runtime.getObjectInitProc())

    // Add string constants
    for (v, label) <- stringTable do
      cb.add(Data.StringLit(label, v))

    // Assume SP is already setup by the underlying runtime platform, which is
    // the case for Linux.
    cb.mark(entryLabel)
    val addr = getFunAddress(runtime.Core_start)
    cb.add(Instr.Jump(addr))

    // generate code
    cb.getResult()

  def addString(v: String): Label =
    stringTable.get(v) match
      case Some(label) => label
      case None =>
        val label = Label("string")
        stringTable(v) = label
        label
