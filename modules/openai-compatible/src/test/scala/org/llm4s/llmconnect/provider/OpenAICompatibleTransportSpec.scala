package org.llm4s.llmconnect.provider

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

/** How `OpenAICompatibleClient` uses its HTTP transport: closing it, and the headers it sends. */
class OpenAICompatibleTransportSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val settings = OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", "http://localhost:1/v1", None))

  "OpenAICompatibleClient" should "close its HTTP client when it is closed" in {
    val closed = new AtomicBoolean(false)
    val transport = new org.llm4s.http.JdkHttpClient(None) {
      override def close(): Unit = closed.set(true)
    }
    val client = new OpenAICompatibleClient(settings, OpenAICompatibleDialect.Standard) {
      override protected[provider] val httpClient: Llm4sHttpClient = transport
    }
    client.close()
    closed.get shouldBe true
  }

  "OpenAICompatibleClient.combineRepeated" should "send a repeated header once, its values comma-joined in order" in {
    val combined = OpenAICompatibleClient.combineRepeated(
      Seq("X-Feature" -> "a", "X-Other" -> "1", "x-feature" -> "b", "X-Feature" -> "c")
    )
    combined.toMap shouldBe Map("X-Feature" -> "a, b, c", "X-Other" -> "1")
  }

  it should "leave distinct headers alone" in {
    OpenAICompatibleClient.combineRepeated(Seq("A" -> "1", "B" -> "2")).toMap shouldBe Map("A" -> "1", "B" -> "2")
  }
}
