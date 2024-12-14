package native

import native.Assembly.Prog
import native.NativeRuntime

abstract class Backend:
  def runtime: NativeRuntime
  def compile(nss: List[Namespace], main: Symbol): Prog

  /** Maps function symbols to addresses */
  val funLabelMap: mutable.Map[Symbol, Label] = mutable.Map.empty

  /** Worklist to add all reachable functions */
  val workList = new WorkList[Symbol]

  def getAddress(sym: Symbol): Label =
    assert(sym.isFunction, "Not a function, sym = " + sym)

    funLabelMap.get(sym) match
      case Some(addr) => addr

      case None =>
        runtime.locate(sym) match
          case Some(addrOrSymbol) =>
            addr match
              case label: Label =>
                // cache result
                funLabelMap(sym) = label
                label

              case redirectSym: Symbol =>
                getAddress(sym)

          case None =>
            val label = Label(sym.name)
            funLabelMap(sym) = label

            // Add function to work list
            workList.add(sym)

            label

  /** Maps string constants to labels */
  val stringTable: mutable.Map[String, Label] = mutable.Map.empty

  def addString(v: String): Label =
    stringTable.get(v) match
      case Some(label) => label
      case None =>
        val label = Label("string")
        stringTable(v) = label
        label
