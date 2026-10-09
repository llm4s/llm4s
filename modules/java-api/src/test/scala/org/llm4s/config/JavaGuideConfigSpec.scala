package org.llm4s.config

import org.llm4s.javaapi.{ GuideDocs, JLlmClient, LlmResult }
import org.llm4s.llmconnect.config.{ AnthropicConfig, OllamaConfig, OpenAIConfig }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The `application.conf` blocks of `docs/guide/java.md`, loaded as written.
 *
 * The blocks are read from the guide, not copied here, so editing one in the guide changes what is tested. This is
 * in package `org.llm4s.config` because loading from a given configuration source is not public API; the specs for
 * the other guides that teach configuration (`DocumentedProviderConfigSpec`) sit here for the same reason. The
 * environment is injected with the real one switched off, so the cases do not depend on the machine's variables.
 */
class JavaGuideConfigSpec extends AnyWordSpec with Matchers {

  private val GuideFile = "docs/guide/java.md"

  private lazy val hocon: List[String] = GuideDocs.blocksIn(GuideDocs.read(GuideFile, GuideFile), "hocon")

  private val env = Map("OPENAI_API_KEY" -> "sk-openai-env", "ANTHROPIC_API_KEY" -> "sk-anthropic-env")

  "The application.conf blocks of the guide" should {

    "be exactly two" in {
      hocon.size shouldBe 2
    }

    "give OpenAI the key from OPENAI_API_KEY with only provider and model in the section" in {
      Llm4sConfig.defaultProvider(ReferenceConfig.withEnv(hocon(0), env)) match {
        case Right(openai: OpenAIConfig) =>
          openai.model shouldBe "gpt-4o-mini"
          openai.apiKey shouldBe "sk-openai-env"
        case other => fail(s"Expected OpenAIConfig, got $other")
      }
    }

    "name the variable and the section when OPENAI_API_KEY is not set" in {
      Llm4sConfig.defaultProvider(ReferenceConfig.withEnv(hocon(0), Map.empty)) match {
        case Left(error) =>
          error.message should include("OPENAI_API_KEY")
          error.message should include("openai-main")
          // the Java facade hands such an error to the caller as a failed result, message intact
          LlmResult.failure[JLlmClient](error).getError().getMessage shouldBe error.message
        case Right(config) => fail(s"Expected a missing-key error, got $config")
      }
    }

    "make claude the default, with its key from ANTHROPIC_API_KEY, and keep ollama-local as a named section" in {
      val source = ReferenceConfig.withEnv(hocon(1), env)

      Llm4sConfig.defaultProvider(source) match {
        case Right(anthropic: AnthropicConfig) =>
          anthropic.model shouldBe "claude-sonnet-4-20250514"
          anthropic.apiKey shouldBe "sk-anthropic-env"
        case other => fail(s"Expected AnthropicConfig, got $other")
      }
      Llm4sConfig.provider(source, "ollama-local") match {
        case Right(ollama: OllamaConfig) =>
          ollama.model shouldBe "llama3.2"
          ollama.baseUrl shouldBe "http://localhost:11434"
        case other => fail(s"Expected OllamaConfig, got $other")
      }
    }
  }
}
