package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.error.ProcessingError
import java.time.Instant

class MultiDocumentGraphBuilderTest extends AnyFunSuite with Matchers with MockFactory {

  private def makeCompletion(content: String): Completion = Completion(
    id = "test-id",
    content = content,
    model = "test-model",
    toolCalls = Nil,
    created = Instant.ofEpochSecond(1234567890L),
    message = org.llm4s.llmconnect.model.AssistantMessage(Some(content), Nil),
    usage = None
  )

  private val schema = ExtractionSchema.simple(
    entityTypes = Seq("Person", "Organization"),
    relationshipTypes = Seq("WORKS_FOR")
  )

  // A Person, an Organization and a Location, which the schema does not list, with an edge to each
  private val replyWithOutOfSchemaNode =
    """{
      |  "nodes": [
      |    {"id": "alice", "label": "Person", "properties": {"name": "Alice"}},
      |    {"id": "acme", "label": "Organization", "properties": {"name": "Acme"}},
      |    {"id": "paris", "label": "Location", "properties": {"name": "Paris"}}
      |  ],
      |  "edges": [
      |    {"source": "alice", "target": "acme", "relationship": "WORKS_FOR"},
      |    {"source": "alice", "target": "paris", "relationship": "WORKS_FOR"}
      |  ]
      |}""".stripMargin

  // "Jobs" and "Steve Jobs": not the same name, so only the LLM pass can merge them
  private val ambiguousReply =
    """{
      |  "nodes": [
      |    {"id": "jobs", "label": "Person", "properties": {"name": "Jobs"}},
      |    {"id": "steve", "label": "Person", "properties": {"name": "Steve Jobs"}}
      |  ],
      |  "edges": []
      |}""".stripMargin

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

  private def extractWithSchema(schema: ExtractionSchema, reply: String): SourceTrackedGraph = {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      schema = Some(schema),
      enableCoreference = false,
      enableEntityLinking = false
    )
    (llmClient.complete _).expects(*, *).returning(Right(makeCompletion(reply)))
    val result = new MultiDocumentGraphBuilder(llmClient, config).extractDocument("text", DocumentSource("doc-1"))
    result.fold(e => fail(e.message), identity)
  }

  test("MultiDocumentGraphBuilder should drop an out-of-schema node and the edges to it when the schema says so") {
    val strict  = ExtractionSchema.simple(Seq("Person", "Organization"), Seq("WORKS_FOR"), allowOutOfSchema = false)
    val tracked = extractWithSchema(strict, replyWithOutOfSchemaNode)

    tracked.graph.nodes.keySet shouldBe Set("alice", "acme")
    tracked.graph.edges shouldBe List(Edge("alice", "acme", "WORKS_FOR"))
  }

  test("MultiDocumentGraphBuilder should keep an out-of-schema node and its edges when the schema allows it") {
    val lenient = ExtractionSchema.simple(Seq("Person", "Organization"), Seq("WORKS_FOR"), allowOutOfSchema = true)
    val tracked = extractWithSchema(lenient, replyWithOutOfSchemaNode)

    tracked.graph.nodes.keySet shouldBe Set("alice", "acme", "paris")
    tracked.graph.nodes("paris").label shouldBe "Location"
    tracked.graph.edges should contain theSameElementsAs List(
      Edge("alice", "acme", "WORKS_FOR"),
      Edge("alice", "paris", "WORKS_FOR")
    )
  }

  test("MultiDocumentGraphBuilder should keep an in-schema edge whose endpoints break its type constraint") {
    // SchemaValidator reports the wrong source and target types as violations but leaves the edge in the
    // valid graph, and the builder only logs the violations.
    val typed = ExtractionSchema(
      entityTypes = Seq(EntityTypeDefinition("Person"), EntityTypeDefinition("Organization")),
      relationshipTypes = Seq(
        RelationshipTypeDefinition("WORKS_FOR", sourceTypes = Seq("Person"), targetTypes = Seq("Organization"))
      ),
      allowOutOfSchema = false
    )
    val backwards =
      """{
        |  "nodes": [
        |    {"id": "alice", "label": "Person", "properties": {"name": "Alice"}},
        |    {"id": "acme", "label": "Organization", "properties": {"name": "Acme"}}
        |  ],
        |  "edges": [
        |    {"source": "acme", "target": "alice", "relationship": "WORKS_FOR"}
        |  ]
        |}""".stripMargin
    val tracked = extractWithSchema(typed, backwards)

    tracked.graph.nodes.keySet shouldBe Set("alice", "acme")
    tracked.graph.edges shouldBe List(Edge("acme", "alice", "WORKS_FOR"))
  }

  // One Organization named "Acme" per document, under a different id in each
  private def extractAcmeTwice(enableEntityLinking: Boolean): SourceTrackedGraph = {
    val llmClient = mock[LLMClient]
    val config = ExtractionConfig(
      schema = Some(schema),
      enableCoreference = false,
      enableEntityLinking = enableEntityLinking
    )
    Seq("acme-1", "acme-2").foreach { id =>
      val reply =
        s"""{"nodes": [{"id": "$id", "label": "Organization", "properties": {"name": "Acme"}}], "edges": []}"""
      (llmClient.complete _).expects(*, *).returning(Right(makeCompletion(reply)))
    }
    val documents = Seq(("Acme.", DocumentSource("doc-1")), ("Acme again.", DocumentSource("doc-2")))
    new MultiDocumentGraphBuilder(llmClient, config).extractDocuments(documents).fold(e => fail(e.message), identity)
  }

  test("MultiDocumentGraphBuilder should keep same-name entities apart when entity linking is off") {
    val tracked = extractAcmeTwice(enableEntityLinking = false)

    tracked.graph.nodes.keySet shouldBe Set("doc-1__acme-1", "doc-2__acme-2")
    tracked.nodeSources("doc-1__acme-1") shouldBe Set("doc-1")
    tracked.nodeSources("doc-2__acme-2") shouldBe Set("doc-2")
  }

  test("MultiDocumentGraphBuilder should merge same-name entities into one node listing both documents") {
    val tracked = extractAcmeTwice(enableEntityLinking = true)

    // The lowest id survives
    tracked.graph.nodes.keySet shouldBe Set("doc-1__acme-1")
    tracked.nodeSources shouldBe Map("doc-1__acme-1" -> Set("doc-1", "doc-2"))
  }

  // The system prompt EntityLinker sends with its disambiguation request
  private def isDisambiguation(conversation: Conversation): Boolean =
    conversation.messages.exists(_.content.contains("entity disambiguation assistant"))

  // Registers the one extraction call, answered with ambiguousReply, then returns the builder
  private def builderExpectingExtraction(
    llmClient: LLMClient,
    llmDisambiguation: Boolean
  ): MultiDocumentGraphBuilder = {
    val config = ExtractionConfig(
      schema = Some(schema),
      enableCoreference = false,
      enableEntityLinking = true,
      llmDisambiguation = llmDisambiguation
    )
    (llmClient.complete _)
      .expects(where((c: Conversation, _: CompletionOptions) => !isDisambiguation(c)))
      .returning(Right(makeCompletion(ambiguousReply)))
    new MultiDocumentGraphBuilder(llmClient, config)
  }

  test("MultiDocumentGraphBuilder should ask the LLM about an ambiguous pair when LLM disambiguation is on") {
    val llmClient = mock[LLMClient]
    val builder   = builderExpectingExtraction(llmClient, llmDisambiguation = true)
    (llmClient.complete _)
      .expects(where { (c: Conversation, _: CompletionOptions) =>
        isDisambiguation(c) && c.messages.exists(m =>
          m.content.contains("\"jobs\"") && m.content.contains("\"steve jobs\"")
        )
      })
      .returning(Right(makeCompletion("""{"merges": [{"pair": 1, "merge": true}]}""")))
      .once()

    val result = builder.extractDocuments(Seq(("Steve Jobs. Jobs founded Apple.", DocumentSource("doc-1"))))

    result should be(a[Right[_, _]])
    // The LLM said merge: the node with the longer name is kept
    result.toOption.get.graph.nodes.keySet shouldBe Set("doc-1__steve")
  }

  test("MultiDocumentGraphBuilder should not ask the LLM about an ambiguous pair when LLM disambiguation is off") {
    val llmClient = mock[LLMClient]
    // Only the extraction call is expected: a disambiguation call would fail the test
    val result = builderExpectingExtraction(llmClient, llmDisambiguation = false)
      .extractDocuments(Seq(("Steve Jobs. Jobs founded Apple.", DocumentSource("doc-1"))))

    result should be(a[Right[_, _]])
    result.toOption.get.graph.nodes.keySet shouldBe Set("doc-1__jobs", "doc-1__steve")
  }

  test("ExtractionConfig() should have no schema, coreference and entity linking on, and LLM disambiguation off") {
    ExtractionConfig() shouldBe ExtractionConfig(
      schema = None,
      enableCoreference = true,
      enableEntityLinking = true,
      llmDisambiguation = false
    )
  }
}
