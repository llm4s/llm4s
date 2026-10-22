package org.llm4s.examples.http.tapir

import org.llm4s.error.{
  AuthenticationError,
  ConfigurationError,
  InvalidInputError,
  NetworkError,
  RateLimitError,
  TimeoutError,
  UnknownError,
  ValidationError
}
import org.llm4s.examples.http.HttpErrors
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.apispec.openapi.{ Operation, Response, ResponsesCodeKey }
import sttp.apispec.openapi.circe.yaml.*
import sttp.model.StatusCode
import sttp.tapir.DecodeResult

import scala.concurrent.duration.*

/** The OpenAPI documentation generated from the endpoint definitions. */
class ChatEndpointsSpec extends AnyFlatSpec with Matchers {

  private val openApi = ChatEndpoints.openApi

  private def post(path: String): Operation =
    openApi.paths.pathItems.get(path).flatMap(_.post).getOrElse(fail(s"no POST $path in the document"))

  private def responses(operation: Operation): Map[Int, Response] =
    operation.responses.responses.collect { case (ResponsesCodeKey(code), Right(response)) => code -> response }.toMap

  "The OpenAPI document" should "describe both endpoints, and nothing else" in {
    openApi.paths.pathItems.keySet shouldBe Set("/chat", "/chat/stream")
    post("/chat").requestBody should not be empty
    post("/chat/stream").requestBody should not be empty
  }

  it should "give POST /chat a JSON answer and every error status HttpErrors can choose" in {
    val documented = responses(post("/chat"))
    documented.keySet shouldBe (200 +: ChatEndpoints.ErrorStatuses.map(_.code)).toSet
    documented(200).content.keySet shouldBe Set("application/json")
    ChatEndpoints.ErrorStatuses.foreach { status =>
      withClue(s"$status: ")(documented(status.code).content.keySet shouldBe Set("application/json"))
      documented(status.code).description should not be empty
    }
  }

  it should "document Retry-After on the rate-limit response only" in {
    val documented = responses(post("/chat"))
    documented(429).headers.keySet shouldBe Set("Retry-After")
    (documented - 429).values.foreach(_.headers shouldBe empty)
  }

  it should "give POST /chat/stream an event stream and the one status that precedes it, 400" in {
    val documented = responses(post("/chat/stream"))
    documented.keySet shouldBe Set(200, 400)
    documented(200).content.keySet shouldBe Set("text/event-stream")
    documented(200).headers.keySet shouldBe Set("Cache-Control")
  }

  it should "describe the request, answer and error bodies" in {
    val schemas = openApi.components.map(_.schemas.keySet).getOrElse(Set.empty)
    Seq("ChatRequest", "ChatResponse", "ErrorBody", "ErrorDetail").filterNot(schemas.contains) shouldBe empty
    val yaml = openApi.toYaml
    yaml should include("threadId")
    yaml should include("recoverable")
  }

  "A JSON body" should "decode JSON and refuse anything else" in {
    val codec = ChatEndpoints.jsonBody(sttp.tapir.Schema.string).codec
    codec.decode("""{"a": 1}""") shouldBe DecodeResult.Value(ujson.Obj("a" -> 1))
    codec.decode("not json") shouldBe a[DecodeResult.Error]
    codec.encode(ujson.Obj("a" -> 1)) shouldBe """{"a":1}"""
  }

  "The error variants" should "cover every status HttpErrors.of answers with" in {
    val errors = Seq(
      ValidationError("message", "blank"),
      InvalidInputError("x", "y", "z"),
      RateLimitError("fake", 1.second),
      TimeoutError("slow", 1.second, "complete"),
      AuthenticationError("fake", "bad key"),
      ConfigurationError("no model"),
      NetworkError("down", None, "http://x"),
      UnknownError("?", new RuntimeException())
    )
    errors.map(e => StatusCode(HttpErrors.of(e).status)).toSet shouldBe ChatEndpoints.ErrorStatuses.toSet
  }
}
