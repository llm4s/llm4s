package org.llm4s.llmconnect.spi

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.SharedCredentials
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.{ AuthConfig, IdentitySource }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.testutil.FixtureChatConfig
import org.llm4s.types.ProviderModelTypes.{ ApiKey, BaseUrl, ModelName, ProviderId }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Direct unit coverage for the `ProviderDescriptor` companion helpers (#2253) -
 * `expectConfig`, `requireField`, `requireExtra`, `requireAuthExtra`, `requireApiKey` and
 * `resolveBaseUrl` - and for the `ProviderConfigSpec` defaults, setters, `extra` lookup and
 * `apiKeyAndDefaultBaseUrl` factory they are built on.
 *
 * `ProviderConfigExtrasSpec` already exercises these helpers indirectly, through a full
 * HOCON -> validate -> buildConfig pipeline; this suite calls them directly, as
 * `ProviderTimeoutsConfigSpec` and `GrowthSafeConfigModelUtilTypesSpec` do for their own
 * types. It covers helper success and failure cases, including exact configuration-error
 * messages, without going through a descriptor or the config loader.
 */
class ProviderDescriptorHelpersSpec extends AnyFlatSpec with Matchers:

  private val providerName = "my-provider"
  private val providerId   = ProviderId("fixturechat")

  private val specWithDefault = ProviderConfigSpec(defaultBaseUrl = Some("https://default.example.com"))
  private val specNoDefault   = ProviderConfigSpec()

  private def section(
    baseUrl: Option[String] = None,
    apiKey: Option[String] = Some("k"),
    extras: Map[String, String] = Map.empty
  ): NamedProviderConfig =
    NamedProviderConfig(
      provider = providerId,
      model = ModelName("m"),
      baseUrl = baseUrl.map(BaseUrl(_)),
      apiKey = apiKey.map(ApiKey(_)),
      extras = extras
    )

  private def expectMessage(result: Result[?]): String =
    result match
      case Left(error: ConfigurationError) => error.message
      case Left(other)                     => fail(s"expected a ConfigurationError, got $other")
      case Right(other)                    => fail(s"expected a Left, got Right($other)")

  /** An incompatible config type used to exercise expectConfig’s type-mismatch error. */
  private object ForeignConfig extends ProviderConfig:
    val providerId: ProviderId                   = ProviderId("other")
    val model: String                            = "foreign-model"
    val contextWindow: Int                       = 8192
    val reserveCompletion: Int                   = 1024
    val endpointUrl: Option[String]              = None
    def withModel(model: String): ProviderConfig = this

  // ---------------------------------------------------------------------------------------------
  // ProviderDescriptor.expectConfig
  // ---------------------------------------------------------------------------------------------

  "expectConfig" should "return the config unchanged when it matches the expected type" in {
    val config = FixtureChatConfig(apiKey = "k", model = "m")
    ProviderDescriptor.expectConfig[FixtureChatConfig](providerId, config) shouldBe Right(config)
  }

  it should "fail, naming the provider and actual config type, when the expected type differs" in {
    val config  = FixtureChatConfig(apiKey = "k", model = "m")
    val message = expectMessage(ProviderDescriptor.expectConfig[ForeignConfig.type](providerId, config))
    message shouldBe s"Invalid config type FixtureChatConfig for provider ${providerId.asString}"
  }

  // ---------------------------------------------------------------------------------------------
  // ProviderDescriptor.requireField
  // ---------------------------------------------------------------------------------------------

  "requireField" should "return the value when it is present" in {
    ProviderDescriptor.requireField(providerName, "field", Some("value"), "hint") shouldBe Right("value")
  }

  it should "fail with the shared message shape, naming the provider, the field and the hint" in {
    val message = expectMessage(ProviderDescriptor.requireField[String](providerName, "field", None, "hint"))
    message shouldBe s"Configured provider '$providerName' is missing field (hint)"
  }

  // ---------------------------------------------------------------------------------------------
  // ProviderDescriptor.requireExtra
  // ---------------------------------------------------------------------------------------------

  "requireExtra" should "return the declared key's value when the section carries it" in {
    val result = ProviderDescriptor.requireExtra(providerName, section(extras = Map("region" -> "eu-west-1")), "region")
    result shouldBe Right("eu-west-1")
  }

  it should "fail naming the key and the generic <name> hint when the section omits it" in {
    val message = expectMessage(ProviderDescriptor.requireExtra(providerName, section(), "region"))
    message shouldBe s"Configured provider '$providerName' is missing region (llm4s.providers.<name>.region)"
  }

  // ---------------------------------------------------------------------------------------------
  // ProviderDescriptor.requireAuthExtra
  // ---------------------------------------------------------------------------------------------

  private def auth(extras: Map[String, String] = Map.empty): AuthConfig =
    AuthConfig(IdentitySource.Literal("token"), extras = extras)

  "requireAuthExtra" should "return the declared auth key's value when it is present" in {
    val result =
      ProviderDescriptor.requireAuthExtra(
        providerName,
        auth(Map("tokenUrl" -> "https://auth.example.com/token")),
        "tokenUrl"
      )
    result shouldBe Right("https://auth.example.com/token")
  }

  it should "fail naming the auth key and the provider-specific hint when it is absent" in {
    val message = expectMessage(ProviderDescriptor.requireAuthExtra(providerName, auth(), "tokenUrl"))
    message shouldBe s"Configured provider '$providerName' is missing auth.tokenUrl (llm4s.providers.$providerName.auth.tokenUrl)"
  }

  // ---------------------------------------------------------------------------------------------
  // ProviderDescriptor.requireApiKey
  // ---------------------------------------------------------------------------------------------

  "requireApiKey" should "return the section's api key when it is set" in {
    ProviderDescriptor
      .requireApiKey(providerName, section(apiKey = Some("secret")))
      .map(_.reveal) shouldBe Right("secret")
  }

  it should "fail naming both the section's own apiKey path and the vendor's shared credential path" in {
    val message = expectMessage(ProviderDescriptor.requireApiKey(providerName, section(apiKey = None)))
    message shouldBe
      s"Configured provider '$providerName' is missing api key " +
      s"(llm4s.providers.$providerName.apiKey, or ${SharedCredentials.apiKeyPath(providerId)})"
    message should include("llm4s.credentials.fixturechat.apiKey")
  }

  // ---------------------------------------------------------------------------------------------
  // ProviderDescriptor.resolveBaseUrl
  // ---------------------------------------------------------------------------------------------

  "resolveBaseUrl" should "prefer the section's own baseUrl over the spec's default" in {
    val result = ProviderDescriptor.resolveBaseUrl(
      providerName,
      section(baseUrl = Some("https://section.example.com")),
      specWithDefault
    )
    result shouldBe Right("https://section.example.com")
  }

  it should "fall back to the spec's default when the section sets no baseUrl" in {
    ProviderDescriptor.resolveBaseUrl(providerName, section(), specWithDefault) shouldBe
      Right("https://default.example.com")
  }

  it should "fail naming the generic <name> hint when neither the section nor the spec has one" in {
    val message = expectMessage(ProviderDescriptor.resolveBaseUrl(providerName, section(), specNoDefault))
    message shouldBe s"Configured provider '$providerName' is missing base URL (llm4s.providers.<name>.baseUrl)"
  }

  // ---------------------------------------------------------------------------------------------
  // ProviderConfigSpec: defaults
  // ---------------------------------------------------------------------------------------------

  "ProviderConfigSpec()" should "default to no requirements, no extras, and the example placeholder base URL hint" in {
    val default = ProviderConfigSpec()
    default.requiresApiKey shouldBe false
    default.requiresBaseUrl shouldBe false
    default.defaultBaseUrl shouldBe None
    default.baseUrlExample shouldBe "e.g. https://api.example.com/"
    default.baseUrlEnv shouldBe None
    default.extras shouldBe Seq.empty
    default.apiKeyEnv shouldBe Seq.empty
    default.authExtras shouldBe Seq.empty
    default.keylessWhen shouldBe None
  }

  // ---------------------------------------------------------------------------------------------
  // ProviderConfigSpec: setters (each checked against a fully populated spec, so an unintended
  // change to an unrelated field is caught, not just the field the setter targets)
  // ---------------------------------------------------------------------------------------------

  private def fullSpec(
    requiresApiKey: Boolean = true,
    requiresBaseUrl: Boolean = true,
    defaultBaseUrl: Option[String] = Some("https://default.example.com"),
    baseUrlExample: String = "e.g. https://example.test",
    baseUrlEnv: Option[String] = Some("EXAMPLE_BASE_URL"),
    extras: Seq[ProviderConfigKey] = Seq(ProviderConfigKey.required("region", "the region")),
    apiKeyEnv: Seq[String] = Seq("EXAMPLE_API_KEY"),
    authExtras: Seq[ProviderConfigKey] = Seq(ProviderConfigKey.optional("tokenUrl", "the token URL")),
    keylessWhen: Option[(String, String)] = Some("authentication" -> "entra-id")
  ): ProviderConfigSpec =
    ProviderConfigSpec(
      requiresApiKey,
      requiresBaseUrl,
      defaultBaseUrl,
      baseUrlExample,
      baseUrlEnv,
      extras,
      apiKeyEnv,
      authExtras,
      keylessWhen
    )

  "withRequiresApiKey" should "set requiresApiKey and leave every other field unchanged" in {
    fullSpec().withRequiresApiKey(false) shouldBe fullSpec(requiresApiKey = false)
  }

  "withRequiresBaseUrl" should "set requiresBaseUrl and leave every other field unchanged" in {
    fullSpec().withRequiresBaseUrl(false) shouldBe fullSpec(requiresBaseUrl = false)
  }

  "withDefaultBaseUrl" should "set defaultBaseUrl from a String and leave every other field unchanged" in {
    fullSpec().withDefaultBaseUrl("https://other.example.com") shouldBe
      fullSpec(defaultBaseUrl = Some("https://other.example.com"))
  }

  it should "set defaultBaseUrl from an Option, including clearing it with None" in {
    fullSpec().withDefaultBaseUrl(Some("https://other.example.com")) shouldBe
      fullSpec(defaultBaseUrl = Some("https://other.example.com"))
    fullSpec().withDefaultBaseUrl(None) shouldBe fullSpec(defaultBaseUrl = None)
  }

  "withBaseUrlExample" should "set baseUrlExample and leave every other field unchanged" in {
    fullSpec().withBaseUrlExample("e.g. https://changed.example.com") shouldBe
      fullSpec(baseUrlExample = "e.g. https://changed.example.com")
  }

  "withBaseUrlEnv" should "set baseUrlEnv from a String and leave every other field unchanged" in {
    fullSpec().withBaseUrlEnv("OTHER_BASE_URL") shouldBe fullSpec(baseUrlEnv = Some("OTHER_BASE_URL"))
  }

  it should "set baseUrlEnv from an Option, including clearing it with None" in {
    fullSpec().withBaseUrlEnv(Some("OTHER_BASE_URL")) shouldBe fullSpec(baseUrlEnv = Some("OTHER_BASE_URL"))
    fullSpec().withBaseUrlEnv(None) shouldBe fullSpec(baseUrlEnv = None)
  }

  "withApiKeyEnv" should "set apiKeyEnv and leave every other field unchanged" in {
    fullSpec().withApiKeyEnv(Seq("OTHER_API_KEY", "FALLBACK_API_KEY")) shouldBe
      fullSpec(apiKeyEnv = Seq("OTHER_API_KEY", "FALLBACK_API_KEY"))
  }

  "withExtras" should "set extras and leave every other field unchanged" in {
    val newExtras = Seq(ProviderConfigKey.optional("profile", "the profile"))
    fullSpec().withExtras(newExtras) shouldBe fullSpec(extras = newExtras)
  }

  "withAuthExtras" should "set authExtras and leave every other field unchanged" in {
    val newAuthExtras = Seq(ProviderConfigKey.required("clientId", "the client id"))
    fullSpec().withAuthExtras(newAuthExtras) shouldBe fullSpec(authExtras = newAuthExtras)
  }

  "withKeylessWhen" should "set keylessWhen from a key/value pair and leave every other field unchanged" in {
    fullSpec().withKeylessWhen("mode", "keyless") shouldBe fullSpec(keylessWhen = Some("mode" -> "keyless"))
  }

  it should "set keylessWhen from an Option, including clearing it with None" in {
    fullSpec().withKeylessWhen(Some("mode" -> "keyless")) shouldBe fullSpec(keylessWhen = Some("mode" -> "keyless"))
    fullSpec().withKeylessWhen(None) shouldBe fullSpec(keylessWhen = None)
  }

  // ---------------------------------------------------------------------------------------------
  // ProviderConfigSpec.extra
  // ---------------------------------------------------------------------------------------------

  "extra" should "return the declared key matching the given name" in {
    val key  = ProviderConfigKey.required("region", "the region")
    val spec = ProviderConfigSpec(extras = Seq(key))
    spec.extra("region") shouldBe Some(key)
  }

  it should "return None when no declared key matches the given name" in {
    ProviderConfigSpec(extras = Seq(ProviderConfigKey.required("region", "the region"))).extra("profile") shouldBe None
    ProviderConfigSpec().extra("anything") shouldBe None
  }

  // ---------------------------------------------------------------------------------------------
  // ProviderConfigSpec.apiKeyAndDefaultBaseUrl
  // ---------------------------------------------------------------------------------------------

  "apiKeyAndDefaultBaseUrl" should "require an api key and default the base URL, with the given apiKeyEnv" in {
    val built = ProviderConfigSpec.apiKeyAndDefaultBaseUrl("https://api.example.com/v1", Seq("EXAMPLE_API_KEY"))
    built shouldBe ProviderConfigSpec(
      requiresApiKey = true,
      defaultBaseUrl = Some("https://api.example.com/v1"),
      apiKeyEnv = Seq("EXAMPLE_API_KEY")
    )
    built.requiresBaseUrl shouldBe false
    built.extras shouldBe Seq.empty
    built.authExtras shouldBe Seq.empty
    built.keylessWhen shouldBe None
  }

  it should "default apiKeyEnv to empty when not given" in {
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl("https://api.example.com/v1") shouldBe
      ProviderConfigSpec(requiresApiKey = true, defaultBaseUrl = Some("https://api.example.com/v1"))
  }
