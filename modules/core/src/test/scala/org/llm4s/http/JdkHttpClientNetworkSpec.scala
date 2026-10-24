package org.llm4s.http

import org.llm4s.error.{ ConfigurationError, NetworkError }
import org.llm4s.testutil.{ RecordingProxy, TestTls }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import scala.concurrent.duration.*
import scala.util.Using

/**
 * The JDK client routed by [[HttpNetworkSettings]] (#2273): through a local recording proxy, with and without proxy
 * authentication, past it for a bypassed host, and over TLS to a stub signed by a test CA. No network, no API key.
 */
class JdkHttpClientNetworkSpec extends AnyFlatSpec with Matchers with EitherValues {

  // A host that does not resolve: only a proxy can answer a request for it.
  private val Target = "http://api.provider.test/v1/chat/completions"

  private def through(proxy: HttpProxy): HttpNetworkSettings = HttpNetworkSettings(proxy = Some(proxy))

  "A client with a proxy" should "send every request to the proxy, in absolute form" in {
    Using.resource(RecordingProxy.start(body = """{"ok":true}""")) { proxy =>
      Using.resource(Llm4sHttpClient.create(through(HttpProxy("127.0.0.1", proxy.port).withNonProxyHosts(Nil)))) {
        client =>
          val response = client.post(Target, Map("Content-Type" -> "application/json"), """{"q":1}""").value
          response.statusCode shouldBe 200
          response.body shouldBe """{"ok":true}"""
          proxy.requests.map(r => (r.method, r.target)) shouldBe Vector(("POST", Target))
          proxy.requests.head.body shouldBe """{"q":1}"""
      }
    }
  }

  it should "answer an authenticating proxy's challenge with the proxy's credentials" in {
    Using.resource(RecordingProxy.start(credentials = Some(("alice", "s3cret")))) { proxy =>
      val settings =
        through(HttpProxy.fromUrl(s"http://alice:s3cret@127.0.0.1:${proxy.port}").value.withNonProxyHosts(Nil))
      Using.resource(Llm4sHttpClient.create(settings, 5.seconds)) { client =>
        client.get(Target).value.statusCode shouldBe 200
        proxy.requests.head.header("proxy-authorization") shouldBe None
        proxy.authorized.map(_.target) shouldBe Vector(Target)
      }
    }
  }

  it should "get the proxy's 407 back when it has no credentials" in {
    Using.resource(RecordingProxy.start(credentials = Some(("alice", "s3cret")))) { proxy =>
      Using.resource(Llm4sHttpClient.create(through(HttpProxy("127.0.0.1", proxy.port).withNonProxyHosts(Nil)))) {
        client =>
          client.get(Target).value.statusCode shouldBe 407
          proxy.authorized shouldBe empty
      }
    }
  }

  it should "call a host in nonProxyHosts directly" in {
    Using.resources(RecordingProxy.start(), RecordingProxy.start(body = "direct")) { (proxy, ollama) =>
      // The default nonProxyHosts send loopback direct, as a local Ollama needs.
      Using.resource(Llm4sHttpClient.create(through(HttpProxy("127.0.0.1", proxy.port)))) { client =>
        client.get(s"${ollama.url}/api/tags").value.body shouldBe "direct"
        proxy.requests shouldBe empty
        ollama.requests.map(_.target) shouldBe Vector("/api/tags")
      }
    }
  }

  "A client with a trust store" should "reach an HTTPS server signed by the store's CA, which it refuses without one" in {
    val server = TestTls.httpsServer(body = """{"tls":true}""")
    val url    = s"https://localhost:${server.getAddress.getPort}/v1/models"
    Using.resource(Llm4sHttpClient.create(HttpNetworkSettings.default)) { plain =>
      plain.get(url).left.value shouldBe a[NetworkError]
    }
    val trusting = HttpNetworkSettings(tls = HttpTls(Some(TestTls.trustStore), Some(TestTls.Password)))
    Using.resource(Llm4sHttpClient.create(trusting)) { client =>
      client.get(url).value.body shouldBe """{"tls":true}"""
    }
    server.stop(0)
  }

  it should "present the key store's client certificate to a server that demands one" in {
    val server    = TestTls.httpsServer(body = "mtls", requireClientCertificate = true)
    val url       = s"https://localhost:${server.getAddress.getPort}/"
    val trustOnly = HttpTls(Some(TestTls.trustStore), Some(TestTls.Password))
    Using.resource(Llm4sHttpClient.create(HttpNetworkSettings(tls = trustOnly))) { client =>
      client.get(url).isLeft shouldBe true
    }
    val withCertificate = trustOnly.withKeyStore(TestTls.clientStore).withKeyStorePassword(TestTls.Password)
    Using.resource(Llm4sHttpClient.create(HttpNetworkSettings(tls = withCertificate))) { client =>
      client.get(url).value.body shouldBe "mtls"
    }
    server.stop(0)
  }

  "A client whose TLS store cannot be read" should "fail every request with a ConfigurationError, never the password" in {
    val broken = HttpNetworkSettings(tls =
      HttpTls(trustStore = Some(Path.of("missing-ca.p12")), trustStorePassword = Some("do-not-print"))
    )
    Using.resource(Llm4sHttpClient.create(broken)) { client =>
      val calls = Seq(
        client.get(Target),
        client.post(Target),
        client.postBytes(Target),
        client.postMultipart(Target),
        client.put(Target),
        client.delete(Target),
        client.postRaw(Target),
        client.postStream(Target)
      )
      calls.foreach { result =>
        val error = result.left.value
        error shouldBe a[ConfigurationError]
        error.message should (include("tls.trustStore").and(not).include("do-not-print"))
      }
    }
  }
}
