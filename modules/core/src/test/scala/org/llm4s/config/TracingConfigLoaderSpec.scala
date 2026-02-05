package org.llm4s.config

import org.llm4s.trace.TracingMode
import pureconfig.ConfigSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.EitherValues

/**
 * Comprehensive unit tests for TracingConfigLoader validation and parsing.
 *
 * These tests use ConfigSource.string() to provide deterministic HOCON input
 * without relying on environment variables or external configuration files.
 */
class TracingConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues {

  // --------------------------------------------------------------------------
  // Successful Parsing Tests
  // --------------------------------------------------------------------------

  "TracingConfigLoader" should {

    "successfully load valid tracing config with all fields" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    mode = "langfuse"
          |    langfuse {
          |      url = "https://custom.langfuse.com/api/public/ingestion"
          |      publicKey = "pk-test-123"
          |      secretKey = "sk-test-456"
          |      env = "staging"
          |      release = "2.0.0"
          |      version = "2.1.0"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val settings = result.value
      settings.mode shouldBe TracingMode.Langfuse
      settings.langfuse.url shouldBe "https://custom.langfuse.com/api/public/ingestion"
      settings.langfuse.publicKey shouldBe Some("pk-test-123")
      settings.langfuse.secretKey shouldBe Some("sk-test-456")
      settings.langfuse.env shouldBe "staging"
      settings.langfuse.release shouldBe "2.0.0"
      settings.langfuse.version shouldBe "2.1.0"
    }

    "use default values when tracing section is missing" in {
      val hocon =
        """
          |llm4s {
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val settings = result.value
      settings.mode shouldBe TracingMode.Console
      settings.langfuse.url shouldBe DefaultConfig.DEFAULT_LANGFUSE_URL
      settings.langfuse.env shouldBe DefaultConfig.DEFAULT_LANGFUSE_ENV
      settings.langfuse.release shouldBe DefaultConfig.DEFAULT_LANGFUSE_RELEASE
      settings.langfuse.version shouldBe DefaultConfig.DEFAULT_LANGFUSE_VERSION
      settings.langfuse.publicKey shouldBe None
      settings.langfuse.secretKey shouldBe None
    }

    "use default values when langfuse section is missing" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    mode = "console"
          |  }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val settings = result.value
      settings.mode shouldBe TracingMode.Console
      settings.langfuse.url shouldBe DefaultConfig.DEFAULT_LANGFUSE_URL
    }

    "use default mode when mode is not specified" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    langfuse {
          |      publicKey = "pk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val settings = result.value
      settings.mode shouldBe TracingMode.Console // Default
      settings.langfuse.publicKey shouldBe Some("pk-test")
    }
  }

  // --------------------------------------------------------------------------
  // Tracing Mode Tests
  // --------------------------------------------------------------------------

  "TracingConfigLoader mode parsing" should {

    "parse 'console' mode correctly" in {
      val hocon =
        """
          |llm4s {
          |  tracing { mode = "console" }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value.mode shouldBe TracingMode.Console
    }

    "parse 'langfuse' mode correctly" in {
      val hocon =
        """
          |llm4s {
          |  tracing { mode = "langfuse" }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value.mode shouldBe TracingMode.Langfuse
    }

    "parse 'noop' mode correctly" in {
      val hocon =
        """
          |llm4s {
          |  tracing { mode = "noop" }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value.mode shouldBe TracingMode.NoOp
    }

    "handle mode with mixed case" in {
      val hocon =
        """
          |llm4s {
          |  tracing { mode = "LANGFUSE" }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value.mode shouldBe TracingMode.Langfuse
    }

    "default to NoOp for unknown mode values" in {
      val hocon =
        """
          |llm4s {
          |  tracing { mode = "unknown-mode" }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value.mode shouldBe TracingMode.NoOp
    }

    "handle empty mode string by using default" in {
      val hocon =
        """
          |llm4s {
          |  tracing { mode = "  " }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value.mode shouldBe TracingMode.Console
    }
  }

  // --------------------------------------------------------------------------
  // Langfuse Configuration Tests
  // --------------------------------------------------------------------------

  "TracingConfigLoader Langfuse fields" should {

    "load partial langfuse config with only required fields" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    mode = "langfuse"
          |    langfuse {
          |      publicKey = "pk-minimal"
          |      secretKey = "sk-minimal"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val settings = result.value
      settings.langfuse.publicKey shouldBe Some("pk-minimal")
      settings.langfuse.secretKey shouldBe Some("sk-minimal")
      // Other fields should have defaults
      settings.langfuse.url shouldBe DefaultConfig.DEFAULT_LANGFUSE_URL
      settings.langfuse.env shouldBe DefaultConfig.DEFAULT_LANGFUSE_ENV
    }

    "handle langfuse config with custom URL only" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    langfuse {
          |      url = "https://self-hosted.langfuse.local/api"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val settings = result.value
      settings.langfuse.url shouldBe "https://self-hosted.langfuse.local/api"
      settings.langfuse.publicKey shouldBe None
      settings.langfuse.secretKey shouldBe None
    }

    "trim whitespace from langfuse string values" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    langfuse {
          |      publicKey = "  pk-with-spaces  "
          |      env = "  development  "
          |    }
          |  }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val settings = result.value
      settings.langfuse.publicKey shouldBe Some("pk-with-spaces")
      settings.langfuse.env shouldBe "development"
    }

    "treat empty strings as missing values" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    langfuse {
          |      publicKey = ""
          |      secretKey = "   "
          |    }
          |  }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val settings = result.value
      // Empty or whitespace-only strings should be treated as None
      settings.langfuse.publicKey shouldBe None
      settings.langfuse.secretKey shouldBe None
    }
  }

  // --------------------------------------------------------------------------
  // Malformed Configuration Tests
  // --------------------------------------------------------------------------

  "TracingConfigLoader with malformed config" should {

    "fail gracefully when llm4s root is missing" in {
      val hocon =
        """
          |someOtherConfig {
          |  value = "test"
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Failed to load llm4s tracing config via PureConfig")
    }

    "fail gracefully when tracing section has wrong structure" in {
      val hocon =
        """
          |llm4s {
          |  tracing = "invalid-scalar-value"
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Failed to load llm4s tracing config via PureConfig")
    }

    "fail gracefully when langfuse section has wrong structure" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    mode = "langfuse"
          |    langfuse = "should-be-an-object"
          |  }
          |}
          |""".stripMargin

      val result = TracingConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("PureConfig")
    }
  }
}
