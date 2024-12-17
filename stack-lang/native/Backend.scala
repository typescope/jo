package native

import native.Assembly.*

import sast.*
import sast.Sast.*
import sast.Symbols.*

import common.WorkList

import scala.collection.mutable

abstract class Backend(val runtime: NativeRuntime):
  def compile(nss: List[Namespace]): Prog

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
            addrOrSymbol match
              case label: Label =>
                // cache result
                funLabelMap(sym) = label
                label

              case redirectSym: Symbol =>
                getAddress(redirectSym)

          case None =>
            val label = Label(sym.name)
            funLabelMap(sym) = label

            // Add function to work list
            workList.add(sym)

            label

  def run(nss: List[Namespace], main: Symbol)(fn: FunDef => Unit) =
    workList.add(main)
    workList.add(runtime.Core_finish)
    for init <- runtime.inits() do workList.add(init)

    val symbolDefMap = mutable.Map.empty[Symbol, FunDef]
    for
      ns <- nss
      case fdef: FunDef <- ns.defs
    do
      symbolDefMap(fdef.symbol) = fdef

    workList.run: sym =>
      val fun = symbolDefMap(sym)
      fn(fun)


  /** Maps string constants to labels */
  val stringTable: mutable.Map[String, Label] = mutable.Map.empty

  def addString(v: String): Label =
    stringTable.get(v) match
      case Some(label) => label
      case None =>
        val label = Label("string")
        stringTable(v) = label
        label
