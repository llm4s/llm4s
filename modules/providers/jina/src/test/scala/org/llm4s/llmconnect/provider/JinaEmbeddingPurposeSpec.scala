package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.read

/**
 * Jina's `task` and the request's purpose. With no explicit task the purpose decides (a document is a
 * passage, a query is a query); an explicit task is a deliberate choice and wins, because it can be
 * one the purpose cannot express.
 */
class JinaEmbeddingPurposeSpec extends AnyFlatSpec with Matchers {

  private val cfg = EmbeddingProviderConfig(baseUrl = "http://jina-test", model = "jina-embeddings-v3", apiKey = "k")
  private val ok  = HttpResponse(200, """{"data":[{"embedding":[0.1]}]}""", Map.empty)

  private def request(purpose: InputPurpose, model: String = "jina-embeddings-v3") =
    EmbeddingRequest(Seq("text"), EmbeddingModelConfig(model, 1024), purpose)

  /** The `task` field of the request the provider sent, if it sent one, and the response metadata. */
  private def sent(
    provider: MockHttpClient => EmbeddingProvider,
    req: EmbeddingRequest
  ): (Option[String], Map[String, String]) = {
    val http   = new MockHttpClient(ok)
    val result = provider(http).embed(req).toOption.get
    (read(http.lastBody.get).obj.get("task").map(_.str), result.metadata)
  }

  private val followingPurpose: MockHttpClient => EmbeddingProvider = http => JinaEmbeddingProvider.forTest(cfg, http)
  private def fixed(task: JinaTask): MockHttpClient => EmbeddingProvider =
    http => JinaEmbeddingProvider.forTest(cfg, http, task)

  "a provider with no explicit task" should "send retrieval.passage for a document" in {
    sent(followingPurpose, request(InputPurpose.Document))._1 shouldBe Some("retrieval.passage")
  }

  it should "send retrieval.query for a query" in {
    sent(followingPurpose, request(InputPurpose.Query))._1 shouldBe Some("retrieval.query")
  }

  it should "report the task it sent in the response metadata" in {
    sent(followingPurpose, request(InputPurpose.Query))._2("task") shouldBe "retrieval.query"
    sent(followingPurpose, request(InputPurpose.Document))._2("task") shouldBe "retrieval.passage"
  }

  it should "still send retrieval.passage when the request does not say, as every request did before" in {
    sent(followingPurpose, EmbeddingRequest(Seq("text"), EmbeddingModelConfig("jina-embeddings-v3", 1024)))._1 shouldBe
      Some("retrieval.passage")
  }

  "an explicit task" should "win over the purpose of the request, whichever it is" in {
    InputPurpose.values.foreach { purpose =>
      withClue(purpose.toString) {
        sent(fixed(JinaTask.TextMatching), request(purpose))._1 shouldBe Some("text-matching")
        sent(fixed(JinaTask.Classification), request(purpose))._1 shouldBe Some("classification")
      }
    }
  }

  it should "win even when it contradicts the purpose" in {
    sent(fixed(JinaTask.RetrievalPassage), request(InputPurpose.Query))._1 shouldBe Some("retrieval.passage")
    sent(fixed(JinaTask.RetrievalQuery), request(InputPurpose.Document))._1 shouldBe Some("retrieval.query")
  }

  "the model" should "still decide whether a task is sent: jina-embeddings-v2 has no task field" in {
    InputPurpose.values.foreach { purpose =>
      sent(followingPurpose, request(purpose, "jina-embeddings-v2-base-en"))._1 shouldBe None
    }
  }

  it should "send a query to jina-clip-v2 as retrieval.query and leave a document unset" in {
    sent(followingPurpose, request(InputPurpose.Query, "jina-clip-v2"))._1 shouldBe Some("retrieval.query")
    sent(followingPurpose, request(InputPurpose.Document, "jina-clip-v2"))._1 shouldBe None
  }

  it should "accept both purposes on jina-embeddings-v4, whose retrieval tasks they map to" in {
    sent(followingPurpose, request(InputPurpose.Query, "jina-embeddings-v4"))._1 shouldBe Some("retrieval.query")
    sent(followingPurpose, request(InputPurpose.Document, "jina-embeddings-v4"))._1 shouldBe Some("retrieval.passage")
  }

  "JinaTask.forPurpose" should "map each purpose to a distinct retrieval task, and a document to the default" in {
    JinaTask.forPurpose(InputPurpose.Document) shouldBe JinaTask.RetrievalPassage
    JinaTask.forPurpose(InputPurpose.Query) shouldBe JinaTask.RetrievalQuery
    JinaTask.forPurpose(InputPurpose.Document) shouldBe JinaTask.default
  }
}
