package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.{ AuthConfig, IdentitySource }
import org.llm4s.llmconnect.config.ProviderTimeouts
import org.llm4s.types.ProviderModelTypes.{ ApiKey, BaseUrl, ModelName, ProviderId }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Path, Paths }
import scala.concurrent.duration.*

class NamedProviderConfigSpec extends AnyFlatSpec with Matchers {

  private val base = NamedProviderConfig(
    provider = ProviderId("openai"),
    model = ModelName("gpt-4o"),
    baseUrl = None,
    apiKey = None
  )

  private val configuredAuth     = AuthConfig(IdentitySource.Literal("identity-token"))
  private val keyFile            = Paths.get("secrets", "provider-key")
  private val configuredTimeouts = ProviderTimeouts(request = Some(10.seconds), stream = Some(30.seconds))

  private def configured(
    provider: ProviderId = ProviderId("openai"),
    model: ModelName = ModelName("gpt-4o"),
    baseUrl: Option[BaseUrl] = Some(BaseUrl("https://configured")),
    apiKey: Option[ApiKey] = Some(ApiKey("configured-key")),
    headers: Map[String, String] = Map("x-header" -> "header-value"),
    extras: Map[String, String] = Map("token" -> "extra-value"),
    timeouts: ProviderTimeouts = configuredTimeouts,
    auth: Option[AuthConfig] = Some(configuredAuth),
    apiKeyFile: Option[Path] = Some(keyFile)
  ): NamedProviderConfig =
    NamedProviderConfig(provider, model, baseUrl, apiKey, headers, extras, timeouts, auth, apiKeyFile)

  "requireProvider" should "return the same config for a matching provider" in {
    base.requireProvider(ProviderId("openai")) shouldBe Right(base)
  }

  it should "report the expected and configured providers on a mismatch" in {
    base.requireProvider(ProviderId("anthropic")) shouldBe Left(
      ConfigurationError("Expected a 'anthropic' provider section, but it is configured for 'openai'")
    )
  }

  "requireBaseUrl" should "report the missing field or return the configured URL" in {
    base.requireBaseUrl shouldBe Left(ConfigurationError("Configured provider is missing required field `baseUrl`"))
    base.withBaseUrl(BaseUrl("https://configured")).requireBaseUrl shouldBe Right(BaseUrl("https://configured"))
  }

  "requireApiKey" should "report the missing field or return the configured key" in {
    base.requireApiKey shouldBe Left(ConfigurationError("Configured provider is missing required field `apiKey`"))
    base.withApiKey(ApiKey("configured-key")).requireApiKey shouldBe Right(ApiKey("configured-key"))
  }

  "baseUrlOrDefault" should "not evaluate the fallback when a URL is configured" in {
    var evaluated = false
    val result = base.withBaseUrl(BaseUrl("https://configured")).baseUrlOrDefault {
      evaluated = true
      "https://default"
    }
    result shouldBe BaseUrl("https://configured")
    evaluated shouldBe false
  }

  it should "use the fallback when no URL is configured" in {
    base.baseUrlOrDefault("https://default") shouldBe BaseUrl("https://default")
  }

  "extra" should "return a configured value and None for an absent key" in {
    base.withExtras(Map("k" -> "v")).extra("k") shouldBe Some("v")
    base.extra("k") shouldBe None
  }

  "with setters" should "change only their own field and preserve the original config" in {
    val original = configured()
    original.withProvider(ProviderId("anthropic")) shouldBe configured(provider = ProviderId("anthropic"))
    original.withModel(ModelName("other-model")) shouldBe configured(model = ModelName("other-model"))
    original.withBaseUrl(BaseUrl("https://other")) shouldBe configured(baseUrl = Some(BaseUrl("https://other")))
    original.withBaseUrl(Some(BaseUrl("https://other"))) shouldBe configured(baseUrl = Some(BaseUrl("https://other")))
    original.withBaseUrl(None) shouldBe configured(baseUrl = None)
    original.withApiKey(ApiKey("other-key")) shouldBe configured(apiKey = Some(ApiKey("other-key")))
    original.withApiKey(Some(ApiKey("other-key"))) shouldBe configured(apiKey = Some(ApiKey("other-key")))
    original.withApiKey(None) shouldBe configured(apiKey = None)
    original.withHeaders(Map("other-header" -> "v")) shouldBe configured(headers = Map("other-header" -> "v"))
    original.withExtras(Map("other-extra" -> "v")) shouldBe configured(extras = Map("other-extra" -> "v"))
    original.withTimeouts(ProviderTimeouts.default) shouldBe configured(timeouts = ProviderTimeouts.default)
    val otherAuth = AuthConfig(IdentitySource.Literal("other-token"))
    original.withAuth(otherAuth) shouldBe configured(auth = Some(otherAuth))
    original.withAuth(Some(otherAuth)) shouldBe configured(auth = Some(otherAuth))
    original.withAuth(None) shouldBe configured(auth = None)
    val otherFile = Paths.get("secrets", "other-key")
    original.withApiKeyFile(otherFile) shouldBe configured(apiKeyFile = Some(otherFile))
    original.withApiKeyFile(Some(otherFile)) shouldBe configured(apiKeyFile = Some(otherFile))
    original.withApiKeyFile(None) shouldBe configured(apiKeyFile = None)
    original shouldBe configured()
  }

  "apply" should "preserve supplied fields and default omitted trailing fields" in {
    val full = configured()
    val eight = NamedProviderConfig(
      full.provider,
      full.model,
      full.baseUrl,
      full.apiKey,
      full.headers,
      full.extras,
      full.timeouts,
      full.auth
    )
    eight shouldBe configured(apiKeyFile = None)
    eight.apiKeyFile shouldBe None

    val six = NamedProviderConfig(full.provider, full.model, full.baseUrl, full.apiKey, full.headers, full.extras)
    six shouldBe configured(timeouts = ProviderTimeouts.default, auth = None, apiKeyFile = None)
    six.timeouts shouldBe ProviderTimeouts.default
    six.auth shouldBe None
    six.apiKeyFile shouldBe None

    base.headers shouldBe Map.empty
    base.extras shouldBe Map.empty
    base.timeouts shouldBe ProviderTimeouts.default
    base.auth shouldBe None
    base.apiKeyFile shouldBe None
  }

  "toString" should "mask credentials while retaining header and extra names and the optional key path" in {
    val config = base
      .withApiKey(ApiKey("sk-secret"))
      .withHeaders(Map("x-api-key" -> "hdr-secret"))
      .withExtras(Map("token" -> "ext-secret"))
      .withApiKeyFile(keyFile)
    val rendered = config.toString
    rendered should include("***")
    rendered should include("x-api-key -> ***")
    rendered should include("token -> ***")
    rendered should include(s",apiKeyFile=$keyFile")
    Seq("sk-secret", "hdr-secret", "ext-secret").foreach(secret => (rendered should not).include(secret))
    (config.withApiKeyFile(None).toString should not).include("apiKeyFile=")
    (base.toString should not).include("apiKeyFile=")
  }
}
