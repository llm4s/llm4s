package org.llm4s.config

import org.llm4s.llmconnect.config.LangfuseConfig
import org.llm4s.trace.{ LangfuseTracing, NoOpTracing, Tracing, TracingMode }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * The config round trip Langfuse users rely on: `LANGFUSE_*` variables, bound by this
 * module's `reference.conf`, reach the backend through core's `TracingSettings.extras`
 * when `TRACING_MODE=langfuse`. The keys and variables are what they were before the carve
 * (#1133); only the module that binds them changed.
 *
 * The Langfuse field cases here were in core's `TracingConfigLoaderSpec`, which read them
 * as `TracingSettings.langfuse`.
 */
class LangfuseTracingConfigSpec extends AnyWordSpec with Matchers with EitherValues {

  private val allVariables = Map(
    LangfuseConfigKeys.LANGFUSE_URL        -> "https://self-hosted.example/api/public/ingestion",
    LangfuseConfigKeys.LANGFUSE_PUBLIC_KEY -> "pk-lf-env",
    LangfuseConfigKeys.LANGFUSE_SECRET_KEY -> "sk-lf-env",
    LangfuseConfigKeys.LANGFUSE_ENV        -> "staging",
    LangfuseConfigKeys.LANGFUSE_RELEASE    -> "2.0.0",
    LangfuseConfigKeys.LANGFUSE_VERSION    -> "2.1.0"
  )

  private val expected = LangfuseConfig(
    "https://self-hosted.example/api/public/ingestion",
    Some("pk-lf-env"),
    Some("sk-lf-env"),
    "staging",
    "2.0.0",
    "2.1.0"
  )

  private def settings(source: ConfigSource) = TracingConfigLoader.load(source).value

  "llm4s-observability's reference.conf" should {

    "bind every LANGFUSE_* variable under llm4s.tracing.langfuse, reaching the backend as extras" in {
      val s = settings(ReferenceConfig.withEnv("", allVariables + ("TRACING_MODE" -> "langfuse")))

      s.mode shouldBe TracingMode.Named("langfuse")
      LangfuseConfig.fromExtras(s.extras) shouldBe expected
      Tracing.fromSettings(s).value shouldBe a[LangfuseTracing]
    }

    "be read by LangfuseConfigLoader whatever the mode" in {
      LangfuseConfigLoader.load(ReferenceConfig.withEnv("", allVariables)).value shouldBe expected
    }

    "leave every field at its default when no variable is set" in {
      val s = settings(ReferenceConfig.withEnv("", Map("TRACING_MODE" -> "langfuse")))

      LangfuseConfig.fromExtras(s.extras) shouldBe LangfuseConfig()
      // ...so the backend refuses to start, and Tracing.create degrades to NoOp.
      Tracing.fromSettings(s).left.value.message should include("LANGFUSE_PUBLIC_KEY")
      Tracing.create(s) shouldBe a[NoOpTracing]
    }

    "let application.conf set the keys directly, over the variables" in {
      val hocon =
        """
          |llm4s.tracing {
          |  mode = "langfuse"
          |  langfuse { publicKey = "pk-from-conf" }
          |}
          |""".stripMargin
      val s = settings(ReferenceConfig.withEnv(hocon, allVariables))

      LangfuseConfig.fromExtras(s.extras).publicKey shouldBe Some("pk-from-conf")
      LangfuseConfig.fromExtras(s.extras).secretKey shouldBe Some("sk-lf-env")
    }
  }

  "TracingConfigLoader Langfuse fields" should {

    "load a partial block, defaulting the rest" in {
      val hocon =
        """
          |llm4s.tracing {
          |  mode = "langfuse"
          |  langfuse { publicKey = "pk-minimal", secretKey = "sk-minimal" }
          |}
          |""".stripMargin
      val cfg = LangfuseConfig.fromExtras(settings(ConfigSource.string(hocon)).extras)

      cfg.publicKey shouldBe Some("pk-minimal")
      cfg.secretKey shouldBe Some("sk-minimal")
      cfg.url shouldBe LangfuseConfig.DEFAULT_URL
      cfg.env shouldBe LangfuseConfig.DEFAULT_ENV
    }

    "trim whitespace and treat empty strings as missing" in {
      val hocon =
        """
          |llm4s.tracing {
          |  mode = "langfuse"
          |  langfuse { publicKey = "  pk-with-spaces  ", secretKey = "", env = "  development  " }
          |}
          |""".stripMargin
      val cfg = LangfuseConfig.fromExtras(settings(ConfigSource.string(hocon)).extras)

      cfg.publicKey shouldBe Some("pk-with-spaces")
      cfg.secretKey shouldBe None
      cfg.env shouldBe "development"
    }
  }

  "LangfuseConfigLoader" should {

    "reject a llm4s.tracing.langfuse that is not an object" in {
      LangfuseConfigLoader
        .load(ConfigSource.string("llm4s.tracing.langfuse = \"should-be-an-object\""))
        .left
        .value
        .message shouldBe "llm4s.tracing.langfuse must be an object, but is a string"
    }

    "give the defaults when the block is absent" in {
      LangfuseConfigLoader.load(ConfigSource.string("llm4s {}")).value shouldBe LangfuseConfig()
    }
  }
}
