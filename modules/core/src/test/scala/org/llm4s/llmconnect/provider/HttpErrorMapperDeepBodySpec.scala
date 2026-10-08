package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ServiceError, ValidationError }
import org.llm4s.testutil.SmallStack
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A deeply nested error body falls back to the default message (#1658).
 *
 * `ujson.read` parses any depth iteratively, but `.obj` on a non-object throws
 * `ujson.Value.InvalidData`, whose message renders the whole value - recursively, once per
 * nesting level. A 100,000-deep top-level array therefore overflowed the stack, and a
 * `StackOverflowError` is not caught by `Try`, so it escaped every provider's non-2xx path.
 * Each case runs on a 256 KiB thread stack, so an overflow is deterministic and fails the test.
 */
class HttpErrorMapperDeepBodySpec extends AnyFlatSpec with Matchers {

  private val provider   = "test-provider"
  private val StackBytes = 256L * 1024L
  private val Depth      = 100000

  private def default(status: Int) = s"$provider API error (HTTP $status)"

  private val deepArray  = "[" * Depth + "]" * Depth
  private val deepObject = """{"a":""" * Depth + "1" + "}" * Depth
  private val deepError  = """{"error":""" + deepArray + "}"

  private def onSmallStack[A](body: => A): A =
    SmallStack.run(body, stackBytes = StackBytes) match {
      case Right(value) => value
      case Left(thrown) => fail(s"the call died on a small stack with ${thrown.getClass.getName}", thrown)
    }

  "HttpErrorMapper.extractErrorDetails" should "return the default message for a 100,000-deep top-level array" in {
    onSmallStack(HttpErrorMapper.extractErrorDetails(deepArray, 400, provider)) shouldBe default(400)
  }

  it should "return the default message for a 100,000-deep object" in {
    onSmallStack(HttpErrorMapper.extractErrorDetails(deepObject, 500, provider)) shouldBe default(500)
  }

  it should "return the default message for a 100,000-deep value under error" in {
    onSmallStack(HttpErrorMapper.extractErrorDetails(deepError, 500, provider)) shouldBe default(500)
  }

  it should "return the default message for a shallow top-level array" in {
    onSmallStack(HttpErrorMapper.extractErrorDetails("[1]", 400, provider)) shouldBe default(400)
  }

  it should "still read a message from an ordinary body" in {
    Seq("""{"message":"m"}""", """{"error":{"message":"m"}}""", """{"error":"m"}""").foreach { body =>
      onSmallStack(HttpErrorMapper.extractErrorDetails(body, 400, provider)) shouldBe "m"
    }
  }

  "HttpErrorMapper.mapHttpError" should "return a Left with the default message for a deep top-level array" in {
    onSmallStack(HttpErrorMapper.mapHttpError(400, deepArray, provider)) shouldBe
      Left(ValidationError("request", default(400)))
    onSmallStack(HttpErrorMapper.mapHttpError(500, deepArray, provider)) shouldBe
      Left(ServiceError(500, provider, default(500)))
  }

  it should "return a Left with the default message for a deep object or a deep value under error" in {
    Seq(deepObject, deepError).foreach { body =>
      onSmallStack(HttpErrorMapper.mapHttpError(503, body, provider)) shouldBe
        Left(ServiceError(503, provider, default(503)))
    }
  }
}
