package org.llm4s.java

import org.llm4s.error.LLMError
import org.llm4s.types.Result

import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.function.{ Consumer, Function => JFunction }

/**
 * Java-friendly wrapper for [[org.llm4s.types.Result]], which is an
 * `Either[LLMError, A]`.
 *
 * Rather than requiring Java callers to deal with Scala's `Either`, `Option`,
 * or `Left`/`Right`, this class surfaces a familiar API modelled after
 * `java.util.Optional` and `CompletableFuture`.
 *
 * {{{
 * LlmResult<String> result = client.complete("What is 2+2?");
 * result.ifSuccess(System.out::println)
 *       .ifFailure(e -> System.err.println(e.getMessage()));
 * }}}
 */
final class LlmResult[A] private (private val underlying: Result[A]) {

  def isSuccess: Boolean = underlying.isRight
  def isFailure: Boolean = underlying.isLeft

  /** Returns the value on success, or throws [[LlmException]] on failure. */
  def get(): A = underlying match {
    case Right(v) => v
    case Left(e)  => throw new LlmException(e)
  }

  /** Returns the value on success, or `null` on failure. */
  def getOrNull(): A = underlying.getOrElse(null.asInstanceOf[A])

  /** Returns the [[LlmException]] on failure, or `null` on success. */
  def getError(): LlmException = underlying match {
    case Left(e)  => new LlmException(e)
    case Right(_) => null
  }

  /**
   * Invokes `action` with the value if this result is a success. Returns
   * `this` to allow chaining with [[ifFailure]].
   */
  def ifSuccess(action: Consumer[A]): LlmResult[A] = {
    underlying.foreach(action.accept)
    this
  }

  /**
   * Invokes `action` with the exception if this result is a failure. Returns
   * `this` to allow chaining with [[ifSuccess]].
   */
  def ifFailure(action: Consumer[LlmException]): LlmResult[A] = {
    underlying.left.foreach(e => action.accept(new LlmException(e)))
    this
  }

  /** Transforms the success value; failures pass through unchanged. */
  def map[B](f: JFunction[A, B]): LlmResult[B] =
    new LlmResult(underlying.map(a => f.apply(a)))

  /** Returns `Optional.of(value)` on success, `Optional.empty()` on failure. */
  def toOptional: Optional[A] =
    underlying.fold(_ => Optional.empty[A](), v => Optional.of(v))

  /** Returns an already-completed `CompletableFuture` wrapping the result. */
  def toCompletableFuture: CompletableFuture[A] = {
    val cf = new CompletableFuture[A]()
    underlying match {
      case Right(v) => cf.complete(v)
      case Left(e)  => cf.completeExceptionally(new LlmException(e))
    }
    cf
  }
}

object LlmResult {
  def success[A](value: A): LlmResult[A]        = new LlmResult(Right(value))
  def failure[A](error: LLMError): LlmResult[A] = new LlmResult(Left(error))

  private[java] def from[A](result: Result[A]): LlmResult[A] = new LlmResult(result)
}
