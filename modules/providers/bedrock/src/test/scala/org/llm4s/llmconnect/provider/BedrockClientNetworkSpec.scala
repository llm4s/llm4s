package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpNetworkSettings, HttpProxy, HttpTls }
import org.llm4s.llmconnect.config.BedrockConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.{ RecordingProxy, TestTls }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import scala.util.{ Try, Using }

/**
 * The AWS SDK's HTTP clients routed by the config's [[HttpNetworkSettings]] (#2273): `Converse` (the Apache client)
 * through a local recording proxy, with and without proxy authentication, and past it for a bypassed host;
 * `ConverseStream` (the Netty client) through it too; and TLS to a stub signed by a test CA. No network, no AWS
 * account.
 */
final class BedrockClientNetworkSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val hello = Conversation(Seq(UserMessage("hello")))

  // A host that does not resolve: only the proxy can answer for it.
  private val Endpoint = "http://bedrock.aws.test"

  private def via(proxy: HttpProxy) = HttpNetworkSettings(proxy = Some(proxy.withNonProxyHosts(Nil)))

  private def client(endpoint: String, network: HttpNetworkSettings) = new BedrockClient(
    config(endpoint).withNetwork(network)
  )

  "BedrockClient with a proxy" should "send Converse through it" in {
    Using.resource(RecordingProxy.start(body = converseResponse("proxied"))) { proxy =>
      Using.resource(client(Endpoint, via(HttpProxy("127.0.0.1", proxy.port)))) { bedrock =>
        bedrock.complete(hello, CompletionOptions()).value.content shouldBe "proxied"
        proxy.requests.map(_.target) shouldBe Vector(s"$Endpoint/model/$Model/converse")
      }
    }
  }

  it should "answer an authenticating proxy's challenge with Basic credentials" in {
    Using.resource(RecordingProxy.start(credentials = Some(("alice", "s3cret")), body = converseResponse("auth"))) {
      proxy =>
        val settings = via(HttpProxy("127.0.0.1", proxy.port).withUsername("alice").withPassword("s3cret"))
        Using.resource(client(Endpoint, settings)) { bedrock =>
          bedrock.complete(hello, CompletionOptions()).value.content shouldBe "auth"
          proxy.authorized.map(_.target) shouldBe Vector(s"$Endpoint/model/$Model/converse")
        }
    }
  }

  it should "call an endpoint whose host it bypasses directly, ignoring the SDK's own proxy discovery" in {
    Using.resources(RecordingProxy.start(), RecordingProxy.start(body = converseResponse("direct"))) {
      (proxy, server) =>
        val settings = HttpNetworkSettings(proxy = Some(HttpProxy("127.0.0.1", proxy.port)))
        Using.resource(client(server.url, settings)) { bedrock =>
          bedrock.complete(hello, CompletionOptions()).value.content shouldBe "direct"
          proxy.requests shouldBe empty
          server.requests.map(_.target) shouldBe Vector(s"/model/$Model/converse")
        }
    }
  }

  it should "send ConverseStream through it" in {
    Using.resource(RecordingProxy.start(credentials = Some(("alice", "s3cret")))) { proxy =>
      val settings = via(HttpProxy("127.0.0.1", proxy.port).withUsername("alice").withPassword("s3cret"))
      Using.resource(client(Endpoint, settings)) { bedrock =>
        // The proxy answers with JSON, not an event stream, so the stream fails; the request reached the proxy.
        bedrock.streamComplete(hello, CompletionOptions(), _ => ()): Unit
        proxy.requests.map(_.target).exists(_.contains("bedrock.aws.test")) shouldBe true
      }
    }
  }

  "BedrockNetwork" should "keep the SDK's default clients without settings, and pin plain http to HTTP/1.1" in {
    BedrockNetwork.syncHttpClient(config(Endpoint)) shouldBe None
    BedrockNetwork.asyncHttpClient(config(Endpoint)) should not be empty
    BedrockNetwork.asyncHttpClient(config(Endpoint).copy(endpointUrl = None)) shouldBe None
    BedrockNetwork.endpointHost(config(Endpoint).copy(endpointUrl = None)) shouldBe
      "bedrock-runtime.us-east-1.amazonaws.com"
    BedrockNetwork.endpointHost(config("not a url")) shouldBe "bedrock-runtime.us-east-1.amazonaws.com"
  }

  it should "build clients carrying a trust store and a client certificate" in {
    val tls =
      HttpTls(Some(TestTls.trustStore), Some(TestTls.Password), Some(TestTls.clientStore), Some(TestTls.Password))
    val withTls: BedrockConfig = config("https://bedrock.aws.test").withNetwork(HttpNetworkSettings(tls = tls))
    BedrockNetwork.syncHttpClient(withTls) should not be empty
    BedrockNetwork.asyncHttpClient(withTls) should not be empty
    val trustOnly = config("https://bedrock.aws.test").withNetwork(
      HttpNetworkSettings(tls = HttpTls(Some(TestTls.trustStore), Some(TestTls.Password)))
    )
    BedrockNetwork.syncHttpClient(trustOnly) should not be empty
    BedrockNetwork.asyncHttpClient(trustOnly) should not be empty
  }

  "BedrockClient with a trust store" should "reach an HTTPS endpoint signed by its CA, which it refuses without one" in {
    val server   = TestTls.httpsServer(body = converseResponse("over tls"))
    val endpoint = s"https://localhost:${server.getAddress.getPort}"
    Using.resource(client(endpoint, HttpNetworkSettings.default)) { plain =>
      plain.complete(hello, CompletionOptions()).isLeft shouldBe true
    }
    val trusting = HttpNetworkSettings(tls = HttpTls(Some(TestTls.trustStore), Some(TestTls.Password)))
    Using.resource(client(endpoint, trusting)) { bedrock =>
      bedrock.complete(hello, CompletionOptions()).value.content shouldBe "over tls"
    }
    server.stop(0)
  }

  "BedrockClient with a trust store it cannot read" should "refuse to build, never naming the password" in {
    val broken = HttpNetworkSettings(tls = HttpTls(Some(Path.of("missing.p12")), Some("never-printed")))
    val error  = Try(client(Endpoint, broken)).failed.get
    error.getMessage should (include("tls.trustStore").and(not).include("never-printed"))
  }
}
