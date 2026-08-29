package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Completion
import org.llm4s.error.ProcessingError
import org.llm4s.knowledgegraph.Graph

class SchemaGuidedExtractorTest extends AnyFunSuite with Matchers with MockFactory {

  private val schema = ExtractionSchema.simple(
    entityTypes = Seq("Person", "Organization"),
    relationshipTypes = Seq("WORKS_FOR", "FOUNDED"),
    properties = Map("Person" -> Seq("role"))
  )

  private def makeCompletion(content: String): Completion = Completion(
    id = "test-id",
    content = content,
    model = "test-model",
    toolCalls = Nil,
    created = 1234567890L,
    message = org.llm4s.llmconnect.model.AssistantMessage(Some(content), Nil),
    usage = None
  )

  test("SchemaGuidedExtractor should extract graph with schema-constrained types") {
    val llmClient = mock[LLMClient]
    val extractor = new SchemaGuidedExtractor(llmClient)

    val jsonResponse =
      """```json
        |{
        |  "nodes": [
        |    {"id": "1", "label": "Person", "properties": {"name": "Alice", "role": "CEO"}},
        |    {"id": "2", "label": "Organization", "properties": {"name": "Acme Corp"}}
        |  ],
        |  "edges": [
        |    {"source": "1", "target": "2", "relationship": "WORKS_FOR"}
        |  ]
        |}
        |```""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(jsonResponse)))

    val result = extractor.extract("Alice is the CEO of Acme Corp", schema)

    result should be(a[Right[_, _]])
    val graph = result.toOption.get
    graph.nodes should have size 2
    graph.nodes("1").label shouldBe "Person"
    graph.nodes("1").properties("role").str shouldBe "CEO"
    graph.edges should have size 1
    graph.edges.head.relationship shouldBe "WORKS_FOR"
  }

  test("SchemaGuidedExtractor should handle plain JSON without markdown") {
    val llmClient = mock[LLMClient]
    val extractor = new SchemaGuidedExtractor(llmClient)

    val jsonResponse = """{"nodes": [], "edges": []}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(jsonResponse)))

    val result = extractor.extract("test", schema)
    result should be(a[Right[_, _]])
    result.toOption.get shouldBe Graph.empty
  }

  test("SchemaGuidedExtractor should fail on invalid JSON") {
    val llmClient = mock[LLMClient]
    val extractor = new SchemaGuidedExtractor(llmClient)

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion("not valid json {")))

    val result = extractor.extract("test", schema)
    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe a[ProcessingError]
  }

  test("SchemaGuidedExtractor should fail on missing nodes field") {
    val llmClient = mock[LLMClient]
    val extractor = new SchemaGuidedExtractor(llmClient)

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion("""{"edges": []}""")))

    val result = extractor.extract("test", schema)
    result should be(a[Left[_, _]])
  }

  test("SchemaGuidedExtractor should fail on missing edges field") {
    val llmClient = mock[LLMClient]
    val extractor = new SchemaGuidedExtractor(llmClient)

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion("""{"nodes": []}""")))

    val result = extractor.extract("test", schema)
    result should be(a[Left[_, _]])
  }

  test("SchemaGuidedExtractor should propagate LLM errors") {
    val llmClient = mock[LLMClient]
    val extractor = new SchemaGuidedExtractor(llmClient)

    val error = ProcessingError("llm_error", "LLM request failed")
    (llmClient.complete _)
      .expects(*, *)
      .returning(Left(error))

    val result = extractor.extract("test", schema)
    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe error
  }

  test("SchemaGuidedExtractor should handle nodes and edges without properties") {
    val llmClient = mock[LLMClient]
    val extractor = new SchemaGuidedExtractor(llmClient)

    val jsonResponse =
      """{
        |  "nodes": [
        |    {"id": "1", "label": "Person"},
        |    {"id": "2", "label": "Organization"}
        |  ],
        |  "edges": [
        |    {"source": "1", "target": "2", "relationship": "WORKS_FOR"}
        |  ]
        |}""".stripMargin

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(jsonResponse)))

    val result = extractor.extract("test", schema)
    result should be(a[Right[_, _]])
    result.toOption.get.nodes("1").properties shouldBe empty
    result.toOption.get.edges.head.properties shouldBe empty
  }
}
