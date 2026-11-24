package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.{ Config, ConfigFactory }
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testutil.{ FixtureChatClient, FixtureChatConfig }
import org.llm4s.trace.TracingMode
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** `Llm4sConfig.from`: llm4s settings read from a config the application supplies (#2340). */
class Llm4sSettingsSpec extends AnyWordSpec with Matchers with EitherValues:

  private def parse(hocon: String): Config = ConfigFactory.parseString(hocon)

  private def section(name: String, body: String, default: Boolean = true): String =
    val selected = if default then s"""llm4s.providers.provider = "$name"\n""" else ""
    s"${selected}llm4s.providers.$name { $body }\n"

  private def settings(hocon: String): Llm4sSettings = Llm4sConfig.from(parse(hocon)).value

  private def fixture(config: ProviderConfig): FixtureChatConfig = config match
    case f: FixtureChatConfig => f
    case other                => fail(s"Expected FixtureChatConfig, got $other")

  private def withSystemProperty[A](key: String, value: String)(body: => A): A =
    val original = System.getProperty(key)
    // scalafix:off DisableSyntax.NoConfigFactory
    try
      System.setProperty(key, value)
      ConfigFactory.invalidateCaches()
      body
    finally
      if original == null then System.clearProperty(key) else System.setProperty(key, original)
      ConfigFactory.invalidateCaches()
    // scalafix:on DisableSyntax.NoConfigFactory

  "Llm4sConfig.from" should {

    "read the default provider from the given config, not application.conf" in {
      val cfg = settings(section("tenant-a", """provider = "fixturechat", model = "model-a", apiKey = "key-a""""))
        .defaultProvider()
        .value
      fixture(cfg).model shouldBe "model-a"
      fixture(cfg).apiKey shouldBe "key-a"
    }

    "keep two configs in one JVM independent, down to the clients they build" in {
      val a = settings(section("tenant", """provider = "fixturechat", model = "model-a", apiKey = "key-a""""))
      val b = settings(section("tenant", """provider = "fixturechat", model = "model-b", apiKey = "key-b""""))

      val clients = for
        registryA <- a.modelRegistryService()
        cfgA      <- a.defaultProvider()
        clientA   <- LLMConnect.getClient(cfgA)(using registryA)
        registryB <- b.modelRegistryService()
        cfgB      <- b.defaultProvider()
        clientB   <- LLMConnect.getClient(cfgB)(using registryB)
      yield (clientA, clientB)

      clients.value match
        case (ca: FixtureChatClient, cb: FixtureChatClient) =>
          ca.config.model shouldBe "model-a"
          ca.config.apiKey shouldBe "key-a"
          cb.config.model shouldBe "model-b"
          cb.config.apiKey shouldBe "key-b"
        case other => fail(s"Expected two FixtureChatClients, got $other")

      // The process-wide default is untouched by either.
      fixture(Llm4sConfig.defaultProvider().value).model shouldBe "fixture-model"
    }

    "resolve a named section and list the sections" in {
      // Another spec may leave a cached -Dllm4s.providers.* property behind; system properties
      // override the given config, so start from the current (restored) set.
      // scalafix:off DisableSyntax.NoConfigFactory
      ConfigFactory.invalidateCaches()
      // scalafix:on DisableSyntax.NoConfigFactory
      val s = settings(
        section("main", """provider = "fixturechat", model = "m1", apiKey = "k1"""") +
          section("other", """provider = "fixturechat", model = "m2", apiKey = "k2"""", default = false)
      )
      fixture(s.provider("other").value).model shouldBe "m2"
      s.defaultProviderName().value shouldBe ProviderName("main")
      s.providers().value.namedProviders.keySet shouldBe Set(ProviderName("main"), ProviderName("other"))
      val sections = s.providerConfigs().value
      sections.keySet shouldBe Set(ProviderName("main"), ProviderName("other"))
      sections.values.forall(_.isRight) shouldBe true
    }

    "fall back to the vendor's shared llm4s.credentials key, as the default source does" in {
      val s = settings(
        """llm4s.credentials.fixturechat.apiKey = "shared-key"
          |""".stripMargin + section("main", """provider = "fixturechat", model = "m"""")
      )
      fixture(s.defaultProvider().value).apiKey shouldBe "shared-key"
      s.apiKeySources().value(ProviderName("main")) shouldBe a[ApiKeySource.Credentials]
    }

    "let JVM system properties override the given config, as they override application.conf" in {
      withSystemProperty("llm4s.providers.main.apiKey", "from-system-property") {
        val cfg = settings(section("main", """provider = "fixturechat", model = "m", apiKey = "from-config""""))
          .defaultProvider()
          .value
        fixture(cfg).apiKey shouldBe "from-system-property"
      }
    }

    "keep every module's reference.conf beneath the given config" in {
      val s = settings("")
      s.tracing().value.mode shouldBe TracingMode.Console
      s.modelRegistryConfig().value shouldBe org.llm4s.model.ModelRegistryConfig.default
      s.httpNetwork().isRight shouldBe true
      s.exchangeLogging().isRight shouldBe true
    }

    "read one model registry source, and refuse two" in {
      settings("""llm4s.modelRegistry.filePath = "/tmp/models.json"""").modelRegistryConfig().value.source shouldBe
        org.llm4s.model.ModelRegistrySource.File("/tmp/models.json")
      settings(
        """llm4s.modelRegistry { filePath = "/tmp/models.json", url = "https://example.invalid/m.json" }"""
      ).modelRegistryConfig().isLeft shouldBe true
    }

    "read the tracing block of the given config" in {
      settings("""llm4s.tracing.mode = "noop"""").tracing().value.mode shouldBe TracingMode.NoOp
    }

    "read the embeddings block of the given config" in {
      val s = settings(
        """llm4s.embeddings {
          |  model = "fixtureembedding/fixture-embed-small"
          |  fixtureembedding { apiKey = "emb-key" }
          |}
          |""".stripMargin
      )
      val (provider, cfg) = s.embeddings().value
      provider shouldBe "fixtureembedding"
      cfg.model shouldBe "fixture-embed-small"
      s.textEmbeddingModel().value.model.name shouldBe "fixture-embed-small"
    }

    "report a section that is not there as Llm4sConfig does" in {
      val s = settings(section("main", """provider = "fixturechat", model = "m", apiKey = "k""""))
      s.provider("missing").isLeft shouldBe true
      s.listModels("missing").isLeft shouldBe true
    }

    "fail with a ConfigurationError, not an exception, when the config cannot be resolved" in {
      val result = Llm4sConfig.from(parse("llm4s.providers.main.model = ${LLM4S_2340_SURELY_UNSET_VARIABLE}"))
      result.left.value shouldBe a[ConfigurationError]
    }

    "report a modelRegistry key that is not a string, instead of quietly using the bundled registry (#2804)" in {
      settings("llm4s.modelRegistry.filePath = [1, 2]").modelRegistryConfig().left.value shouldBe a[ConfigurationError]
      settings("llm4s.modelRegistry.url = { a = 1 }").modelRegistryService().left.value shouldBe a[ConfigurationError]
      settings("""llm4s.modelRegistry.filePath = " /tmp/models.json " """).modelRegistryConfig().value.source shouldBe
        org.llm4s.model.ModelRegistrySource.File("/tmp/models.json")
    }

    "route a section built in code by llm4s.http, as it routes a configured one (#2804)" in {
      import org.llm4s.config.ProvidersConfigModel.{ ApiKey, ModelName, NamedProviderConfig, ProviderId }
      val section = NamedProviderConfig(ProviderId("fixturechat"), ModelName("m"), None, Some(ApiKey("k")))
      val built = settings("""llm4s.http.proxy.url = "http://proxy.corp:3128" """)
        .providerFromSection(ProviderName("built"), section)
        .value
      built.network.proxy.map(_.host) shouldBe Some("proxy.corp")
      // and the process-wide configuration, which is what llm4s-repl reads
      withSystemProperty("llm4s.http.proxy.url", "http://proxy.corp:3128") {
        Llm4sConfig.providerFromSection(ProviderName("built"), section).value.network.proxy.map(_.host)
      } shouldBe Some("proxy.corp")
    }

    "honour an explicit ProviderRegistry" in {
      val s = settings(section("main", """provider = "fixturechat", model = "m", apiKey = "k""""))
      s.defaultProvider()(using ProviderRegistry.of()).isLeft shouldBe true
    }
  }
