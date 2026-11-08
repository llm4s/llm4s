package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A key the provider echoes back in its 401 body never reaches the `LLMError` (#1932). */
final class OpenAICompatibleCredentialEchoSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val echoKey = "echo-key-0123456789abcdef"
  private val hello   = Conversation(Seq(UserMessage("hello")))
  private val opts    = CompletionOptions()

  "The openai-compatible clients" should "keep an echoed key out of the generic client's error" in KnownSecrets
    .isolated {
      CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
        val config = OpenAICompatibleConfig(model = "local-model", baseUrl = baseUrl, apiKey = Some(Secret(echoKey)))
        OpenAICompatibleClient(config).value.complete(hello, opts)
      }
    }

  it should "keep an echoed key out of DeepSeek's error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = DeepSeekConfig(apiKey = Secret(echoKey), model = "deepseek-chat", baseUrl = baseUrl, 128000, 4096)
      DeepSeekClient(config).value.complete(hello, opts)
    }
  }

  it should "keep an echoed key out of Mistral's error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config =
        MistralConfig(apiKey = Secret(echoKey), model = "mistral-small-latest", baseUrl = baseUrl, 128000, 4096)
      MistralClient(config).value.complete(hello, opts)
    }
  }

  it should "keep an echoed key out of Cohere's error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = CohereConfig(apiKey = Secret(echoKey), model = "command-r", baseUrl = baseUrl, 128000, 4096)
      CohereClient(config).value.complete(hello, opts)
    }
  }

  it should "keep an echoed key out of Z.ai's error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = ZaiConfig(apiKey = Secret(echoKey), model = "glm-4.6", baseUrl = baseUrl, 128000, 4096)
      ZaiClient(config).value.complete(hello, opts)
    }
  }

  it should "keep an echoed key out of xAI's error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = XaiConfig(apiKey = Secret(echoKey), model = "grok-4", baseUrl = baseUrl, 128000, 4096)
      XaiClient(config).value.complete(hello, opts)
    }
  }

  it should "keep an echoed key out of OpenRouter's error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = OpenAIConfig.fromValues("openai/gpt-4o", Secret(echoKey), baseUrl).value
      OpenRouterClient(config).value.complete(hello, opts)
    }
  }
}
