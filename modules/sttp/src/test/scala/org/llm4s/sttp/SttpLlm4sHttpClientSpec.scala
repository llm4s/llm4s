package org.llm4s.sttp

import org.llm4s.error.{ CancelledError, NetworkError, TimeoutError, ValidationError }
import org.llm4s.http.MultipartPart
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client4.*
import sttp.client4.testing.{ RecordingBackend, ResponseStub, SyncBackendStub, * }
import sttp.model.{ Header, StatusCode }

import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/**
 * [[SttpLlm4sHttpClient]] over sttp's stub backend: what the request carries, the response it builds, and how each
 * failure sttp raises maps to the error the default client gives. The behaviour shared with the default client over a
 * real socket is in [[HttpTransportContract]].
 */
class SttpLlm4sHttpClientSpec extends AnyFlatSpec with Matchers {

  private def recording(stub: SyncBackendStub) = RecordingBackend(stub)

  private def throwing(e: GenericRequest[?, ?] => Throwable): SttpLlm4sHttpClient =
    new SttpLlm4sHttpClient(SyncBackendStub.whenAnyRequest.thenRespondF(req => throw e(req)))

  "SttpLlm4sHttpClient" should "send the method, URL, headers, body and timeout it is given" in {
    val backend = recording(SyncBackendStub.whenAnyRequest.thenRespondAdjust("done"))
    val client  = new SttpLlm4sHttpClient(backend)

    val response = client.post("https://llm.test/v1/chat", Map("Authorization" -> "Bearer k"), """{"a":1}""", 7.seconds)

    response.map(_.body) shouldBe Right("done")
    val (request, _) = backend.allInteractions.head
    request.method.method shouldBe "POST"
    request.uri.toString shouldBe "https://llm.test/v1/chat"
    request.header("Authorization") shouldBe Some("Bearer k")
    request.forceBodyAsString shouldBe """{"a":1}"""
    request.options.readTimeout shouldBe 7.seconds
  }

  it should "add GET parameters to the URL" in {
    val backend = recording(SyncBackendStub.whenAnyRequest.thenRespondAdjust("ok"))
    new SttpLlm4sHttpClient(backend).get("https://llm.test/models", params = Map("page" -> "2")).isRight shouldBe true
    backend.allInteractions.head._1.uri.paramsMap shouldBe Map("page" -> "2")
  }

  it should "key response headers in lower case and keep every value" in {
    val stub = SyncBackendStub.whenAnyRequest.thenRespond(
      ResponseStub.adjust(
        "x",
        StatusCode.TooManyRequests,
        Seq(Header("Retry-After", "3"), Header("X-A", "1"), Header("x-a", "2"))
      )
    )
    val response = new SttpLlm4sHttpClient(stub).post("https://llm.test/x").toOption.get
    response.statusCode shouldBe 429
    response.headers("retry-after") shouldBe Seq("3")
    response.headers("x-a") shouldBe Seq("1", "2")
  }

  it should "return a streamed body and raw bytes from the stub" in {
    val stub   = SyncBackendStub.whenAnyRequest.thenRespondAdjust("data: one\n\n")
    val client = new SttpLlm4sHttpClient(stub)
    new String(client.postStream("https://llm.test/s").toOption.get.body.readAllBytes(), UTF_8) shouldBe "data: one\n\n"
    client.postRaw("https://llm.test/r").toOption.get.body.toSeq shouldBe "data: one\n\n".getBytes(UTF_8).toSeq
  }

  it should "send a multipart body of the parts it is given" in {
    val backend = recording(SyncBackendStub.whenAnyRequest.thenRespondAdjust("ok"))
    new SttpLlm4sHttpClient(backend)
      .postMultipart("https://llm.test/upload", parts = Seq(MultipartPart.TextField("model", "m")))
      .isRight shouldBe true
    backend.allInteractions.head._1.body match {
      case BasicMultipartBody(parts) => parts.map(_.name) shouldBe Seq("model")
      case other                     => fail(s"expected a multipart body, got $other")
    }
  }

  it should "map a timeout sttp reports to a TimeoutError, whatever its cause" in {
    throwing(req => new SttpClientException.TimeoutException(req, new HttpTimeoutException("slow")))
      .post("https://llm.test/x", timeout = 1.second)
      .left
      .toOption
      .get shouldBe a[TimeoutError]
    throwing(req => new SttpClientException.TimeoutException(req, new RuntimeException("slow")))
      .postStream("https://llm.test/x", timeout = 1.second)
      .left
      .toOption
      .get shouldBe a[TimeoutError]
  }

  it should "map a connect failure to a NetworkError naming the endpoint, not its query" in {
    val error = throwing(req => new SttpClientException.ConnectException(req, new UnknownHostException("llm.test")))
      .get("https://llm.test/x?key=secret")
      .left
      .toOption
      .get
    error shouldBe a[NetworkError]
    (error.message should not).include("secret")
  }

  it should "map a failure sttp gives no cause, such as too many redirects, to a NetworkError" in {
    throwing(req => new SttpClientException.TooManyRedirectsException(req, 32))
      .get("https://llm.test/x")
      .left
      .toOption
      .get shouldBe a[NetworkError]
  }

  it should "map an interrupt to a CancelledError and keep the interrupt flag" in {
    val error = throwing(_ => new InterruptedException("stop")).post("https://llm.test/x")
    val flag  = Thread.interrupted()
    error.left.toOption.get shouldBe a[CancelledError]
    flag shouldBe true
  }

  it should "refuse a URL that is not absolute http or https, without sending it" in {
    val backend = recording(SyncBackendStub.whenAnyRequest.thenRespondAdjust("ok"))
    val client  = new SttpLlm4sHttpClient(backend)
    Seq("not a url", "/relative", "ftp://llm.test/x").foreach(url =>
      client.post(url).left.toOption.get shouldBe a[ValidationError]
    )
    backend.allInteractions shouldBe empty
  }

  it should "leave the backend open when it is closed: the caller owns it" in {
    val closed = new AtomicBoolean(false)
    val stub   = SyncBackendStub.whenAnyRequest.thenRespondAdjust("ok")
    val backend = new SyncBackend {
      override def send[T](
        request: GenericRequest[T, Any & sttp.capabilities.Effect[sttp.shared.Identity]]
      ): Response[T] =
        stub.send(request)
      override def close(): Unit = closed.set(true)
    }
    val client = new SttpLlm4sHttpClient(backend)
    client.close()
    closed.get shouldBe false
    client.post("https://llm.test/x").isRight shouldBe true
  }
}
