package org.llm4s.java

import org.llm4s.error.{ LLMError, ValidationError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Optional

class LlmResultSpec extends AnyFlatSpec with Matchers {

  private val error: LLMError = ValidationError("test error", "field")

  "LlmResult.success" should "report isSuccess" in {
    LlmResult.success("hello").isSuccess shouldBe true
    LlmResult.success("hello").isFailure shouldBe false
  }

  "LlmResult.failure" should "report isFailure" in {
    LlmResult.failure[String](error).isFailure shouldBe true
    LlmResult.failure[String](error).isSuccess shouldBe false
  }

  "get()" should "return value on success" in {
    LlmResult.success(42).get() shouldBe 42
  }

  it should "throw LlmException on failure" in {
    val ex = intercept[LlmException] {
      LlmResult.failure[Int](error).get()
    }
    ex.error shouldBe error
  }

  "getOrNull()" should "return null on failure" in {
    LlmResult.failure[String](error).getOrNull() shouldBe null
  }

  "getError()" should "return exception on failure" in {
    val ex = LlmResult.failure[String](error).getError()
    ex.error shouldBe error
  }

  it should "return null on success" in {
    LlmResult.success("ok").getError() shouldBe null
  }

  "ifSuccess" should "invoke action with value" in {
    var seen: Option[String] = None
    LlmResult.success("hi").ifSuccess(v => seen = Some(v))
    seen shouldBe Some("hi")
  }

  it should "not invoke action on failure" in {
    var called = false
    LlmResult.failure[String](error).ifSuccess(_ => called = true)
    called shouldBe false
  }

  "ifFailure" should "invoke action with exception" in {
    var seen: Option[LlmException] = None
    LlmResult.failure[String](error).ifFailure(e => seen = Some(e))
    seen.isDefined shouldBe true
    seen.get.error shouldBe error
  }

  it should "not invoke action on success" in {
    var called = false
    LlmResult.success("ok").ifFailure(_ => called = true)
    called shouldBe false
  }

  "map" should "transform value on success" in {
    LlmResult.success(3).map(n => n * 2).get() shouldBe 6
  }

  it should "pass failure through unchanged" in {
    LlmResult.failure[Int](error).map(n => n * 2).isFailure shouldBe true
  }

  "toOptional" should "return Optional.of on success" in {
    LlmResult.success("value").toOptional shouldBe Optional.of("value")
  }

  it should "return Optional.empty on failure" in {
    LlmResult.failure[String](error).toOptional shouldBe Optional.empty()
  }

  "toCompletableFuture" should "complete normally on success" in {
    val cf = LlmResult.success("done").toCompletableFuture
    cf.isDone shouldBe true
    cf.get() shouldBe "done"
  }

  it should "complete exceptionally on failure" in {
    val cf = LlmResult.failure[String](error).toCompletableFuture
    cf.isCompletedExceptionally shouldBe true
  }

  "chaining ifSuccess and ifFailure" should "only trigger the matching branch" in {
    var successCalled = false
    var failureCalled = false

    LlmResult
      .success("ok")
      .ifSuccess(_ => successCalled = true)
      .ifFailure(_ => failureCalled = true)

    successCalled shouldBe true
    failureCalled shouldBe false
  }
}
