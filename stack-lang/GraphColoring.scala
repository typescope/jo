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
    var nodes: Set[Node],
    var conflicts: Set[Edge],
    var moves: Set[Edge]):

    val actions = mutable.ArrayBuffer.empty[Action]

    def isMoveRelated(node: Node): Boolean =
      moves.exists(e => e.node1 == node || e.node2 == node)

    def degree(node: Node): Int =
      conflicts.filter(e => e.node1 == node || e.node2 == node).size

    def conflict(node1: Node, node2: Node) =
      conflicts.contains(Edge(node1, node2)) ||
      conflicts.contains(Edge(node2, node1))

    def simplify(node: Node): Unit =
      assert(moves.forall(e => e.node1 != node && e.node2 != node))

      actions += Action.Remove(node)
      nodes -= node
      conflicts = conflicts.filter(e => e.node1 != node && e.node2 != node)

    def coalesce(node1: Node, node2: Node): Unit =
      val merged = new Node.Merged(node1, node2)
      actions += Action.Coalesce(merged)

      nodes -= node1
      nodes -= node2
      nodes += merged
      moves -= Edge(node1, node2)
      moves -= Edge(node2, node1)

      def replace(edge: Edge): Edge =
        if edge.node1 == node1 || edge.node1 == node2 then
          Edge(merged, edge.node2)
        else if edge.node2 == node1 || edge.node2 == node2 then
          Edge(edge.node1, merged)
        else
          edge

      conflicts = conflicts.map(replace)
      moves = moves.map(replace)

      // TODO: dedup in moves and conflicts


  case class Result(assignment: Map[Int, Int], spillings: List[Int])

  def build(result: Liveness.Result): Graph =
    val nodeMap = mutable.Map.empty[Int, Node]
    val conflicts = mutable.Set.empty[Edge]
    val moves = mutable.Set.empty[Edge]

    for (_, liveSet) <- result.liveSets if liveSet.nonEmpty do
      val reg1 = liveSet.head
      val remains = liveSet - reg1
      val node1 = nodeMap.getOrElseUpdate(reg1, Node.Single(reg1))
      for reg2 <- remains do
        val node2 = nodeMap.getOrElseUpdate(reg2, Node.Single(reg2))
        // Avoid adding both (a, b) and (b, a)
        conflicts -= Edge(node2, node1)
        conflicts += Edge(node1, node2)
      end for

    for (reg1, toSet) <- result.liveSets do
      val node1 = nodeMap.getOrElseUpdate(reg1, Node.Single(reg1))
      for reg2 <- toSet do
        val node2 = nodeMap.getOrElseUpdate(reg2, Node.Single(reg2))
        // Avoid adding both (a, b) and (b, a)
        moves -= Edge(node2, node1)
        moves += Edge(node1, node2)
      end for

    Graph(nodeMap.values.toSet, conflicts.toSet, moves.toSet)

  def simplify(graph: Graph, k: Int): Boolean =
    var simplified = false
    for node <- graph.nodes do
      if graph.isMoveRelated(node) && graph.degree(node) < k then
        simplified = true
        graph.simplify(node)

    simplified

  def coalesce(graph: Graph, k: Int): Boolean =
    var coalesced = false
    for Edge(node1, node2) <- graph.moves do
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
