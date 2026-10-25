package org.llm4s.javaapi

import org.llm4s.http.HttpNetworkSettings
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.testutil.{ RecordingProxy, TestTls }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import scala.util.Using

/** The client builders' proxy and TLS settings (#2273): the Java form of `llm4s.http`. */
class ClientNetworkSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def error(result: LlmResult[JLlmClient]): (LlmErrorKind, String) =
    (result.getError().getKind, result.getError().getMessage)

  "A client builder" should "route its client through the proxy it was given, with the proxy's credentials" in {
    Using.resource(
      RecordingProxy.start(
        credentials = Some(("alice", "s3cret")),
        body = LocalProviderTestServer.openAICompletion("via proxy")
      )
    ) { proxy =>
      val client = Llm4s
        .openAiCompatible()
        .baseUrl("http://llm.gateway.test/v1")
        .model("local-model")
        .proxy(proxy.url)
        .proxyCredentials("alice", "s3cret")
        .nonProxyHosts()
        .build()
        .get()
      client.complete("hello").get() shouldBe "via proxy"
      proxy.authorized.map(_.target) shouldBe Vector("http://llm.gateway.test/v1/chat/completions")
    }
  }

  it should "route an SDK-backed client through the proxy too" in {
    Using.resource(RecordingProxy.start(body = LocalProviderTestServer.anthropicMessage("claude via proxy"))) { proxy =>
      val client = Llm4s
        .anthropic()
        .apiKey("sk-ant-test")
        .model("claude-sonnet-4-20250514")
        .baseUrl("http://api.anthropic.test")
        .proxy(s"http://alice:s3cret@127.0.0.1:${proxy.port}")
        .nonProxyHosts("localhost")
        .build()
        .get()
      client.complete("hello").get() shouldBe "claude via proxy"
      proxy.requests.map(_.target) shouldBe Vector("http://api.anthropic.test/v1/messages")
    }
  }

  it should "trust the CA of the trust store it was given" in {
    val server = TestTls.httpsServer(body = LocalProviderTestServer.openAICompletion("over tls"))
    val base   = Llm4s.openAiCompatible().baseUrl(s"https://localhost:${server.getAddress.getPort}/v1").model("m")
    base.build().get().complete("hello").isFailure shouldBe true
    base.trustStore(TestTls.trustStore, TestTls.Password).build().get().complete("hello").get() shouldBe "over tls"
    server.stop(0)
  }

  it should "refuse a proxy URL or a store it cannot use as VALIDATION, naming the field and never a password" in {
    val proxy = error(Llm4s.ollama().model("llama3.2").proxy("socks5://u:pw-in-url@proxy:1080").build())
    proxy._1 shouldBe LlmErrorKind.VALIDATION
    proxy._2 should (include("'proxy'").and(not).include("pw-in-url"))
    val trust =
      error(Llm4s.gemini().apiKey("k").model("gemini-2.0-flash").trustStore(Path.of("missing.p12"), "tpw").build())
    trust._1 shouldBe LlmErrorKind.VALIDATION
    trust._2 should (include("'trustStore'").and(not).include("tpw"))
    val key = error(Llm4s.openAi().apiKey("k").model("gpt-4o").keyStore(Path.of("missing.p12"), "kpw").build())
    key._1 shouldBe LlmErrorKind.VALIDATION
    key._2 should (include("'keyStore'").and(not).include("kpw"))
    // A failure the builder reports first is not hidden by the network settings.
    error(Llm4s.openAi().model("gpt-4o").proxy("socks5://p:1").build())._2 shouldBe "Field 'apiKey' is required"
  }

  it should "show the network settings in toString, never a password" in {
    val described = Llm4s
      .azureOpenAi()
      .proxy("http://u:url-secret@proxy.corp:3128")
      .proxyCredentials("alice", "proxy-secret")
      .nonProxyHosts("localhost", "*.internal")
      .trustStore(Path.of("ca.p12"), "trust-secret")
      .keyStore(Path.of("client.p12"), "key-secret")
      .toString
    described should include("proxy=http://***@proxy.corp:3128")
    described should include("proxyUsername=alice")
    described should include("nonProxyHosts=[localhost, *.internal]")
    described should include("trustStore=ca.p12")
    described should include("keyStore=client.p12")
    Seq("url-secret", "proxy-secret", "trust-secret", "key-secret").foreach(secret =>
      (described should not).include(secret)
    )
    Llm4s.openAi().toString shouldBe "OpenAiClientBuilder()"
    Llm4s.openAiCompatible().nonProxyHosts((null: Array[String])*).toString shouldBe "OpenAiCompatibleClientBuilder()"
  }

  "The Java guide's proxy block" should "build its client, or name the trust store it cannot open" in {
    val result = GuideSnippets.behindAProxy("sk-test", "alice", "s3cret", "trust-pw")
    error(result)._1 shouldBe LlmErrorKind.VALIDATION
    error(result)._2 should (include("'trustStore'").and(include("corp-ca.p12")).and(not).include("trust-pw"))
  }

  "ClientBuilds.network" should "be the default settings when nothing was set" in {
    ClientBuilds.network(ClientNetwork.EMPTY).value shouldBe HttpNetworkSettings.default
    ClientBuilds.network(ClientNetwork.EMPTY.proxy("  ")).value shouldBe HttpNetworkSettings.default
  }

  it should "take the URL's credentials unless proxyCredentials sets them, and nonProxyHosts as given" in {
    val fromUrl = ClientBuilds.network(ClientNetwork.EMPTY.proxy("http://u:p@proxy:3128")).value.proxy.get
    fromUrl.credentials shouldBe Some(("u", "p"))
    fromUrl.nonProxyHosts shouldBe org.llm4s.http.HttpProxy.DefaultNonProxyHosts
    val set = ClientBuilds
      .network(
        ClientNetwork.EMPTY.proxy("http://u:p@proxy:3128").proxyCredentials("alice", "s3cret").nonProxyHosts("a")
      )
      .value
      .proxy
      .get
    set.credentials shouldBe Some(("alice", "s3cret"))
    set.nonProxyHosts shouldBe Seq("a")
    ClientBuilds
      .network(ClientNetwork.EMPTY.proxy("proxy:1").proxyCredentials(" ", null))
      .value
      .proxy
      .get
      .credentials shouldBe None
  }

  it should "carry the stores it can open" in {
    val tls = ClientBuilds
      .network(
        ClientNetwork.EMPTY
          .trustStore(TestTls.trustStore, TestTls.Password)
          .keyStore(TestTls.clientStore, TestTls.Password)
      )
      .value
      .tls
    tls.trustStore should not be empty
    tls.keyStore should not be empty
  }
}
