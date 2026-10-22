package org.llm4s.http

import org.llm4s.error.{ NetworkError, ServiceError }
import org.llm4s.http.HttpResponse.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneOffset }
import scala.concurrent.duration.*

/**
 * The request id and `Retry-After` a non-2xx response carries, read into its `ServiceError` by
 * `HttpResponse#ensureSuccess` and kept by `Result#mapServiceError` (#2266).
 */
class HttpServiceErrorSpec extends AnyFlatSpec with Matchers {

  private val clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC)

  private def serviceError(result: Result[?]): ServiceError = result match {
    case Left(err: ServiceError) => err
    case other                   => fail(s"Expected a ServiceError, got $other")
  }

  "HttpHeaders.retryAfter" should "read delta-seconds" in {
    HttpHeaders.retryAfter(Map("retry-after" -> Seq(" 120 ")), clock) shouldBe Some(120.seconds)
    HttpHeaders.retryAfter(Map("Retry-After" -> Seq("0")), clock) shouldBe Some(Duration.Zero)
  }

  it should "measure an HTTP-date against the clock, giving zero for one already past" in {
    HttpHeaders.retryAfter(Map("Retry-After" -> Seq("Thu, 01 Oct 2026 12:00:30 GMT")), clock) shouldBe Some(30.seconds)
    HttpHeaders.retryAfter(Map("Retry-After" -> Seq("Thu, 01 Oct 2026 11:00:00 GMT")), clock) shouldBe Some(
      Duration.Zero
    )
  }

  it should "give None when the header is absent, malformed, negative or too large" in {
    HttpHeaders.retryAfter(Map.empty, clock) shouldBe None
    HttpHeaders.retryAfter(Map("Retry-After" -> Seq("soon")), clock) shouldBe None
    HttpHeaders.retryAfter(Map("Retry-After" -> Seq("")), clock) shouldBe None
    HttpHeaders.retryAfter(Map("Retry-After" -> Seq("-5")), clock) shouldBe None
    HttpHeaders.retryAfter(Map("Retry-After" -> Seq("1.5")), clock) shouldBe None
    HttpHeaders.retryAfter(Map("Retry-After" -> Seq("99999999999999999999")), clock) shouldBe None
  }

  "HttpHeaders.requestId" should "read the common request-id headers, in any case" in {
    HttpHeaders.requestId(Map("X-Request-Id" -> Seq("req-1"))) shouldBe Some("req-1")
    HttpHeaders.requestId(Map("request-id" -> Seq("req_2"))) shouldBe Some("req_2")
    HttpHeaders.requestId(Map("x-amzn-RequestId" -> Seq("aws-3"))) shouldBe Some("aws-3")
    HttpHeaders.requestId(Map("apim-request-id" -> Seq("az-4"))) shouldBe Some("az-4")
  }

  it should "prefer x-request-id, skip a blank value and give None when there is none" in {
    HttpHeaders.requestId(Map("request-id" -> Seq("b"), "x-request-id" -> Seq("a"))) shouldBe Some("a")
    HttpHeaders.requestId(Map("x-request-id" -> Seq("  "), "request-id" -> Seq("b"))) shouldBe Some("b")
    HttpHeaders.requestId(Map("content-type" -> Seq("application/json"))) shouldBe None
  }

  "ensureSuccess" should "put the response's request id and Retry-After on the ServiceError" in {
    val response = HttpResponse(503, "overloaded", Map("x-request-id" -> Seq("req-9"), "retry-after" -> Seq("7")))
    val err      = serviceError(response.ensureSuccess("p"))

    err.httpStatus shouldBe 503
    err.provider shouldBe "p"
    err.requestId shouldBe Some("req-9")
    err.retryAfter shouldBe Some(7.seconds)
    err.retryDelay shouldBe Some(7.seconds)
    err.message should include("overloaded")
    err.message should include("req-9")
  }

  it should "read an HTTP-date Retry-After (one already past is zero)" in {
    val response = HttpResponse(503, "x", Map("Retry-After" -> Seq("Thu, 01 Jan 2015 00:00:00 GMT")))
    serviceError(response.ensureSuccess("p")).retryAfter shouldBe Some(Duration.Zero)
  }

  it should "leave both unset when the response sent neither, or a malformed Retry-After" in {
    val bare = serviceError(HttpResponse(500, "boom").ensureSuccess("p"))
    bare.requestId shouldBe None
    bare.retryAfter shouldBe None
    bare.retryDelay shouldBe Some(ServiceError.DefaultRetryDelay)

    serviceError(HttpResponse(502, "x", Map("Retry-After" -> Seq("later"))).ensureSuccess("p")).retryAfter shouldBe None
  }

  "HttpResponse.serviceError" should "measure an HTTP-date against the given clock" in {
    val response = HttpResponse(503, "x", Map("Retry-After" -> Seq("Thu, 01 Oct 2026 12:01:00 GMT")))
    HttpResponse.serviceError(response, "p", "details", clock).retryAfter shouldBe Some(1.minute)
  }

  "mapServiceError" should "keep the status, request id and retryAfter, with the new provider and message" in {
    val original = ServiceError(503, "inner", "down", "req-1").withRetryAfter(9.seconds)
    val err      = serviceError((Left(original): Result[Int]).mapServiceError("outer", "Failed to discover models"))

    err.httpStatus shouldBe 503
    err.provider shouldBe "outer"
    err.requestId shouldBe Some("req-1")
    err.retryAfter shouldBe Some(9.seconds)
    err.message should include("Failed to discover models")
    err.message should include("down")
    err.context("requestId") shouldBe "req-1"
  }

  it should "keep an absent request id and retryAfter absent" in {
    val err = serviceError((Left(ServiceError(500, "inner", "down")): Result[Int]).mapServiceError("outer", "m"))
    err.requestId shouldBe None
    err.retryAfter shouldBe None
  }

  it should "pass other errors and successes through" in {
    val network = NetworkError("unreachable", None, "http://x")
    (Left(network): Result[Int]).mapServiceError("p", "m") shouldBe Left(network)
    (Right(1): Result[Int]).mapServiceError("p", "m") shouldBe Right(1)
  }

  "ServiceError" should "match with all five fields, retryAfter included" in {
    ServiceError(503, "p", "d", "req").withRetryAfter(3.seconds) match {
      case ServiceError(message, status, provider, requestId, retryAfter) =>
        message should include("d")
        status shouldBe 503
        provider shouldBe "p"
        requestId shouldBe Some("req")
        retryAfter shouldBe Some(3.seconds)
    }
  }
}
