package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.http.{ HttpNetworkSettings, HttpProxy }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider, FixtureEmbeddingProvider, RecordingProxy, TestTls }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.file.Path
import scala.util.Using

/** The `llm4s.http` block and a section's `http` override, as `Llm4sConfig` loads them (#2273). */
class HttpNetworkConfigSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ProviderRegistry = ProviderRegistry.of(FixtureChatProvider)

  // Backslashes in a Windows path would be escapes in a HOCON string.
  private def hocon(path: Path): String = path.toString.replace("\\", "/")

  private def network(text: String) = Llm4sConfig.httpNetwork(ConfigSource.string(text))

  private val section =
    """llm4s.providers { provider = main, main { provider = fixturechat, model = m, apiKey = k } }"""

  private def providerNetwork(text: String): HttpNetworkSettings =
    Llm4sConfig.provider(ConfigSource.string(text), "main").value.asInstanceOf[FixtureChatConfig].network

  "llm4s.http" should "be the default settings when absent or empty" in {
    network("a = 1").value shouldBe HttpNetworkSettings.default
    network("llm4s.http {}").value shouldBe HttpNetworkSettings.default
    network("""llm4s.http.proxy.url = "" """).value shouldBe HttpNetworkSettings.default
    network("llm4s.http = null").value shouldBe HttpNetworkSettings.default
  }

  it should "read the proxy, its credentials and nonProxyHosts as a list or a comma-separated string" in {
    val listed = network(
      """llm4s.http.proxy { url = "http://proxy.corp:3128", username = alice, password = s3cret, nonProxyHosts = ["localhost", "*.internal"] }"""
    ).value.proxy.get
    (listed.host, listed.port, listed.credentials) shouldBe (("proxy.corp", 3128, Some(("alice", "s3cret"))))
    listed.nonProxyHosts shouldBe Seq("localhost", "*.internal")
    val noProxyVariable = network(
      """llm4s.http.proxy { url = "http://u:p@proxy.corp:3128", nonProxyHosts = "localhost, .corp.example,," }"""
    ).value.proxy.get
    noProxyVariable.nonProxyHosts shouldBe Seq("localhost", ".corp.example")
    noProxyVariable.credentials shouldBe Some(("u", "p"))
    network("""llm4s.http.proxy { url = "proxy:1", password = 123 }""").value.proxy.get.password shouldBe Some("123")
  }

  it should "read the TLS stores, and fail the load for one it cannot open" in {
    val trust = TestTls.trustStore
    val tls = network(
      s"""llm4s.http.tls { trustStore = "${hocon(trust)}", trustStorePassword = ${TestTls.Password} }"""
    ).value.tls
    tls.trustStore shouldBe Some(trust)
    tls.trustStorePassword shouldBe Some(TestTls.Password)
    val wrong = network(
      s"""llm4s.http.tls { trustStore = "${hocon(trust)}", trustStorePassword = "not-the-password" }"""
    ).left.value
    wrong shouldBe a[ConfigurationError]
    wrong.message should (include("llm4s.http.tls.trustStore").and(not).include("not-the-password"))
    network("""llm4s.http.tls.keyStore = "/no/such/client.p12" """).left.value.message should
      include("llm4s.http.tls.keyStore")
  }

  it should "name an unknown key, a value of the wrong shape and a proxy URL it cannot use" in {
    network("llm4s.http.proxie {}").left.value.message should include("llm4s.http.proxie: unknown key")
    network("llm4s.http.proxy.urll = x").left.value.message should include("llm4s.http.proxy.urll: unknown key")
    network("llm4s.http.tls.truststore = x").left.value.message should include("llm4s.http.tls.truststore")
    network("llm4s.http = 3").left.value.message should include("llm4s.http must be an object")
    network("llm4s.http.proxy = 3").left.value.message should include("llm4s.http.proxy: must be an object")
    network("llm4s.http.proxy.url = [a]").left.value.message should include("llm4s.http.proxy.url: must be a string")
    network("llm4s.http.proxy { url = \"p:1\", nonProxyHosts = 3 }").left.value.message should
      include("llm4s.http.proxy.nonProxyHosts")
    network("llm4s.http.proxy { url = \"p:1\", nonProxyHosts = [[a]] }").left.value.message should
      include("must be a list of host names")
    val badUrl = network("""llm4s.http.proxy.url = "socks5://user:pw-in-url@proxy:1080" """).left.value.message
    badUrl should (include("llm4s.http.proxy.url").and(include("scheme must be http")).and(not).include("pw-in-url"))
    network("llm4s.http.tls.trustStore = \"a\\u0000b\"").left.value.message should include("not a valid path")
  }

  "A provider section" should "carry llm4s.http" in {
    val proxy = providerNetwork(s"""$section
      llm4s.http.proxy.url = "http://proxy.corp:3128"""").proxy
    proxy.map(p => (p.host, p.port)) shouldBe Some(("proxy.corp", 3128))
  }

  it should "override llm4s.http key by key with its own http block" in {
    val merged = providerNetwork(s"""$section
      llm4s.http.proxy { url = "http://proxy.corp:3128", username = alice, password = s3cret }
      llm4s.providers.main.http.proxy.url = "http://gateway-proxy:8080"
    """).proxy.get
    (merged.host, merged.port, merged.credentials) shouldBe (("gateway-proxy", 8080, Some(("alice", "s3cret"))))
    providerNetwork(s"""$section
      llm4s.http.proxy.url = "http://proxy.corp:3128"
      llm4s.providers.main.http.proxy.url = ""
    """).proxy shouldBe None
    providerNetwork(s"""$section
      llm4s.providers.main.http.proxy.url = "http://only-here:1"
    """).proxy.map(_.host) shouldBe Some("only-here")
    providerNetwork(section) shouldBe HttpNetworkSettings.default
  }

  it should "fail with the section's path for an http block it cannot use, and not take it as an extra" in {
    val error = Llm4sConfig
      .provider(
        ConfigSource.string(s"""$section
        llm4s.providers.main.http.proxy.url = "ftp://x:1"
      """),
        "main"
      )
      .left
      .value
    error.message should include("llm4s.providers.main.http.proxy.url")
    Llm4sConfig
      .provider(
        ConfigSource.string(s"""$section
        llm4s.providers.main.http = 3
      """),
        "main"
      )
      .isLeft shouldBe true
  }

  it should "carry the settings when every section is loaded" in {
    val (errors, configs) = Llm4sConfig
      .providerConfigs(ConfigSource.string(s"""$section
        llm4s.http.proxy.url = "http://proxy.corp:3128"
        llm4s.providers.broken { provider = fixturechat, model = m, apiKey = k, http.proxy.url = "nope://x" }
      """))
      .value
    configs.values.head.network.proxy.map(_.host) shouldBe Some("proxy.corp")
    errors.keySet.map(_.asName) shouldBe Set("broken")
  }

  "An embedding config" should "carry llm4s.http" in {
    given ProviderRegistry = ProviderRegistry.default
    val (_, config) = EmbeddingsConfigLoader
      .loadProviderFor(
        ConfigSource.string(
          s"""llm4s.http.proxy.url = "http://proxy.corp:3128"
             |llm4s.embeddings.${FixtureEmbeddingProvider.id.asString} { apiKey = k }""".stripMargin
        ),
        s"${FixtureEmbeddingProvider.id.asString}/model"
      )
      .value
    config.network.proxy.map(_.port) shouldBe Some(3128)
  }

  "Llm4sConfig.listModels" should "list through the section's proxy" in {
    Using.resource(RecordingProxy.start(body = """{"data":[{"id":"m1"}]}""")) { proxy =>
      val result = Llm4sConfig.listModels(ConfigSource.string(s"""
        llm4s.providers { provider = main, main { provider = fixturechat, model = m, apiKey = k, baseUrl = "http://models.provider.test/v1" } }
        llm4s.http.proxy { url = "${proxy.url}", nonProxyHosts = [] }
      """))
      result.value.map(_.name.asString) shouldBe List("m1")
      proxy.requests.map(_.target) shouldBe Vector("http://models.provider.test/v1/models")
    }
  }
}
