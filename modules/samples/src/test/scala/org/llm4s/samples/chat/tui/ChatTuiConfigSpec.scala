package org.llm4s.samples.chat.tui

import org.llm4s.llmconnect.config.{ ContextWindowResolver, DeepSeekConfig, OpenAICompatibleConfig }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `LLM_MODEL=<provider>/<model>` in chat-tui, read from a map rather than the real environment.
 */
class ChatTuiConfigSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get)

  private def resolve(spec: String, env: (String, String)*) =
    ChatTuiConfig.fromLlmModel(spec, env.toMap.get)

  "LLM_MODEL=openai-compatible/<model>" should "build a generic config from OPENAI_COMPATIBLE_BASE_URL and _API_KEY" in {
    val cfg = resolve(
      "openai-compatible/llama3.2",
      "OPENAI_COMPATIBLE_BASE_URL" -> "http://localhost:8000/v1/",
      "OPENAI_COMPATIBLE_API_KEY"  -> "sk-local"
    ).value

    cfg shouldBe an[OpenAICompatibleConfig]
    cfg.providerId shouldBe ProviderId("openai-compatible")
    cfg.model shouldBe "llama3.2"
    val generic = cfg.asInstanceOf[OpenAICompatibleConfig]
    generic.baseUrl shouldBe "http://localhost:8000/v1"
    generic.apiKey shouldBe Some("sk-local")
  }

  it should "split on the first / only, so a model id containing / survives" in {
    val cfg = resolve(
      "openai-compatible/openai/gpt-oss-120b",
      "OPENAI_COMPATIBLE_BASE_URL" -> "https://api.groq.com/openai/v1"
    ).value
    cfg.model shouldBe "openai/gpt-oss-120b"
  }

  it should "need no API key, for a local server" in {
    val cfg = resolve("openai-compatible/m", "OPENAI_COMPATIBLE_BASE_URL" -> "http://localhost:8000/v1").value
    cfg.asInstanceOf[OpenAICompatibleConfig].apiKey shouldBe None
  }

  it should "fail, naming OPENAI_COMPATIBLE_BASE_URL, when the base URL is unset" in {
    resolve("openai-compatible/m", "OPENAI_COMPATIBLE_API_KEY" -> "k").left.value.message should include(
      "OPENAI_COMPATIBLE_BASE_URL is required"
    )
  }

  "LLM_MODEL for other providers" should "still resolve as before" in {
    resolve("deepseek/deepseek-chat", "DEEPSEEK_API_KEY" -> "k").value shouldBe a[DeepSeekConfig]
    resolve("nope/m").left.value.message should include("openai-compatible")
    resolve("no-slash").left.value.message should include("provider/model")
  }
}
