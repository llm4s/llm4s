package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIConfig }
import org.llm4s.testkit.LocalProviderTestServer.{ openAICompletion, sendJsonResponse, withServer }
import org.llm4s.testutil.FixtureChatClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.{ StandardEnvironment, SystemEnvironmentPropertySource }
import org.springframework.mock.env.MockEnvironment

import java.util.concurrent.atomic.AtomicReference

/**
 * Any provider on the classpath through `llm4s.providers.*` properties, with no per-provider Spring code
 * (#1467): the properties become llm4s's own provider configuration, which the provider SPI resolves.
 */
class SpringProvidersSpec extends AnyFlatSpec with Matchers {

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration], classOf[LlmActuatorAutoConfiguration]))

  private def failureText(ctx: AssertableApplicationContext): String = {
    ctx.getStartupFailure should not be null
    Iterator
      .iterate[Throwable](ctx.getStartupFailure)(_.getCause)
      .takeWhile(_ != null)
      .map(_.getMessage)
      .mkString(" | ")
  }

  private def env(properties: (String, String)*): MockEnvironment =
    properties.foldLeft(new MockEnvironment) { case (e, (k, v)) => e.withProperty(k, v) }

  private val geminiReply =
    """{"candidates":[{"content":{"role":"model","parts":[{"text":"gemini says hello"}]},"finishReason":"STOP"}],
      |"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4,"totalTokenCount":7}}""".stripMargin

  // ---- the property names ------------------------------------------------------------------------

  "SpringProviderSource.camel" should "read kebab-case and snake_case names as the camelCase HOCON names" in {
    SpringProviderSource.camel("api-key") shouldBe "apiKey"
    SpringProviderSource.camel("api_key") shouldBe "apiKey"
    SpringProviderSource.camel("context-window") shouldBe "contextWindow"
    SpringProviderSource.camel("apiKey") shouldBe "apiKey"
    SpringProviderSource.camel("model") shouldBe "model"
    SpringProviderSource.camel("-") shouldBe "-"
  }

  "SpringProviderSource.hoconPath" should "keep the section name and the header names as written" in {
    SpringProviderSource.hoconPath("llm4s.providers.my-gemini.api-key") shouldBe
      Right(List("llm4s", "providers", "my-gemini", "apiKey"))
    SpringProviderSource.hoconPath("llm4s.providers.oc.headers.X-Team-Id") shouldBe
      Right(List("llm4s", "providers", "oc", "headers", "X-Team-Id"))
    SpringProviderSource.hoconPath("llm4s.providers.provider") shouldBe Right(List("llm4s", "providers", "provider"))
    SpringProviderSource.hoconPath("llm4s.credentials.openai.api-key") shouldBe
      Right(List("llm4s", "credentials", "openai", "apiKey"))
  }

  it should "read the names below the first one as camelCase too, except header names" in {
    SpringProviderSource.hoconPath("llm4s.providers.fx.some-group.inner_key") shouldBe
      Right(List("llm4s", "providers", "fx", "someGroup", "innerKey"))
    SpringProviderSource.hoconPath("llm4s.credentials.vendor.some-group.inner-key") shouldBe
      Right(List("llm4s", "credentials", "vendor", "someGroup", "innerKey"))
    SpringProviderSource.hoconPath("llm4s.providers.fx.headers.X-Api-Key") shouldBe
      Right(List("llm4s", "providers", "fx", "headers", "X-Api-Key"))
  }

  it should "reject a key that is not a provider property" in {
    SpringProviderSource.hoconPath("llm4s.providers.lonely").isLeft shouldBe true
    SpringProviderSource.hoconPath("llm4s.other.thing").isLeft shouldBe true
  }

  "SpringProviderSource.usesProvidersBlock" should "be true exactly when a llm4s.providers property is set" in {
    SpringProviderSource.usesProvidersBlock(new StandardEnvironment) shouldBe false
    SpringProviderSource.usesProvidersBlock(env("llm4s.provider" -> "openai", "llm4s.model" -> "m")) shouldBe false
    SpringProviderSource.usesProvidersBlock(env("llm4s.credentials.openai.api-key" -> "k")) shouldBe false
    SpringProviderSource.usesProvidersBlock(env("llm4s.providers.x.model" -> "m")) shouldBe true
  }

  it should "not read environment-variable names, which Spring lists in upper case" in {
    val e = new MockEnvironment
    e.getPropertySources.addFirst(
      new SystemEnvironmentPropertySource(
        "env",
        java.util.Map.of[String, Object]("LLM4S_PROVIDERS_X_MODEL", "m", "LLM4S_PROVIDERS_PROVIDER", "x")
      )
    )
    SpringProviderSource.usesProvidersBlock(e) shouldBe false
  }

  // ---- resolving through the provider SPI ---------------------------------------------------------

  "a llm4s.providers section" should "start the client for the fixture provider and answer through it" in {
    runner
      .withPropertyValues(
        "llm4s.providers.provider=fx",
        "llm4s.providers.fx.provider=fixturechat",
        "llm4s.providers.fx.model=fixture-model",
        "llm4s.providers.fx.api-key=sk-fixture"
      )
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBean(classOf[JLlmClient]).complete("hi").get() shouldBe FixtureChatClient.Reply
        ctx.getBean(classOf[LLM4STemplate]) should not be null
      }
  }

  it should "reach an OpenAI-compatible endpoint, with its headers, through properties alone" in {
    val seen = new AtomicReference[String]("")
    withServer("/") { exchange =>
      seen.set(Option(exchange.getRequestHeaders.getFirst("X-Team-Id")).getOrElse("none"))
      sendJsonResponse(exchange, 200, openAICompletion("compatible says hello"))
    } { baseUrl =>
      runner
        .withPropertyValues(
          "llm4s.providers.provider=oc",
          "llm4s.providers.oc.provider=openai-compatible",
          "llm4s.providers.oc.model=local-model",
          s"llm4s.providers.oc.base-url=$baseUrl/v1",
          "llm4s.providers.oc.api-key=sk-local",
          "llm4s.providers.oc.context-window=32000",
          "llm4s.providers.oc.reserve-completion=2000",
          "llm4s.providers.oc.headers.X-Team-Id=team-7"
        )
        .run { ctx =>
          ctx.getStartupFailure shouldBe null
          ctx.getBean(classOf[JLlmClient]).complete("hi").get() shouldBe "compatible says hello"
        }
    }
    seen.get shouldBe "team-7"
  }

  it should "reach Gemini through properties alone" in {
    withServer("/")(exchange => sendJsonResponse(exchange, 200, geminiReply)) { baseUrl =>
      runner
        .withPropertyValues(
          "llm4s.providers.provider=gem",
          "llm4s.providers.gem.provider=gemini",
          "llm4s.providers.gem.model=gemini-2.0-flash",
          s"llm4s.providers.gem.base-url=$baseUrl",
          "llm4s.providers.gem.api-key=gem-key"
        )
        .run { ctx =>
          ctx.getStartupFailure shouldBe null
          ctx.getBean(classOf[JLlmClient]).complete("hi").get() shouldBe "gemini says hello"
        }
    }
  }

  it should "reach Azure OpenAI, whose endpoint and API version are extras of the provider" in {
    withServer("/")(exchange => sendJsonResponse(exchange, 200, openAICompletion("azure says hello"))) { baseUrl =>
      runner
        .withPropertyValues(
          "llm4s.providers.provider=az",
          "llm4s.providers.az.provider=azure",
          "llm4s.providers.az.model=my-deployment",
          "llm4s.providers.az.api-key=az-key",
          s"llm4s.providers.az.endpoint=$baseUrl",
          "llm4s.providers.az.api-version=2024-02-01"
        )
        .run { ctx =>
          ctx.getStartupFailure shouldBe null
          ctx.getBean(classOf[JLlmClient]).complete("hi").get() shouldBe "azure says hello"
        }
    }
  }

  it should "resolve openai to the same key, model, base URL and organisation as the flat properties" in {
    val flat = ProviderConfigParser
      .parse {
        val p = new Llm4sProperties
        p.provider = "openai"
        p.model = "gpt-4o"
        p.apiKey = "sk-x"
        p.baseUrl = "https://example.invalid/v1"
        p.organization = "org-1"
        p
      }
      .get()
      .asInstanceOf[OpenAIConfig]
    val viaSpi = SpringProviderSource
      .resolve(
        env(
          "llm4s.providers.provider"       -> "o",
          "llm4s.providers.o.provider"     -> "openai",
          "llm4s.providers.o.model"        -> "gpt-4o",
          "llm4s.providers.o.api-key"      -> "sk-x",
          "llm4s.providers.o.base-url"     -> "https://example.invalid/v1",
          "llm4s.providers.o.organization" -> "org-1"
        ),
        ""
      )
      .get()
      .config
      .asInstanceOf[OpenAIConfig]
    (viaSpi.apiKey, viaSpi.model, viaSpi.baseUrl, viaSpi.organization) shouldBe
      ((flat.apiKey, flat.model, flat.baseUrl, flat.organization))
  }

  it should "take the context sizes of openai from the model registry, not from a context-window property" in {
    // `contextWindow` and `reserveCompletion` are extras of the generic openai-compatible provider only: for
    // the other providers llm4s reads them from the model registry, as it does for a HOCON file.
    val section = Seq(
      "llm4s.providers.provider"   -> "o",
      "llm4s.providers.o.provider" -> "openai",
      "llm4s.providers.o.model"    -> "gpt-4o",
      "llm4s.providers.o.api-key"  -> "sk-x"
    )
    val plain = SpringProviderSource.resolve(env(section: _*), "").get().config
    val asked = SpringProviderSource
      .resolve(env((section ++ Seq("llm4s.providers.o.context-window" -> "32000")): _*), "")
      .get()
      .config
    asked.contextWindow shouldBe plain.contextWindow
    asked.contextWindow should not be 32000
  }

  it should "give an openai-compatible section the context sizes it sets" in {
    val config = SpringProviderSource
      .resolve(
        env(
          "llm4s.providers.provider"              -> "oc",
          "llm4s.providers.oc.provider"           -> "openai-compatible",
          "llm4s.providers.oc.model"              -> "local-model",
          "llm4s.providers.oc.base-url"           -> "http://localhost:1/v1",
          "llm4s.providers.oc.context-window"     -> "32000",
          "llm4s.providers.oc.reserve-completion" -> "2000"
        ),
        ""
      )
      .get()
      .config
    (config.contextWindow, config.reserveCompletion) shouldBe ((32000, 2000))
  }

  it should "keep the Azure endpoint and API version it was given" in {
    SpringProviderSource
      .resolve(
        env(
          "llm4s.providers.provider"       -> "az",
          "llm4s.providers.az.provider"    -> "azure",
          "llm4s.providers.az.model"       -> "dep",
          "llm4s.providers.az.api-key"     -> "k",
          "llm4s.providers.az.endpoint"    -> "https://r.openai.azure.com",
          "llm4s.providers.az.api-version" -> "2024-02-01"
        ),
        ""
      )
      .get()
      .config match {
      case azure: AzureConfig =>
        azure.endpoint shouldBe "https://r.openai.azure.com"
        azure.apiVersion shouldBe "2024-02-01"
      case other => fail(s"Expected AzureConfig, got $other")
    }
  }

  // ---- how the default section is chosen ----------------------------------------------------------

  "the default section" should "be the one llm4s.providers.provider names, not the flat llm4s.provider" in {
    SpringProviderSource
      .resolve(
        env(
          "llm4s.providers.provider"   -> "b",
          "llm4s.providers.a.provider" -> "fixturechat",
          "llm4s.providers.a.model"    -> "model-a",
          "llm4s.providers.a.api-key"  -> "k",
          "llm4s.providers.b.provider" -> "fixturechat",
          "llm4s.providers.b.model"    -> "model-b",
          "llm4s.providers.b.api-key"  -> "k"
        ),
        "a"
      )
      .get()
      .config
      .model shouldBe "model-b"
  }

  it should "be the section the flat llm4s.provider names when llm4s.providers.provider is absent" in {
    SpringProviderSource
      .resolve(
        env(
          "llm4s.providers.a.provider" -> "fixturechat",
          "llm4s.providers.a.model"    -> "model-a",
          "llm4s.providers.a.api-key"  -> "k"
        ),
        "a"
      )
      .get()
      .config
      .model shouldBe "model-a"
  }

  "a context with both the flat properties and a providers block" should "use the block" in {
    runner
      .withPropertyValues(
        "llm4s.provider=openai",
        "llm4s.model=gpt-4o",
        "llm4s.providers.provider=fx",
        "llm4s.providers.fx.provider=fixturechat",
        "llm4s.providers.fx.model=fixture-model",
        "llm4s.providers.fx.api-key=sk-fixture"
      )
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBean(classOf[JLlmClient]).complete("hi").get() shouldBe FixtureChatClient.Reply
      }
  }

  // ---- values -------------------------------------------------------------------------------------

  "a property value" should "have its placeholders resolved, and one that cannot be resolved is named" in {
    runner
      .withPropertyValues(
        "FX_KEY=sk-from-placeholder",
        "llm4s.providers.provider=fx",
        "llm4s.providers.fx.provider=fixturechat",
        "llm4s.providers.fx.model=fixture-model",
        "llm4s.providers.fx.api-key=${FX_KEY}"
      )
      .run(ctx => ctx.getStartupFailure shouldBe null)
    runner
      .withPropertyValues(
        "llm4s.providers.provider=fx",
        "llm4s.providers.fx.provider=fixturechat",
        "llm4s.providers.fx.model=fixture-model",
        "llm4s.providers.fx.api-key=${NO_SUCH_KEY_ANYWHERE}"
      )
      .run(ctx => failureText(ctx) should include("llm4s.providers.fx.api-key"))
  }

  it should "not be read as HOCON, so a quote, a dollar sign or a brace in it stays literal" in {
    SpringProviderSource
      .resolve(
        env(
          "llm4s.providers.provider"    -> "fx",
          "llm4s.providers.fx.provider" -> "fixturechat",
          "llm4s.providers.fx.model"    -> "m \"quoted\" costs $5 {x} #hash",
          "llm4s.providers.fx.api-key"  -> "k"
        ),
        ""
      )
      .get()
      .config
      .model shouldBe "m \"quoted\" costs $5 {x} #hash"
  }

  "a name set both as a value and as a section" should "fail with the name, whichever is read first" in {
    val value   = List("llm4s", "providers", "fx", "model")         -> "m"
    val section = List("llm4s", "providers", "fx", "model", "deep") -> "x"
    for (entries <- Seq(Vector(value, section), Vector(section, value))) {
      val result = SpringProviderSource.nest(entries)
      result.isLeft shouldBe true
      result.left.toOption.get.message should include("llm4s.providers.fx.model")
    }
  }

  it should "otherwise nest every property under its section" in {
    val tree = SpringProviderSource
      .nest(
        Vector(
          List("llm4s", "providers", "a", "model")           -> "m1",
          List("llm4s", "providers", "a", "headers", "X-Id") -> "7",
          List("llm4s", "providers", "b", "model")           -> "m2"
        )
      )
      .toOption
      .get
    tree.toString shouldBe "{llm4s={providers={a={model=m1, headers={X-Id=7}}, b={model=m2}}}}"
  }

  "a vendor's shared credential" should "supply the key a section does not set" in {
    SpringProviderSource
      .resolve(
        env(
          "llm4s.providers.provider"              -> "fx",
          "llm4s.providers.fx.provider"           -> "fixturechat",
          "llm4s.providers.fx.model"              -> "fixture-model",
          "llm4s.credentials.fixturechat.api-key" -> "sk-shared"
        ),
        ""
      )
      .get()
      .secrets shouldBe Seq("sk-shared")
  }

  // ---- the errors a user sees ---------------------------------------------------------------------

  "an unknown provider id in a section" should "fail startup naming the registered providers and the remedy" in {
    runner
      .withPropertyValues(
        "llm4s.providers.provider=x",
        "llm4s.providers.x.provider=no-such-provider",
        "llm4s.providers.x.model=m",
        "llm4s.providers.x.api-key=k"
      )
      .run { ctx =>
        val text = failureText(ctx)
        text should include("no-such-provider")
        text should include("is not registered")
        text should include("Registered providers:")
        text should include("openai")
        text should include("add the dependency that supplies it")
      }
  }

  "a section without the key its provider needs" should "fail startup naming the missing key" in {
    runner
      .withPropertyValues(
        "llm4s.providers.provider=fx",
        "llm4s.providers.fx.provider=fixturechat",
        "llm4s.providers.fx.model=m"
      )
      .run(ctx => failureText(ctx).toLowerCase should include("apikey"))
  }

  "no default section" should "fail startup when the properties and the pointer name none" in {
    runner
      .withPropertyValues("llm4s.providers.provider=missing", "llm4s.providers.fx.provider=fixturechat")
      .run(ctx => failureText(ctx) should include("missing"))
  }

  "the flat llm4s.provider key" should "name the providers on the classpath when it is given another id" in {
    runner
      .withPropertyValues("llm4s.provider=gemini", "llm4s.model=m")
      .run { ctx =>
        val text = failureText(ctx)
        text should include("Unknown provider: 'gemini'")
        text should include("configure any other provider under llm4s.providers.<name>")
        text should include("fixturechat")
      }
  }

  // ---- beans around the client --------------------------------------------------------------------

  "health" should "report the provider and model the section resolves to, with no credential in it" in {
    runner
      .withPropertyValues(
        "llm4s.providers.provider=fx",
        "llm4s.providers.fx.provider=fixturechat",
        "llm4s.providers.fx.model=fixture-model",
        "llm4s.providers.fx.api-key=sk-SECRET-fixture-key"
      )
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        val health = ctx.getBean(classOf[HealthIndicator]).health()
        health.getDetails.get("provider") shouldBe "fixturechat"
        health.getDetails.get("model") shouldBe "fixture-model"
        (health.toString should not).include("sk-SECRET-fixture-key")
      }
  }

  "the health settings" should "carry the credentials of the section for redaction" in {
    val settings = HealthSettings.from(
      new Llm4sProperties,
      env(
        "llm4s.providers.provider"                 -> "fx",
        "llm4s.providers.fx.provider"              -> "fixturechat",
        "llm4s.providers.fx.model"                 -> "fixture-model",
        "llm4s.providers.fx.api-key"               -> "sk-SECRET",
        "llm4s.providers.fx.headers.Authorization" -> "Bearer abc"
      )
    )
    settings.provider shouldBe "fixturechat"
    settings.model shouldBe "fixture-model"
    (settings.secrets should contain).allOf("sk-SECRET", "Bearer abc")
    (settings.toString should not).include("sk-SECRET")
  }

  they should "fall back to the flat properties when the block does not resolve" in {
    val flat = new Llm4sProperties
    flat.provider = "openai"
    flat.model = "gpt-4o"
    val settings = HealthSettings.from(
      flat,
      env("llm4s.providers.provider" -> "missing", "llm4s.providers.x.provider" -> "fixturechat")
    )
    settings.provider shouldBe "openai"
    settings.model shouldBe "gpt-4o"
  }

  "a user-defined client" should "still back off when a providers block is present" in {
    runner
      .withUserConfiguration(classOf[StarterContractSpec.UserBeans])
      .withPropertyValues(
        "llm4s.providers.provider=x",
        "llm4s.providers.x.provider=no-such-provider"
      )
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[JLlmClient]).size shouldBe 1
      }
  }

  "llm4s.enabled=false" should "create nothing, whatever the providers block holds" in {
    runner
      .withPropertyValues("llm4s.enabled=false", "llm4s.providers.x.provider=no-such-provider")
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[JLlmClient]).size shouldBe 0
      }
  }
}
