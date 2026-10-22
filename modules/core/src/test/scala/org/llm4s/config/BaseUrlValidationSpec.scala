package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.testutil.FixtureChatConfig
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * A provider section's `baseUrl`, and an embeddings block's, must be an absolute http(s) URL with a
 * host when the section is loaded (#2347), rather than failing on the first call.
 */
class BaseUrlValidationSpec extends AnyWordSpec with Matchers with EitherValues:

  private val Path = "llm4s.providers.main.baseUrl"

  private val valid = Seq(
    "http://localhost:11434",
    "https://api.openai.com",
    "https://api.openai.com:8443",
    "https://api.openai.com/v1",
    "https://api.openai.com/v1/",
    "http://[::1]:8080/v1",
    "http://localhost",
    "http://127.0.0.1:8000/v1",
    "HTTPS://API.EXAMPLE.COM/v1",
    "http://ollama_server:11434",
    "https://user:secret@proxy.example.com/v1"
  )

  private val invalid = Seq(
    "localhost:11434"          -> "scheme 'localhost'",
    "api.openai.com/v1"        -> "no scheme",
    "ftp://files.example.com"  -> "scheme 'ftp'",
    "htp://api.example.com"    -> "scheme 'htp'",
    "/v1/chat"                 -> "no scheme",
    "v1"                       -> "no scheme",
    "   "                      -> "blank",
    "not a url"                -> "not a valid URI",
    "http://api example.com"   -> "not a valid URI",
    "http:///v1"               -> "no host",
    "http://:8080/v1"          -> "no host",
    "file:///etc/passwd"       -> "scheme 'file'",
    "mailto:someone@localhost" -> "scheme 'mailto'"
  )

  "BaseUrlValidation.validate" should {

    valid.foreach { url =>
      s"accept $url" in {
        BaseUrlValidation.validate(Path, url).value shouldBe url
      }
    }

    invalid.foreach { (url, reason) =>
      s"refuse '$url', naming the key and why" in {
        val error = BaseUrlValidation.validate(Path, url).left.value
        error shouldBe a[ConfigurationError]
        error.message should include(Path)
        error.message should include("absolute http or https URL")
        error.message should include(reason)
      }
    }

    "show the bad value in the error" in {
      BaseUrlValidation.validate(Path, "htp://api.example.com/v1").left.value.message should include(
        "'htp://api.example.com/v1'"
      )
    }

    "redact the userinfo of a bad URL" in {
      val message = BaseUrlValidation.validate(Path, "ftp://alice:hunter2@files.example.com/x").left.value.message
      (message should not).include("hunter2")
      (message should not).include("alice")
      message should include("files.example.com")
    }

    "redact the query of a bad URL, which may carry a key" in {
      val message = BaseUrlValidation.validate(Path, "ftp://files.example.com/x?api_key=sk-secret").left.value.message
      (message should not).include("sk-secret")
    }

    "redact a userinfo that comes without a scheme" in {
      val message = BaseUrlValidation.validate(Path, "alice:hunter2@api.example.com").left.value.message
      (message should not).include("hunter2")
      message should include("'***'")
    }
  }

  private def section(baseUrl: Option[String]) =
    RawNamedProviderSection(
      provider = Some("fixturechat"),
      model = Some("fixture-model"),
      baseUrl = baseUrl,
      apiKey = Some("sk-test")
    )

  private def validateSection(baseUrl: Option[String]) =
    NamedProviderConfigValidator.validate(ProviderName("main"), section(baseUrl))

  "A named provider section" should {

    "keep a valid baseUrl, trimmed" in {
      validateSection(Some("  https://fixture.example.com/v1  ")).value.baseUrl.map(_.asUrl) shouldBe
        Some("https://fixture.example.com/v1")
    }

    "treat a blank baseUrl as unset" in {
      validateSection(Some("   ")).value.baseUrl shouldBe None
    }

    "refuse a baseUrl that is not an http(s) URL, naming llm4s.providers.<name>.baseUrl" in {
      val error = validateSection(Some("localhost:9999")).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("llm4s.providers.main.baseUrl")
      error.message should include("localhost:9999")
    }

    "not show the password of a refused baseUrl" in {
      (validateSection(Some("ftp://bob:pa55word@host.example.com")).left.value.message should not).include("pa55word")
    }
  }

  "Loading one section" should {

    def source(badUrl: String) =
      ConfigSource.string(
        s"""
           |llm4s.providers {
           |  provider = "good"
           |  good { provider = "fixturechat", model = "fixture-model", apiKey = "good-key", baseUrl = "https://good.example.com/v1" }
           |  bad  { provider = "fixturechat", model = "fixture-model", apiKey = "bad-key", baseUrl = "$badUrl" }
           |}
           |""".stripMargin
      )

    "not be affected by another section's bad baseUrl" in {
      Llm4sConfig.provider(source("not a url"), "good").value match
        case cfg: FixtureChatConfig => cfg.baseUrl shouldBe "https://good.example.com/v1"
        case other                  => fail(s"Expected FixtureChatConfig, got $other")
      Llm4sConfig.defaultProvider(source("not a url")).isRight shouldBe true
    }

    "fail for the section whose baseUrl is bad" in {
      Llm4sConfig.provider(source("not a url"), "bad").left.value.message should include(
        "llm4s.providers.bad.baseUrl"
      )
    }
  }

  "An embeddings block" should {

    def load(baseUrl: String) =
      EmbeddingsConfigLoader.loadProvider(
        ConfigSource.string(
          s"""
             |llm4s.embeddings {
             |  model = "fixtureembedding/fixture-embed-small"
             |  fixtureembedding { apiKey = "vk-test", baseUrl = "$baseUrl" }
             |}
             |""".stripMargin
        )
      )

    "accept an http(s) baseUrl" in {
      load("http://localhost:8080/v1").value._2.baseUrl shouldBe "http://localhost:8080/v1"
    }

    "treat a blank baseUrl as unset, so the provider's default applies" in {
      load("  ").value._2.baseUrl shouldBe "https://fixtureembedding.invalid/v1"
    }

    "refuse a baseUrl that is not an http(s) URL, naming llm4s.embeddings.<id>.baseUrl" in {
      val error = load("ftp://user:topsecret@embed.example.com").left.value
      error shouldBe a[ConfigurationError]
      error.message should include("llm4s.embeddings.fixtureembedding.baseUrl")
      (error.message should not).include("topsecret")
    }
  }
