package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpNetworkSettings, HttpProxy, HttpTls }
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.testutil.{ RecordingProxy, TestTls }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import scala.util.Using

/**
 * `anthropic-java`'s OkHttp client routed by the config's [[HttpNetworkSettings]] (#2273): through a local recording
 * proxy, with and without proxy authentication, past it for a bypassed host, and over TLS to a stub signed by a test
 * CA. No network, no API key.
 */
final class AnthropicClientNetworkSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val hello = Conversation(Seq(UserMessage("hello")))

  // A host that does not resolve: only the proxy can answer for it.
  private val BaseUrl = "http://api.anthropic.test"

  private def config(baseUrl: String, network: HttpNetworkSettings): AnthropicConfig =
    AnthropicConfig("sk-ant-test", "claude-sonnet-4-5").withBaseUrl(baseUrl).withNetwork(network)

  private def via(proxy: HttpProxy) = HttpNetworkSettings(proxy = Some(proxy.withNonProxyHosts(Nil)))

  private def reply(text: String) = LocalProviderTestServer.anthropicMessage(text)

  "AnthropicClient with a proxy" should "send the message through it" in {
    Using.resource(RecordingProxy.start(body = reply("proxied"))) { proxy =>
      val client = AnthropicClient(config(BaseUrl, via(HttpProxy("127.0.0.1", proxy.port)))).value
      client.complete(hello, CompletionOptions()).value.content shouldBe "proxied"
      proxy.requests.map(r => (r.method, r.target)) shouldBe Vector(("POST", s"$BaseUrl/v1/messages"))
      proxy.requests.head.header("x-api-key") shouldBe Some("sk-ant-test")
      client.close()
    }
  }

  it should "answer an authenticating proxy's challenge with Basic credentials" in {
    Using.resource(RecordingProxy.start(credentials = Some(("alice", "s3cret")), body = reply("authenticated"))) {
      proxy =>
        val settings = via(HttpProxy.fromUrl(s"http://alice:s3cret@127.0.0.1:${proxy.port}").value)
        val client   = AnthropicClient(config(BaseUrl, settings)).value
        client.complete(hello, CompletionOptions()).value.content shouldBe "authenticated"
        proxy.requests.head.header("proxy-authorization") shouldBe None
        proxy.authorized.map(_.target) shouldBe Vector(s"$BaseUrl/v1/messages")
        client.close()
    }
  }

  it should "call a base URL whose host it bypasses directly" in {
    Using.resources(RecordingProxy.start(), RecordingProxy.start(body = reply("direct"))) { (proxy, server) =>
      val settings = HttpNetworkSettings(proxy = Some(HttpProxy("127.0.0.1", proxy.port)))
      val client   = AnthropicClient(config(server.url, settings)).value
      client.complete(hello, CompletionOptions()).value.content shouldBe "direct"
      proxy.requests shouldBe empty
      server.requests.map(_.target) shouldBe Vector("/v1/messages")
      client.close()
    }
  }

  "AnthropicClient with a trust store" should "reach an HTTPS server signed by its CA, which it refuses without one" in {
    val server  = TestTls.httpsServer(body = reply("over tls"))
    val baseUrl = s"https://localhost:${server.getAddress.getPort}"
    val plain   = AnthropicClient(config(baseUrl, HttpNetworkSettings.default)).value
    plain.complete(hello, CompletionOptions()).isLeft shouldBe true
    plain.close()
    val trusting = HttpNetworkSettings(tls = HttpTls(Some(TestTls.trustStore), Some(TestTls.Password)))
    val client   = AnthropicClient(config(baseUrl, trusting)).value
    client.complete(hello, CompletionOptions()).value.content shouldBe "over tls"
    client.close()
    server.stop(0)
  }

  "AnthropicClient with a trust store it cannot read" should "refuse to build, never naming the password" in {
    val broken = HttpNetworkSettings(tls = HttpTls(Some(Path.of("missing.p12")), Some("never-printed")))
    val error  = AnthropicClient(config(BaseUrl, broken)).left.value
    error.message should (include("tls.trustStore").and(not).include("never-printed"))
  }
}
