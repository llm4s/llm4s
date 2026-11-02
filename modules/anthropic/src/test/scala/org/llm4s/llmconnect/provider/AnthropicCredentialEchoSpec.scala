package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A key the provider echoes back in its 401 body never reaches the `LLMError` (#1932). */
final class AnthropicCredentialEchoSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val echoKey = "echo-key-0123456789abcdef"

  "AnthropicClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = AnthropicConfig(Secret(echoKey), "claude-sonnet-4-5").withBaseUrl(baseUrl)
      AnthropicClient(config).value.complete(Conversation(Seq(UserMessage("hello"))), CompletionOptions())
    }
  }
}
