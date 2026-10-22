package org.llm4s.examples.http

import org.llm4s.agent.graph.GraphError
import org.llm4s.error.{
  AuthenticationError,
  ConfigurationError,
  InvalidInputError,
  LLMError,
  RateLimitError,
  TimeoutError,
  ValidationError
}

import scala.concurrent.duration.FiniteDuration

/**
 * How an [[org.llm4s.error.LLMError LLMError]] becomes an HTTP response: the status, the `Retry-After` delay when
 * there is one, and the error that decided them. Framework-neutral, so every reference app maps errors the same way.
 */
final case class HttpFailure(status: Int, retryAfter: Option[FiniteDuration], error: LLMError) {

  /** `Retry-After` in whole seconds, rounded up: the header has no smaller unit, and `0` would mean "now". */
  def retryAfterSeconds: Option[Long] =
    retryAfter.map(d => math.max(1L, (d.toMillis + 999L) / 1000L))

  /** The response body: `{"error": {"type", "message", "recoverable"}}`. */
  def body: ujson.Obj =
    ujson.Obj(
      "error" -> ujson.Obj(
        "type"        -> error.getClass.getSimpleName,
        "message"     -> error.message,
        "recoverable" -> LLMError.isRecoverable(error)
      )
    )
}

object HttpErrors {

  /**
   * The error a run failed with, without the agent runtime's wrapping: a failed model call reaches the caller as
   * `GraphError.NodeFailed(cause)`, which is non-recoverable whatever its cause, so the status is decided by the
   * cause.
   */
  def rootCause(error: LLMError): LLMError = error match {
    case GraphError.NodeFailed(_, _, cause)     => rootCause(cause)
    case failed: GraphError.ToolFailed          => rootCause(failed.cause)
    case GraphError.StateUpdateFailed(_, cause) => rootCause(cause)
    case other                                  => other
  }

  /**
   * The status for `error`:
   *
   *  - `400` for a request the caller must change (`ValidationError`, `InvalidInputError`);
   *  - `429` with `Retry-After` for a provider rate limit (the provider's hint, else `RateLimitError`'s default);
   *  - `504` for a provider timeout;
   *  - `502` for a provider that refused the server's credentials (`AuthenticationError`): the credential is the
   *    server's, not the caller's, so a `401` would wrongly ask the caller to authenticate;
   *  - `500` for a server that is misconfigured (`ConfigurationError`);
   *  - otherwise by the markers in `org.llm4s.error`: `503` for a `RecoverableError` (worth trying again), `502` for
   *    anything else.
   */
  def of(error: LLMError): HttpFailure = rootCause(error) match {
    case e @ (_: ValidationError | _: InvalidInputError) => HttpFailure(400, None, e)
    case e: RateLimitError                               => HttpFailure(429, e.retryDelay, e)
    case e: TimeoutError                                 => HttpFailure(504, None, e)
    case e: AuthenticationError                          => HttpFailure(502, None, e)
    case e: ConfigurationError                           => HttpFailure(500, None, e)
    case e if LLMError.isRecoverable(e)                  => HttpFailure(503, None, e)
    case e                                               => HttpFailure(502, None, e)
  }
}
