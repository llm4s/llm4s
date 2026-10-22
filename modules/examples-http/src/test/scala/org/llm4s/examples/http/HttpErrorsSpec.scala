package org.llm4s.examples.http

import org.llm4s.agent.graph.{ GraphError, NodeId, StateKeyId, TaskId, ToolCallId, ToolName }
import org.llm4s.error.{
  AuthenticationError,
  ConfigurationError,
  InvalidInputError,
  NetworkError,
  ProcessingError,
  RateLimitError,
  ServiceError,
  TimeoutError,
  ValidationError
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class HttpErrorsSpec extends AnyFlatSpec with Matchers {

  "HttpErrors.of" should "answer 400 for a request the caller must change" in {
    HttpErrors.of(ValidationError("message", "must not be blank")).status shouldBe 400
    HttpErrors.of(InvalidInputError("message", "", "empty")).status shouldBe 400
  }

  it should "answer 429 with the provider's Retry-After for a rate limit" in {
    val failure = HttpErrors.of(RateLimitError("fake", 7.seconds))
    failure.status shouldBe 429
    failure.retryAfterSeconds shouldBe Some(7L)
  }

  it should "fall back to RateLimitError's default delay when the provider gave none" in {
    HttpErrors.of(RateLimitError("fake")).retryAfterSeconds shouldBe Some(RateLimitError.DefaultRetryDelay.toSeconds)
  }

  it should "answer 504 for a timeout, 502 for refused credentials and 500 for a misconfigured server" in {
    HttpErrors.of(TimeoutError("slow", 30.seconds, "complete")).status shouldBe 504
    HttpErrors.of(AuthenticationError("fake", "bad key")).status shouldBe 502
    HttpErrors.of(ConfigurationError("no provider")).status shouldBe 500
  }

  it should "answer 503 for any other recoverable error and 502 for any other error" in {
    HttpErrors.of(NetworkError("down", None, "http://x")).status shouldBe 503
    HttpErrors.of(ServiceError(500, "fake", "oops")).status shouldBe 503
    HttpErrors.of(ProcessingError("parse", "bad answer")).status shouldBe 502
  }

  it should "decide by the cause of the agent runtime's wrapping, not the wrapper" in {
    val limited = RateLimitError("fake", 2.seconds)
    HttpErrors.of(GraphError.NodeFailed(NodeId("model"), TaskId("t"), limited)).status shouldBe 429
    HttpErrors.of(GraphError.ToolFailed(ToolName("search"), ToolCallId("c"), limited)).status shouldBe 429
    HttpErrors
      .of(GraphError.StateUpdateFailed(StateKeyId("k"), GraphError.NodeFailed(NodeId("n"), TaskId("t"), limited)))
      .error shouldBe limited
  }

  "HttpFailure" should "round Retry-After up to whole seconds, at least one" in {
    val error = RateLimitError("fake")
    HttpFailure(429, Some(1500.millis), error).retryAfterSeconds shouldBe Some(2L)
    HttpFailure(429, Some(Duration.Zero), error).retryAfterSeconds shouldBe Some(1L)
    HttpFailure(429, None, error).retryAfterSeconds shouldBe None
  }

  it should "describe the error in its body" in {
    val body = HttpErrors.of(NetworkError("down", None, "http://x")).body
    body("error")("type").str shouldBe "NetworkError"
    body("error")("message").str should include("down")
    body("error")("recoverable").bool shouldBe true
  }
}
