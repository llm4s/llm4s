package org.llm4s.llmconnect.provider

import org.llm4s.error.NetworkError
import org.llm4s.error.TroubleshootingLinks.Entry
import org.llm4s.error.{ AuthenticationError, LLMError, RateLimitError, ServiceError, TroubleshootingLinks }
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.ServerSocket
import scala.util.Using

/**
 * Ollama's 401, 429 and 404, and a refused connection, say the next step and link their troubleshooting entry
 * (#2285), fed from a local server. A 404 is a model the server has not pulled, so it names the pull command.
 */
final class OllamaTroubleshootingLinksSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val hello = Conversation(Seq(UserMessage("hello")))

  /** The error a call fails with when the server answers `status` with `body`. */
  private def failure(status: Int, body: String): LLMError = {
    var result: Option[LLMError] = None
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes(): Unit
      exchange.getResponseHeaders.add("x-should-retry", "false") // an SDK must not retry on its own
      LocalProviderTestServer.sendJsonResponse(exchange, status, body)
    } { baseUrl =>
      result = Some(call(baseUrl).fold(identity, completion => fail(s"expected HTTP $status to fail, got $completion")))
    }
    result.getOrElse(fail("the server was not called"))
  }

  private def assertLinked(error: LLMError, sentence: String, entry: Entry): Unit = {
    error.message should include(sentence)
    error.message should include(TroubleshootingLinks.forId(entry))
  }

  private def config(baseUrl: String): OllamaConfig =
    OllamaConfig(model = "llama3", baseUrl = baseUrl, contextWindow = 4096, reserveCompletion = 512)

  private def call(baseUrl: String): Result[org.llm4s.llmconnect.model.Completion] =
    new OllamaClient(config(baseUrl)).complete(hello, CompletionOptions())

  "Ollama replying 401" should {
    "say to check the key's project or organisation, and link #authentication-failed" in {
      val error = failure(401, """{"error":"unauthorized"}""")
      error shouldBe an[AuthenticationError]
      assertLinked(error, TroubleshootingLinks.AuthenticationAction, Entry.AuthenticationFailed)
    }
  }

  "Ollama replying 429" should {
    "say to lower concurrency or use ReliableClient, and link #rate-limited" in {
      val error = failure(429, """{"error":"too many requests"}""")
      error shouldBe a[RateLimitError]
      assertLinked(error, TroubleshootingLinks.RateLimitAction, Entry.RateLimited)
    }
  }

  "Ollama replying 404" should {
    "say to pull the model, and link #ollama-model-missing" in {
      val error = failure(404, """{"error":"model \"llama3\" not found, try pulling it first"}""")
      error shouldBe a[ServiceError]
      assertLinked(error, TroubleshootingLinks.ollamaModelMissingAction("llama3"), Entry.OllamaModelMissing)
      error.message should include("not found, try pulling it first")
    }
  }

  "A base URL nothing listens on" should {
    "say to start `ollama serve`, and link #ollama-not-running, when streaming too" in {
      val port   = Using.resource(new ServerSocket(0))(_.getLocalPort)
      val client = new OllamaClient(config(s"http://127.0.0.1:$port"))
      val errors = Seq(
        client.complete(hello, CompletionOptions()),
        client.streamComplete(hello, CompletionOptions(), _ => ())
      ).map(_.left.toOption.getOrElse(fail("expected the call to fail")))
      errors.foreach { error =>
        error shouldBe a[NetworkError]
        error.message should include("connection failed")
        assertLinked(error, TroubleshootingLinks.OllamaNotRunningAction, Entry.OllamaNotRunning)
      }
    }
  }
}
