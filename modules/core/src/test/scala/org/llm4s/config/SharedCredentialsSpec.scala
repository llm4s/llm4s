package org.llm4s.config

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.core.read.ListAppender
import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor, ProviderRegistry }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider, FixtureEmbeddingProvider }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.*

/**
 * A client's API key is its own `apiKey`, else its vendor's shared `llm4s.credentials.<id>.apiKey`.
 *
 * Core ships no provider, so these use fixtures and write the credentials binding a provider
 * module's `reference.conf` would hold into the test's own HOCON. Each provider module proves
 * its real binding in its own `Llm4s<Name>ModuleSpec`.
 */
class SharedCredentialsSpec extends AnyWordSpec with Matchers with EitherValues:

  /** A fixture provider that declares the variables its module would bind, and an alias. */
  private object KeyedProvider extends ProviderDescriptor:
    val id: ProviderId                = ProviderId("keyed")
    override val aliases: Set[String] = Set("keyed-alias")
    val PrimaryEnv                    = "KEYED_API_KEY"
    val SecondaryEnv                  = "KEYED_ALT_API_KEY"
    val configSpec: ProviderConfigSpec =
      ProviderConfigSpec.apiKeyAndDefaultBaseUrl(FixtureChatProvider.DefaultBaseUrl, Seq(PrimaryEnv, SecondaryEnv))

    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] =
      ProviderDescriptor
        .requireApiKey(providerName, section)
        .flatMap(FixtureChatConfig.fromValues(section.model.asString, _, FixtureChatProvider.DefaultBaseUrl))

    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
      FixtureChatProvider.buildClient(config, options)

  private given ProviderRegistry = ProviderRegistry.of(KeyedProvider, FixtureChatProvider)

  /** What `KeyedProvider`'s module would put in its `reference.conf`. */
  private val keyedBinding =
    """llm4s.credentials.keyed.apiKey = ${?KEYED_API_KEY}
      |""".stripMargin

  private def section(name: String, body: String): String =
    s"llm4s.providers.$name { $body }\n"

  private def apiKeyOf(source: ConfigSource, name: String): Result[String] =
    Llm4sConfig.provider(source, name).map {
      case fixture: FixtureChatConfig => fixture.apiKey
      case other                      => fail(s"Expected a FixtureChatConfig, got $other")
    }

  private def captureInfo(f: => Unit): List[String] =
    val logger   = LoggerFactory.getLogger(SharedCredentials.getClass).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.INFO)
    try f
    finally
      logger.detachAppender(appender)
      logger.setLevel(previous)
    appender.list.asScala.toList.filter(_.getLevel == Level.INFO).map(_.getFormattedMessage)

  "a chat section without an apiKey" should {

    "take its vendor's shared key, bound to the vendor's variable" in {
      val source = ReferenceConfig.withEnv(
        keyedBinding + section("keyed-main", """provider = "keyed", model = "m""""),
        Map("KEYED_API_KEY" -> "sk-shared")
      )
      apiKeyOf(source, "keyed-main").value shouldBe "sk-shared"
    }

    "take the canonical id's key when it names the provider by an alias" in {
      val source = ReferenceConfig.withEnv(
        keyedBinding + section("aliased", """provider = "keyed-alias", model = "m""""),
        Map("KEYED_API_KEY" -> "sk-shared")
      )
      apiKeyOf(source, "aliased").value shouldBe "sk-shared"
    }

    "not take another vendor's key" in {
      val source = ReferenceConfig.withEnv(
        "llm4s.credentials.fixturechat.apiKey = \"sk-other-vendor\"\n" +
          section("keyed-main", """provider = "keyed", model = "m""""),
        Map.empty
      )
      apiKeyOf(source, "keyed-main").left.value.message should include("apiKey")
    }

    "fail naming the variable and the section when neither is set" in {
      val source = ReferenceConfig.withEnv(
        keyedBinding + section("keyed-main", """provider = "keyed", model = "m""""),
        Map.empty
      )
      apiKeyOf(source, "keyed-main").left.value.message should include(
        "apiKey: set KEYED_API_KEY or KEYED_ALT_API_KEY, or set apiKey under llm4s.providers.keyed-main in application.conf"
      )
    }

    "name the shared path when the provider declares no variable" in {
      val source = ReferenceConfig.withEnv(section("fx", """provider = "fixturechat", model = "m""""), Map.empty)
      apiKeyOf(source, "fx").left.value.message should include(
        "apiKey: set llm4s.credentials.fixturechat.apiKey, or set apiKey under llm4s.providers.fx in application.conf"
      )
    }

    "fail on an unreadable shared entry, naming it" in {
      val source = ReferenceConfig.withEnv(
        "llm4s.credentials.keyed = 42\n" + section("keyed-main", """provider = "keyed", model = "m""""),
        Map.empty
      )
      apiKeyOf(source, "keyed-main").left.value.message should include("llm4s.credentials.keyed")
    }
  }

  "a chat section with its own apiKey" should {

    "win over the shared key" in {
      val source = ReferenceConfig.withEnv(
        keyedBinding + section("keyed-main", """provider = "keyed", model = "m", apiKey = "sk-own""""),
        Map("KEYED_API_KEY" -> "sk-shared")
      )
      apiKeyOf(source, "keyed-main").value shouldBe "sk-own"
    }

    "not be failed by an unreadable shared entry it does not use" in {
      val source = ReferenceConfig.withEnv(
        "llm4s.credentials.keyed = 42\n" +
          section("keyed-main", """provider = "keyed", model = "m", apiKey = "sk-own""""),
        Map.empty
      )
      apiKeyOf(source, "keyed-main").value shouldBe "sk-own"
    }

    "let two sections of one provider bill two accounts" in {
      val hocon = keyedBinding +
        section("keyed-main", """provider = "keyed", model = "m"""") +
        section("keyed-batch", """provider = "keyed", model = "m", apiKey = ${?KEYED_BATCH_API_KEY}""")
      val source =
        ReferenceConfig.withEnv(hocon, Map("KEYED_API_KEY" -> "sk-main", "KEYED_BATCH_API_KEY" -> "sk-batch"))

      apiKeyOf(source, "keyed-main").value shouldBe "sk-main"
      apiKeyOf(source, "keyed-batch").value shouldBe "sk-batch"
    }
  }

  "loading every section at once" should {

    "fall back to the shared key too" in {
      val source = ReferenceConfig.withEnv(
        keyedBinding + section("keyed-main", """provider = "keyed", model = "m""""),
        Map("KEYED_API_KEY" -> "sk-shared")
      )
      Llm4sConfig
        .providers(source)
        .value
        .namedProviders(ProviderName("keyed-main"))
        .apiKey
        .map(_.asKey) shouldBe Some("sk-shared")
    }
  }

  "where a key came from" should {

    "be logged at INFO as a path, never as the value" in {
      val source = ReferenceConfig.withEnv(
        keyedBinding +
          section("keyed-main", """provider = "keyed", model = "m"""") +
          section("keyed-own", """provider = "keyed", model = "m", apiKey = "sk-own-secret""""),
        Map("KEYED_API_KEY" -> "sk-shared-secret")
      )

      val messages = captureInfo {
        apiKeyOf(source, "keyed-main").isRight shouldBe true
        apiKeyOf(source, "keyed-own").isRight shouldBe true
      }

      messages should contain("llm4s.providers.keyed-main: API key from llm4s.credentials.keyed.apiKey")
      messages should contain("llm4s.providers.keyed-own: API key from llm4s.providers.keyed-own.apiKey")
      messages.exists(message => message.contains("sk-shared-secret") || message.contains("sk-own-secret")) shouldBe
        false
    }

    "be redacted from the credentials block's toString" in {
      val credentials = SharedCredentials.read(ConfigSource.string("""llm4s.credentials.keyed.apiKey = "sk-secret""""))
      credentials.toString shouldBe "SharedCredentials(keyed -> ***)"
    }

    "be redacted from a resolved key's toString" in {
      (SharedCredentials.Resolved("sk-secret", "llm4s.credentials.x.apiKey").toString should not).include("sk-secret")
    }

    "be reported per section by apiKeySources" in {
      val source = ReferenceConfig.withEnv(
        keyedBinding +
          section("keyed-main", """provider = "keyed", model = "m"""") +
          section("keyed-own", """provider = "keyed", model = "m", apiKey = "sk-own"""") +
          section("aliased", """provider = "keyed-alias", model = "m""""),
        Map.empty
      )

      Llm4sConfig.apiKeySourcesFrom(source).value shouldBe Map(
        ProviderName("keyed-main") -> ApiKeySource.Credentials("llm4s.credentials.keyed.apiKey"),
        ProviderName("keyed-own")  -> ApiKeySource.Section("llm4s.providers.keyed-own.apiKey"),
        ProviderName("aliased")    -> ApiKeySource.Credentials("llm4s.credentials.keyed.apiKey")
      )
    }
  }

  "an embeddings block without an apiKey" should {

    given ProviderRegistry = ProviderRegistry.default

    val select = "llm4s.embeddings.model = \"fixtureembedding/fixture-embed-small\"\n"
    val binding =
      s"llm4s.credentials.fixtureembedding.apiKey = $${?${FixtureEmbeddingProvider.ApiKeyEnv}}\n"

    "take the vendor's shared key" in {
      val source = ReferenceConfig.withEnv(select + binding, Map(FixtureEmbeddingProvider.ApiKeyEnv -> "sk-shared"))
      EmbeddingsConfigLoader.loadProvider(source).value._2.apiKey shouldBe "sk-shared"
    }

    "take its own key over the shared one" in {
      val source = ReferenceConfig.withEnv(
        select + binding + "llm4s.embeddings.fixtureembedding.apiKey = \"sk-own\"\n",
        Map(FixtureEmbeddingProvider.ApiKeyEnv -> "sk-shared")
      )
      EmbeddingsConfigLoader.loadProvider(source).value._2.apiKey shouldBe "sk-own"
    }

    "take the canonical id's key when selected by an alias" in {
      val source = ReferenceConfig.withEnv(
        s"llm4s.embeddings.model = \"${FixtureEmbeddingProvider.Alias}/fixture-embed-small\"\n" + binding,
        Map(FixtureEmbeddingProvider.ApiKeyEnv -> "sk-shared")
      )
      EmbeddingsConfigLoader.loadProvider(source).value._2.apiKey shouldBe "sk-shared"
    }

    "fail naming the variable and the block when neither is set" in {
      EmbeddingsConfigLoader
        .loadProvider(ReferenceConfig.withEnv(select + binding, Map.empty))
        .left
        .value
        .message shouldBe
        s"Missing fixtureembedding embeddings apiKey: set ${FixtureEmbeddingProvider.ApiKeyEnv}, or set apiKey " +
        "under llm4s.embeddings.fixtureembedding in application.conf"
    }

    "log the source path and not the value" in {
      val source =
        ReferenceConfig.withEnv(select + binding, Map(FixtureEmbeddingProvider.ApiKeyEnv -> "sk-shared-secret"))
      val messages = captureInfo(EmbeddingsConfigLoader.loadProvider(source).isRight shouldBe true)

      messages should contain(
        "llm4s.embeddings.fixtureembedding: API key from llm4s.credentials.fixtureembedding.apiKey"
      )
      messages.exists(_.contains("sk-shared-secret")) shouldBe false
    }
  }

  "the credentials block" should {

    "read as empty when absent" in {
      SharedCredentials.read(ConfigSource.string("llm4s {}")) shouldBe SharedCredentials.empty
    }

    "treat a blank key as unset" in {
      SharedCredentials
        .read(ConfigSource.string("""llm4s.credentials.keyed.apiKey = "  """"))
        .apiKey(ProviderId("keyed"))
        .value shouldBe None
    }

    "fail every lookup, and nothing else, when it is not an object" in {
      val credentials = SharedCredentials.read(ConfigSource.string("llm4s.credentials = 42"))
      credentials.apiKey(ProviderId("keyed")).left.value.message should include("llm4s.credentials")
      credentials.resolve(Some("sk-own"), "p", ProviderId("keyed")).value.map(_.value) shouldBe Some("sk-own")
    }
  }
