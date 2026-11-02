package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A key the provider echoes back in its 401 body never reaches the `LLMError` (#1932). */
final class OpenAICredentialEchoSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val echoKey = "echo-key-0123456789abcdef"
  private val hello   = Conversation(Seq(UserMessage("hello")))

  "OpenAIClient" should "keep an echoed OpenAI key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = OpenAIConfig.fromValues("gpt-4o", Secret(echoKey), None, s"$baseUrl/v1").value
      OpenAIClient(config).value.complete(hello, CompletionOptions())
    }
  }

  it should "keep an echoed Azure key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = AzureConfig.fromValues("gpt-4o", baseUrl, Secret(echoKey), "2025-01-01-preview").value
      OpenAIClient(config).value.complete(hello, CompletionOptions())
    }
  }
}
