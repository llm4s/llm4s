package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }

class DocumentSourceTest extends AnyFunSuite with Matchers {

  test("DocumentSource should store id, title, and metadata") {
    val source = DocumentSource("doc-1", "Annual Report 2025", Map("author" -> "Alice", "year" -> "2025"))

    source.id shouldBe "doc-1"
    source.title shouldBe "Annual Report 2025"
    source.metadata should have size 2
    source.metadata("author") shouldBe "Alice"
  }

  test("DocumentSource should default to empty title and metadata") {
    val source = DocumentSource("doc-1")
    source.title shouldBe ""
    source.metadata shouldBe empty
  }

  test("SourceTrackedGraph.empty should create an empty tracked graph") {
    val tracked = SourceTrackedGraph.empty

    tracked.graph shouldBe Graph.empty
    tracked.sources shouldBe empty
    tracked.nodeSources shouldBe empty
    tracked.edgeSources shouldBe empty
  }

  test("SourceTrackedGraph.fromGraph should wrap a graph with single-source provenance") {
    val source = DocumentSource("doc-1", "Test Document")
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"))),
        "2" -> Node("2", "Organization", Map("name" -> ujson.Str("Acme")))
      ),
      List(Edge("1", "2", "WORKS_FOR"))
    )

    val tracked = SourceTrackedGraph.fromGraph(graph, source)

    tracked.graph shouldBe graph
    tracked.sources should have size 1
    tracked.sources.head shouldBe source
    tracked.nodeSources("1") shouldBe Set("doc-1")
    tracked.nodeSources("2") shouldBe Set("doc-1")
    tracked.edgeSources(("1", "2", "WORKS_FOR")) shouldBe Set("doc-1")
  }

  test("SourceTrackedGraph.addDocument should merge graphs and track provenance") {
    val doc1 = DocumentSource("doc-1", "Document 1")
    val graph1 = Graph(
      Map("1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice")))),
      List.empty
    )
    val tracked = SourceTrackedGraph.fromGraph(graph1, doc1)

    val doc2 = DocumentSource("doc-2", "Document 2")
    val graph2 = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"))),
        "3" -> Node("3", "Organization", Map("name" -> ujson.Str("Acme")))
      ),
      List(Edge("1", "3", "WORKS_FOR"))
    )

    val updated = tracked.addDocument(graph2, doc2)

    // doc2's nodes are namespaced as "doc-2__1" and "doc-2__3" to avoid
    // overwriting doc1's "1" node. Entity linking merges them afterwards.
    updated.graph.nodes should have size 3 // doc-1__1, doc-2__1, doc-2__3
    updated.graph.edges should have size 1
    updated.sources should have size 2
    updated.nodeSources("doc-2__1") shouldBe Set("doc-2")
    updated.nodeSources("doc-2__3") shouldBe Set("doc-2")
    updated.edgeSources(("doc-2__1", "doc-2__3", "WORKS_FOR")) shouldBe Set("doc-2")
  }

  test("SourceTrackedGraph.addDocument should not duplicate sources") {
    val doc1    = DocumentSource("doc-1", "Document 1")
    val graph1  = Graph(Map("1" -> Node("1", "Person")), List.empty)
    val tracked = SourceTrackedGraph.fromGraph(graph1, doc1)

    val graph2  = Graph(Map("2" -> Node("2", "Person")), List.empty)
    val updated = tracked.addDocument(graph2, doc1) // Same source again

    updated.sources should have size 1
  }

  test("SourceTrackedGraph.getNodeSources should return document sources for a node") {
    val doc1  = DocumentSource("doc-1", "Document 1")
    val doc2  = DocumentSource("doc-2", "Document 2")
    val graph = Graph(Map("1" -> Node("1", "Person")), List.empty)
    val tracked = SourceTrackedGraph(
      graph = graph,
      sources = Seq(doc1, doc2),
      nodeSources = Map("1" -> Set("doc-1", "doc-2")),
      edgeSources = Map.empty
    )

    val sources = tracked.getNodeSources("1")
    sources should have size 2
    sources should contain(doc1)
    sources should contain(doc2)
  }

  test("SourceTrackedGraph.getNodeSources should return empty for unknown node") {
    val tracked = SourceTrackedGraph.empty
    tracked.getNodeSources("unknown") shouldBe empty
  }

  test("SourceTrackedGraph.getEdgeSources should return document sources for an edge") {
    val doc1 = DocumentSource("doc-1", "Document 1")
    val graph = Graph(
      Map("1" -> Node("1", "Person"), "2" -> Node("2", "Organization")),
      List(Edge("1", "2", "WORKS_FOR"))
    )
    val tracked = SourceTrackedGraph(
      graph = graph,
      sources = Seq(doc1),
      nodeSources = Map("1" -> Set("doc-1"), "2" -> Set("doc-1")),
      edgeSources = Map(("1", "2", "WORKS_FOR") -> Set("doc-1"))
    )

    val sources = tracked.getEdgeSources("1", "2", "WORKS_FOR")
    sources should have size 1
    sources should contain(doc1)
  }

  test("SourceTrackedGraph.getEdgeSources should return empty for unknown edge") {
    val tracked = SourceTrackedGraph.empty
    tracked.getEdgeSources("1", "2", "KNOWS") shouldBe empty
  }

  test("SourceTrackedGraph.withGraph should replace graph preserving provenance") {
    val doc1    = DocumentSource("doc-1", "Document 1")
    val graph1  = Graph(Map("1" -> Node("1", "Person")), List.empty)
    val tracked = SourceTrackedGraph.fromGraph(graph1, doc1)

    val newGraph = Graph(
      Map("1" -> Node("1", "Person"), "2" -> Node("2", "Organization")),
      List.empty
    )
    val updated = tracked.withGraph(newGraph)

    updated.graph shouldBe newGraph
    updated.sources shouldBe tracked.sources
    updated.nodeSources shouldBe tracked.nodeSources
  }

  test("SourceTrackedGraph.withUpdatedNodeSources should replace node source mappings") {
    val tracked = SourceTrackedGraph.empty
    val updated = tracked.withUpdatedNodeSources(Map("1" -> Set("doc-1")))

    updated.nodeSources shouldBe Map("1" -> Set("doc-1"))
  }

  test("SourceTrackedGraph.withUpdatedEdgeSources should replace edge source mappings") {
    val tracked = SourceTrackedGraph.empty
    val updated = tracked.withUpdatedEdgeSources(Map(("1", "2", "KNOWS") -> Set("doc-1")))

    updated.edgeSources shouldBe Map(("1", "2", "KNOWS") -> Set("doc-1"))
  }

  test("SchemaViolation should store description and optional entity ID") {
    val violation = SchemaViolation("Invalid source type", Some("node-1"), "invalid_source_type")

    violation.description shouldBe "Invalid source type"
    violation.entityId shouldBe Some("node-1")
    violation.violationType shouldBe "invalid_source_type"
  }

  test("ValidationResult.isFullyValid should return true for valid result") {
    val result = ValidationResult(Graph.empty, List.empty, List.empty, List.empty)
    result.isFullyValid shouldBe true
  }

  test("ValidationResult.isFullyValid should return false when violations exist") {
    val result = ValidationResult(
      Graph.empty,
      List.empty,
      List.empty,
      List(SchemaViolation("error"))
    )
    result.isFullyValid shouldBe false
  }

  test("ValidationResult.isFullyValid should return false when out-of-schema nodes exist") {
    val result = ValidationResult(
      Graph.empty,
      List(Node("1", "Unknown")),
      List.empty,
      List.empty
    )
    result.isFullyValid shouldBe false
  }
}
