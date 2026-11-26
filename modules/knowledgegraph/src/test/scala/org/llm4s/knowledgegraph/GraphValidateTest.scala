package org.llm4s.knowledgegraph

import org.llm4s.error.ProcessingError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GraphValidateTest extends AnyFunSuite with Matchers {

  private def validationError(graph: Graph): ProcessingError =
    graph.validate() match {
      case Left(error: ProcessingError) => error
      case other                        => fail(s"Expected a ProcessingError, got: $other")
    }

  test("Graph.validate accepts an empty graph, nodes without edges, and edges with existing endpoints") {
    Graph.empty.validate() shouldBe Right(())
    Graph(Map("a" -> Node("a", "A")), List.empty).validate() shouldBe Right(())

    val valid = Graph.empty
      .addNode(Node("a", "A"))
      .addNode(Node("b", "B"))
      .addEdge(Edge("a", "b", "REL"))

    valid.validate() shouldBe Right(())
  }

  test("Graph.validate reports a missing source") {
    val graph = Graph(Map("a" -> Node("a", "A")), List(Edge("x", "a", "REL")))

    val error = validationError(graph)

    error.operation shouldBe "graph_integrity_violation"
    error.message should include(
      "Graph contains 1 edge(s) with missing endpoints: Edge(x->a): missing nodes: source=x"
    )
  }

  test("Graph.validate reports a missing target without naming a source") {
    val graph = Graph(Map("a" -> Node("a", "A")), List(Edge("a", "y", "REL")))

    val error = validationError(graph)

    error.operation shouldBe "graph_integrity_violation"
    error.message should include("Edge(a->y): missing nodes: target=y")
    (error.message should not).include("source=")
  }

  test("Graph.validate reports an edge with both endpoints missing") {
    val error = validationError(Graph.empty.addEdge(Edge("x", "y", "REL")))

    error.operation shouldBe "graph_integrity_violation"
    error.message should include("Edge(x->y): missing nodes: source=x, target=y")
  }

  test("Graph.validate counts every bad edge but describes only the first five") {
    val edges = (1 to 7).map(i => Edge(s"s$i", s"t$i", "REL")).toList
    val error = validationError(Graph(Map.empty, edges))

    error.operation shouldBe "graph_integrity_violation"
    error.message should include("Graph contains 7 edge(s) with missing endpoints")
    "Edge\\(".r.findAllIn(error.message).length shouldBe 5
    (error.message should not).include("Edge(s6->t6)")
  }
}
