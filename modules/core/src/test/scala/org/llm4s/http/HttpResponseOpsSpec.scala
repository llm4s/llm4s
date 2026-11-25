package org.llm4s.http

import org.llm4s.error.{ NetworkError, ServiceError, ValidationError }
import org.llm4s.http.HttpResponse.*
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The `HttpResponse` extensions every model lister uses: the 2xx bounds of `ensureSuccess`, `toJson` with a field
 * name of its own, and `mapServiceError`. `HttpServiceErrorSpec` covers the request id and `Retry-After` they keep.
 */
class HttpResponseOpsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val headers = Map("Content-Type" -> Seq("application/json"), "X-Trace" -> Seq("t-1"))

  private def failure(result: Result[?]): ServiceError = result.left.value match {
    case error: ServiceError => error
    case other               => fail(s"expected a ServiceError, got $other")
  }

  private def invalid(result: Result[?]): ValidationError = result.left.value match {
    case error: ValidationError => error
    case other                  => fail(s"expected a ValidationError, got $other")
  }

  // ==========================================================================
  // ensureSuccess
  // ==========================================================================

  "ensureSuccess" should "return the same response for every status from 200 to 299" in {
    Seq(200, 201, 204, 299).foreach { status =>
      val response = HttpResponse(status, s"body-$status", headers)
      withClue(s"status $status: ")(
        response.ensureSuccess(ProviderId("acme")).value shouldBe theSameInstanceAs(response)
      )
    }
  }

  it should "refuse every status outside 200 to 299 with a ServiceError naming the status and provider" in {
    Seq(199, 300, 302, 404, 500).foreach { status =>
      val error = failure(HttpResponse(status, "upstream said no").ensureSuccess(ProviderId("acme")))
      withClue(s"status $status: ") {
        error.status shouldBe status
        error.provider shouldBe ProviderId("acme")
        error.message should include("upstream said no")
      }
    }
  }

  // ==========================================================================
  // toJson
  // ==========================================================================

  "toJson" should "parse a JSON object body and keep the status and headers" in {
    val json = HttpResponse(201, """{"models": ["a", "b"], "count": 2}""", headers).toJson().value

    json.statusCode shouldBe 201
    json.headers shouldBe headers
    json.body shouldBe ujson.Obj("models" -> ujson.Arr("a", "b"), "count" -> 2)
  }

  it should "name the given field when the body is not JSON" in {
    val error = invalid(HttpResponse(200, "<html>not json</html>").toJson("models"))

    error.field shouldBe "models"
    error.message should include("Failed to parse JSON response")
  }

  it should "name responseBody by default" in {
    invalid(HttpResponse(200, "not json").toJson()).field shouldBe "responseBody"
  }

  it should "refuse an empty body" in {
    invalid(HttpResponse(200, "").toJson("models")).field shouldBe "models"
  }

  // ==========================================================================
  // mapServiceError
  // ==========================================================================

  "mapServiceError" should "rewrap a ServiceError as \"<message>: <original message>\" with its status" in {
    val original = ServiceError(503, ProviderId("x"), "down")
    val error    = failure((Left(original): Result[Int]).mapServiceError(ProviderId("acme"), "Listing models"))

    error.status shouldBe 503
    error.provider shouldBe ProviderId("acme")
    error.message should include(s"Listing models: ${original.message}")
  }

  it should "return the very same error when it is not a ServiceError, and a success unchanged" in {
    val network  = NetworkError("unreachable", None, "http://acme.example")
    val returned = (Left(network): Result[Int]).mapServiceError(ProviderId("acme"), "Listing models").left.value
    returned shouldBe theSameInstanceAs(network)
    (Right(42): Result[Int]).mapServiceError(ProviderId("acme"), "Listing models") shouldBe Right(42)
  }
}
