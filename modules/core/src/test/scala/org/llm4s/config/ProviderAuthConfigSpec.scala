package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.{ AuthConfig, IdentitySource }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor, ProviderRegistry }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import java.nio.file.Path

class ProviderAuthConfigSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:

  /** A fixture provider that needs an API key unless the section uses auth, and declares auth extras. */
  private object AuthProvider extends ProviderDescriptor:
    val id: ProviderId = ProviderId("authfixture")
    val configSpec: ProviderConfigSpec =
      ProviderConfigSpec
        .apiKeyAndDefaultBaseUrl(FixtureChatProvider.DefaultBaseUrl, Seq("AUTHFIXTURE_API_KEY"))
        .withAuthExtras(
          Seq(
            ProviderConfigKey.required("tokenUrl", "the token endpoint"),
            ProviderConfigKey.optional("clientId", "the client id"),
            ProviderConfigKey.optional("scope", "the scope", default = Some("all-apis"))
          )
        )
    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] =
      FixtureChatConfig.fromValues(section.model.asString, "unused", FixtureChatProvider.DefaultBaseUrl)
    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
      FixtureChatProvider.buildClient(config, options)

  private given ProviderRegistry = ProviderRegistry.of(AuthProvider, FixtureChatProvider)

  private val sharedKey = "llm4s.credentials.authfixture.apiKey = \"sk-shared\"\n"

  private def load(hocon: String, name: String = "main"): Result[NamedProviderConfig] =
    ProvidersConfigLoader.loadSections(ConfigSource.string(hocon)).flatMap(_.validated(ProviderName(name)))

  private def section(body: String) = s"llm4s.providers.main { provider = authfixture, model = m, $body }\n"

  "an auth block" should {
    "parse the identity-token file and pass the other keys as auth extras, defaults applied" in {
      val config = load(section("""auth { identityTokenFile = "/var/run/svid", tokenUrl = "https://t" }""")).value
      config.apiKey shouldBe None
      config.auth.value.identityToken shouldBe IdentitySource.File(Path.of("/var/run/svid").toAbsolutePath)
      config.auth.value.extras shouldBe Map("tokenUrl" -> "https://t", "scope" -> "all-apis")
    }

    "report an identity-token path the platform cannot represent as a configuration error, not an exception" in {
      // HOCON turns the escape into a NUL character, which no platform accepts in a path.
      val error = load(section("auth { identityTokenFile = \"svid\\u0000file\", tokenUrl = \"https://t\" }")).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("identityTokenFile")
    }

    "be read beside a timeouts block, each kept as its own built-in field" in {
      val config = load(
        section("""auth { identityToken = "eyJ.x.y", tokenUrl = "https://t" }, timeouts { request = 3m }""")
      ).value
      config.auth.value.identityToken shouldBe IdentitySource.Literal("eyJ.x.y")
      config.timeouts.request shouldBe Some(scala.concurrent.duration.DurationInt(3).minutes)
      config.extras shouldBe empty
      config.withTimeouts(config.timeouts).auth shouldBe config.auth
      config.withAuth(None).timeouts shouldBe config.timeouts
    }

    "accept a literal identity token" in {
      load(
        section("""auth { identityToken = "eyJ.x.y", tokenUrl = "https://t" }""")
      ).value.auth.value.identityToken shouldBe
        IdentitySource.Literal("eyJ.x.y")
    }

    "resolves a relative identityTokenFile to an absolute path" in {
      val file = load(
        section("""auth { identityTokenFile = "secrets/svid", tokenUrl = "https://t" }""")
      ).value.auth.value.identityToken
      file shouldBe IdentitySource.File(Path.of("secrets/svid").toAbsolutePath.normalize)
    }

    "treats an unset optional auth key as absent" in {
      val hocon =
        section("""auth { identityTokenFile = "/s", tokenUrl = "https://t", clientId = ${?LLM4S_TEST_UNSET_VAR} }""")
      load(hocon).value.auth.value.extra("clientId") shouldBe None
    }

    "treat a null auth key as absent" in {
      load(section("""auth { identityTokenFile = "/s", tokenUrl = "https://t", clientId = null }""")).value.auth.value
        .extra("clientId") shouldBe None
    }

    "accept numbers and booleans as strings" in {
      load(
        section("""auth { identityTokenFile = "/s", tokenUrl = "https://t", clientId = 42, scope = true }""")
      ).value.auth.value.extras shouldBe
        Map("tokenUrl" -> "https://t", "clientId" -> "42", "scope" -> "true")
    }

    "ignores the shared credential when the section uses auth" in {
      val config = load(sharedKey + section("""auth { identityTokenFile = "/s", tokenUrl = "https://t" }""")).value
      config.apiKey shouldBe None
      config.auth shouldBe defined
    }

    "still use the shared credential when the section has no auth" in {
      load(sharedKey + "llm4s.providers.main { provider = authfixture, model = m }\n").value.apiKey
        .map(_.asKey) shouldBe
        Some("sk-shared")
    }

    "reject both apiKey and auth in one section" in {
      val error =
        load(section("""apiKey = "sk", auth { identityTokenFile = "/s", tokenUrl = "https://t" }""")).left.value
      error shouldBe a[ConfigurationError]
      error.message should (include("apiKey").and(include("auth")))
    }

    "reject an Authorization header beside auth, in any case, since the exchanged token is the bearer" in {
      for header <- Seq("Authorization", "authorization", "AUTHORIZATION") do
        val error = load(
          section(
            s"""headers { "$header" = "Bearer static" }, auth { identityTokenFile = "/s", tokenUrl = "https://t" }"""
          )
        ).left.value
        error shouldBe a[ConfigurationError]
        error.message should (include("Authorization").and(include("llm4s.providers.main.headers")))
    }

    "accept other headers beside auth" in {
      load(
        section("""headers { "X-Trace" = "1" }, auth { identityTokenFile = "/s", tokenUrl = "https://t" }""")
      ).value.headers shouldBe Map("X-Trace" -> "1")
    }

    "reject neither or both identity-token keys" in {
      load(section("""auth { tokenUrl = "https://t" }""")).left.value.message should include("identityTokenFile")
      load(
        section("""auth { identityTokenFile = "/s", identityToken = "x", tokenUrl = "https://t" }""")
      ).left.value.message should
        include("only one")
    }

    "treat a blank identity-token value as unset" in {
      load(section("""auth { identityTokenFile = "  ", tokenUrl = "https://t" }""")).left.value.message should
        include("identityTokenFile")
    }

    "reject a missing required auth extra" in {
      load(section("""auth { identityTokenFile = "/s" }""")).left.value.message should
        (include("auth.tokenUrl").and(include("missing required fields")))
    }

    "reject auth on a provider that does not support it" in {
      val hocon = """llm4s.providers.main { provider = fixturechat, model = m, auth { identityTokenFile = "/s" } }"""
      val error = load(hocon).left.value
      error shouldBe a[ConfigurationError]
      error.message should (include("fixturechat").and(include("does not support workload-identity")))
    }

    "reject a nested object inside auth" in {
      val error = load(section("""auth { identityTokenFile = "/s", tokenUrl = { a = b } }""")).left.value
      error.message should include("auth key")
    }

    "reject an auth value that is not an object" in {
      load(section("""auth = "nope"""")).isLeft shouldBe true
    }

    "fail only the section that is loaded" in {
      val hocon = section("""auth { identityTokenFile = "/s", tokenUrl = "https://t" }""") +
        """llm4s.providers.broken { provider = authfixture, model = m, auth { tokenUrl = "x" } }"""
      load(hocon).isRight shouldBe true
      load(hocon, "broken").isLeft shouldBe true
    }

    "warn about an auth key the provider does not declare, and drop it" in {
      val raw = ProvidersConfigLoader
        .loadSections(
          ConfigSource.string(section("""auth { identityTokenFile = "/s", tokenUrl = "https://t", typo = x }"""))
        )
        .value
        .sections(ProviderName("main"))
        .value
      val normalized = NamedProviderConfigNormalizer.normalize(ProviderName("main"), raw).value
      val (config, warnings) =
        NamedProviderSectionValidator.validateWithWarnings(ProviderName("main"), AuthProvider, normalized).value
      config.auth.value.extras.keySet should not contain "typo"
      warnings.mkString should (include("typo").and(include("identityTokenFile")))
    }

    "redact auth extras and a literal token in toString" in {
      val config = load(section("""auth { identityToken = "eyJ.secret", tokenUrl = "https://secret-host" }""")).value
      (config.toString should not).include("eyJ.secret")
      (config.toString should not).include("secret-host")
      config.toString should include("tokenUrl")
    }
  }

  "a descriptor's auth keys" should {
    "not be allowed to shadow the identity-token keys" in {
      object Shadowing extends ProviderDescriptor:
        val id: ProviderId = ProviderId("shadowing")
        val configSpec: ProviderConfigSpec =
          ProviderConfigSpec(requiresApiKey = true)
            .withAuthExtras(Seq(ProviderConfigKey.optional("identityTokenFile", "oops")))
        def buildConfig(providerName: String, section: NamedProviderConfig)(using
          ContextWindowResolver
        ): Result[ProviderConfig] = FixtureChatProvider.buildConfig(providerName, section)
        def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
          ModelRegistryService
        ): Result[LLMClient] = FixtureChatProvider.buildClient(config, options)

      given ProviderRegistry = ProviderRegistry.of(Shadowing)
      val hocon = """llm4s.providers.s { provider = shadowing, model = m, auth { identityTokenFile = "/s" } }"""
      ProvidersConfigLoader
        .loadSections(ConfigSource.string(hocon))
        .flatMap(_.validated(ProviderName("s")))
        .left
        .value
        .message should include("identity-token keys")
    }

    "support auth exactly when they declare auth keys" in {
      AuthProvider.configSpec.supportsAuth shouldBe true
      FixtureChatProvider.configSpec.supportsAuth shouldBe false
    }

    "not accept an auth key named auth as a provider extra" in {
      ProviderConfigSpec.BuiltinKeys should contain("auth")
    }
  }

  "ProviderDescriptor.requireAuthExtra" should {
    val auth = AuthConfig(IdentitySource.Literal("t"), Map("tokenUrl" -> "https://t"))
    "read a key that is present" in {
      ProviderDescriptor.requireAuthExtra("p", auth, "tokenUrl") shouldBe Right("https://t")
    }
    "fail with a ConfigurationError naming the key and the section for a missing one" in {
      val error = ProviderDescriptor.requireAuthExtra("p", auth, "clientId").left.value
      error shouldBe a[ConfigurationError]
      error.message should (include("auth.clientId").and(include("llm4s.providers.p.auth.clientId")))
    }
    "report a key built blank in code as missing, naming it" in {
      val blank = AuthConfig(IdentitySource.Literal("t"), Map("tokenUrl" -> "  "))
      ProviderDescriptor.requireAuthExtra("p", blank, "tokenUrl").left.value.message should include(
        "llm4s.providers.p.auth.tokenUrl"
      )
    }
  }

  "AuthConfig" should {
    "trim its extras and drop blank ones, through apply and withExtras" in {
      val auth = AuthConfig(IdentitySource.Literal("t"), Map("tokenUrl" -> " https://t ", "clientId" -> ""))
      auth.extras shouldBe Map("tokenUrl" -> "https://t")
      auth.withExtras(Map("scope" -> " ", "audience" -> "a")).extras shouldBe Map("audience" -> "a")
      auth.extra("clientId") shouldBe None
    }
  }

  "NamedProviderConfig.withAuth" should {
    "set and clear the auth block" in {
      val config = load(section("""auth { identityTokenFile = "/s", tokenUrl = "https://t" }""")).value
      val auth   = config.auth.value
      config.withAuth(None).auth shouldBe None
      config.withAuth(auth).auth shouldBe Some(auth)
    }
  }

  "apiKeySources" should {
    "report a section with auth as WorkloadIdentity" in {
      val hocon = section("""auth { identityTokenFile = "/s", tokenUrl = "https://t" }""")
      ProvidersConfigLoader.loadSections(ConfigSource.string(hocon)).value.apiKeySources shouldBe
        Map(ProviderName("main") -> ApiKeySource.WorkloadIdentity("llm4s.providers.main.auth"))
    }
    "not report WorkloadIdentity for a provider that rejects auth" in {
      val hocon =
        """llm4s.providers.main { provider = fixturechat, model = m, auth { identityTokenFile = "/s" } }"""
      ProvidersConfigLoader
        .loadSections(ConfigSource.string(hocon))
        .value
        .apiKeySources(ProviderName("main")) shouldBe a[ApiKeySource.Credentials]
    }

    "keep reporting the shared credential for a section with neither key" in {
      ProvidersConfigLoader
        .loadSections(ConfigSource.string("llm4s.providers.main { provider = authfixture, model = m }"))
        .value
        .apiKeySources(ProviderName("main")) shouldBe a[ApiKeySource.Credentials]
    }
  }
