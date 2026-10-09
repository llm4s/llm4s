package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * OpenAI's embedding models embed a query and a document alike, so the purpose of a request must not
 * reach the wire: the body sent is the same for both, and carries no `input_type` or `task`.
 */
class OpenAIEmbeddingPurposeSpec extends AnyFlatSpec with Matchers {

  private val model = EmbeddingModelConfig("text-embedding-3-small", 2)

  private def bodiesFor(purpose: InputPurpose): List[ujson.Value] = {
    val seen = new ConcurrentLinkedQueue[ujson.Value]()
    withServer("/v1/embeddings") { (ex: HttpExchange) =>
      seen.add(ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
      sendJsonResponse(ex, 200, """{"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]}]}""")
    } { baseUrl =>
      val provider = OpenAIEmbeddingProvider.fromConfig(EmbeddingProviderConfig(baseUrl, model.name, "key"))
      provider.embed(EmbeddingRequest(Seq("a", "b"), model, purpose)).isRight shouldBe true
    }
    seen.asScala.toList
  }

  "OpenAIEmbeddingProvider" should "send the same request for a query as for a document" in {
    val doc   = bodiesFor(InputPurpose.Document)
    val query = bodiesFor(InputPurpose.Query)

    doc should have size 1
    query shouldBe doc
  }

  it should "send no field that names the purpose" in {
    val keys = bodiesFor(InputPurpose.Query).flatMap(_.obj.keys)

    keys should not contain "input_type"
    keys should not contain "task"
    keys should not contain "purpose"
  }
}
