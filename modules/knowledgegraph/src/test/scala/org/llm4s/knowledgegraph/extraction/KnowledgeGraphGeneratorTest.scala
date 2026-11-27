package org.llm4s.knowledgegraph.extraction

import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Completion
import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.knowledgegraph.storage.{ GraphStore, InMemoryGraphStore }
import java.time.Instant

class KnowledgeGraphGeneratorTest extends AnyFunSuite with Matchers with MockFactory {

  test("KnowledgeGraphGenerator should extract graph from LLM response") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val jsonResponse =
      """
        |```json
        |{
        |  "nodes": [
        |    {"id": "1", "label": "Person", "properties": {"name": "Alice"}},
        |    {"id": "2", "label": "Person", "properties": {"name": "Bob"}}
        |  ],
        |  "edges": [
        |    {"source": "1", "target": "2", "relationship": "KNOWS", "properties": {}}
        |  ]
        |}
        |```
        |""".stripMargin

    val completion = Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("Alice knows Bob", DocumentSource("doc"))

    result should be(a[Right[_, _]])

    // Verify data was written to store
    val node1 = graphStore.getNode("doc__1")
    node1 should be(a[Right[_, _]])
    node1.toOption.get.isDefined shouldBe true
    node1.toOption.get.get.properties("name").str shouldBe "Alice"

    val graph = graphStore.loadAll().toOption.get
    graph.nodes should have size 2
    graph.edges should have size 1
    graph.edges.head.relationship shouldBe "KNOWS"
  }

  test("KnowledgeGraphGenerator should handle JSON without markdown") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val jsonResponse = """{"nodes": [], "edges": []}"""
    val completion = Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test", DocumentSource("doc"))
    result should be(a[Right[_, _]])
  }

  test("KnowledgeGraphGenerator should fail on invalid JSON") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val badJson = "not valid json {"
    val completion = Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(badJson), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test", DocumentSource("doc"))
    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe a[ProcessingError]
  }

  test("KnowledgeGraphGenerator should fail on missing 'nodes' field") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val jsonMissingNodes = """{"edges": []}"""
    val completion = Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonMissingNodes), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test", DocumentSource("doc"))
    result should be(a[Left[_, _]])
  }

  test("KnowledgeGraphGenerator should fail on missing 'edges' field") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val jsonMissingEdges = """{"nodes": []}"""
    val completion = Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonMissingEdges), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test", DocumentSource("doc"))
    result should be(a[Left[_, _]])
  }

  test("KnowledgeGraphGenerator should propagate LLM errors") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val error = ProcessingError("llm_error", "LLM request failed")
    (llmClient.complete _)
      .expects(*, *)
      .returning(Left(error))

    val result = generator.extract("test", DocumentSource("doc"))
    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe error
  }

  test("KnowledgeGraphGenerator should handle nodes without properties") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val jsonResponse =
      """
        |{
        |  "nodes": [
        |    {"id": "1", "label": "Person"}
        |  ],
        |  "edges": []
        |}
        |""".stripMargin

    val completion = Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test", DocumentSource("doc"))
    result should be(a[Right[_, _]])

    val node1 = graphStore.getNode("doc__1").toOption.get.get
    node1.properties shouldBe empty
  }

  test("KnowledgeGraphGenerator should handle edges without properties") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val jsonResponse =
      """
        |{
        |  "nodes": [
        |    {"id": "1", "label": "Person"},
        |    {"id": "2", "label": "Person"}
        |  ],
        |  "edges": [
        |    {"source": "1", "target": "2", "relationship": "KNOWS"}
        |  ]
        |}
        |""".stripMargin

    val completion = Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test", DocumentSource("doc"))
    result should be(a[Right[_, _]])

    val graph = graphStore.loadAll().toOption.get
    graph.edges.head.properties shouldBe empty
  }

  test("KnowledgeGraphGenerator should propagate node upsert failures") {
    val llmClient  = mock[LLMClient]
    val graphStore = mock[GraphStore]
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val jsonResponse =
      """
        |{
        |  "nodes": [
        |    {"id": "1", "label": "Person", "properties": {"name": "Alice"}}
        |  ],
        |  "edges": []
        |}
        |""".stripMargin

    val completion = Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    // Mock graphStore to fail on upsertNode
    val testError = ProcessingError("test-store", "Node upsert failed")
    (graphStore.upsertNode _)
      .expects(*)
      .returning(Left(testError))

    val result = generator.extract("test", DocumentSource("doc"))

    result should be(a[Left[_, _]])
    result.swap.toOption.get shouldBe testError
  }

  test("KnowledgeGraphGenerator should propagate edge upsert failures") {
    val llmClient  = mock[LLMClient]
    val graphStore = mock[GraphStore]
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val jsonResponse =
      """
        |{
        |  "nodes": [
        |    {"id": "1", "label": "Person"},
        |    {"id": "2", "label": "Person"}
        |  ],
        |  "edges": [
        |    {"source": "1", "target": "2", "relationship": "KNOWS"}
        |  ]
        |}
        |""".stripMargin

    val completion = Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    // Mock graphStore to succeed on upsertNode but fail on upsertEdge
    (graphStore.upsertNode _)
      .expects(*)
      .returning(Right(()))
      .twice()

    val testError = ProcessingError("test-store", "Edge upsert failed")
    (graphStore.upsertEdge _)
      .expects(*)
      .returning(Left(testError))

    val result = generator.extract("test", DocumentSource("doc"))

    result should be(a[Left[_, _]])
    result.swap.toOption.get shouldBe testError
  }

  private def completionOf(json: String): Completion =
    Completion(
      id = "test-id",
      model = ModelName("test-model"),
      created = Instant.ofEpochSecond(1234567890L),
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(json), Nil),
      usage = None
    )

  test("two extract calls on one store keep both documents' entities and edges (#2966)") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    (llmClient.complete _)
      .expects(*, *)
      .returning(
        Right(
          completionOf(
            """{"nodes": [{"id": "1", "label": "Person", "properties": {"name": "Alice"}},
              |           {"id": "2", "label": "Org", "properties": {"name": "Acme"}}],
              | "edges": [{"source": "1", "target": "2", "relationship": "WORKS_FOR"}]}""".stripMargin
          )
        )
      )
      .noMoreThanOnce()
    (llmClient.complete _)
      .expects(*, *)
      .returning(
        Right(
          completionOf(
            """{"nodes": [{"id": "1", "label": "Person", "properties": {"name": "Bob"}},
              |           {"id": "2", "label": "City", "properties": {"name": "Paris"}}],
              | "edges": [{"source": "1", "target": "2", "relationship": "LIVES_IN"}]}""".stripMargin
          )
        )
      )
      .noMoreThanOnce()

    val a = generator.extract("Alice works for Acme.", DocumentSource("a"))
    val b = generator.extract("Bob lives in Paris.", DocumentSource("b"))
    a.map(_.nodes.keySet) shouldBe Right(Set("a__1", "a__2"))
    b.map(_.edges.map(e => (e.source, e.target))) shouldBe Right(List(("b__1", "b__2")))

    val stored = graphStore.loadAll().toOption.get
    stored.nodes.view.mapValues(_.properties("name").str).toMap shouldBe Map(
      "a__1" -> "Alice",
      "a__2" -> "Acme",
      "b__1" -> "Bob",
      "b__2" -> "Paris"
    )
    stored.edges.map(e => (e.source, e.relationship, e.target)).toSet shouldBe Set(
      ("a__1", "WORKS_FOR", "a__2"),
      ("b__1", "LIVES_IN", "b__2")
    )
  }

  test("extract refuses a blank source id before calling the model or the store") {
    val llmClient  = mock[LLMClient]
    val graphStore = mock[GraphStore]
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    generator.extract("text", DocumentSource("  ")) should matchPattern { case Left(_: ValidationError) => }
  }

  test("no edge is written once a node write has failed (#2963)") {
    val llmClient  = mock[LLMClient]
    val graphStore = mock[GraphStore]
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    (llmClient.complete _)
      .expects(*, *)
      .returning(
        Right(
          completionOf(
            """{"nodes": [{"id": "1", "label": "Person"}, {"id": "2", "label": "Person"}],
              | "edges": [{"source": "1", "target": "2", "relationship": "KNOWS"}]}""".stripMargin
          )
        )
      )
    val testError = ProcessingError("test-store", "Node upsert failed")
    (graphStore.upsertNode _).expects(*).returning(Right(())).noMoreThanOnce()
    (graphStore.upsertNode _).expects(*).returning(Left(testError)).noMoreThanOnce()
    (graphStore.upsertEdge _).expects(*).never()

    generator.extract("test", DocumentSource("doc")) shouldBe Left(testError)
  }

  test("FreeFormExtractor returns the model's graph and touches no store") {
    val llmClient = mock[LLMClient]
    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completionOf("""{"nodes": [{"id": "1", "label": "Person"}], "edges": []}""")))

    new FreeFormExtractor(llmClient).extract("text").map(_.nodes.keySet) shouldBe Right(Set("1"))
  }
}
