package org.llm4s.knowledgegraph.query

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.knowledgegraph.{ Edge, Node }
import org.llm4s.knowledgegraph.storage.{ Direction, InMemoryGraphStore }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.error.ProcessingError

class GraphQueryTranslatorTest extends AnyFunSuite with Matchers with MockFactory {

  private def buildTestStore(): InMemoryGraphStore = {
    val store = new InMemoryGraphStore()
    store.upsertNode(Node("alice", "Person", Map("name" -> ujson.Str("Alice"))))
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

  test("translate should parse find_nodes response") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val jsonResponse = """{"type": "find_nodes", "label": "Person", "properties": {"name": "Alice"}}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(jsonResponse)))

    val result = translator.translate("Find Alice")

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe a[GraphQuery.FindNodes]
    val query = result.toOption.get.asInstanceOf[GraphQuery.FindNodes]
    query.label shouldBe Some("Person")
    query.properties shouldBe Map("name" -> "Alice")
  }

  test("translate should parse find_neighbors response") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val jsonResponse = """{"type": "find_neighbors", "node_id": "alice", "direction": "outgoing", "max_depth": 2}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(jsonResponse)))

    val result = translator.translate("Who does Alice know?")

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe a[GraphQuery.FindNeighbors]
    val query = result.toOption.get.asInstanceOf[GraphQuery.FindNeighbors]
    query.nodeId shouldBe "alice"
    query.direction shouldBe Direction.Outgoing
    query.maxDepth shouldBe 2
  }

  test("translate should parse find_path response") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val jsonResponse = """{"type": "find_path", "from_node_id": "alice", "to_node_id": "bob", "max_hops": 3}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(jsonResponse)))

    val result = translator.translate("How is Alice connected to Bob?")

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe a[GraphQuery.FindPath]
    val query = result.toOption.get.asInstanceOf[GraphQuery.FindPath]
    query.fromNodeId shouldBe "alice"
    query.toNodeId shouldBe "bob"
    query.maxHops shouldBe 3
  }

  test("translate should parse describe_node response") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val jsonResponse = """{"type": "describe_node", "node_id": "alice", "include_neighbors": true}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(jsonResponse)))

    val result = translator.translate("Tell me about Alice")

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe a[GraphQuery.DescribeNode]
    val query = result.toOption.get.asInstanceOf[GraphQuery.DescribeNode]
    query.nodeId shouldBe "alice"
    query.includeNeighbors shouldBe true
  }

  test("translate should parse composite response") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val jsonResponse =
      """
        |{
        |  "type": "composite",
        |  "steps": [
        |    {"type": "find_nodes", "label": "Person"},
        |    {"type": "find_neighbors", "node_id": "alice", "direction": "both"}
        |  ]
        |}
        |""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(jsonResponse)))

    val result = translator.translate("Get all people and Alice's connections")

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe a[GraphQuery.CompositeQuery]
    val query = result.toOption.get.asInstanceOf[GraphQuery.CompositeQuery]
    query.steps should have size 2
  }

  test("translate should handle markdown-wrapped JSON") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val jsonResponse =
      """```json
        |{"type": "find_nodes", "label": "Person"}
        |```""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(jsonResponse)))

    val result = translator.translate("Find all people")

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe a[GraphQuery.FindNodes]
  }

  test("translate should fail on invalid JSON") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion("not valid json {")))

    val result = translator.translate("test")

    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe a[ProcessingError]
  }

  test("translate should fail on unknown query type") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion("""{"type": "unknown_query"}""")))

    val result = translator.translate("test")

    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe a[ProcessingError]
  }

  test("translate should propagate LLM errors") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val error = ProcessingError("llm_error", "LLM request failed")
    (llmClient.complete _)
      .expects(*, *)
      .returning(Left(error))

    val result = translator.translate("test")

    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe error
  }

  test("buildSchemaContext should include node labels and relationship types") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val schemaResult = translator.buildSchemaContext()

    schemaResult should be(a[Right[_, _]])
    val schema = schemaResult.toOption.get
    schema should include("Person")
    schema should include("Organization")
    schema should include("WORKS_FOR")
    schema should include("KNOWS")
  }

  test("parseQueryResponse should handle find_neighbors without optional fields") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val result = translator.parseQueryResponse("""{"type": "find_neighbors", "node_id": "alice"}""")

    result should be(a[Right[_, _]])
    val query = result.toOption.get.asInstanceOf[GraphQuery.FindNeighbors]
    query.nodeId shouldBe "alice"
    query.direction shouldBe Direction.Both
    query.maxDepth shouldBe 1
    query.relationshipType shouldBe None
  }

  test("parseQueryResponse should fail when find_neighbors missing node_id") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val result = translator.parseQueryResponse("""{"type": "find_neighbors"}""")

    result should be(a[Left[_, _]])
  }

  test("parseQueryResponse should fail when find_path missing required fields") {
    val llmClient  = mock[LLMClient]
    val store      = buildTestStore()
    val translator = new GraphQueryTranslator(llmClient, store)

    val result = translator.parseQueryResponse("""{"type": "find_path", "from_node_id": "alice"}""")

    result should be(a[Left[_, _]])
  }
}
