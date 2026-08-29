package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Completion
import org.llm4s.error.ProcessingError
import org.llm4s.knowledgegraph.storage.{ GraphStore, InMemoryGraphStore }

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
      content = jsonResponse,
      model = "test-model",
      toolCalls = Nil,
      created = 1234567890L,
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("Alice knows Bob")

    result should be(a[Right[_, _]])

    // Verify data was written to store
    val node1 = graphStore.getNode("1")
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
      content = jsonResponse,
      model = "test-model",
      toolCalls = Nil,
      created = 1234567890L,
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test")
    result should be(a[Right[_, _]])
  }

  test("KnowledgeGraphGenerator should fail on invalid JSON") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val badJson = "not valid json {"
    val completion = Completion(
      id = "test-id",
      content = badJson,
      model = "test-model",
      toolCalls = Nil,
      created = 1234567890L,
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(badJson), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test")
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
      content = jsonMissingNodes,
      model = "test-model",
      toolCalls = Nil,
      created = 1234567890L,
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonMissingNodes), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test")
    result should be(a[Left[_, _]])
  }

  test("KnowledgeGraphGenerator should fail on missing 'edges' field") {
    val llmClient  = mock[LLMClient]
    val graphStore = new InMemoryGraphStore()
    val generator  = new KnowledgeGraphGenerator(llmClient, graphStore)

    val jsonMissingEdges = """{"nodes": []}"""
    val completion = Completion(
      id = "test-id",
      content = jsonMissingEdges,
      model = "test-model",
      toolCalls = Nil,
      created = 1234567890L,
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonMissingEdges), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test")
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

    val result = generator.extract("test")
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
      content = jsonResponse,
      model = "test-model",
      toolCalls = Nil,
      created = 1234567890L,
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test")
    result should be(a[Right[_, _]])

    val node1 = graphStore.getNode("1").toOption.get.get
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
      content = jsonResponse,
      model = "test-model",
      toolCalls = Nil,
      created = 1234567890L,
      message = org.llm4s.llmconnect.model.AssistantMessage(Some(jsonResponse), Nil),
      usage = None
    )

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(completion))

    val result = generator.extract("test")
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
      content = jsonResponse,
      model = "test-model",
      toolCalls = Nil,
      created = 1234567890L,
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

    val result = generator.extract("test")

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
      content = jsonResponse,
      model = "test-model",
      toolCalls = Nil,
      created = 1234567890L,
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

    val result = generator.extract("test")

    result should be(a[Left[_, _]])
    result.swap.toOption.get shouldBe testError
  }
}
