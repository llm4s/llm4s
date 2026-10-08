package org.llm4s.core.safety

import org.llm4s.error.{ AuthenticationError, LLMError, RateLimitError, UnknownError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * [[DefaultErrorMapper]] classifies a 401 or a 429 only when the exception's message names an HTTP
 * status, never on a bare substring (#1668).
 */
class DefaultErrorMapperSpec extends AnyFlatSpec with Matchers {

  private def unknownWithCause(t: Throwable): Unit =
    DefaultErrorMapper(t) match {
      case e: UnknownError =>
        (e.cause should be).theSameInstanceAs(t)
        e.message shouldBe t.getMessage
      case other => fail(s"expected an UnknownError for '${t.getMessage}', got $other")
    }

  // Messages that merely contain the digits: an index, an id, a port, a count, a JSON path, a file name.
  private def notAStatus: Seq[Throwable] = Seq(
    new ArrayIndexOutOfBoundsException("Index 4012 out of bounds for length 10"),
    new IllegalStateException("request 1700401234 timed out"),
    new IllegalStateException("Connection to localhost port 14290 refused"),
    new IllegalStateException("wrote 1429 tokens"),
    new IllegalArgumentException("user 401 not found"),
    new IllegalArgumentException("amount 429.00 exceeds the limit"),
    new IllegalArgumentException("line 429: unexpected token"),
    new IllegalArgumentException("unexpected character at $[401]"),
    new java.io.FileNotFoundException("report-429.txt (No such file or directory)"),
    new RuntimeException("retry 401 of 500 failed"),
    new RuntimeException("timeout after 429ms")
  )

  "DefaultErrorMapper" should "map a message that only contains 401 or 429 to UnknownError, keeping the cause" in {
    notAStatus.foreach(unknownWithCause)
  }

  it should "not classify a status code that is part of a longer number" in {
    unknownWithCause(new RuntimeException("HTTP 4010"))
    unknownWithCause(new RuntimeException("status code: 42901"))
    unknownWithCause(new RuntimeException("14290 Too Many Requests"))
  }

  it should "map an exception without a message to UnknownError" in {
    val t = new RuntimeException()
    DefaultErrorMapper(t) match {
      case e: UnknownError =>
        e.message shouldBe "Unknown error"
        (e.cause should be).theSameInstanceAs(t)
      case other => fail(s"expected an UnknownError, got $other")
    }
  }

  // Message shapes that name an HTTP status, from the SDKs and HTTP libraries an exception can come from.
  private def unauthorized: Seq[String] = Seq(
    // openai-java and anthropic-java (Stainless): "<status>: <body>"
    """401: {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
    // the same exception wrapped, whose message is the cause's toString
    new RuntimeException(
      new IllegalStateException("""401: {"error":{"message":"Incorrect API key provided"}}""")
    ).getMessage,
    "HTTP 401 Unauthorized",
    "HTTP 401",
    "HTTP/1.1 401 Unauthorized",
    "HTTP error 401",
    "Error: 401 - Unauthorized",
    "401 Unauthorized from POST https://api.example.com/v1/chat",           // Spring WebClient
    "401 Unauthorized: [no body]",                                          // Spring RestTemplate
    "status code: 401, reason phrase: Unauthorized",                        // Apache HttpClient
    "Request failed (Service: Bedrock, Status Code: 401, Request ID: abc)", // AWS SDK
    "Error code: 401 - {'error': 'invalid key'}",                           // OpenAI Python-style
    "statusCode=401",
    "received status 401 from server"
  )

  private def rateLimited: Seq[String] = Seq(
    """429: {"type":"error","error":{"type":"rate_limit_error","message":"Number of requests exceeded"}}""",
    new RuntimeException(new IllegalStateException("""429: {"error":{"message":"Rate limit reached"}}""")).getMessage,
    "HTTP 429 Too Many Requests",
    "HTTP 429",
    "HTTP/2 429",
    "Error: 429 - Too Many Requests",
    "429 Too Many Requests from POST https://api.example.com/v1/chat",
    "429 Too Many Requests: [slow down]",
    "status code: 429, reason phrase: Too Many Requests",
    "Rate exceeded (Service: Bedrock, Status Code: 429, Request ID: abc)",
    "Error code: 429 - {'error': 'rate limit'}",
    "status_code=429",
    "Response code 429"
  )

  it should "map a message naming HTTP status 401 to AuthenticationError, keeping the message and code" in {
    unauthorized.foreach { message =>
      DefaultErrorMapper(new RuntimeException(message)) match {
        case e: AuthenticationError =>
          e.provider shouldBe "unknown"
          e.code shouldBe Some("401")
          e.message should startWith("Authentication failed for unknown: ")
          LLMError.isRecoverable(e) shouldBe false
        case other => fail(s"expected an AuthenticationError for '$message', got $other")
      }
    }
  }

  it should "keep the original message, redacted, in the AuthenticationError" in {
    DefaultErrorMapper(new RuntimeException("HTTP 401 Unauthorized")).message shouldBe
      "Authentication failed for unknown: HTTP 401 Unauthorized"

    val leaked = DefaultErrorMapper(
      new RuntimeException("HTTP 401 Unauthorized: Authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz123456")
    ).message
    leaked should include("HTTP 401 Unauthorized")
    (leaked should not).include("sk-abcdefghijklmnopqrstuvwxyz123456")
  }

  it should "map a message naming HTTP status 429 to RateLimitError" in {
    rateLimited.foreach { message =>
      DefaultErrorMapper(new RuntimeException(message)) match {
        case e: RateLimitError =>
          e.provider shouldBe "unknown"
          LLMError.isRecoverable(e) shouldBe true
        case other => fail(s"expected a RateLimitError for '$message', got $other")
      }
    }
  }

  it should "prefer authentication when a message names both statuses" in {
    DefaultErrorMapper(new RuntimeException("HTTP 429 Too Many Requests, then HTTP 401 Unauthorized")) shouldBe
      a[AuthenticationError]
  }

  it should "reach the same classification through Safety.safely" in {
    Safety.safely(throw new IllegalStateException("request 1700401234 timed out")).left.toOption.get shouldBe
      an[UnknownError]
    Safety.safely(throw new IllegalStateException("HTTP 429 Too Many Requests")).left.toOption.get shouldBe
      a[RateLimitError]
  }
}
