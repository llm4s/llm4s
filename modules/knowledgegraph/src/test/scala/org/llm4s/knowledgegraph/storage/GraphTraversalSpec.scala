package org.llm4s.knowledgegraph.storage

import org.llm4s.error.{ LLMError, ProcessingError }
import org.llm4s.knowledgegraph.Node
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/**
 * Direct tests of [[GraphTraversal.bfs]], the breadth-first traversal behind `InMemoryGraphStore` and
 * `JsonGraphStore` (and the reference for `Neo4jGraphStore`).
 *
 * The graph is a plain adjacency map, so the traversal is tested in isolation from any store: the
 * `getNode` and `getNeighborIds` functions it is given are the only things it can see.
 */
class GraphTraversalSpec extends AnyFunSuite with Matchers {

  private def node(id: String): Node = Node(id, "Thing")

  /**
   * How many `getNode`/`getNeighborIds` calls one traversal may make before the test fails it. The largest
   * legitimate traversal here is the 20,001-node chain and star, about 40,000 calls (one `getNode` and one
   * `getNeighborIds` per node), so the budget is a six-fold headroom over that; a traversal cannot loop
   * without consulting the callbacks (the queue only refills from neighbour results), so a broken cycle
   * guard trips the budget within a couple of seconds. The budget replaces a `Future` plus `Await` time
   * limit: a timed-out `Await` could not stop the runaway traversal, which kept spinning on a global
   * `ExecutionContext` worker and could starve the rest of the suite (Codex review). Here the failure
   * happens on the test's own thread, with nothing left running and no wall clock involved.
   */
  private val CallBudget = 250000

  /** Wraps a traversal's two callbacks so a runaway traversal fails fast instead of spinning forever. */
  private def budgeted(
    getNode: String => Result[Option[Node]],
    getNeighbours: (String, Direction) => Result[Seq[String]]
  ): (String => Result[Option[Node]], (String, Direction) => Result[Seq[String]]) = {
    var calls = 0
    def spend(): Unit = {
      calls += 1
      if (calls > CallBudget)
        throw new IllegalStateException(
          s"the traversal made more than $CallBudget getNode/getNeighborIds calls: a runaway loop (broken cycle guard?)"
        )
    }
    (id => { spend(); getNode(id) }, (id, direction) => { spend(); getNeighbours(id, direction) })
  }

  /** An in-memory graph that records how the traversal used it. */
  final private class Fixture(
    adjacency: Map[String, Seq[String]],
    missing: Set[String] = Set.empty,
    failNode: Map[String, LLMError] = Map.empty,
    failNeighbours: Map[String, LLMError] = Map.empty
  ) {
    val neighbourCalls: mutable.ArrayBuffer[(String, Direction)] = mutable.ArrayBuffer.empty

    private val ids: Set[String] = adjacency.keySet ++ adjacency.values.flatten

    def getNode(id: String): Result[Option[Node]] =
      failNode.get(id) match {
        case Some(error) => Left(error)
        case None        => Right(if (ids.contains(id) && !missing.contains(id)) Some(node(id)) else None)
      }

    def getNeighborIds(id: String, direction: Direction): Result[Seq[String]] = {
      neighbourCalls += ((id, direction))
      failNeighbours.get(id) match {
        case Some(error) => Left(error)
        case None        => Right(adjacency.getOrElse(id, Seq.empty))
      }
    }

    def bfs(start: String, config: TraversalConfig = TraversalConfig()): Result[Seq[Node]] = {
      val (budgetedNode, budgetedNeighbours) = budgeted(getNode, getNeighborIds)
      GraphTraversal.bfs(start, config)(budgetedNode, budgetedNeighbours)
    }

    def visitedIds(start: String, config: TraversalConfig = TraversalConfig()): Seq[String] =
      bfs(start, config) match {
        case Right(nodes) => nodes.map(_.id)
        case Left(error)  => fail(s"expected a traversal, got $error")
      }
  }

  // root -> a, b ; a -> a1, a2 ; b -> b1
  private val tree = Map(
    "root" -> Seq("a", "b"),
    "a"    -> Seq("a1", "a2"),
    "b"    -> Seq("b1")
  )

  // ---- the start node

  test("an unknown start node returns an empty result, not an error") {
    val graph = new Fixture(Map("a" -> Seq("b")))
    graph.bfs("nobody") shouldBe Right(Seq.empty)
  }

  test("an unknown start node never asks for its neighbours") {
    val graph = new Fixture(Map("a" -> Seq("b")))
    graph.bfs("nobody")
    graph.neighbourCalls shouldBe empty
  }

  test("a lone start node with no neighbours is the whole result") {
    val graph = new Fixture(Map("only" -> Seq.empty))
    graph.visitedIds("only") shouldBe Seq("only")
  }

  // ---- ordering

  test("nodes come back level by level, each level in the order the neighbours were listed") {
    val graph = new Fixture(tree)
    graph.visitedIds("root") shouldBe Seq("root", "a", "b", "a1", "a2", "b1")
  }

  test("a different neighbour order gives the matching level order") {
    val graph = new Fixture(tree.updated("root", Seq("b", "a")))
    graph.visitedIds("root") shouldBe Seq("root", "b", "a", "b1", "a1", "a2")
  }

  test("a shallower node is never listed after a deeper one") {
    // c is reachable at depth 1 (root -> c) and at depth 3 (root -> a -> b -> c): it must be listed with depth 1
    val graph = new Fixture(
      Map(
        "root" -> Seq("a", "c"),
        "a"    -> Seq("b"),
        "b"    -> Seq("c"),
        "c"    -> Seq("d")
      )
    )
    graph.visitedIds("root") shouldBe Seq("root", "a", "c", "b", "d")
  }

  test("a node reachable by two routes is returned once, after the first route reaches it") {
    // diamond: a -> b, c ; b -> d ; c -> d
    val graph = new Fixture(Map("a" -> Seq("b", "c"), "b" -> Seq("d"), "c" -> Seq("d")))
    graph.visitedIds("a") shouldBe Seq("a", "b", "c", "d")
  }

  test("the returned nodes are the ones getNode produced, not copies rebuilt from their ids") {
    val special = Node("a", "Person", Map("name" -> ujson.Str("Alice")))
    val (budgetedNode, budgetedNeighbours) = budgeted(
      id => Right(if (id == "a") Some(special) else None),
      (_, _) => Right(Seq.empty)
    )
    val result = GraphTraversal.bfs("a", TraversalConfig())(budgetedNode, budgetedNeighbours)
    result shouldBe Right(Seq(special))
  }

  // ---- maxDepth

  test("maxDepth = 0 returns only the start node, and never asks for neighbours") {
    val graph = new Fixture(tree)
    graph.visitedIds("root", TraversalConfig(maxDepth = 0)) shouldBe Seq("root")
    graph.neighbourCalls shouldBe empty
  }

  test("maxDepth = 1 returns the start node and its direct neighbours only") {
    val graph = new Fixture(tree)
    graph.visitedIds("root", TraversalConfig(maxDepth = 1)) shouldBe Seq("root", "a", "b")
  }

  test("maxDepth = 2 includes the second level and stops there") {
    val graph = new Fixture(tree)
    graph.visitedIds("root", TraversalConfig(maxDepth = 2)) shouldBe Seq("root", "a", "b", "a1", "a2", "b1")
  }

  test("nodes at the depth limit are not expanded") {
    val graph = new Fixture(tree)
    graph.bfs("root", TraversalConfig(maxDepth = 1))
    graph.neighbourCalls.map(_._1).toSet shouldBe Set("root")
  }

  test("a depth limit larger than the graph changes nothing") {
    val graph = new Fixture(tree)
    graph.visitedIds("root", TraversalConfig(maxDepth = 50)) shouldBe graph.visitedIds("root")
  }

  // ---- cycles and repeated edges

  test("a cycle is walked once: every node appears once and the traversal ends") {
    val graph = new Fixture(Map("a" -> Seq("b"), "b" -> Seq("c"), "c" -> Seq("a")))
    graph.visitedIds("a") shouldBe Seq("a", "b", "c")
  }

  test("a self-loop does not repeat the node") {
    val graph = new Fixture(Map("a" -> Seq("a", "b"), "b" -> Seq("b")))
    graph.visitedIds("a") shouldBe Seq("a", "b")
  }

  test("a neighbour listed twice is returned once") {
    val graph = new Fixture(Map("a" -> Seq("b", "b", "c")))
    graph.visitedIds("a") shouldBe Seq("a", "b", "c")
  }

  test("each node's neighbours are fetched at most once, even in a graph full of cycles") {
    val ids   = (0 until 6).map(i => s"n$i")
    val graph = new Fixture(ids.map(id => id -> ids).toMap) // complete graph with self-loops
    graph.visitedIds("n0") should contain theSameElementsAs ids
    graph.neighbourCalls.map(_._1) should contain theSameElementsAs ids
  }

  // ---- visitedNodeIds

  test("nodes in visitedNodeIds are skipped") {
    val graph = new Fixture(tree)
    graph.visitedIds("root", TraversalConfig(visitedNodeIds = Set("a1", "b1"))) shouldBe Seq("root", "a", "b", "a2")
  }

  test("a skipped node is not expanded, so what lies only behind it is not reached") {
    val graph = new Fixture(tree)
    graph.visitedIds("root", TraversalConfig(visitedNodeIds = Set("a"))) shouldBe Seq("root", "b", "b1")
    graph.neighbourCalls.map(_._1) should not contain "a"
  }

  test("a skipped node does not block another route to the nodes behind it") {
    // c is skipped, but d is also reachable through b
    val graph = new Fixture(Map("a" -> Seq("b", "c"), "b" -> Seq("d"), "c" -> Seq("d")))
    graph.visitedIds("a", TraversalConfig(visitedNodeIds = Set("c"))) shouldBe Seq("a", "b", "d")
  }

  test("a start node that is already in visitedNodeIds gives an empty result") {
    val graph = new Fixture(tree)
    graph.visitedIds("root", TraversalConfig(visitedNodeIds = Set("root"))) shouldBe empty
  }

  // ---- direction

  test("the configured direction is handed to getNeighborIds for every expansion") {
    Seq[Direction](Direction.Outgoing, Direction.Incoming, Direction.Both).foreach { direction =>
      val graph = new Fixture(tree)
      graph.bfs("root", TraversalConfig(direction = direction))
      graph.neighbourCalls.map(_._2).toSet shouldBe Set(direction)
      graph.neighbourCalls should not be empty
    }
  }

  test("the default direction is Both") {
    val graph = new Fixture(tree)
    graph.bfs("root")
    graph.neighbourCalls.map(_._2).toSet shouldBe Set[Direction](Direction.Both)
  }

  test("the direction decides which neighbours are followed") {
    val outgoing = Map("a" -> Seq("b"), "b" -> Seq.empty, "c" -> Seq("a"))
    val incoming = Map("a" -> Seq("c"), "b" -> Seq("a"), "c" -> Seq.empty)
    def neighbours(id: String, direction: Direction): Result[Seq[String]] =
      Right((if (direction == Direction.Incoming) incoming else outgoing).getOrElse(id, Seq.empty))
    val nodes = Set("a", "b", "c")
    def run(direction: Direction): Seq[String] = {
      val (budgetedNode, budgetedNeighbours) = budgeted(
        id => Right(if (nodes.contains(id)) Some(node(id)) else None),
        neighbours
      )
      GraphTraversal
        .bfs("a", TraversalConfig(direction = direction))(budgetedNode, budgetedNeighbours)
        .map(_.map(_.id))
        .getOrElse(fail("expected a traversal"))
    }
    run(Direction.Outgoing) shouldBe Seq("a", "b")
    run(Direction.Incoming) shouldBe Seq("a", "c")
  }

  // ---- dangling edges

  test("an edge to a node that does not exist is skipped and the traversal continues") {
    val graph = new Fixture(Map("a" -> Seq("ghost", "b"), "b" -> Seq("c")), missing = Set("ghost"))
    graph.visitedIds("a") shouldBe Seq("a", "b", "c")
  }

  test("a missing node is not expanded") {
    val graph = new Fixture(Map("a" -> Seq("ghost"), "ghost" -> Seq("hidden")), missing = Set("ghost"))
    graph.visitedIds("a") shouldBe Seq("a")
    graph.neighbourCalls.map(_._1) should not contain "ghost"
  }

  // ---- errors

  test("a Left from getNode for the start node is returned") {
    val error = ProcessingError("graph", "start lookup failed")
    val graph = new Fixture(tree, failNode = Map("root" -> error))
    graph.bfs("root") shouldBe Left(error)
  }

  test("a Left from getNode for a later node is returned, not swallowed") {
    val error = ProcessingError("graph", "lookup of a1 failed")
    val graph = new Fixture(tree, failNode = Map("a1" -> error))
    graph.bfs("root") shouldBe Left(error)
  }

  test("a Left from getNeighborIds is returned, not swallowed") {
    val error = ProcessingError("graph", "neighbours of a failed")
    val graph = new Fixture(tree, failNeighbours = Map("a" -> error))
    graph.bfs("root") shouldBe Left(error)
  }

  test("a Left from getNeighborIds for a node at the depth limit is never hit, because it is never asked") {
    val error = ProcessingError("graph", "neighbours of a failed")
    val graph = new Fixture(tree, failNeighbours = Map("a" -> error))
    graph.visitedIds("root", TraversalConfig(maxDepth = 1)) shouldBe Seq("root", "a", "b")
  }

  test("a failure stops the traversal: nothing after the failing node is looked up") {
    val error = ProcessingError("graph", "neighbours of root failed")
    val graph = new Fixture(tree, failNeighbours = Map("root" -> error))
    graph.bfs("root") shouldBe Left(error)
    graph.neighbourCalls.map(_._1) shouldBe Seq("root")
  }

  // ---- scale

  test("a long chain is traversed without exhausting the stack") {
    val length = 20000
    val chain  = (0 until length).map(i => s"n$i" -> Seq(s"n${i + 1}")).toMap + (s"n$length" -> Seq.empty[String])
    val graph  = new Fixture(chain)
    val result = graph.visitedIds("n0")
    (result should have).length((length + 1).toLong)
    result.head shouldBe "n0"
    result.last shouldBe s"n$length"
  }

  test("a wide star is traversed once per node, in listed order") {
    val width  = 20000
    val leaves = (0 until width).map(i => s"leaf$i")
    val graph  = new Fixture(Map("hub" -> leaves))
    graph.visitedIds("hub") shouldBe ("hub" +: leaves)
  }
}
