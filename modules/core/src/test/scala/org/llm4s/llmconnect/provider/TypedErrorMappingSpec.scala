package org.llm4s.llmconnect.provider

import org.llm4s.error.{
  AuthenticationError,
  ContextLengthError,
  LLMError,
  ModelNotFoundError,
  RateLimitError,
  ServiceError,
  ValidationError
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

/**
 * What `HttpErrorMapper` makes of the error replies a caller reacts to by type, each body as its provider documents
 * it, and the transport facts (`httpStatus`, `requestId`, `retryAfter`) every mapped error carries (#2916).
 */
class TypedErrorMappingSpec extends AnyFlatSpec with Matchers {

  private def map(status: Int, body: String, headers: Map[String, Seq[String]] = Map.empty): LLMError =
    HttpErrorMapper.toError(status, body, "p", headers)

  private def openAIBody(code: String, message: String): String =
    ujson
      .Obj("error" -> ujson.Obj("message" -> message, "type" -> "invalid_request_error", "code" -> code))
      .render()

  // ---- context length

  "A context-length reply" should "be a ContextLengthError with OpenAI's code, limit and requested tokens" in {
    val error = map(
      400,
      openAIBody(
        "context_length_exceeded",
        "This model's maximum context length is 8192 tokens. However, your messages resulted in 9431 tokens. " +
          "Please reduce the length of the messages."
      ),
      Map("x-request-id" -> Seq("req_1"))
    )
    error match {
      case e: ContextLengthError =>
        e.limit shouldBe Some(8192)
        e.requestedTokens shouldBe Some(9431)
        e.httpStatus shouldBe Some(400)
        e.requestId shouldBe Some("req_1")
        e.provider shouldBe "p"
        e.code shouldBe Some("context_length_exceeded")
        e.isRetryable shouldBe false
        e.message should include("maximum context length is 8192 tokens")
      case other => fail(s"expected a ContextLengthError, got $other")
    }
  }

  it should "read OpenAI's other wording, where the request includes the completion" in {
    val error = map(
      400,
      openAIBody(
        "context_length_exceeded",
        "This model's maximum context length is 4097 tokens. However, you requested 5000 tokens " +
          "(4000 in the messages, 1000 in the completion)."
      )
    )
    error shouldBe a[ContextLengthError]
    val typed = error.asInstanceOf[ContextLengthError]
    (typed.limit, typed.requestedTokens) shouldBe (Some(4097), Some(5000))
  }

  it should "be recognised by OpenAI's code alone, with no tokens when the message states none" in {
    val error = map(400, openAIBody("context_length_exceeded", "Request too large."))
    error shouldBe a[ContextLengthError]
    val typed = error.asInstanceOf[ContextLengthError]
    (typed.limit, typed.requestedTokens) shouldBe (None, None)
  }

  it should "be recognised in Anthropic's message, with its tokens" in {
    val body =
      """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 213462 tokens > 200000 maximum"}}"""
    val typed = map(400, body) match {
      case e: ContextLengthError => e
      case other                 => fail(s"expected a ContextLengthError, got $other")
    }
    (typed.limit, typed.requestedTokens) shouldBe (Some(200000), Some(213462))
  }

  it should "be recognised in Gemini's message, with its tokens" in {
    val body =
      """{"error":{"code":400,"message":"The input token count (1200000) exceeds the maximum number of tokens allowed (1048576).","status":"INVALID_ARGUMENT"}}"""
    val typed = map(400, body) match {
      case e: ContextLengthError => e
      case other                 => fail(s"expected a ContextLengthError, got $other")
    }
    (typed.limit, typed.requestedTokens) shouldBe (Some(1048576), Some(1200000))
  }

  it should "be recognised in the wordings of the OpenAI-compatible services" in
    Seq(
      "Prompt contains 40000 tokens, too large for model with 32768 maximum context length",
      "This endpoint's maximum context length is 131072 tokens. However, you requested about 150000 tokens",
      "the request exceeds the available context size, try increasing the context window",
      "too many tokens: total number of tokens in the prompt cannot exceed 4081",
      "Input is too long for requested model."
    ).foreach(message => withClue(message)(map(400, openAIBody("invalid_request", message)) shouldBe a[ContextLengthError]))

  it should "also be recognised on a 413 or a 422, which some gateways answer with" in {
    map(413, openAIBody("context_length_exceeded", "too long")) shouldBe a[ContextLengthError]
    map(422, openAIBody("context_length_exceeded", "too long")) shouldBe a[ContextLengthError]
  }

  "A reply that only mentions tokens" should "not be a ContextLengthError" in {
    // a rate limit counted in tokens is a 429, and stays a rate limit
    map(429, openAIBody("rate_limit_exceeded", "Request too large for the context window: too many tokens")) shouldBe
      a[RateLimitError]
    // a server failure that mentions the context window is still a server failure
    map(500, openAIBody("server_error", "failed to load the context window")) shouldBe a[ServiceError]
    // an ordinary refused request
    map(400, openAIBody("invalid_request_error", "max_tokens must be positive")) shouldBe a[ValidationError]
  }

  // ---- model not found

  "A model-not-found reply" should "be a ModelNotFoundError with OpenAI's 404 and code" in {
    val error = map(
      404,
      openAIBody("model_not_found", "The model `gpt-9` does not exist or you do not have access to it."),
      Map("x-request-id" -> Seq("req_2"))
    )
    error match {
      case e: ModelNotFoundError =>
        e.httpStatus shouldBe Some(404)
        e.requestId shouldBe Some("req_2")
        e.model shouldBe None
        e.code shouldBe Some("model_not_found")
        e.isRetryable shouldBe false
        // the next step, as a 404 has always carried (#2285)
        e.message should include("troubleshooting#model-not-found")
      case other => fail(s"expected a ModelNotFoundError, got $other")
    }
  }

  it should "name the model once the client says which it asked for" in {
    val error = map(404, openAIBody("model_not_found", "The model `gpt-9` does not exist"))
    HttpErrorMapper.naming(error, "gpt-9") match {
      case e: ModelNotFoundError =>
        e.model shouldBe Some("gpt-9")
        e.context should contain("model" -> "gpt-9")
      case other => fail(s"expected a ModelNotFoundError, got $other")
    }
    // any other error is returned as it is
    val refused = map(400, openAIBody("invalid_request_error", "bad"))
    HttpErrorMapper.naming(refused, "gpt-9") shouldBe refused
  }

  it should "be recognised in Anthropic's 404, which names the model after `model:`" in {
    val body = """{"type":"error","error":{"type":"not_found_error","message":"model: claude-nope"}}"""
    map(404, body) shouldBe a[ModelNotFoundError]
  }

  it should "be recognised in Gemini's 404" in {
    val body =
      """{"error":{"code":404,"message":"models/gemini-nope is not found for API version v1beta, or is not supported for generateContent.","status":"NOT_FOUND"}}"""
    map(404, body) shouldBe a[ModelNotFoundError]
  }

  it should "be recognised in Ollama's 404" in {
    map(404, """{"error":"model 'llama9' not found"}""") shouldBe a[ModelNotFoundError]
    map(404, """{"error":"model \"llama9\" not found, try pulling it first"}""") shouldBe a[ModelNotFoundError]
  }

  it should "be recognised in the 400s some OpenAI-compatible services answer with" in {
    // Mistral
    map(400, """{"object":"error","message":"Invalid model: mistral-nope","type":"invalid_model","code":"1500"}""") shouldBe
      a[ModelNotFoundError]
    // DeepSeek
    map(400, openAIBody("invalid_request_error", "Model Not Exist")) shouldBe a[ModelNotFoundError]
    // OpenRouter
    map(400, """{"error":{"message":"nope/nope is not a valid model ID","code":400}}""") shouldBe a[ModelNotFoundError]
  }

  "A 404 that does not say the model is unknown" should "stay a ServiceError, as a wrong base URL answers 404 too" in {
    map(404, "<html>Not Found</html>") match {
      case e: ServiceError =>
        e.status shouldBe 404
        e.httpStatus shouldBe Some(404)
        e.isRetryable shouldBe false
      case other => fail(s"expected a ServiceError, got $other")
    }
    map(404, """{"error":{"message":"The API deployment for this resource does not exist.","code":"DeploymentNotFound"}}""") shouldBe
      a[ServiceError]
  }

  // ---- transport facts on every mapped error

  "Every mapped error" should "carry the response's status" in {
    map(400, openAIBody("invalid_request_error", "bad")).httpStatus shouldBe Some(400)
    map(401, "{}").httpStatus shouldBe Some(401)
    map(403, "{}").httpStatus shouldBe Some(403)
    map(429, "{}").httpStatus shouldBe Some(429)
    map(503, "{}").httpStatus shouldBe Some(503)
  }

  it should "keep a 400 a ValidationError on field `request`, now with its status" in {
    map(400, openAIBody("invalid_request_error", "max_tokens must be positive")) match {
      case e: ValidationError =>
        e.field shouldBe "request"
        e.httpStatus shouldBe Some(400)
        e.message should include("max_tokens must be positive")
      case other => fail(s"expected a ValidationError, got $other")
    }
    // one the library raises before sending has none
    ValidationError("request", "bad").httpStatus shouldBe None
  }

  it should "carry the request id of the response's headers, whichever header names it" in {
    Seq("x-request-id", "request-id", "x-amzn-requestid", "apim-request-id").foreach { header =>
      Seq(400, 401, 404, 429, 500).foreach { status =>
        withClue(s"$header / $status")(map(status, "{}", Map(header -> Seq("id-1"))).requestId shouldBe Some("id-1"))
      }
    }
    map(500, "{}").requestId shouldBe None
  }

  it should "carry Retry-After as retryAfter, and none when the server sent none" in {
    map(429, "{}", Map("retry-after" -> Seq("5"))).retryAfter shouldBe Some(5.seconds)
    map(503, "{}", Map("retry-after" -> Seq("7"))).retryAfter shouldBe Some(7.seconds)
    map(429, "{}").retryAfter shouldBe None
    map(503, "{}").retryAfter shouldBe None
    map(401, "{}").retryAfter shouldBe None
  }

  it should "say whether the same request is worth sending again" in {
    map(429, "{}").isRetryable shouldBe true
    map(500, "{}").isRetryable shouldBe true
    map(503, "{}").isRetryable shouldBe true
    map(408, "{}").isRetryable shouldBe true
    map(400, "{}").isRetryable shouldBe false
    map(401, "{}").isRetryable shouldBe false
    map(404, "{}").isRetryable shouldBe false
    map(409, "{}").isRetryable shouldBe false
    map(422, "{}").isRetryable shouldBe false
  }

  "An AuthenticationError built with a status code" should "report it as httpStatus" in {
    AuthenticationError("p", "bad key", "401").httpStatus shouldBe Some(401)
    AuthenticationError("p", "bad key", "INVALID_KEY").httpStatus shouldBe None
    AuthenticationError("p", "bad key").httpStatus shouldBe None
    AuthenticationError("p", "bad key").withHttpStatus(400).httpStatus shouldBe Some(400)
  }

  "A stream's error event" should "map to the typed errors as a response would" in {
    HttpErrorMapper.mapStreamError(400, "prompt is too long: 10 tokens > 5 maximum", "p") shouldBe a[ContextLengthError]
    HttpErrorMapper.mapStreamError(404, "model: claude-nope not found", "p") shouldBe a[ModelNotFoundError]
  }

  "ProviderErrorSignals" should "read only the head of a huge message, and in linear time" in {
    val huge = "x" * 2_000_000 + " maximum context length is 5 tokens"
    ProviderErrorSignals.isContextLength(400, Set.empty, huge) shouldBe false
    ProviderErrorSignals.tokens(huge) shouldBe (None, None)
    ProviderErrorSignals.isContextLength(400, Set.empty, "maximum context length is 5 tokens" + huge) shouldBe true
  }
}
