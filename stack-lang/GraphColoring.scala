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
        conflicts = conflicts.updated(conflictee, conflicts(conflictee).map(replace))

      for conflictee <- conflictees2 do
        conflicts = conflicts.updated(conflictee, conflicts(conflictee).map(replace))

      // update moves

      val targets1 = moves(node1)
      val targets2 = moves(node2)
      moves -= node1
      moves -= node2
      moves = moves.updated(merged, targets1.union(targets2))

      for target <- targets1 if target != node2 do
        moves = moves.updated(target, moves(target).map(replace))

      for target <- targets2 if target != node1 do
        moves = moves.updated(target, moves(target).map(replace))

  case class Result(assignment: Map[Int, Int], spillings: List[Int])

  def build(result: Liveness.Result): Graph =
    val nodeMap = mutable.Map.empty[Int, Node]
    val conflicts = mutable.Map.empty[Node, Set[Node]]
    val moves = mutable.Map.empty[Node, Set[Node]]

    for (_, liveSet) <- result.liveSets if liveSet.nonEmpty do
      val reg1 = liveSet.head
      val remains = liveSet - reg1
      val node1 = nodeMap.getOrElseUpdate(reg1, Node.Single(reg1))
      for reg2 <- remains do
        val node2 = nodeMap.getOrElseUpdate(reg2, Node.Single(reg2))
        conflicts(node1) = conflicts.getOrElseUpdate(node1, Set.empty) + node2
        conflicts(node2) = conflicts.getOrElseUpdate(node2, Set.empty) + node1
      end for

    for (reg1, toSet) <- result.liveSets do
      val node1 = nodeMap.getOrElseUpdate(reg1, Node.Single(reg1))
      for reg2 <- toSet do
        val node2 = nodeMap.getOrElseUpdate(reg2, Node.Single(reg2))
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
          !graph.conflict(node1, node2) &&
          graph.degree(node1) + graph.degree(node2) < k
        then
          // TODO: node1 and node2 conflict implies bad generated code
          coalesced = true
          graph.coalesce(node1, node2)
    end for

    coalesced

  def freeze(graph: Graph): Unit = ???

  def spill(graph: Graph): Unit = ???

  def select(graph: Graph): Unit = ???
