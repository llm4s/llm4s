package org.llm4s.knowledgegraph

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.engine.GraphEngine

class GraphTest extends AnyFunSuite with Matchers {

  test("Graph should add nodes and edges correctly") {
    val node1 = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    val node2 = Node("2", "Person", Map("name" -> ujson.Str("Bob")))
    val edge  = Edge("1", "2", "KNOWS")

    val graph = Graph.empty
      .addNode(node1)
      .addNode(node2)
      .addEdge(edge)

    (graph.nodes should contain).key("1")
    (graph.nodes should contain).key("2")
    graph.edges should have size 1
    graph.edges.head shouldBe edge
  }

  test("Graph.getOutgoingEdges should return edges where node is source") {
    val graph = Graph(
      Map("1" -> Node("1", "A"), "2" -> Node("2", "B"), "3" -> Node("3", "C")),
      List(Edge("1", "2", "REL1"), Edge("1", "3", "REL2"), Edge("2", "3", "REL3"))
    )

    val outgoing = graph.getOutgoingEdges("1")
    outgoing should have size 2
    outgoing.map(_.target) should contain theSameElementsAs List("2", "3")
  }

  test("Graph.getIncomingEdges should return edges where node is target") {
    val graph = Graph(
      Map("1" -> Node("1", "A"), "2" -> Node("2", "B"), "3" -> Node("3", "C")),
      List(Edge("1", "3", "REL1"), Edge("2", "3", "REL2"))
    )

    val incoming = graph.getIncomingEdges("3")
    incoming should have size 2
    incoming.map(_.source) should contain theSameElementsAs List("1", "2")
  }

  test("Graph.getConnectedEdges should return all edges connected to node") {
    val graph = Graph(
      Map("1" -> Node("1", "A"), "2" -> Node("2", "B"), "3" -> Node("3", "C")),
      List(Edge("1", "2", "OUT"), Edge("3", "1", "IN"))
    )

    val connected = graph.getConnectedEdges("1")
    connected should have size 2
  }

  test("Graph.getNeighbors should return all connected nodes") {
    val n1 = Node("1", "A")
    val n2 = Node("2", "B")
    val n3 = Node("3", "C")
    val graph = Graph(
      Map("1" -> n1, "2" -> n2, "3" -> n3),
      List(Edge("1", "2", "REL1"), Edge("3", "1", "REL2"))
    )

    val neighbors = graph.getNeighbors("1")
    neighbors should contain theSameElementsAs Set(n2, n3)
  }

  test("Graph.hasNode should check node existence") {
    val graph = Graph(Map("1" -> Node("1", "A")), List.empty)

    graph.hasNode("1") shouldBe true
    graph.hasNode("999") shouldBe false
  }

  test("Graph.hasEdge should check edge existence") {
    val graph = Graph(
      Map("1" -> Node("1", "A"), "2" -> Node("2", "B")),
      List(Edge("1", "2", "KNOWS"))
    )

    graph.hasEdge("1", "2") shouldBe true
    graph.hasEdge("1", "2", Some("KNOWS")) shouldBe true
    graph.hasEdge("1", "2", Some("HATES")) shouldBe false
    graph.hasEdge("2", "1") shouldBe false
  }

  test("Graph.findNodesByLabel should find nodes with matching label") {
    val n1    = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    val n2    = Node("2", "Person", Map("name" -> ujson.Str("Bob")))
    val n3    = Node("3", "Organization")
    val graph = Graph(Map("1" -> n1, "2" -> n2, "3" -> n3), List.empty)

    val people = graph.findNodesByLabel("Person")
    people should have size 2
    people should contain theSameElementsAs List(n1, n2)
  }

  test("Graph.findNodesByProperty should find nodes with matching property") {
    val n1    = Node("1", "Person", Map("city" -> ujson.Str("NYC")))
    val n2    = Node("2", "Person", Map("city" -> ujson.Str("NYC")))
    val n3    = Node("3", "Person", Map("city" -> ujson.Str("LA")))
    val graph = Graph(Map("1" -> n1, "2" -> n2, "3" -> n3), List.empty)

    val nycPeople = graph.findNodesByProperty("city", "NYC")
    nycPeople should have size 2
    nycPeople should contain theSameElementsAs List(n1, n2)
  }

  test("Graph.merge should combine two graphs") {
    val g1 = Graph(Map("1" -> Node("1", "A")), List(Edge("1", "2", "REL")))
    val g2 = Graph(Map("2" -> Node("2", "B")), List(Edge("2", "3", "REL")))

    val merged = g1.merge(g2)
    merged.nodes should have size 2
    merged.edges should have size 2
  }

  test("GraphEngine should find shortest path") {
    val n1 = Node("1", "A")
    val n2 = Node("2", "B")
    val n3 = Node("3", "C")
    val n4 = Node("4", "D")

    val e1 = Edge("1", "2", "REL")
    val e2 = Edge("2", "3", "REL")
    val e3 = Edge("3", "4", "REL")
    val e4 = Edge("1", "3", "REL") // Shortcut

    val graph = Graph(
      Map("1" -> n1, "2" -> n2, "3" -> n3, "4" -> n4),
      List(e1, e2, e3, e4)
    )

    val engine = new GraphEngine(graph)
    val path   = engine.findShortestPath("1", "4")

    path should be(defined)
    path.get should have size 2 // 1->3->4 is length 2, 1->2->3->4 is length 3
    (path.get.map(_.target) should contain).theSameElementsInOrderAs(List("3", "4"))
  }

  test("GraphEngine traverse should visit nodes BFS") {
    val n1 = Node("1", "Root")
    val n2 = Node("2", "Child1")
    val n3 = Node("3", "Child2")

    val e1 = Edge("1", "2", "PARENT_OF")
    val e2 = Edge("1", "3", "PARENT_OF")

    val graph = Graph(
      Map("1" -> n1, "2" -> n2, "3" -> n3),
      List(e1, e2)
    )

    val engine = new GraphEngine(graph)
    val result = engine.traverse("1", maxDepth = 1)

    result should contain(n1)
    result should contain(n2)
    result should contain(n3)
  }
}
