package org.llm4s.samples.guides

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import ciris.{ default, ConfigValue, Effect }
import com.typesafe.config.ConfigFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.{ Config, ConfigProvider, Runtime, Unsafe, ZIO }
import zio.config.*

/**
 * Runs the recipes of docs/getting-started/configuration-in-code.md (#2340), whose blocks DocSnippets only compiles:
 * each way of configuring the same provider builds the same OpenAI client, the ciris and zio-config ones from a
 * source the test controls instead of the process environment. ciris and zio-config are on the samples' Test
 * classpath only.
 */
class ConfigurationInCodeGuideSpec extends AnyWordSpec with Matchers with EitherValues {

  private val model = "gpt-4o-mini"

  /** The plain-values recipe that the ciris and zio-config ones end in. */
  private def openAiClient(model: String, apiKey: String, baseUrl: String): Result[LLMClient] =
    for {
      registry <- ModelRegistryService.default()
      given ModelRegistryService = registry
      config <- OpenAIConfig.fromValues(model, Secret(apiKey), baseUrl)
      client <- LLMConnect.getClient(config)
    } yield client

  private def contextWindowOf(client: LLMClient): Int = {
    val window = client.getContextWindow()
    client.close()
    window
  }

  private val expectedWindow: Int =
    contextWindowOf(openAiClient(model, "sk-test", OpenAIConfig.DEFAULT_BASE_URL).value)

  "the configuration-in-code guide" should {

    "build a client from plain values with the bundled model catalogue" in {
      expectedWindow should be > 0
    }

    "build the same client from an explicit Config" in {
      val appConfig = ConfigFactory.parseString(
        s"""llm4s.providers {
           |  provider = "openai-main"
           |  openai-main { provider = "openai", model = "$model", apiKey = "sk-tenant-a" }
           |}""".stripMargin
      )
      val client = for {
        settings <- Llm4sConfig.from(appConfig)
        registry <- settings.modelRegistryService()
        given ModelRegistryService = registry
        config <- settings.defaultProvider()
        client <- LLMConnect.getClient(config)
      } yield client

      contextWindowOf(client.value) shouldBe expectedWindow
    }

    "build the same client from values ciris loaded" in {
      final case class LlmSettings(model: String, apiKey: ciris.Secret[String], baseUrl: String)

      // The guide reads env(...); default(...) stands in for the environment.
      val settings: ConfigValue[Effect, LlmSettings] = (
        default(model),
        default("sk-test").secret,
        default(OpenAIConfig.DEFAULT_BASE_URL)
      ).parMapN(LlmSettings.apply)

      val client = settings
        .load[IO]
        .flatMap(s =>
          IO.fromEither(
            openAiClient(s.model, s.apiKey.value, s.baseUrl).leftMap(e => new IllegalStateException(e.message))
          )
        )
        .unsafeRunSync()

      contextWindowOf(client) shouldBe expectedWindow
    }

    "build the same client from values zio-config read" in {
      final case class LlmSettings(model: String, apiKey: Config.Secret, baseUrl: String)

      val descriptor: Config[LlmSettings] =
        (Config.string("model").withDefault(model) ++
          Config.secret("api_key") ++
          Config.string("base_url").withDefault(OpenAIConfig.DEFAULT_BASE_URL))
          .nested("openai")
          .map { case (model, apiKey, baseUrl) => LlmSettings(model, apiKey, baseUrl) }

      // The guide reads ConfigProvider.envProvider; a map with the same OPENAI_API_KEY name stands in for it.
      val provider = ConfigProvider.fromMap(Map("OPENAI_API_KEY" -> "sk-test"), pathDelim = "_").upperCase

      val program: ZIO[Any, Throwable, LLMClient] =
        read(descriptor.from(provider)).flatMap { s =>
          ZIO
            .fromEither(openAiClient(s.model, s.apiKey.stringValue, s.baseUrl))
            .mapError(e => new IllegalStateException(e.message))
        }

      val client = Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(program).getOrThrow())

      contextWindowOf(client) shouldBe expectedWindow
    }

    "report a missing key through zio-config, before any client is built" in {
      val descriptor = Config.secret("api_key").nested("openai")
      val result = Unsafe.unsafe(implicit unsafe =>
        Runtime.default.unsafe.run(read(descriptor.from(ConfigProvider.fromMap(Map.empty))).either).getOrThrow()
      )
      result.isLeft shouldBe true
    }
  }
}
