package org.llm4s.llmconnect

import org.llm4s.error.{ AuthenticationError, ValidationUnsupportedError }
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `LLMClient.validate()` never reports success without having checked something (#2830): a client that does not
 * implement the check says so with a `ValidationUnsupportedError`. The wrappers' own specs show that each returns
 * what its client returns.
 */
class LLMClientValidateSpec extends AnyFlatSpec with Matchers {

  /** A client that implements only what `LLMClient` leaves abstract. */
  private class Bare extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Left(AuthenticationError("bare", "not used"))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  "LLMClient.validate" should "be Left(ValidationUnsupportedError) for a client that implements no check" in {
    new Bare().validate() match {
      case Left(error: ValidationUnsupportedError) =>
        error.client shouldBe "Bare"
        error.message should include("checked nothing")
      case other => fail(s"expected a ValidationUnsupportedError, got $other")
    }
  }

  it should "name an anonymous client by its class" in {
    val anonymous = new Bare {}
    anonymous.validate() match {
      case Left(error: ValidationUnsupportedError) => error.client should not be empty
      case other                                   => fail(s"expected a ValidationUnsupportedError, got $other")
    }
  }
}
