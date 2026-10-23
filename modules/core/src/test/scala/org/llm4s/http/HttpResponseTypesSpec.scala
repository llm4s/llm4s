package org.llm4s.http

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream

/** The HTTP response types are growth-prone (#2356): built through `apply`, changed through `with*`. */
class HttpResponseTypesSpec extends AnyFlatSpec with Matchers {

  private val headers = Map("x-request-id" -> Seq("req-1"))

  "HttpResponse" should "default its headers to empty" in {
    HttpResponse(200, "ok").headers shouldBe empty
  }

  it should "change each field through its setter" in {
    val response = HttpResponse(200, "ok")
      .withStatusCode(404)
      .withBody("missing")
      .withHeaders(headers)

    response shouldBe HttpResponse(404, "missing", headers)
    response.header("X-Request-Id") shouldBe Some("req-1")
  }

  it should "still match positionally" in {
    HttpResponse(201, "made", headers) match {
      case HttpResponse(status, body, hs) => (status, body, hs) shouldBe ((201, "made", headers))
    }
  }

  "JsonHttpResponse" should "change each field through its setter" in {
    val response = JsonHttpResponse(200, ujson.Obj("a" -> 1))
    response.headers shouldBe empty

    val changed = response.withStatusCode(500).withBody(ujson.Arr(1, 2)).withHeaders(headers)

    changed shouldBe JsonHttpResponse(500, ujson.Arr(1, 2), headers)
    changed.header("x-request-id") shouldBe Some("req-1")
  }

  it should "be what HttpResponse.toJson builds" in {
    HttpResponse(200, """{"a":1}""", headers).toJson() shouldBe Right(
      JsonHttpResponse(200, ujson.Obj("a" -> 1), headers)
    )
  }

  "HttpRawResponse" should "change each field through its setter" in {
    val bytes    = Array[Byte](1, 2, 3)
    val response = HttpRawResponse(200, Array.emptyByteArray)
    response.headers shouldBe empty

    val changed = response.withStatusCode(206).withBody(bytes).withHeaders(headers)

    changed.statusCode shouldBe 206
    assert(changed.body eq bytes)
    changed.header("x-request-id") shouldBe Some("req-1")
  }

  "StreamingHttpResponse" should "change each field through its setter" in {
    val stream   = new ByteArrayInputStream(Array[Byte](1))
    val response = StreamingHttpResponse(200, new ByteArrayInputStream(Array.emptyByteArray))
    response.headers shouldBe empty

    val changed = response.withStatusCode(429).withBody(stream).withHeaders(headers)

    changed.statusCode shouldBe 429
    assert(changed.body eq stream)
    changed.header("x-request-id") shouldBe Some("req-1")
  }
}
