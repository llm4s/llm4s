package org.llm4s.knowledgegraph.query

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.knowledgegraph.{ Edge, Node }
import org.llm4s.knowledgegraph.storage.InMemoryGraphStore
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.error.ProcessingError

class GraphQAPipelineTest extends AnyFunSuite with Matchers with MockFactory {

  private def buildTestStore(): InMemoryGraphStore = {
    val store = new InMemoryGraphStore()
    store.upsertNode(Node("alice", "Person", Map("name" -> ujson.Str("Alice"), "role" -> ujson.Str("Engineer"))))
    store.upsertNode(Node("bob", "Person", Map("name" -> ujson.Str("Bob"), "role" -> ujson.Str("Manager"))))
    store.upsertNode(Node("acme", "Organization", Map("name" -> ujson.Str("Acme Corp"))))
    store.upsertNode(Node("nyc", "Location", Map("name" -> ujson.Str("New York City"))))
    store.upsertEdge(Edge("alice", "acme", "WORKS_FOR"))
    store.upsertEdge(Edge("bob", "acme", "WORKS_FOR"))
    store.upsertEdge(Edge("alice", "bob", "KNOWS"))
    store.upsertEdge(Edge("acme", "nyc", "LOCATED_IN"))
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

  test("ask should produce an answer with citations") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()
    val pipeline  = new GraphQAPipeline(llmClient, store, GraphQAConfig(useRanking = false))

    // First LLM call: entity identification
    val entityResponse = """[{"mention": "Alice", "label": "Person"}]"""
    // Second LLM call: query translation
    val queryResponse = """{"type": "describe_node", "node_id": "alice", "include_neighbors": true}"""
    // Third LLM call: answer generation
    val answerResponse = "Alice is an Engineer who works for Acme Corp and knows Bob."

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

    val result = pipeline.ask("Tell me about Alice")

    result should be(a[Right[_, _]])
    val qaResult = result.toOption.get
    qaResult.answer should include("Alice")
    qaResult.entities should not be empty
    qaResult.citations should not be empty
    qaResult.queryResult.nodes should not be empty
  }

  test("askWithQuery should use provided query instead of translating") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()
    val pipeline  = new GraphQAPipeline(llmClient, store, GraphQAConfig(useRanking = false))

    // First LLM call: entity identification
    val entityResponse = """[{"mention": "Alice", "label": "Person"}]"""
    // Second LLM call: answer generation (no query translation needed)
    val answerResponse = "Alice is a Person who works at Acme Corp."

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(entityResponse)))
      .once()

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(answerResponse)))
      .once()

    val query  = GraphQuery.FindNodes(label = Some("Person"))
    val result = pipeline.askWithQuery("Who are the people?", query)

    result should be(a[Right[_, _]])
    val qaResult = result.toOption.get
    qaResult.answer should include("Alice")
    qaResult.queryResult.nodes should have size 2 // Alice and Bob
  }

  test("ask should handle entity identification failure gracefully") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()
    val pipeline  = new GraphQAPipeline(llmClient, store, GraphQAConfig(useRanking = false))

    // Entity identification returns invalid JSON - should fallback to empty entities
    val entityResponse = "not valid json"
    val queryResponse  = """{"type": "find_nodes", "label": "Person"}"""
    val answerResponse = "Found 2 people: Alice and Bob."

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

    val result = pipeline.ask("Who are all the people?")

    result should be(a[Right[_, _]])
    result.toOption.get.entities shouldBe empty
    result.toOption.get.answer should include("Found 2 people")
  }

  test("ask should propagate LLM errors during query translation") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()
    val pipeline  = new GraphQAPipeline(llmClient, store)

    // Entity identification succeeds
    val entityResponse = """[{"mention": "Alice", "label": "Person"}]"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(entityResponse)))
      .once()

    // Query translation fails with LLM error
    val error = ProcessingError("llm_error", "LLM request failed")
    (llmClient.complete _)
      .expects(*, *)
      .returning(Left(error))
      .once()

    val result = pipeline.ask("Tell me about Alice")

    result should be(a[Left[_, _]])
  }

  test("buildContext should format nodes and edges correctly") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()
    val pipeline  = new GraphQAPipeline(llmClient, store)

    val queryResult = GraphQueryResult(
      nodes = Seq(
        Node("alice", "Person", Map("name" -> ujson.Str("Alice"))),
        Node("acme", "Organization", Map("name" -> ujson.Str("Acme Corp")))
      ),
      edges = Seq(Edge("alice", "acme", "WORKS_FOR"))
    )

    val context = pipeline.buildContext(queryResult)

    context should include("[Person]")
    context should include("alice")
    context should include("[Organization]")
    context should include("WORKS_FOR")
  }

  test("buildContext should include paths when present") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()
    val pipeline  = new GraphQAPipeline(llmClient, store)

    val queryResult = GraphQueryResult(
      nodes = Seq(Node("a", "A"), Node("b", "B"), Node("c", "C")),
      edges = Seq(Edge("a", "b", "R1"), Edge("b", "c", "R2")),
      paths = Seq(List(Edge("a", "b", "R1"), Edge("b", "c", "R2")))
    )

    val context = pipeline.buildContext(queryResult)

    context should include("Path 1")
  }

  test("ask with ranking should limit context nodes") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()

    // Use very small context limits to test ranking/trimming
    val config   = GraphQAConfig(maxContextNodes = 2, useRanking = true)
    val pipeline = new GraphQAPipeline(llmClient, store, config)

    val entityResponse = """[]"""
    val queryResponse  = """{"type": "find_nodes"}"""
    val answerResponse = "Answer based on ranked entities."

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

    val result = pipeline.ask("Get everything")

    result should be(a[Right[_, _]])
    // Result should be trimmed to maxContextNodes
    result.toOption.get.queryResult.nodes.size should be <= 2
  }

  test("identifyEntities should resolve mentions to graph nodes") {
    val llmClient = mock[LLMClient]
    val store     = buildTestStore()
    val pipeline  = new GraphQAPipeline(llmClient, store)

    val entityResponse = """[{"mention": "Alice", "label": "Person"}]"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(entityResponse)))
      .once()

    val result = pipeline.identifyEntities("Tell me about Alice")

    result should be(a[Right[_, _]])
    val entities = result.toOption.get
    entities should not be empty
    entities.head.mention shouldBe "Alice"
    entities.head.nodeId shouldBe "alice"
  }
}
