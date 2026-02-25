package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Completion
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.error.ProcessingError

class MultiDocumentGraphBuilderTest extends AnyFunSuite with Matchers with MockFactory {

  private def makeCompletion(content: String): Completion = Completion(
    id = "test-id",
    content = content,
    model = "test-model",
    toolCalls = Nil,
    created = 1234567890L,
    message = org.llm4s.llmconnect.model.AssistantMessage(Some(content), Nil),
    usage = None
  )

  private val schema = ExtractionSchema.simple(
    entityTypes = Seq("Person", "Organization"),
    relationshipTypes = Seq("WORKS_FOR")
  )

  test("MultiDocumentGraphBuilder should extract single document with schema") {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      schema = Some(schema),
      enableCoreference = false,
      enableEntityLinking = false
    )
    val builder = new MultiDocumentGraphBuilder(llmClient, config)
    val source  = DocumentSource("doc-1", "Test Doc")

    val extractionResponse =
      """{
        |  "nodes": [
        |    {"id": "1", "label": "Person", "properties": {"name": "Alice"}},
        |    {"id": "2", "label": "Organization", "properties": {"name": "Acme"}}
        |  ],
        |  "edges": [
        |    {"source": "1", "target": "2", "relationship": "WORKS_FOR"}
        |  ]
        |}""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(extractionResponse)))

    val result = builder.extractDocument("Alice works at Acme", source)

    result should be(a[Right[_, _]])
    val tracked = result.toOption.get
    tracked.graph.nodes should have size 2
    tracked.graph.edges should have size 1
    tracked.sources should have size 1
    tracked.sources.head.id shouldBe "doc-1"
    tracked.nodeSources("1") shouldBe Set("doc-1")
  }

  test("MultiDocumentGraphBuilder should extract without schema using free-form extraction") {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      enableCoreference = false,
      enableEntityLinking = false
    )
    val builder = new MultiDocumentGraphBuilder(llmClient, config)
    val source  = DocumentSource("doc-1", "Test Doc")

    val extractionResponse =
      """{
        |  "nodes": [
        |    {"id": "1", "label": "Person", "properties": {"name": "Alice"}}
        |  ],
        |  "edges": []
        |}""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(extractionResponse)))

    val result = builder.extractDocument("Alice is a person.", source)

    result should be(a[Right[_, _]])
    result.toOption.get.graph.nodes should have size 1
  }

  test("MultiDocumentGraphBuilder should extract multiple documents and merge") {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      schema = Some(schema),
      enableCoreference = false,
      enableEntityLinking = false
    )
    val builder = new MultiDocumentGraphBuilder(llmClient, config)

    val doc1Response =
      """{
        |  "nodes": [
        |    {"id": "1", "label": "Person", "properties": {"name": "Alice"}},
        |    {"id": "2", "label": "Organization", "properties": {"name": "Acme"}}
        |  ],
        |  "edges": [
        |    {"source": "1", "target": "2", "relationship": "WORKS_FOR"}
        |  ]
        |}""".stripMargin

    val doc2Response =
      """{
        |  "nodes": [
        |    {"id": "3", "label": "Person", "properties": {"name": "Bob"}},
        |    {"id": "4", "label": "Organization", "properties": {"name": "Beta Inc"}}
        |  ],
        |  "edges": [
        |    {"source": "3", "target": "4", "relationship": "WORKS_FOR"}
        |  ]
        |}""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(doc1Response)))

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(doc2Response)))

    val documents = Seq(
      ("Alice works at Acme", DocumentSource("doc-1", "Doc 1")),
      ("Bob works at Beta Inc", DocumentSource("doc-2", "Doc 2"))
    )

    val result = builder.extractDocuments(documents)

    result should be(a[Right[_, _]])
    val tracked = result.toOption.get
    tracked.graph.nodes should have size 4
    tracked.graph.edges should have size 2
    tracked.sources should have size 2
  }

  test("MultiDocumentGraphBuilder should build incrementally on existing graph") {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      schema = Some(schema),
      enableCoreference = false,
      enableEntityLinking = false
    )
    val builder = new MultiDocumentGraphBuilder(llmClient, config)

    // Existing graph
    val existingGraph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"))),
        "2" -> Node("2", "Organization", Map("name" -> ujson.Str("Acme")))
      ),
      List(Edge("1", "2", "WORKS_FOR"))
    )
    val existingTracked = SourceTrackedGraph.fromGraph(existingGraph, DocumentSource("doc-1", "Doc 1"))

    // New document
    val newDocResponse =
      """{
        |  "nodes": [
        |    {"id": "3", "label": "Person", "properties": {"name": "Bob"}}
        |  ],
        |  "edges": []
        |}""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(newDocResponse)))

    val result = builder.extractDocuments(
      Seq(("Bob is a person.", DocumentSource("doc-2", "Doc 2"))),
      existingGraph = Some(existingTracked)
    )

    result should be(a[Right[_, _]])
    val tracked = result.toOption.get
    tracked.graph.nodes should have size 3 // Alice + Acme + Bob
    tracked.graph.edges should have size 1 // Original edge preserved
    tracked.sources should have size 2
  }

  test("MultiDocumentGraphBuilder should run coreference resolution when enabled") {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      schema = Some(schema),
      enableCoreference = true,
      enableEntityLinking = false
    )
    val builder = new MultiDocumentGraphBuilder(llmClient, config)
    val source  = DocumentSource("doc-1", "Test Doc")

    // First call: coreference resolution
    val resolvedText = "Alice works at Acme. Alice is the CEO."
    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(resolvedText)))

    // Second call: extraction
    val extractionResponse =
      """{
        |  "nodes": [
        |    {"id": "1", "label": "Person", "properties": {"name": "Alice"}}
        |  ],
        |  "edges": []
        |}""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(extractionResponse)))

    val result = builder.extractDocument("Alice works at Acme. She is the CEO.", source)

    result should be(a[Right[_, _]])
  }

  test("MultiDocumentGraphBuilder should propagate extraction errors") {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      enableCoreference = false,
      enableEntityLinking = false
    )
    val builder = new MultiDocumentGraphBuilder(llmClient, config)

    val error = ProcessingError("llm_error", "LLM request failed")
    (llmClient.complete _)
      .expects(*, *)
      .returning(Left(error))

    val result = builder.extractDocument("test", DocumentSource("doc-1"))

    result should be(a[Left[_, _]])
  }

  test("MultiDocumentGraphBuilder should stop on first document failure in multi-doc extraction") {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      enableCoreference = false,
      enableEntityLinking = false
    )
    val builder = new MultiDocumentGraphBuilder(llmClient, config)

    val error = ProcessingError("llm_error", "LLM request failed")
    (llmClient.complete _)
      .expects(*, *)
      .returning(Left(error))

    val documents = Seq(
      ("failing doc", DocumentSource("doc-1")),
      ("should not run", DocumentSource("doc-2"))
    )

    val result = builder.extractDocuments(documents)

    result should be(a[Left[_, _]])
  }

  test("MultiDocumentGraphBuilder should apply entity linking on merged result") {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      schema = Some(schema),
      enableCoreference = false,
      enableEntityLinking = true, // Deterministic linking enabled
      llmDisambiguation = false
    )
    val builder = new MultiDocumentGraphBuilder(llmClient, config)

    // Two docs mention the same person "Alice"
    val doc1Response =
      """{
        |  "nodes": [
        |    {"id": "alice-1", "label": "Person", "properties": {"name": "Alice"}},
        |    {"id": "acme", "label": "Organization", "properties": {"name": "Acme"}}
        |  ],
        |  "edges": [
        |    {"source": "alice-1", "target": "acme", "relationship": "WORKS_FOR"}
        |  ]
        |}""".stripMargin

    val doc2Response =
      """{
        |  "nodes": [
        |    {"id": "alice-2", "label": "Person", "properties": {"name": "Alice"}},
        |    {"id": "beta", "label": "Organization", "properties": {"name": "Beta Inc"}}
        |  ],
        |  "edges": [
        |    {"source": "alice-2", "target": "beta", "relationship": "WORKS_FOR"}
        |  ]
        |}""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(doc1Response)))

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(doc2Response)))

    val documents = Seq(
      ("Alice works at Acme", DocumentSource("doc-1")),
      ("Alice works at Beta Inc", DocumentSource("doc-2"))
    )

    val result = builder.extractDocuments(documents)

    result should be(a[Right[_, _]])
    val tracked = result.toOption.get
    // After entity linking, the two "Alice" nodes should be merged into one
    tracked.graph.nodes.values.count(_.label == "Person") shouldBe 1
    tracked.graph.nodes.values.count(_.label == "Organization") shouldBe 2
  }

  test("MultiDocumentGraphBuilder should track source provenance across documents") {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      schema = Some(schema),
      enableCoreference = false,
      enableEntityLinking = false
    )
    val builder = new MultiDocumentGraphBuilder(llmClient, config)

    val doc1Response =
      """{
        |  "nodes": [
        |    {"id": "1", "label": "Person", "properties": {"name": "Alice"}}
        |  ],
        |  "edges": []
        |}""".stripMargin

    val doc2Response =
      """{
        |  "nodes": [
        |    {"id": "1", "label": "Person", "properties": {"name": "Alice"}},
        |    {"id": "2", "label": "Organization", "properties": {"name": "Acme"}}
        |  ],
        |  "edges": [
        |    {"source": "1", "target": "2", "relationship": "WORKS_FOR"}
        |  ]
        |}""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(doc1Response)))

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(doc2Response)))

    val doc1 = DocumentSource("doc-1", "Doc 1")
    val doc2 = DocumentSource("doc-2", "Doc 2")
    val documents = Seq(
      ("Alice is a person.", doc1),
      ("Alice works at Acme.", doc2)
    )

    val result = builder.extractDocuments(documents)

    result should be(a[Right[_, _]])
    val tracked = result.toOption.get

    // Node "1" (Alice) — each doc gets its own namespaced copy until entity linking
    tracked.nodeSources("doc-1__1") shouldBe Set("doc-1")
    tracked.nodeSources("doc-2__1") shouldBe Set("doc-2")
    // Node "2" (Acme) only in doc-2
    tracked.nodeSources("doc-2__2") shouldBe Set("doc-2")
    // Edge only in doc-2, using namespaced IDs
    tracked.edgeSources(("doc-2__1", "doc-2__2", "WORKS_FOR")) shouldBe Set("doc-2")
  }
}
