package native
package register

import Assembly.*
import PreAssembly.*

import scala.collection.mutable
import java.nio.charset.StandardCharsets

/**
  * Register allocation based on graph coloring.
  *
  * The algorithm performs simplification until it reaches a state where all
  * nodes have degree >= k. It then selects a random node to spill to enable
  * more simplifictions. The process repeats until the graph contains only
  * pre-colored registers.
  *
  * Spillings require inserting load/store instructions. As these instructions
  * use registers, liveness analysis and graph coloring need to be executed on
  * the rewritten assembly code again. The process repeats until coloring
  * succeeds without spillings.
  *
  * The algorithm only handles uniform register resources. It assumes all
  * registers are the same. A concrete achitecture needs to encode sub-word
  * register usage as using the whole register, as well as encode allocation
  * constraints with conflict edges.
  *
  * For example, it is not possible to directly use the sub-word of the register
  * ESI on x86. Either the backend generates adapting instructions with bit
  * masks or it constrains the 8-bit virtual register with a conflict edge to
  * ESI.
  *
  * A cost model can be used to spill least-used (thus less expensive)
  * registers.
  *
  * Currently, each spilled register occupies a memory cell. In principle,
  * the same cell can be shared by multiple non-conflicting spilled nodes.
  *
  * TODO: how to coalesce spillings in a simple way?
  */
object GraphColoring:
  enum Node:
    case Single(reg: Int)

    /** A merged node
      *
      * This is not strictly necessary. However, it is useful for debugging.
      */
    case Merged(node1: Node, node2: Node)

    def show: String =
      this match
        case Single(reg) => reg.toString
        case Merged(node1, node2) => node1.show + "," + node2.show

  enum Action:
    case Simplify(node: Node, conflicts: Set[Node])
    case Coalesce(node: Node.Merged)
    case Spill(node: Node, conflicts: Set[Node])

  /** Interference graph */
  class Graph(
    /** Registers less than this number are virtual registers */
    preColorLimit: Int,

    /** Bidirectional conflict edges */
    var conflicts: Map[Node, Set[Node]],

    /** Bidirectional move edges */
    var moves: Map[Node, Set[Node]]):

    val actions = mutable.ArrayBuffer.empty[Action]

    def isPreColored(node: Node): Boolean =
      node match
        case Node.Single(reg) =>
          reg < preColorLimit

        case Node.Merged(node1, node2) =>
          isPreColored(node1) || isPreColored(node2)

    /** It's guaranteed that at most one color exists */
    def preColor(node: Node): Option[Int] =
      node match
        case Node.Single(reg) =>
          if reg < preColorLimit then Some(reg) else None

        case Node.Merged(node1, node2) =>
          preColor(node1) match
            case None => preColor(node2)
            case res  => res

    def isMoveRelated(node: Node): Boolean =
      moves.contains(node)

    def degree(node: Node): Int = conflicts(node).size

    def conflict(node1: Node, node2: Node): Boolean =
      conflicts(node1).contains(node2)

    def simplify(node: Node): Unit =
      assert(!moves.contains(node))

      val conflictees = conflicts(node)
      conflicts -= node

      actions += Action.Simplify(node, conflictees)

      for conflictee <- conflictees do
        conflicts = conflicts.updated(conflictee, conflicts(conflictee) - node)

    def coalesce(node1: Node, node2: Node): Unit =
      assert(!isPreColored(node1) || !isPreColored(node2))

      val merged = new Node.Merged(node1, node2)
      actions += Action.Coalesce(merged)

      def replace(node: Node): Node =
        if node == node1 || node == node2 then merged else node

      // update conflicts

      val conflictees1 = conflicts(node1)
      val conflictees2 = conflicts(node2)
      conflicts -= node1
      conflicts -= node2
      conflicts = conflicts.updated(merged, conflictees1.union(conflictees2))

      for conflictee <- conflictees1 do
        assert(conflictee != node2)
        conflicts = conflicts.updated(conflictee, conflicts(conflictee).map(replace))

      for conflictee <- conflictees2 do
        assert(conflictee != node1)
        conflicts = conflicts.updated(conflictee, conflicts(conflictee).map(replace))

      // update moves

      // The move targets might in conflict with `merged`.
      val targets1 = moves(node1) - node2
      val targets2 = moves(node2) - node1
      val targets  = targets1.union(targets2)
      val nonConflictMoves = targets.filter(node => !conflict(merged, node))

      moves -= node1
      moves -= node2
      if nonConflictMoves.nonEmpty then
        moves = moves.updated(merged, nonConflictMoves)

      for target <- targets do
        assert(target != node1 && target != node2, s"target = $target, node1 = $node1, node2 = $node2")
        val targetsReverse =
          if conflict(merged, target) then
            moves(target).filter(node => node != node1 && node != node2)
          else
            moves(target).map(replace)

        if targetsReverse.isEmpty then
          moves = moves - target
        else
          moves = moves.updated(target, targetsReverse)
      end for
    end coalesce

    def freeze(node1: Node, node2: Node): Unit =
      val targets1 = moves(node1) - node2
      val targets2 = moves(node2) - node1

      if targets1.isEmpty then
        moves = moves - node1
      else
        moves = moves.updated(node1, targets1)

      if targets2.isEmpty then
        moves = moves - node2
      else
        moves = moves.updated(node2, targets2)

    def spill(node: Node): Unit =
      val conflictees = conflicts(node)
      conflicts -= node

      actions += Action.Spill(node, conflictees)

      for conflictee <- conflictees do
        conflicts = conflicts.updated(conflictee, conflicts(conflictee) - node)

      // high-degree moves are not tried by freeze
      moves.get(node) match
        case Some(targets) =>
          moves -= node
          for target <- targets do
            val targets2 = moves(target) - node
            if targets2.isEmpty then
              moves = moves - target
            else
              moves = moves.updated(target, targets2)

        case None =>

    def check(): Unit =
      for (node1, cfls) <- conflicts do
        checkPreColor(node1)
        for node2 <- cfls do
          assert(node1 != node2)
          assert(conflicts(node2).contains(node1))

      for (node, targets) <- moves do
        assert(conflicts.contains(node), node)
        checkPreColor(node)
        assert(targets.nonEmpty)
        for target <- targets do
          assert(node != target)
          assert(moves(target).contains(node))

    def checkPreColor(node: Node): Boolean =
      node match
        case Node.Single(_) =>
          isPreColored(node)

        case Node.Merged(node1, node2) =>
          val res1 = checkPreColor(node1)
          val res2 = checkPreColor(node2)
          assert(!res1 || !res2, "Cannot merge two precolored nodes")
          res1 || res2

    /** Represent the graph using the DOT language
      *
      *    graph {
      *      a -- b -- c;
      *      b -- d [style="dotted"];
      *    }
      *
      * https://en.wikipedia.org/wiki/DOT_(graph_description_language)
      */
    def toDot: String =
      val sb = new StringBuilder
      sb ++= "graph {"

      def quote(node: Node) = s""""${node.show}""""

      for (node, cfls) <- conflicts do
        sb ++= quote(node)
        sb ++= " -- {"
        for node2 <- cfls do sb ++= " " + quote(node2)
        sb ++= "};\n"


      for (node, targets) <- moves do
        sb ++= quote(node)
        sb ++= " -- {"
        for node2 <- targets do sb ++= " " + quote(node2)
        sb ++= """}[style="dotted"];"""
        sb += '\n'

      sb ++= "}"
      sb.toString

  end Graph

  /** Generate additional constraints for a specific platform (os+arch) */
  trait PlatformRules:
    /** Return extra conflicts specific to the platform */
    def conflicts(info: Liveness.Info): List[(Int, Int)]

  /** Create conflict graph from liveness data
    *
    * @param extracConflicts Platform-specific constraints can be encoded as extra conflicts
    */
  def build(info: Liveness.Info, reserved: List[Int], preColor: Int, extraConflicts: List[(Int, Int)]): Graph =
    val nodeMap = mutable.Map.empty[Int, Node]
    val conflicts = mutable.Map.empty[Node, Set[Node]]
    val moves = mutable.Map.empty[Node, Set[Node]]

    def notReserved(reg: Int) = !reserved.contains(reg)

    def addConflict(reg1: Int, reg2: Int) =
      // Ensure that a node with no conflicts gets an entry
      val node1 = nodeMap.getOrElseUpdate(reg1, Node.Single(reg1))
      val node2 = nodeMap.getOrElseUpdate(reg2, Node.Single(reg2))

      if reg1 == reg2 then
        // ensure that a node is represented in the graph
        if notReserved(reg1) then
          conflicts.getOrElseUpdate(node1, Set.empty)
      else
        if notReserved(reg1) then
          val conflictsNode1 = conflicts.getOrElse(node1, Set.empty)
          conflicts(node1) =
            if notReserved(reg2) then conflictsNode1 + node2
            else conflictsNode1

        if notReserved(reg2) then
          val conflictsNode2 = conflicts.getOrElse(node2, Set.empty)
          conflicts(node2) =
            if notReserved(reg1) then conflictsNode2 + node1
            else conflictsNode2

    for
      (loc, outLiveSet) <- info.liveSets
      reg2 <- outLiveSet
    do
      val preInstr = info.instrs(loc)
      val RegInfo(defs, uses) = preInstr.regInfo

      preInstr match
        case PreInstr.Instr(Instr.Move(v, reg1)) =>
           v match
             case Reg(reg3) if reg3 != reg2 => addConflict(reg1, reg2)
             case _ => addConflict(reg1, reg2)

        case _ =>
           for reg1 <- defs do addConflict(reg1, reg2)

    end for

    for (reg1, toSet) <- info.moves if !reserved.contains(reg1) do
      val node1 = nodeMap.getOrElse(reg1, Node.Single(reg1))
      // It is meaningless to add a move edge if two nodes are in conflict.
      // It is never possible to coalesce the two nodes
      for
        reg2 <- toSet
        node2 = nodeMap.getOrElse(reg2, Node.Single(reg2))
        if reg1 != reg2 && !reserved.contains(reg2)
           && !conflicts.getOrElse(node1, Set.empty).contains(node2)
      do
        conflicts.getOrElseUpdate(node1, Set.empty)
        conflicts.getOrElseUpdate(node2, Set.empty)
        moves(node1) = moves.getOrElse(node1, Set.empty) + node2
        moves(node2) = moves.getOrElse(node2, Set.empty) + node1
      end for

    for (reg1, reg2) <- extraConflicts do addConflict(reg1, reg2)

    Graph(preColor, conflicts.toMap, moves.toMap)

  /** Remove non-move nodes that are always satisfiable */
  def simplify(graph: Graph, k: Int): Boolean =
    graph.conflicts.exists: (node, conflictees) =>
      if
        !graph.isPreColored(node)
        && !graph.isMoveRelated(node)
        && conflictees.size < k
      then
        graph.simplify(node)
        true
      else
        false

  /** Merge two move-related nodes into a single node */
  def coalesce(graph: Graph, k: Int): Boolean =
    // No need to consider nodes not in moves as they can be simplied
    graph.moves.exists: (node1, targets) =>
      targets.exists: node2 =>
        if
          (!graph.isPreColored(node1) || !graph.isPreColored(node2))
          && (graph.conflicts(node1).union(graph.conflicts(node2))).size < k
        then
          // Conflict moves are removed and should never be encountered.
          //
          // node1 and node2 conflict implies bad generated code?
          // For RISC instructions, yes. For x86, no.
          //
          // In x86, we can copy SP and perform destructive operation on the
          // copied virtual register.
          //
          // For RISC, it is possible to indicate target register thus the move
          // is unnecessary.
          //
          // The conflict might also result from coalescing.
          assert(!graph.conflict(node1, node2))
          graph.coalesce(node1, node2)
          true
        else
          false

  /** Decouple two move-related nodes to enable simplification */
  def freeze(graph: Graph, k: Int): Boolean =
    graph.moves.exists: (node, targets) =>
      assert(targets.nonEmpty, node.show + " -> " + targets + ", " + graph)
      targets.exists: target =>
        if graph.degree(node) < k || graph.degree(target) < k then
          graph.freeze(node, targets.head)
          true
        else
          false

  /** Spill a node with degree big or equal to k */
  def spill(graph: Graph, k: Int): Boolean =
    // Pick a random node to spill.
    //
    // More optimal spilling should be based on a cost model.
    graph.conflicts.exists: (node, conflictees) =>
      if !graph.isPreColored(node) && conflictees.nonEmpty then
        assert(conflictees.size >= k, "node = " + node + ", conflict = " + conflictees)
        graph.spill(node)
        true
      else
        false
  end spill

  case class Result(regAlloc: Map[Int, Int], stackAlloc: Map[Int, Int], usedRegs: Set[Int])

  def select(graph: Graph, regs: List[Int]): Result =
    assert(graph.conflicts.keys.forall(node => graph.isPreColored(node)))

    val k = regs.size

    val usedRegs = mutable.ArrayBuffer.empty[Int]

    var stackSlot = 0
    val stackAssignment = mutable.Map.empty[Int, Int]
    def assignStackSlot(node: Node, stackSlot: Int): Unit =
      node match
        case Node.Single(reg) =>
          stackAssignment(reg) = stackSlot

        case Node.Merged(node1, node2) =>
          assignStackSlot(node1, stackSlot)
          assignStackSlot(node2, stackSlot)

    val regAssignment = mutable.Map.empty[Int, Int]
    def assignRegister(node: Node, regAssigned: Int): Unit =
      usedRegs += regAssigned
      node match
        case Node.Single(reg) =>
          if !graph.isPreColored(node) then
            regAssignment(reg) = regAssigned

        case Node.Merged(node1, node2) =>
          assignRegister(node1, regAssigned)
          assignRegister(node2, regAssigned)

    def getAssignment(node: Node): List[Int] =
      node match
        case Node.Single(reg) =>
          if graph.isPreColored(node) then
            List(reg)
          else
            regAssignment.get(reg) match
              case Some(regAssign) =>
                List(regAssign)
              case None =>
                assert(stackAssignment.contains(reg), reg)
                Nil

        case Node.Merged(node1, node2) =>
          getAssignment(node1)

    // assign merges with pre-colored
    for node <- graph.conflicts.keys do
      val Some(reg) = graph.preColor(node): @unchecked
      usedRegs += reg
      assignRegister(node, reg)

    while graph.actions.nonEmpty do
      val action = graph.actions.remove(graph.actions.size - 1)
      action match
        case Action.Simplify(node: Node, conflicts: Set[Node]) =>
          assert(conflicts.size < k)
          val used = conflicts.flatMap(getAssignment).toSeq
          val reg = regs.diff(used).head
          assignRegister(node, reg)

        case Action.Coalesce(node @ Node.Merged(node1, node2)) =>
          graph.preColor(node) match
            case Some(reg) =>
              assignRegister(node, reg)

            case None =>

        case Action.Spill(node: Node, conflicts: Set[Node]) =>
          val used = conflicts.flatMap(getAssignment).toSeq
          val unused = regs.diff(used)
          // potential spill might not be an actual spill
          if unused.isEmpty then
            assignStackSlot(node, stackSlot)
            stackSlot += 1
          else
            val reg = unused.head
            assignRegister(node, reg)
    end while

    Result(regAssignment.toMap, stackAssignment.toMap, usedRegs.toSet)
  end select

  enum State:
    case Simplify, Coalesce, Freeze, Spill, Select

  final val DEBUG = false
  var round = 1

  def alloc(
    name: String, liveness: Liveness.Info, regs: List[Int],
    reserved: List[Int], preColor: Int,
    extraConflicts: List[(Int, Int)]): Result =

    import State.*

    round += 1

    val k = regs.size
    val graph = build(liveness, reserved, preColor, extraConflicts)

    var state = State.Simplify

    import java.nio.file.{ Files, Paths }

    var i = 0

    while state != Select do
      if DEBUG then
        Files.write(Paths.get(s"$name-$round-$i-before-$state.dot"), graph.toDot.getBytes(StandardCharsets.UTF_8))
        i += 1

      graph.check()

      state match
        case Simplify =>
          state = if simplify(graph, k) then Simplify else Coalesce

        case Coalesce =>
          state = if coalesce(graph, k) then Simplify else Freeze

        case Freeze =>
          state = if freeze(graph, k) then Simplify else Spill

        case Spill =>
          state = if spill(graph, k) then Simplify else Select

        case Select =>
    end while

    select(graph, regs)
