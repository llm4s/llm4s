package org.llm4s.config

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.core.read.ListAppender
import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor, ProviderRegistry }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.*

/**
 * Provider-specific keys in named provider sections (#1215): how a descriptor declares them
 * in `ProviderConfigSpec.extras`, how they travel raw -> normalised -> `NamedProviderConfig`,
 * and how the section validator checks them.
 *
 * `RegionalProvider` is the shape Bedrock needs - a required `region`, an optional `profile`,
 * and a `tier` with a default - plus a deprecated alias from a repurposed built-in field, as
 * Vertex AI has. It builds a `FixtureChatConfig`, putting the region in the base URL so a
 * round trip can show the value reached `buildConfig`.
 */
class ProviderConfigExtrasSpec extends AnyFlatSpec with Matchers:

  object RegionalProvider extends ProviderDescriptor:
    val id: ProviderId = ProviderId("regional")

    val configSpec: ProviderConfigSpec = ProviderConfigSpec(
      requiresApiKey = true,
      extras = Seq(
        ProviderConfigKey(
          "region",
          "the cloud region hosting the model, e.g. eu-west-1",
          required = true,
          deprecatedAliases = Seq("organization")
        ),
        ProviderConfigKey.optional("profile", "the named credentials profile"),
        ProviderConfigKey.optional("tier", "the service tier", default = Some("standard"))
      )
    )

    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] =
      for
        apiKey <- ProviderDescriptor.requireApiKey(providerName, section)
        region <- ProviderDescriptor.requireExtra(providerName, section, "region")
        config <- FixtureChatConfig.fromValues(section.model.asString, apiKey, s"https://$region.regional.invalid")
      yield config

    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
      ModelRegistryService
    ): Result[LLMClient] = Left(ConfigurationError("not needed for this test"))

  /** A provider whose only difference from the fixture is an environment variable it reads. */
  object EnvBoundProvider extends ProviderDescriptor:
    val id: ProviderId = ProviderId("envbound")

    val configSpec: ProviderConfigSpec = ProviderConfigSpec(
      requiresBaseUrl = true,
      baseUrlExample = "e.g. http://localhost:9000",
      baseUrlEnv = Some("ENVBOUND_BASE_URL"),
      extras = Seq(ProviderConfigKey("space", "the deployment space id", required = true, env = Some("ENVBOUND_SPACE")))
    )

    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] = Left(ConfigurationError("not needed for this test"))

    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
      ModelRegistryService
    ): Result[LLMClient] = Left(ConfigurationError("not needed for this test"))

  /** A descriptor that only has a spec: for validation cases that never build a config. */
  private def stub(idName: String, spec: ProviderConfigSpec): ProviderDescriptor =
    new ProviderDescriptor:
      val id: ProviderId                 = ProviderId(idName)
      val configSpec: ProviderConfigSpec = spec
      def buildConfig(providerName: String, section: NamedProviderConfig)(using
        ContextWindowResolver
      ): Result[ProviderConfig] = Left(ConfigurationError("unused"))
      def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
        ModelRegistryService
      ): Result[LLMClient] = Left(ConfigurationError("unused"))

  /** A key renamed twice: `proj` (a former extra key), then `organization` (a built-in field). */
  private val twoAliases = stub(
    "twoaliases",
    ProviderConfigSpec(extras =
      Seq(ProviderConfigKey("projectId", "the project", deprecatedAliases = Seq("proj", "organization")))
    )
  )

  private given ProviderRegistry = ProviderRegistry.of(RegionalProvider, EnvBoundProvider, FixtureChatProvider)

  private def section(
    provider: String = "regional",
    apiKey: Option[String] = Some("k"),
    organization: Option[String] = None,
    extras: Map[String, String] = Map.empty
  ): RawNamedProviderSection =
    RawNamedProviderSection(Some(provider), Some("m"), None, apiKey, organization, None, None, extras = extras)

  private def validated(
    raw: RawNamedProviderSection,
    descriptor: ProviderDescriptor = RegionalProvider,
    name: String = "my-regional"
  ): Result[(NamedProviderConfig, Seq[String])] =
    val providerName = ProviderName(name)
    NamedProviderConfigNormalizer
      .normalize(providerName, raw)
      .flatMap(NamedProviderSectionValidator.validateWithWarnings(providerName, descriptor, _))

  private def ok(result: Result[(NamedProviderConfig, Seq[String])]): (NamedProviderConfig, Seq[String]) =
    result.fold(err => fail(s"Expected Right, got ${err.message}"), identity)

  private def error(result: Result[?]): String =
    result.left.toOption.getOrElse(fail(s"Expected Left, got $result")).message

  "declared keys" should "carry required and optional values through to NamedProviderConfig" in {
    val (config, warnings) = ok(validated(section(extras = Map("region" -> " eu-west-1 ", "profile" -> "dev"))))

    config.extras shouldBe Map("region" -> "eu-west-1", "profile" -> "dev", "tier" -> "standard")
    config.extra("region") shouldBe Some("eu-west-1")
    warnings shouldBe empty
  }

  it should "fill in a default only when the section omits the key" in {
    ok(validated(section(extras = Map("region" -> "r"))))._1.extra("tier") shouldBe Some("standard")
    ok(validated(section(extras = Map("region" -> "r", "tier" -> "priority"))))._1.extra("tier") shouldBe
      Some("priority")
  }

  it should "leave an optional key without a default absent" in {
    ok(validated(section(extras = Map("region" -> "r"))))._1.extra("profile") shouldBe None
  }

  it should "treat a blank value as absent" in {
    error(validated(section(extras = Map("region" -> "   ")))) should include("- region:")
  }

  "a missing required key" should "fail, naming the key, the section and what the key means" in {
    val message = error(validated(section()))

    message should include("Provider 'my-regional' (provider = regional) is missing required fields")
    message should include(
      "- region: the cloud region hosting the model, e.g. eu-west-1 (set it in llm4s.conf under providers.my-regional.region)"
    )
  }

  it should "be reported alongside missing built-in fields" in {
    val message = error(validated(section(apiKey = None)))

    message should include("- apiKey:")
    message should include("- region:")
  }

  "unknown keys" should "be dropped with a warning that names them and what the provider accepts" in {
    val (config, warnings) = ok(validated(section(extras = Map("region" -> "r", "regoin" -> "typo"))))

    config.extras.keySet shouldBe Set("region", "tier")
    warnings should have size 1
    warnings.head should include("llm4s.providers.my-regional has unknown key(s) regoin, which are ignored")
    warnings.head should include("provider = regional also accepts region, profile, tier")
    warnings.head should include("baseUrl")
  }

  it should "say so when the provider declares no provider-specific keys" in {
    val (config, warnings) =
      ok(validated(section(provider = "fixturechat", extras = Map("region" -> "r")), FixtureChatProvider))

    config.extras shouldBe empty
    warnings.head should include("provider = fixturechat declares no provider-specific keys")
  }

  it should "be logged as a warning by validate" in {
    val logger   = LoggerFactory.getLogger(NamedProviderSectionValidator.getClass).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.WARN)

    val name = ProviderName("logged")
    val result = NamedProviderConfigNormalizer
      .normalize(name, section(extras = Map("region" -> "r", "bogus" -> "x")))
      .flatMap(NamedProviderSectionValidator.validate(name, RegionalProvider, _))

    logger.detachAppender(appender)
    logger.setLevel(previous)

    result.isRight shouldBe true
    val events = appender.list.asScala.toList
    events.map(_.getLevel) should contain(Level.WARN)
    events.map(_.getFormattedMessage).exists(_.contains("unknown key(s) bogus")) shouldBe true
  }

  "a deprecated alias" should "stand in for the key, with a deprecation warning" in {
    val (config, warnings) = ok(validated(section(organization = Some("eu-west-1"))))

    config.extra("region") shouldBe Some("eu-west-1")
    warnings shouldBe Seq(
      "llm4s.providers.my-regional.organization is deprecated for provider = regional; rename it to " +
        "llm4s.providers.my-regional.region. The old name will stop working in a future release."
    )
  }

  it should "not be reported as an unknown key when it is a former extra key" in {
    object Renamed extends ProviderDescriptor:
      val id: ProviderId = ProviderId("renamed")
      val configSpec: ProviderConfigSpec =
        ProviderConfigSpec(extras =
          Seq(ProviderConfigKey("projectId", "the project", deprecatedAliases = Seq("project")))
        )
      def buildConfig(providerName: String, section: NamedProviderConfig)(using
        ContextWindowResolver
      ): Result[ProviderConfig] = Left(ConfigurationError("unused"))
      def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
        ModelRegistryService
      ): Result[LLMClient] = Left(ConfigurationError("unused"))

    val (config, warnings) = ok(validated(section(provider = "renamed", extras = Map("project" -> "p")), Renamed))

    config.extras shouldBe Map("projectId" -> "p")
    warnings should have size 1
    warnings.head should include("project is deprecated")
  }

  it should "be accepted, with a warning, when it agrees with the key" in {
    val (config, warnings) = ok(validated(section(organization = Some("r"), extras = Map("region" -> "r"))))

    config.extra("region") shouldBe Some("r")
    warnings.head should include("organization is deprecated")
  }

  it should "be an error when it disagrees with the key" in {
    val message = error(validated(section(organization = Some("us-east-1"), extras = Map("region" -> "eu-west-1"))))

    message should include(
      "- region: also set, to a different value, as its deprecated alias `organization`; remove llm4s.providers.my-regional.organization"
    )
  }

  "a descriptor" should "not be able to declare a built-in field as a provider-specific key" in {
    object Clashing extends ProviderDescriptor:
      val id: ProviderId                 = ProviderId("clashing")
      val configSpec: ProviderConfigSpec = ProviderConfigSpec(extras = Seq(ProviderConfigKey.optional("baseUrl", "x")))
      def buildConfig(providerName: String, section: NamedProviderConfig)(using
        ContextWindowResolver
      ): Result[ProviderConfig] = Left(ConfigurationError("unused"))
      def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
        ModelRegistryService
      ): Result[LLMClient] = Left(ConfigurationError("unused"))

    error(validated(section(provider = "clashing"), Clashing)) should include(
      "Provider 'clashing' declares provider-specific key(s) baseUrl, which are built-in named-provider fields"
    )
  }

  it should "not be able to use a built-in field without a string form as a deprecated alias" in {
    val spec = ProviderConfigSpec(extras =
      Seq(
        ProviderConfigKey("modelId", "x", deprecatedAliases = Seq("model")),
        ProviderConfigKey("h", "x", deprecatedAliases = Seq("headers"))
      )
    )

    val message = error(validated(section(provider = "badalias"), stub("badalias", spec)))
    message should include("Provider 'badalias' declares built-in field(s) model, headers as deprecated aliases")
    message should include(
      "only apiKey, apiVersion, baseUrl, contextWindow, endpoint, organization, reserveCompletion can"
    )
  }

  "a built-in field as a deprecated alias" should "resolve for every field with a string form, apiKey included" in {
    val spec = ProviderConfigSpec(extras =
      Seq(
        ProviderConfigKey("token", "the access token", required = true, deprecatedAliases = Seq("apiKey")),
        ProviderConfigKey("window", "the window", deprecatedAliases = Seq("contextWindow"))
      )
    )
    val raw = section(provider = "tokened", apiKey = Some("secret-token")).copy(contextWindow = Some(4096))

    val (config, warnings) = ok(validated(raw, stub("tokened", spec)))

    config.extras shouldBe Map("token" -> "secret-token", "window" -> "4096")
    warnings.map(_.takeWhile(_ != ';')) shouldBe Seq(
      "llm4s.providers.my-regional.apiKey is deprecated for provider = tokened",
      "llm4s.providers.my-regional.contextWindow is deprecated for provider = tokened"
    )
  }

  "several deprecated aliases" should "be accepted when they agree, with a warning for each" in {
    val (config, warnings) =
      ok(validated(section(provider = "twoaliases", organization = Some("p"), extras = Map("proj" -> "p")), twoAliases))

    config.extras shouldBe Map("projectId" -> "p")
    warnings.map(_.takeWhile(_ != ';')) shouldBe Seq(
      "llm4s.providers.my-regional.proj is deprecated for provider = twoaliases",
      "llm4s.providers.my-regional.organization is deprecated for provider = twoaliases"
    )
  }

  it should "be an error when they disagree, rather than silently using the first" in {
    val message =
      error(
        validated(
          section(provider = "twoaliases", organization = Some("new"), extras = Map("proj" -> "old")),
          twoAliases
        )
      )

    message should include(
      "- projectId: set through several deprecated aliases with different values (`proj` = \"old\", " +
        "`organization` = \"new\"); replace them with llm4s.providers.my-regional.projectId"
    )
  }

  "environment-variable hints" should "show a declared variable as the binding that reads it" in {
    val message = error(validated(section(provider = "envbound", apiKey = None), EnvBoundProvider, "my-env"))

    message should include(
      "- baseUrl: set it in llm4s.conf under providers.my-env.baseUrl (e.g. http://localhost:9000; to read it " +
        "from ENVBOUND_BASE_URL, add baseUrl = ${?ENVBOUND_BASE_URL} to the section)"
    )
    message should include(
      "- space: the deployment space id (set it in llm4s.conf under providers.my-env.space; to read it from " +
        "ENVBOUND_SPACE, add space = ${?ENVBOUND_SPACE} to the section)"
    )
    // Named sections read no variable unbound, so it is never "set X" on its own.
    (message should not).include("or set")
  }

  it should "not invent <PROVIDER>_BASE_URL for a provider that declares none" in {
    object NoEnv extends ProviderDescriptor:
      val id: ProviderId                 = ProviderId("noenv")
      val configSpec: ProviderConfigSpec = ProviderConfigSpec(requiresBaseUrl = true)
      def buildConfig(providerName: String, section: NamedProviderConfig)(using
        ContextWindowResolver
      ): Result[ProviderConfig] = Left(ConfigurationError("unused"))
      def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
        ModelRegistryService
      ): Result[LLMClient] = Left(ConfigurationError("unused"))

    val message = error(validated(section(provider = "noenv"), NoEnv, "my-noenv"))

    message should include(
      "- baseUrl: set it in llm4s.conf under providers.my-noenv.baseUrl (e.g. https://api.example.com/)"
    )
    (message should not).include("NOENV_BASE_URL")
    (message should not).include("or set")
  }

  "NamedProviderConfig.toString" should "redact extra values but keep their names" in {
    val (config, _) = ok(validated(section(extras = Map("region" -> "secret-region"))))

    config.toString should include("region -> ***")
    (config.toString should not).include("secret-region")
  }

  "HOCON" should "round-trip provider-specific keys from a section to the provider's config" in {
    val hocon =
      """
        |llm4s.providers {
        |  my-regional {
        |    provider = "regional"
        |    model    = "m"
        |    apiKey   = "k"
        |    region   = "eu-west-1"
        |    tier     = 2
        |    enabled  = true
        |    profile  = null
        |  }
        |}
        |""".stripMargin

    val raw = RawProvidersConfigLoader.load(ConfigSource.string(hocon)).fold(e => fail(e.message), identity)
    raw.namedProviders(ProviderName("my-regional")).extras shouldBe
      Map("region" -> "eu-west-1", "tier" -> "2", "enabled" -> "true")

    val providers = ProvidersConfigLoader.load(ConfigSource.string(hocon)).fold(e => fail(e.message), identity)
    providers.namedProviders(ProviderName("my-regional")).extras shouldBe Map("region" -> "eu-west-1", "tier" -> "2")

    Llm4sConfig.provider(ConfigSource.string(hocon), "my-regional") match
      case Right(config: FixtureChatConfig) => config.baseUrl shouldBe "https://eu-west-1.regional.invalid"
      case other                            => fail(s"Expected FixtureChatConfig, got $other")
  }

  it should "fail to load a section whose missing required key is provider-specific" in {
    val hocon =
      """
        |llm4s.providers.my-regional { provider = "regional", model = "m", apiKey = "k" }
        |""".stripMargin

    error(Llm4sConfig.provider(ConfigSource.string(hocon), "my-regional")) should include(
      "- region: the cloud region hosting the model"
    )
  }

  it should "reject an object or list where a provider-specific key is expected" in {
    val hocon =
      """
        |llm4s.providers.my-regional { provider = "regional", model = "m", apiKey = "k", region { a = 1 } }
        |""".stripMargin

    val message = error(RawProvidersConfigLoader.load(ConfigSource.string(hocon)))
    message should include("Failed to load raw providers config")
    message should include("provider-specific key")
    message should include("region")
    message should include("must be a string, number or boolean")
  }
