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
 * Ollama's embedding models embed a query and a document alike, so the purpose of a request must not
 * reach the wire: the requests sent are the same for both, and carry no `input_type` or `task`.
 */
class OllamaEmbeddingPurposeSpec extends AnyFlatSpec with Matchers {

  private val model = EmbeddingModelConfig("nomic-embed-text", 2)

  private def bodiesFor(purpose: InputPurpose): List[ujson.Value] = {
    val seen = new ConcurrentLinkedQueue[ujson.Value]()
    withServer("/api/embeddings") { (ex: HttpExchange) =>
      seen.add(ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
      sendJsonResponse(ex, 200, """{"embedding":[1.0, 2.0]}""")
    } { baseUrl =>
      val provider = OllamaEmbeddingProvider.fromConfig(EmbeddingProviderConfig(baseUrl, model.name, "not-required"))
      provider.embed(EmbeddingRequest(Seq("first", "second"), model, purpose)).isRight shouldBe true
    }
    seen.asScala.toList
  }

  "OllamaEmbeddingProvider" should "send the same requests for a query as for a document" in {
    val doc   = bodiesFor(InputPurpose.Document)
    val query = bodiesFor(InputPurpose.Query)

    doc should have size 2
    query shouldBe doc
  }

  it should "send no field that names the purpose" in {
    val keys = bodiesFor(InputPurpose.Query).flatMap(_.obj.keys)

    keys should not contain "input_type"
    keys should not contain "task"
    keys should not contain "purpose"
  }
}
