package org.llm4s.http

import org.llm4s.error.ConfigurationError
import org.llm4s.testutil.TestTls
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ Authenticator, InetAddress, Proxy, URI }
import java.nio.file.{ Files, Path }
import java.security.cert.X509Certificate

/** The proxy and TLS settings types and their resolution into JDK objects (#2273). */
class HttpNetworkSettingsSpec extends AnyFlatSpec with Matchers with EitherValues {

  "HttpProxy.fromUrl" should "read host, port and the credentials of the URL" in {
    val proxy = HttpProxy.fromUrl(" http://alice:s3cret@proxy.corp:3128 ").value
    proxy.host shouldBe "proxy.corp"
    proxy.port shouldBe 3128
    proxy.username shouldBe Some("alice")
    proxy.password shouldBe Some("s3cret")
    proxy.credentials shouldBe Some(("alice", "s3cret"))
    proxy.nonProxyHosts shouldBe HttpProxy.DefaultNonProxyHosts
  }

  it should "read a bare host:port as an http proxy, without credentials" in {
    val proxy = HttpProxy.fromUrl("proxy.corp:8080").value
    (proxy.host, proxy.port, proxy.username, proxy.password) shouldBe (("proxy.corp", 8080, None, None))
    proxy.credentials shouldBe None
  }

  it should "read a user with no password as a user name alone" in {
    val proxy = HttpProxy.fromUrl("http://alice@proxy.corp:3128").value
    proxy.username shouldBe Some("alice")
    proxy.password shouldBe None
    proxy.credentials shouldBe None
  }

  it should "refuse another scheme, a missing port and garbage, never repeating a password" in {
    val https = HttpProxy.fromUrl("https://bob:hunter2@proxy.corp:443").left.value
    https shouldBe a[ConfigurationError]
    https.message should include("scheme must be http")
    (https.message should not).include("hunter2")
    HttpProxy.fromUrl("http://bob:hunter2@proxy.corp").left.value.message should (include("port")
      .and(not)
      .include("hunter2"))
    HttpProxy.fromUrl("http://").left.value.message should include("expected http://host:port")
  }

  "HttpProxy.bypasses" should "match exact names, suffixes, prefixes, IPv6 literals and *, ignoring case" in {
    val proxy = HttpProxy("proxy", 3128).withNonProxyHosts(
      Seq("Ollama.Local", "*.internal", ".corp.example", "10.*", "[::1]", " ")
    )
    proxy.bypasses("ollama.local") shouldBe true
    proxy.bypasses("api.internal") shouldBe true
    proxy.bypasses("internal") shouldBe true
    proxy.bypasses("a.corp.example") shouldBe true
    proxy.bypasses("corp.example") shouldBe true
    proxy.bypasses("10.1.2.3") shouldBe true
    proxy.bypasses("[::1]") shouldBe true
    proxy.bypasses("api.openai.com") shouldBe false
    proxy.bypasses("notinternal") shouldBe false
    HttpProxy("proxy", 3128).withNonProxyHosts(Seq("*")).bypasses("anything") shouldBe true
  }

  it should "send loopback hosts direct by default" in {
    val proxy = HttpProxy("proxy", 3128)
    Seq("localhost", "127.0.0.1", "::1").foreach(host => proxy.bypasses(host) shouldBe true)
    proxy.bypasses("api.anthropic.com") shouldBe false
  }

  "The settings types" should "keep passwords out of toString" in {
    val proxy = HttpProxy("proxy", 3128).withUsername("alice").withPassword("s3cret")
    val tls = HttpTls.default
      .withTrustStore(Path.of("/etc/ca.p12"))
      .withTrustStorePassword("trustpw")
      .withKeyStore(Path.of("/etc/client.p12"))
      .withKeyStorePassword("keypw")
    val settings = HttpNetworkSettings.default.withProxy(proxy).withTls(tls)
    val printed  = Seq(proxy.toString, tls.toString, settings.toString)
    printed.foreach { text =>
      (text should not).include("s3cret")
      (text should not).include("trustpw")
      (text should not).include("keypw")
    }
    proxy.toString should (include("proxy:3128").and(include("alice")).and(include("password=***")))
    tls.toString should (include("/etc/ca.p12")
      .and(include("trustStorePassword=***"))
      .and(include("keyStorePassword=***")))
    HttpProxy("p", 1).toString should include("password=None")
    HttpTls.default.toString should include("trustStorePassword=None")
  }

  they should "build with the setters, and report when they change nothing" in {
    HttpNetworkSettings.default.isDefault shouldBe true
    HttpNetworkSettings().isDefault shouldBe true
    val proxy = HttpProxy("a", 1).withHost("b").withPort(2).withUsername(Some("u")).withPassword(Some("p"))
    (proxy.host, proxy.port, proxy.credentials) shouldBe (("b", 2, Some(("u", "p"))))
    proxy.withUsername(None).withPassword(None).credentials shouldBe None
    proxy.withUsername("").credentials shouldBe None
    val settings = HttpNetworkSettings.default.withProxy(Some(proxy))
    settings.isDefault shouldBe false
    settings.proxyFor("api.openai.com") shouldBe Some(proxy)
    settings.proxyFor("localhost") shouldBe None
    settings.withProxy(None).isDefault shouldBe true
    val tls = HttpTls(trustStore = Some(Path.of("t")))
    tls.isDefault shouldBe false
    tls
      .withTrustStore(None)
      .withTrustStorePassword(None)
      .withKeyStore(None)
      .withKeyStorePassword(None) shouldBe HttpTls.default
    HttpTls.default.withKeyStore(Path.of("k")).withKeyStorePassword("x").isDefault shouldBe false
    HttpNetworkSettings(tls = tls).isDefault shouldBe false
  }

  "HttpNetwork.proxySelector" should "route through the proxy except to the hosts it bypasses" in {
    val selector = HttpNetwork.proxySelector(HttpProxy("proxy.corp", 3128))
    val routed   = selector.select(URI.create("https://api.openai.com/v1"))
    routed.size shouldBe 1
    routed.get(0).`type`() shouldBe Proxy.Type.HTTP
    routed.get(0).address().toString should include("proxy.corp")
    selector.select(URI.create("http://localhost:11434")).get(0) shouldBe Proxy.NO_PROXY
    selector.select(URI.create("mailto:x")).get(0).`type`() shouldBe Proxy.Type.HTTP
    noException should be thrownBy selector.connectFailed(URI.create("http://x"), null, new java.io.IOException("x"))
  }

  "HttpNetwork.proxyForUrl" should "give the proxy for a base URL it does not bypass" in {
    val settings = HttpNetworkSettings(proxy = Some(HttpProxy("proxy.corp", 3128)))
    HttpNetwork.proxyForUrl(settings, "https://api.anthropic.com").map(_.`type`()) shouldBe Some(Proxy.Type.HTTP)
    HttpNetwork.proxyForUrl(settings, "http://localhost:11434") shouldBe None
    HttpNetwork.proxyForUrl(HttpNetworkSettings.default, "https://api.anthropic.com") shouldBe None
  }

  "HttpNetwork.authenticator" should "answer only the proxy's own challenge" in {
    HttpNetwork.authenticator(HttpProxy("proxy.corp", 3128)) shouldBe None
    val auth = HttpNetwork.authenticator(HttpProxy("proxy.corp", 3128).withUsername("u").withPassword("p")).get
    def ask(host: String, kind: Authenticator.RequestorType) =
      auth.requestPasswordAuthenticationInstance(
        host,
        InetAddress.getLoopbackAddress,
        3128,
        "http",
        "realm",
        "basic",
        URI.create("http://api.example").toURL,
        kind
      )
    val answer = ask("PROXY.corp", Authenticator.RequestorType.PROXY)
    answer.getUserName shouldBe "u"
    new String(answer.getPassword) shouldBe "p"
    ask("api.example", Authenticator.RequestorType.SERVER) shouldBe null
    ask("other.proxy", Authenticator.RequestorType.PROXY) shouldBe null
  }

  "HttpNetwork.tlsMaterial" should "be absent without a store" in {
    HttpNetwork.tlsMaterial(HttpTls.default).value shouldBe None
  }

  it should "trust the store's CA in addition to the JDK's" in {
    val material = HttpNetwork
      .tlsMaterial(HttpTls.default.withTrustStore(TestTls.trustStore).withTrustStorePassword(TestTls.Password))
      .value
      .get
    val issuers = material.trustManager.getAcceptedIssuers.toSeq.map(_.getSubjectX500Principal.getName)
    issuers should contain("CN=llm4s test CA")
    issuers.size should be > 1
    material.keyManagers shouldBe empty
    an[Exception] should be thrownBy material.trustManager.checkServerTrusted(Array.empty[X509Certificate], "RSA")
    an[Exception] should be thrownBy material.trustManager.checkClientTrusted(Array.empty[X509Certificate], "RSA")
  }

  it should "load a client certificate's key managers" in {
    val material = HttpNetwork
      .tlsMaterial(HttpTls.default.withKeyStore(TestTls.clientStore).withKeyStorePassword(TestTls.Password))
      .value
      .get
    material.keyManagers should not be empty
  }

  it should "name the store, never the password, when it cannot be read" in {
    val missing = HttpNetwork.tlsMaterial(HttpTls(trustStore = Some(Path.of("/no/such/ca.p12")))).left.value
    missing.message should (include("tls.trustStore").and(include("/no/such/ca.p12")))
    val wrong = HttpNetwork
      .tlsMaterial(HttpTls.default.withTrustStore(TestTls.trustStore).withTrustStorePassword("wrong-secret-pw"))
      .left
      .value
    wrong.message should include("tls.trustStore")
    (wrong.message should not).include("wrong-secret-pw")
    val notAStore = Files.createTempFile("llm4s-not-a-store", ".p12")
    Files.writeString(notAStore, "not a key store")
    HttpNetwork.tlsMaterial(HttpTls(keyStore = Some(notAStore))).left.value.message should include("tls.keyStore")
    val badKey = HttpNetwork
      .tlsMaterial(HttpTls(keyStore = Some(TestTls.clientStore), keyStorePassword = None))
      .left
      .value
    badKey.message should include("tls.keyStore")
  }
}
