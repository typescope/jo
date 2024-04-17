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
    case Multiple(regs: List[Int])

  enum Action:
    case Remove(node: Node)
    case Coalesce(node1: Node, node2: Node, to: Node)
    case Spill(node: Node)

  case class Edge(node1: Node, node2: Node)

  /** Interference graph */
  class Graph(
    val nodes: mutable.Set[Node],
    conflicts: mutable.Set[Edge],
    moves: mutable.Set[Edge]):

    val actions: mutable.ArrayBuffer[Action] = mutable.ArrayBuffer.empty

    def isMoveRelated(node: Node): Boolean =
      moves.exists(e => e.node1 == node || e.node2 == node)

    def degree(node: Node): Int =
      conflicts.filter(e => e.node1 == node || e.node2 == node).size

    def simplify(node: Node): Unit =
      assert(moves.forall(e => e.node1 != node && e.node2 != node))

      actions += Action.Remove(node)
      nodes -= node
      conflicts.filterInPlace(e => e.node1 != node && e.node2 != node)

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

    Graph(mutable.Set.from(nodeMap.values), conflicts, moves)

  def simplify(graph: Graph, k: Int): Unit =
    var simplified = false
    for node <- graph.nodes do
      if graph.isMoveRelated(node) && graph.degree(node) < k then
        simplified = true
        graph.simplify(node)

    if simplified then simplify(graph, k)

  def coalesce(graph: Graph): Unit = ???

  def freeze(graph: Graph): Unit = ???

  def spill(graph: Graph): Unit = ???

  def select(graph: Graph): Unit = ???
