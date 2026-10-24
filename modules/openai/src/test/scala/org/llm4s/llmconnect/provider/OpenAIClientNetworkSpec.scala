package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpNetworkSettings, HttpProxy, HttpTls }
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig }
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
 * `openai-java`'s OkHttp client routed by the config's [[HttpNetworkSettings]] (#2273): through a local recording
 * proxy, with and without proxy authentication, past it for a bypassed host, and over TLS to a stub signed by a test
 * CA. No network, no API key.
 */
final class OpenAIClientNetworkSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val hello = Conversation(Seq(UserMessage("hello")))

  // A host that does not resolve: only the proxy can answer for it.
  private val BaseUrl = "http://api.openai.test/v1"

  private def openAI(baseUrl: String, network: HttpNetworkSettings): OpenAIConfig =
    OpenAIConfig.fromValues("gpt-4o", "sk-test", None, baseUrl).value.withNetwork(network)

  private def via(proxy: HttpProxy) = HttpNetworkSettings(proxy = Some(proxy.withNonProxyHosts(Nil)))

  "OpenAIClient with a proxy" should "send the completion through it" in {
    Using.resource(RecordingProxy.start(body = LocalProviderTestServer.openAICompletion("proxied", "gpt-4o"))) {
      proxy =>
        val client = OpenAIClient(openAI(BaseUrl, via(HttpProxy("127.0.0.1", proxy.port)))).value
        client.complete(hello, CompletionOptions()).value.content shouldBe "proxied"
        proxy.requests.map(r => (r.method, r.target)) shouldBe Vector(("POST", s"$BaseUrl/chat/completions"))
        proxy.requests.head.header("authorization") shouldBe Some("Bearer sk-test")
        client.close()
    }
  }

  it should "answer an authenticating proxy's challenge with Basic credentials" in {
    Using.resource(
      RecordingProxy.start(
        credentials = Some(("alice", "s3cret")),
        body = LocalProviderTestServer.openAICompletion("authenticated", "gpt-4o")
      )
    ) { proxy =>
      val settings = via(HttpProxy("127.0.0.1", proxy.port).withUsername("alice").withPassword("s3cret"))
      val client   = OpenAIClient(openAI(BaseUrl, settings)).value
      client.complete(hello, CompletionOptions()).value.content shouldBe "authenticated"
      proxy.requests.head.header("proxy-authorization") shouldBe None
      proxy.authorized.map(_.target) shouldBe Vector(s"$BaseUrl/chat/completions")
      client.close()
    }
  }

  it should "call a base URL whose host it bypasses directly" in {
    Using.resources(
      RecordingProxy.start(),
      RecordingProxy.start(body = LocalProviderTestServer.openAICompletion("direct", "gpt-4o"))
    ) { (proxy, server) =>
      val settings = HttpNetworkSettings(proxy = Some(HttpProxy("127.0.0.1", proxy.port)))
      val client   = OpenAIClient(openAI(s"${server.url}/v1", settings)).value
      client.complete(hello, CompletionOptions()).value.content shouldBe "direct"
      proxy.requests shouldBe empty
      server.requests.map(_.target) shouldBe Vector("/v1/chat/completions")
      client.close()
    }
  }

  "OpenAIClient for Azure with a proxy" should "send the completion through it" in {
    Using.resource(RecordingProxy.start(body = LocalProviderTestServer.openAICompletion("azure", "gpt-4o"))) { proxy =>
      val config = AzureConfig
        .fromValues("gpt-4o", "http://my-resource.openai.test", "azure-key", "2025-01-01-preview")
        .value
        .withNetwork(via(HttpProxy("127.0.0.1", proxy.port)))
      val client = OpenAIClient(config).value
      client.complete(hello, CompletionOptions()).value.content shouldBe "azure"
      proxy.requests.head.target should startWith("http://my-resource.openai.test/openai/deployments/gpt-4o/")
      client.close()
    }
  }

  "OpenAIClient with a trust store" should "reach an HTTPS server signed by its CA, which it refuses without one" in {
    val server  = TestTls.httpsServer(body = LocalProviderTestServer.openAICompletion("over tls", "gpt-4o"))
    val baseUrl = s"https://localhost:${server.getAddress.getPort}/v1"
    val plain   = OpenAIClient(openAI(baseUrl, HttpNetworkSettings.default)).value
    plain.complete(hello, CompletionOptions()).isLeft shouldBe true
    plain.close()
    val trusting = HttpNetworkSettings(tls = HttpTls(Some(TestTls.trustStore), Some(TestTls.Password)))
    val client   = OpenAIClient(openAI(baseUrl, trusting)).value
    client.complete(hello, CompletionOptions()).value.content shouldBe "over tls"
    client.close()
    server.stop(0)
  }

  "OpenAIClient with a trust store it cannot read" should "refuse to build, never naming the password" in {
    val broken = HttpNetworkSettings(tls = HttpTls(Some(Path.of("missing.p12")), Some("never-printed")))
    val error  = OpenAIClient(openAI(BaseUrl, broken)).left.value
    error.message should (include("tls.trustStore").and(not).include("never-printed"))
  }
}
