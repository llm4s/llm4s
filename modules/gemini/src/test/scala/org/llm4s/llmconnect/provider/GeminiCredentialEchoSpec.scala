package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A key the provider echoes back in its 401 body never reaches the `LLMError` (#1932). */
final class GeminiCredentialEchoSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val echoKey = "echo-key-0123456789abcdef"

  "GeminiClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = GeminiConfig(
        apiKey = Secret(echoKey),
        model = "gemini-2.0-flash",
        baseUrl = baseUrl,
        contextWindow = 1048576,
        reserveCompletion = 8192
      )
      new GeminiClient(config).complete(Conversation(Seq(UserMessage("hello"))), CompletionOptions())
    }
  }

  it should "keep the key out of the error when the request URL is rejected before it is sent" in KnownSecrets
    .isolated {
      // A space in the model name makes URI.create throw; the JDK's message quotes the whole URL, ?key= included.
      val config = GeminiConfig(
        apiKey = Secret(echoKey),
        model = "gemini 1.5",
        baseUrl = "http://127.0.0.1:1/v1beta",
        contextWindow = 1048576,
        reserveCompletion = 8192
      )
      val result = new GeminiClient(config).complete(Conversation(Seq(UserMessage("hello"))), CompletionOptions())
      result.isLeft shouldBe true
      CredentialEchoChecks.assertHidesKey(echoKey, result)
    }
}
