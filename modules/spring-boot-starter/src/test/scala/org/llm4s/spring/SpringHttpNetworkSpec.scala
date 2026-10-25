package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.testutil.{ FixtureChatConfig, RecordingProxy }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment

import scala.util.Using

/** The `llm4s.http.*` properties (#2273): the proxy and TLS settings, for both forms of provider configuration. */
class SpringHttpNetworkSpec extends AnyFlatSpec with Matchers {

  private def env(properties: (String, String)*): MockEnvironment =
    properties.foldLeft(new MockEnvironment) { case (e, (k, v)) => e.withProperty(k, v) }

  private def flat(provider: String, model: String, apiKey: String, baseUrl: String = ""): Llm4sProperties = {
    val p = new Llm4sProperties
    p.provider = provider
    p.model = model
    p.apiKey = apiKey
    p.baseUrl = baseUrl
    p
  }

  private val fixtureSection = Seq(
    "llm4s.providers.provider"    -> "fx",
    "llm4s.providers.fx.provider" -> "fixturechat",
    "llm4s.providers.fx.model"    -> "fixture-model",
    "llm4s.providers.fx.api-key"  -> "sk-fixture"
  )

  "SpringProviderSource.hoconPath" should "map llm4s.http properties to the camelCase HOCON keys" in {
    SpringProviderSource.hoconPath("llm4s.http.proxy.url") shouldBe Right(List("llm4s", "http", "proxy", "url"))
    SpringProviderSource.hoconPath("llm4s.http.proxy.non-proxy-hosts[1]") shouldBe
      Right(List("llm4s", "http", "proxy", "nonProxyHosts[1]"))
    SpringProviderSource.hoconPath("llm4s.http.tls.trust-store-password") shouldBe
      Right(List("llm4s", "http", "tls", "trustStorePassword"))
    SpringProviderSource.hoconPath("llm4s.http").isLeft shouldBe true
  }

  "SpringProviderSource.joinIndexed" should "join a list property's elements, in index order, into one value" in {
    val entries = Vector(
      List("llm4s", "http", "proxy", "nonProxyHosts[1]")                    -> "*.internal",
      List("llm4s", "http", "proxy", "url")                                 -> "http://p:1",
      List("llm4s", "http", "proxy", "nonProxyHosts[0]")                    -> "localhost",
      List("llm4s", "http", "proxy", "nonProxyHosts[99999999999999999999]") -> "last"
    )
    SpringProviderSource.joinIndexed(entries).toSet shouldBe Set(
      List("llm4s", "http", "proxy", "url")           -> "http://p:1",
      List("llm4s", "http", "proxy", "nonProxyHosts") -> "localhost,*.internal,last"
    )
  }

  "SpringProviderSource.network" should "read the llm4s.http properties, a YAML list included" in {
    val network = SpringProviderSource
      .network(
        env(
          "llm4s.http.proxy.url"                -> "http://proxy.corp:3128",
          "llm4s.http.proxy.username"           -> "alice",
          "llm4s.http.proxy.password"           -> "s3cret",
          "llm4s.http.proxy.non-proxy-hosts[0]" -> "localhost",
          "llm4s.http.proxy.non-proxy-hosts[1]" -> "*.internal",
          "llm4s.providers.fx.model"            -> "ignored here"
        )
      )
      .toOption
      .get
    val proxy = network.proxy.get
    (proxy.host, proxy.port, proxy.credentials) shouldBe (("proxy.corp", 3128, Some(("alice", "s3cret"))))
    proxy.nonProxyHosts shouldBe Seq("localhost", "*.internal")
  }

  it should "report a setting it cannot use" in {
    SpringProviderSource.network(env("llm4s.http.proxy.url" -> "ftp://x:1")).isLeft shouldBe true
    SpringProviderSource.network(env("llm4s.http.proxy" -> "x")).isLeft shouldBe true
  }

  "The llm4s.providers form" should "carry llm4s.http and a section's own http block" in {
    val global = SpringProviderSource
      .resolve(env(fixtureSection :+ ("llm4s.http.proxy.url" -> "http://proxy.corp:3128")*), "")
      .get()
    global.config.network.proxy.map(_.host) shouldBe Some("proxy.corp")
    val overridden = SpringProviderSource
      .resolve(
        env(
          fixtureSection ++ Seq(
            "llm4s.http.proxy.url"                -> "http://proxy.corp:3128",
            "llm4s.http.proxy.password"           -> "global-secret",
            "llm4s.providers.fx.http.proxy.url"   -> "http://gateway-proxy:8080",
            "llm4s.http.tls.trust-store-password" -> "never-used"
          )*
        ),
        ""
      )
    overridden.isFailure shouldBe false
    val resolved = overridden.get()
    resolved.config.asInstanceOf[FixtureChatConfig].network.proxy.map(_.host) shouldBe Some("gateway-proxy")
    (resolved.secrets should contain).allOf("global-secret", "never-used")
  }

  "The flat form" should "carry llm4s.http" in {
    val config = ProviderSelection
      .resolve(flat("openai", "gpt-4o", "sk-test"), env("llm4s.http.proxy.url" -> "http://proxy.corp:3128"))
      .get()
    config.asInstanceOf[OpenAIConfig].network.proxy.map(_.port) shouldBe Some(3128)
  }

  it should "fail for an llm4s.http setting it cannot use, and keep its own failure first" in {
    ProviderSelection
      .resolve(flat("openai", "gpt-4o", "sk-test"), env("llm4s.http.proxy.url" -> "socks5://x:1"))
      .isFailure shouldBe true
    ProviderSelection
      .resolve(flat("", "gpt-4o", "sk-test"), env("llm4s.http.proxy.url" -> "socks5://x:1"))
      .getError()
      .getMessage should include("llm4s.provider")
  }

  it should "send the started client's calls through the proxy" in {
    Using.resource(RecordingProxy.start(body = LocalProviderTestServer.openAICompletion("spring via proxy"))) { proxy =>
      new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration]))
        .withPropertyValues(
          "llm4s.provider=openai",
          "llm4s.model=gpt-4o",
          "llm4s.api-key=sk-test",
          "llm4s.base-url=http://api.openai.test/v1",
          s"llm4s.http.proxy.url=${proxy.url}"
        )
        .run { ctx =>
          ctx.getStartupFailure shouldBe null
          ctx.getBean(classOf[JLlmClient]).complete("hi").get() shouldBe "spring via proxy"
          proxy.requests.map(_.target) shouldBe Vector("http://api.openai.test/v1/chat/completions")
        }
    }
  }
}
