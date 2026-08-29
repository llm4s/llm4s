package org.llm4s.knowledgegraph.query

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.error.ProcessingError

class NativeQueryGeneratorTest extends AnyFunSuite with Matchers with MockFactory {

  private def makeCompletion(content: String): Completion = Completion(
    id = "test-id",
    content = content,
    model = "test-model",
    toolCalls = Nil,
    created = 1234567890L,
    message = AssistantMessage(Some(content), Nil),
    usage = None
  )

  test("generate should produce Cypher query from natural language") {
    val llmClient = mock[LLMClient]
    val generator = new NativeQueryGenerator(llmClient)

    val response =
      """{"query": "MATCH (p:Person {name: 'Alice'}) RETURN p", "explanation": "Find person named Alice"}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(response)))

    val result = generator.generate("Find Alice", QueryLanguage.Cypher)

    result should be(a[Right[_, _]])
    val nativeQuery = result.toOption.get
    nativeQuery.language shouldBe QueryLanguage.Cypher
    nativeQuery.queryString should include("MATCH")
    nativeQuery.queryString should include("Alice")
    nativeQuery.explanation should include("Find person")
  }

  test("generate should produce Gremlin query") {
    val llmClient = mock[LLMClient]
    val generator = new NativeQueryGenerator(llmClient)

    val response =
      """{"query": "g.V().hasLabel('Person').has('name','Alice')", "explanation": "Find person named Alice"}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(response)))

    val result = generator.generate("Find Alice", QueryLanguage.Gremlin)

    result should be(a[Right[_, _]])
    val nativeQuery = result.toOption.get
    nativeQuery.language shouldBe QueryLanguage.Gremlin
    nativeQuery.queryString should include("g.V()")
  }

  test("generate should produce SPARQL query") {
    val llmClient = mock[LLMClient]
    val generator = new NativeQueryGenerator(llmClient)

    val response =
      """{"query": "SELECT ?s WHERE { ?s rdf:type :Person ; :name 'Alice' }", "explanation": "Find Alice"}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(response)))

    val result = generator.generate("Find Alice", QueryLanguage.SPARQL)

    result should be(a[Right[_, _]])
    val nativeQuery = result.toOption.get
    nativeQuery.language shouldBe QueryLanguage.SPARQL
    nativeQuery.queryString should include("SELECT")
  }

  test("generate should handle markdown-wrapped JSON response") {
    val llmClient = mock[LLMClient]
    val generator = new NativeQueryGenerator(llmClient)

    val response =
      """```json
        |{"query": "MATCH (p:Person) RETURN p", "explanation": "Get all people"}
        |```""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(response)))

    val result = generator.generate("Get all people", QueryLanguage.Cypher)

    result should be(a[Right[_, _]])
    result.toOption.get.queryString should include("MATCH")
  }

  test("generate should pass schema context to prompt") {
    val llmClient = mock[LLMClient]
    val generator = new NativeQueryGenerator(llmClient)

    val response =
      """{"query": "MATCH (p:Person)-[:WORKS_FOR]->(o:Organization) RETURN p, o", "explanation": "Find employees"}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(response)))

    val schemaContext = "Node labels: Person, Organization. Relationships: WORKS_FOR"
    val result        = generator.generate("Who works where?", QueryLanguage.Cypher, schemaContext)

    result should be(a[Right[_, _]])
  }

  test("generate should fail on invalid JSON response") {
    val llmClient = mock[LLMClient]
    val generator = new NativeQueryGenerator(llmClient)

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion("not valid json")))

    val result = generator.generate("test", QueryLanguage.Cypher)

    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe a[ProcessingError]
  }

  test("generate should propagate LLM errors") {
    val llmClient = mock[LLMClient]
    val generator = new NativeQueryGenerator(llmClient)

    val error = ProcessingError("llm_error", "LLM request failed")
    (llmClient.complete _)
      .expects(*, *)
      .returning(Left(error))

    val result = generator.generate("test", QueryLanguage.Cypher)

    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe error
  }

  test("fromGraphQuery should convert structured query to native query") {
    val llmClient = mock[LLMClient]
    val generator = new NativeQueryGenerator(llmClient)

    val response =
      """{"query": "MATCH (p:Person {name: 'Alice'}) RETURN p", "explanation": "Find person Alice"}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(response)))

    val graphQuery = GraphQuery.FindNodes(label = Some("Person"), properties = Map("name" -> "Alice"))
    val result     = generator.fromGraphQuery(graphQuery, QueryLanguage.Cypher)

    result should be(a[Right[_, _]])
    result.toOption.get.queryString should include("Alice")
  }

  test("fromGraphQuery should handle composite queries") {
    val llmClient = mock[LLMClient]
    val generator = new NativeQueryGenerator(llmClient)

    val response =
      """{"query": "MATCH (p:Person)-[*1..2]-(n) RETURN p, n", "explanation": "Multi-step query"}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(response)))

    val graphQuery = GraphQuery.CompositeQuery(
      steps = Seq(
        GraphQuery.FindNodes(label = Some("Person")),
        GraphQuery.FindNeighbors(nodeId = "alice", maxDepth = 2)
      )
    )
    val result = generator.fromGraphQuery(graphQuery, QueryLanguage.Cypher)

    result should be(a[Right[_, _]])
  }

  test("QueryLanguage should have correct names") {
    QueryLanguage.Cypher.name shouldBe "Cypher"
    QueryLanguage.Gremlin.name shouldBe "Gremlin"
    QueryLanguage.SPARQL.name shouldBe "SPARQL"
  }
}
