package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Completion
import org.llm4s.error.ProcessingError

class KnowledgeGraphGeneratorTest extends AnyFunSuite with Matchers with MockFactory {

  test("KnowledgeGraphGenerator should extract graph from LLM response") {
    val llmClient = mock[LLMClient]
    val generator = new KnowledgeGraphGenerator(llmClient)

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
    val graph = result.toOption.get

    (graph.nodes should contain).key("1")
    graph.nodes("1").properties("name").str shouldBe "Alice"
    graph.edges should have size 1
    graph.edges.head.relationship shouldBe "KNOWS"
  }

  test("KnowledgeGraphGenerator should handle JSON without markdown") {
    val llmClient = mock[LLMClient]
    val generator = new KnowledgeGraphGenerator(llmClient)

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
    val llmClient = mock[LLMClient]
    val generator = new KnowledgeGraphGenerator(llmClient)

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
    val llmClient = mock[LLMClient]
    val generator = new KnowledgeGraphGenerator(llmClient)

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
    val llmClient = mock[LLMClient]
    val generator = new KnowledgeGraphGenerator(llmClient)

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
    val llmClient = mock[LLMClient]
    val generator = new KnowledgeGraphGenerator(llmClient)

    val error = ProcessingError("llm_error", "LLM request failed")
    (llmClient.complete _)
      .expects(*, *)
      .returning(Left(error))

    val result = generator.extract("test")
    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe error
  }

  test("KnowledgeGraphGenerator should handle nodes without properties") {
    val llmClient = mock[LLMClient]
    val generator = new KnowledgeGraphGenerator(llmClient)

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
    result.toOption.get.nodes("1").properties shouldBe empty
  }

  test("KnowledgeGraphGenerator should handle edges without properties") {
    val llmClient = mock[LLMClient]
    val generator = new KnowledgeGraphGenerator(llmClient)

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
    result.toOption.get.edges.head.properties shouldBe empty
  }
}
