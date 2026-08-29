package org.llm4s.knowledgegraph.tool

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.knowledgegraph.{ Edge, Node }
import org.llm4s.knowledgegraph.query.GraphQAConfig
import org.llm4s.knowledgegraph.storage.InMemoryGraphStore
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.toolapi.{ ToolCallRequest, ToolRegistry }

class GraphQueryToolTest extends AnyFunSuite with Matchers with MockFactory {

  private def buildTestStore(): InMemoryGraphStore = {
    val store = new InMemoryGraphStore()
    store.upsertNode(Node("alice", "Person", Map("name" -> ujson.Str("Alice"), "role" -> ujson.Str("Engineer"))))
    store.upsertNode(Node("bob", "Person", Map("name" -> ujson.Str("Bob"))))
    store.upsertNode(Node("acme", "Organization", Map("name" -> ujson.Str("Acme Corp"))))
    store.upsertEdge(Edge("alice", "acme", "WORKS_FOR"))
    store.upsertEdge(Edge("alice", "bob", "KNOWS"))
    store
  }

  private def makeCompletion(content: String): Completion = Completion(
    id = "test-id",
    content = content,
    model = "test-model",
    toolCalls = Nil,
    created = 1234567890L,
    message = AssistantMessage(Some(content), Nil),
    usage = None
  )

  test("toolSafe should create a valid tool") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()

    val result = GraphQueryTool.toolSafe(llmClient, store)

    result should be(a[Right[_, _]])
    val tool = result.toOption.get
    tool.name shouldBe "graph_query"
    tool.description should include("knowledge graph")
  }

  test("tool should be registrable in ToolRegistry") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()

    val tool = GraphQueryTool.toolSafe(llmClient, store)
    tool should be(a[Right[_, _]])

    val registry = new ToolRegistry(Seq(tool.toOption.get))
    registry.getTool("graph_query") should be(defined)
  }

  test("tool should execute via ToolRegistry with question parameter") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()
    val config    = GraphQAConfig(useRanking = false)

    // Entity identification response
    val entityResponse = """[{"mention": "Alice", "label": "Person"}]"""
    // Query translation response
    val queryResponse = """{"type": "describe_node", "node_id": "alice", "include_neighbors": true}"""
    // Answer generation response
    val answerResponse = "Alice is an Engineer at Acme Corp."

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(entityResponse)))
      .once()

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(queryResponse)))
      .once()

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(answerResponse)))
      .once()

    val tool     = GraphQueryTool.toolSafe(llmClient, store, config).toOption.get
    val registry = new ToolRegistry(Seq(tool))

    val args   = ujson.Obj("question" -> ujson.Str("Tell me about Alice"))
    val result = registry.execute(ToolCallRequest("graph_query", args))

    result should be(a[Right[_, _]])
    val json = result.toOption.get
    json("answer").str should include("Alice")
    json("entityCount").num.toInt should be >= 0
  }

  test("tool schema should include question as required parameter") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()

    val tool   = GraphQueryTool.toolSafe(llmClient, store).toOption.get
    val schema = tool.toOpenAITool()

    val functionSchema = schema("function")
    functionSchema("name").str shouldBe "graph_query"

    val params   = functionSchema("parameters")
    val required = params("required").arr.map(_.str)
    required should contain("question")
  }

  test("GraphQueryToolResult should serialize to JSON correctly") {
    val result = GraphQueryToolResult(
      answer = "Test answer",
      citations = Seq("[Person] alice (name: Alice)"),
      entityCount = 1
    )

    val json = upickle.default.writeJs(result)
    json("answer").str shouldBe "Test answer"
    json("citations").arr should have size 1
    json("entityCount").num.toInt shouldBe 1
  }
}
