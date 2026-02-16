package org.llm4s.knowledgegraph.engine

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }

class GraphEngineTest extends AnyFunSuite with Matchers {

  // Helper to build a test graph
  val testGraph = {
    val nodes = Map(
      "1" -> Node("1", "A"),
      "2" -> Node("2", "B"),
      "3" -> Node("3", "C"),
      "4" -> Node("4", "D"),
      "5" -> Node("5", "E")
    )
    val edges = List(
      Edge("1", "2", "REL"),
      Edge("1", "3", "REL"),
      Edge("2", "4", "REL"),
      Edge("3", "4", "REL"),
      Edge("4", "5", "REL")
    )
    Graph(nodes, edges)
  }

  test("traverse with BFS should visit nodes up to maxDepth") {
    val engine  = new GraphEngine(testGraph)
    val visited = engine.traverse("1", maxDepth = 1)

    visited.map(_.id) should contain theSameElementsAs Set("1", "2", "3")
  }

  test("traverse should return empty set for non-existent start node") {
    val engine  = new GraphEngine(testGraph)
    val visited = engine.traverse("999", maxDepth = 5)

    visited shouldBe empty
  }

  test("traverse with filter should exclude filtered nodes") {
    val engine  = new GraphEngine(testGraph)
    val filter  = (node: Node, _: Edge) => node.id != "3"
    val visited = engine.traverse("1", maxDepth = 2, filter)

    visited.map(_.id) should not contain "3"
    visited.map(_.id) should contain("1")
    visited.map(_.id) should contain("2")
  }

  test("traverseDFS should visit nodes depth-first") {
    val engine  = new GraphEngine(testGraph)
    val visited = engine.traverseDFS("1", maxDepth = 2)

    visited should have size 4 // 1, 2, 3, 4
    visited.map(_.id) should contain theSameElementsAs Set("1", "2", "3", "4")
  }

  test("traverseDFS should return empty for non-existent node") {
    val engine  = new GraphEngine(testGraph)
    val visited = engine.traverseDFS("999", maxDepth = 5)

    visited shouldBe empty
  }

  test("traverseDFS with filter should respect filter predicate") {
    val engine  = new GraphEngine(testGraph)
    val filter  = (node: Node, _: Edge) => node.label != "C"
    val visited = engine.traverseDFS("1", maxDepth = 2, filter)

    visited.map(_.label) should not contain "C"
  }

  test("findShortestPath should find path between connected nodes") {
    val engine = new GraphEngine(testGraph)
    val path   = engine.findShortestPath("1", "5")

    path should be(defined)
    path.get should have size 3 // 1->2->4->5 or 1->3->4->5
    path.get.last.target shouldBe "5"
  }

  test("findShortestPath should return None for disconnected nodes") {
    val disconnectedGraph = Graph(
      Map("1" -> Node("1", "A"), "2" -> Node("2", "B")),
      List.empty
    )
    val engine = new GraphEngine(disconnectedGraph)
    val path   = engine.findShortestPath("1", "2")

    path shouldBe None
  }

  test("findShortestPath should return None for non-existent nodes") {
    val engine = new GraphEngine(testGraph)

    engine.findShortestPath("999", "1") shouldBe None
    engine.findShortestPath("1", "999") shouldBe None
  }

  test("findShortestPath should handle same start and end node") {
    val engine = new GraphEngine(testGraph)
    val path   = engine.findShortestPath("1", "1")

    path should be(defined)
    path.get shouldBe empty
  }

  test("findAllPaths should find multiple paths") {
    val engine = new GraphEngine(testGraph)
    val paths  = engine.findAllPaths("1", "4", maxLength = 3)

    paths.size should be >= 2 // At least two paths: 1->2->4 and 1->3->4
    paths.foreach { path =>
      path.head.source shouldBe "1"
      path.last.target shouldBe "4"
    }
  }

  test("findAllPaths should respect maxLength") {
    val engine = new GraphEngine(testGraph)
    val paths  = engine.findAllPaths("1", "5", maxLength = 2)

    paths shouldBe empty // No path from 1 to 5 with length <= 2
  }

  test("findAllPaths should return empty for non-existent nodes") {
    val engine = new GraphEngine(testGraph)
    val paths  = engine.findAllPaths("999", "1", maxLength = 5)

    paths shouldBe empty
  }

  test("findAllPaths should return empty for disconnected nodes") {
    val disconnectedGraph = Graph(
      Map("1" -> Node("1", "A"), "2" -> Node("2", "B")),
      List.empty
    )
    val engine = new GraphEngine(disconnectedGraph)
    val paths  = engine.findAllPaths("1", "2", maxLength = 5)

    paths shouldBe empty
  }

  test("getNodesAtDistance should return nodes at exact distance") {
    val engine       = new GraphEngine(testGraph)
    val nodesAtDist1 = engine.getNodesAtDistance("1", 1)

    nodesAtDist1.map(_.id) should contain theSameElementsAs Set("2", "3")
  }

  test("getNodesAtDistance should return empty for distance 0") {
    val engine       = new GraphEngine(testGraph)
    val nodesAtDist0 = engine.getNodesAtDistance("1", 0)

    nodesAtDist0.map(_.id) should contain only "1"
  }

  test("getNodesAtDistance should handle unreachable distances") {
    val engine         = new GraphEngine(testGraph)
    val nodesAtDist999 = engine.getNodesAtDistance("1", 999)

    nodesAtDist999 shouldBe empty
  }
}
