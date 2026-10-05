package org.llm4s.deploy

import io.undertow.Undertow
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ InetSocketAddress, URI }
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.time.Duration

/**
 * The real routes behind a real server on an ephemeral port, driven over HTTP. Only the LLM check is a stub,
 * so no network and no key is involved.
 */
class DeployServiceHttpSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  import LlmCheckOutcome._

  @volatile private var check: () => LlmCheckOutcome = () => Ready("fixturechat")

  private val app    = new DeployApp(DeployServiceConfig("127.0.0.1", 0), () => check())
  private val server = Undertow.builder().addHttpListener(0, "127.0.0.1").setHandler(app.defaultHandler).build()
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  private var port = 0

  override def beforeAll(): Unit = {
    server.start()
    port = server.getListenerInfo.get(0).getAddress.asInstanceOf[InetSocketAddress].getPort
  }

  override def afterAll(): Unit = server.stop()

  private def send(method: String, path: String): HttpResponse[String] = {
    val request = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
      .timeout(Duration.ofSeconds(10))
      .method(method, HttpRequest.BodyPublishers.noBody())
      .build()
    client.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private def contentType(response: HttpResponse[String]): String =
    response.headers().firstValue("Content-Type").orElse("")

  "GET /health" should "return 200 and {\"status\":\"up\"} as JSON" in {
    val response = send("GET", "/health")

    response.statusCode() shouldBe 200
    contentType(response) should startWith("application/json")
    ujson.read(response.body()) shouldBe ujson.Obj("status" -> "up")
  }

  it should "stay 200 whatever the LLM check says" in {
    check = () => Unconfigured("ConfigurationError")

    send("GET", "/health").statusCode() shouldBe 200
  }

  "GET /llm-check" should "return 200 with the provider when the check is ready" in {
    check = () => Ready("openai")
    val response = send("GET", "/llm-check")

    response.statusCode() shouldBe 200
    contentType(response) should startWith("application/json")
    ujson.read(response.body()) shouldBe ujson.Obj("status" -> "ready", "provider" -> "openai")
  }

  it should "return 503 when no provider is configured" in {
    check = () => Unconfigured("ConfigurationError")
    val response = send("GET", "/llm-check")

    response.statusCode() shouldBe 503
    ujson.read(response.body()) shouldBe ujson.Obj("status" -> "unconfigured", "error" -> "ConfigurationError")
  }

  it should "return 503 when a client cannot be built" in {
    check = () => Degraded("openai", "NetworkError")
    val response = send("GET", "/llm-check")

    response.statusCode() shouldBe 503
    ujson.read(response.body())("status").str shouldBe "degraded"
  }

  it should "return 503, not 500, when the check throws" in {
    check = () => throw new IllegalStateException("boom")
    val response = send("GET", "/llm-check")

    response.statusCode() shouldBe 503
    ujson.read(response.body()) shouldBe
      ujson.Obj("status" -> "degraded", "provider" -> "unknown", "error" -> "UnexpectedError")
  }

  "DeployApp" should "listen on the host and port it is configured with" in {
    val configured = new DeployApp(DeployServiceConfig("10.1.2.3", 9123), () => Ready("p"))

    configured.host shouldBe "10.1.2.3"
    configured.port shouldBe 9123
  }

  "The service" should "return 404 for a path it does not serve" in {
    send("GET", "/nope").statusCode() shouldBe 404
  }

  it should "return 405 for a method an endpoint does not allow" in {
    send("POST", "/health").statusCode() shouldBe 405
  }
}
