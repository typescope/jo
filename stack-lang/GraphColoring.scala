import scala.collection.mutable

/**
  * Register allocation based on graph coloring.
  *
  * TODO: how to coalesce spillings in a simple way? Increase the number of
  * registers with a cost model?
  */
object GraphColoring:
  enum Node:
    case Single(reg: Int)
    case Merged(node1: Node, node2: Node)

  enum Action:
    case Remove(node: Node)
    case Coalesce(node: Node.Merged)
    case Spill(node: Node)

  case class Edge(node1: Node, node2: Node)

  /** Interference graph */
  class Graph(
    var conflicts: Map[Node, Set[Node]],
    var moves: Map[Node, Set[Node]]):

    val actions = mutable.ArrayBuffer.empty[Action]

    def isMoveRelated(node: Node): Boolean =
      moves.contains(node)

    def degree(node: Node): Int = conflicts(node).size

    def conflict(node1: Node, node2: Node) =
      conflicts(node1).contains(node2)

    def simplify(node: Node): Unit =
      assert(!moves.contains(node))

      actions += Action.Remove(node)

      val conflictees = conflicts(node)
      conflicts -= node
      for conflictee <- conflictees do
        conflicts = conflicts.updated(conflictee, conflicts(conflictee) - node)

    def coalesce(node1: Node, node2: Node): Unit =
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
      if nonConflictMoves.isEmpty then
        moves = moves.updated(merged, nonConflictMoves)

      for target <- targets do
        assert(target != node1 && target != node2)
        val targetsReverse =
          if conflict(merged, target)
          then
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
      val targets1 = moves(node1).filter(_ != node2)
      val targets2 = moves(node2).filter(_ != node1)

      if targets1.isEmpty then
        moves = moves - node1
      else
        moves = moves.updated(node1, targets1)

      if targets2.isEmpty then
        moves = moves - node2
      else
        moves = moves.updated(node2, targets1)

    def spill(node: Node): Unit =
      actions += Action.Spill(node)

      val conflictees = conflicts(node)
      conflicts -= node
      for conflictee <- conflictees do
        conflicts = conflicts.updated(conflictee, conflicts(conflictee) - node)

      // high-degree moves are not tried by freeze
      val targets = moves(node)
      moves -= node
      for target <- targets do
        moves = moves.updated(target, moves(target) - node)

  end Graph

  case class Result(assignment: Map[Int, Int], spillings: List[Int])

  def build(result: Liveness.Result): Graph =
    val nodeMap = mutable.Map.empty[Int, Node]
    val conflicts = mutable.Map.empty[Node, Set[Node]]
    val moves = mutable.Map.empty[Node, Set[Node]]

    for (_, liveSet) <- result.liveSets if liveSet.nonEmpty do
      val reg1 = liveSet.head
      val remains = liveSet - reg1
      val node1 = nodeMap.getOrElseUpdate(reg1, Node.Single(reg1))
      // Ensure that a node with no conflicts gets an entry
      val conflictsNode1 = conflicts.getOrElseUpdate(node1, Set.empty)
      for reg2 <- remains do
        val node2 = nodeMap.getOrElseUpdate(reg2, Node.Single(reg2))
        val conflictsNode2 = conflicts.getOrElseUpdate(node2, Set.empty)
        conflicts(node1) = conflictsNode1 + node2
        conflicts(node2) = conflictsNode2 + node1
      end for

    // assumes that source and dest must appear in conflicts
    for (reg1, toSet) <- result.moves do
      val node1 = nodeMap(reg1)
      // It is meaningless to add a move edge if two nodes are in conflict.
      // It is never possible to coalesce the two nodes
      for
        reg2 <- toSet
        node2 = nodeMap(reg2)
        if !conflicts(node1).contains(node2)
      do
        moves(node1) = moves.getOrElseUpdate(node1, Set.empty) + node2
        moves(node2) = moves.getOrElseUpdate(node2, Set.empty) + node1
      end for

    Graph(conflicts.toMap, moves.toMap)

  def simplify(graph: Graph, k: Int): Boolean =
    var simplified = false
    for node <- graph.conflicts.keys do
      if graph.isMoveRelated(node) && graph.degree(node) < k then
        simplified = true
        graph.simplify(node)

    simplified

  def coalesce(graph: Graph, k: Int): Boolean =
    var coalesced = false
    for (node1, targets) <- graph.moves do
      for node2 <- targets do
        if
          graph.degree(node1) + graph.degree(node2) < k
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

          coalesced = true
          graph.coalesce(node1, node2)
    end for

    coalesced

  def freeze(graph: Graph, k: Int): Boolean =
    graph.moves.exists: (node, targets) =>
      assert(targets.nonEmpty)
      targets.exists: target =>
        if graph.degree(node) < k || graph.degree(target) < k then
          graph.freeze(node, targets.head)
          true
        else
          false

  def spill(graph: Graph, k: Int): Boolean =
    // pick a random
    graph.conflicts.exists: (node, conflictees) =>
      if conflictees.nonEmpty then
        assert(conflictees.size >= k)
        graph.spill(node)
        true
      else
        false
  end spill

  def select(graph: Graph): Unit = ???
