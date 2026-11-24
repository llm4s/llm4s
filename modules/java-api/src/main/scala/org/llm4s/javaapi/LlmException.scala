package org.llm4s.javaapi

import org.llm4s.error.*

import java.time.Duration
import java.util.{ Optional, OptionalInt }
import scala.jdk.DurationConverters.*

/**
 * A checked-free runtime exception wrapping an [[org.llm4s.error.LLMError LLMError]], thrown only when
 * Java callers dereference a failed [[LlmResult]] via [[LlmResult#get]].
 *
 * If the wrapped error carries an underlying `Throwable` (for example a
 * `cause: Option[Throwable]` field), it is exposed through `getCause`.
 *
 * The failure can be read without Scala types: [[getKind]] is a Java enum to `switch` on, [[isRetryable]] says
 * whether sending the same request again may succeed, and [[getRetryAfter]], [[getStatusCode]] and [[getRequestId]]
 * report what the provider said.
 *
 * {{{
 * LlmException e = result.getError();
 * switch (e.getKind()) {
 *     case RATE_LIMIT -> wait(e.getRetryAfter().orElse(Duration.ofSeconds(30)));
 *     case AUTHENTICATION, CONFIGURATION -> alertOperator(e.getMessage());
 *     case CONTEXT_LENGTH -> shortenAndRetry();
 *     default -> { if (e.isRetryable()) retry(); else report(e); }
 * }
 * }}}
 */
final class LlmException(val error: LLMError) extends RuntimeException(error.message, LlmException.causeOf(error)) {

  /** What kind of failure this is; [[LlmErrorKind.OTHER]] for an error the library does not know. */
  def getKind: LlmErrorKind = LlmErrorKinds.of(error)

  /**
   * Whether sending the same request again may succeed: core's `LLMError.isRetryable`, the one rule the library's
   * own retries apply. `true` for a rate limit, a timeout, a network failure and a provider's answer with status 408,
   * 429 or 5xx; `false` for a request the provider refused as wrong (such as a `400` or a `404`), a rejected
   * credential and a cancellation.
   */
  def isRetryable: Boolean = error.isRetryable

  /**
   * How long the provider asked the caller to wait before trying again - its `Retry-After` header - or empty when it
   * did not say. It is never a delay the library made up. Usually on a rate limit (`RATE_LIMIT`) or a provider's
   * error response (`SERVICE`).
   */
  def getRetryAfter: Optional[Duration] =
    error.retryAfter.fold(Optional.empty[Duration]())(delay => Optional.of(delay.toJava))

  /**
   * The HTTP status of the provider's error response (core's `LLMError.httpStatus`), or empty when the error is not
   * a provider's response: `401` for a rejected key, `429` for a rate limit, `400` for a refused request, and
   * whatever a `SERVICE` error answered with.
   */
  def getStatusCode: OptionalInt = error.httpStatus.fold(OptionalInt.empty())(OptionalInt.of)

  /**
   * The provider's id for the failed request (core's `LLMError.requestId`), from the response's headers, or empty
   * when it sent none: what a support ticket to the provider quotes.
   */
  def getRequestId: Optional[String] = error.requestId.fold(Optional.empty[String]())(Optional.of)
}

object LlmException {

  /** Finds the underlying `Throwable` carried by an error, or `null` when there is none. */
  private def causeOf(error: LLMError): Throwable =
    error.productIterator.collectFirst {
      case t: Throwable       => t
      case Some(t: Throwable) => t
    }.orNull
}

/**
 * How [[LlmException]] reads an [[org.llm4s.error.LLMError LLMError]]: its kind.
 *
 * Every concrete error class in `org.llm4s.error` has an entry in [[byClass]], and `LlmErrorKindSpec` scans that
 * package and fails when one has not, so a new error class is given a kind on purpose rather than falling to
 * [[LlmErrorKind.OTHER]] by accident. `EmbeddingError`'s entry is the kind of a provider's error response; [[of]] reads
 * the one it is given by its status, since `JEmbeddingClient` hands it to Java callers.
 */
private[javaapi] object LlmErrorKinds {

  /** The kind of each concrete error class of `org.llm4s.error`. */
  val byClass: Map[Class[? <: LLMError], LlmErrorKind] = Map(
    classOf[AuthenticationError]         -> LlmErrorKind.AUTHENTICATION,
    classOf[RateLimitError]              -> LlmErrorKind.RATE_LIMIT,
    classOf[TimeoutError]                -> LlmErrorKind.TIMEOUT,
    classOf[NetworkError]                -> LlmErrorKind.NETWORK,
    classOf[ServiceError]                -> LlmErrorKind.SERVICE,
    classOf[EmbeddingError]              -> LlmErrorKind.SERVICE,
    classOf[ValidationError]             -> LlmErrorKind.VALIDATION,
    classOf[InvalidInputError]           -> LlmErrorKind.VALIDATION,
    classOf[UnsupportedContentPartError] -> LlmErrorKind.VALIDATION,
    classOf[ContentFilteredError]        -> LlmErrorKind.CONTENT_FILTER,
    classOf[ContextLengthError]          -> LlmErrorKind.CONTEXT_LENGTH,
    classOf[ModelNotFoundError]          -> LlmErrorKind.MODEL_NOT_FOUND,
    classOf[ConfigurationError]          -> LlmErrorKind.CONFIGURATION,
    classOf[CancelledError]              -> LlmErrorKind.CANCELLED,
    classOf[ContextError]                -> LlmErrorKind.OTHER,
    classOf[ValidationUnsupportedError]  -> LlmErrorKind.OTHER,
    classOf[ExecutionError]              -> LlmErrorKind.OTHER,
    classOf[NotFoundError]               -> LlmErrorKind.OTHER,
    classOf[OptimisticLockFailure]       -> LlmErrorKind.OTHER,
    classOf[CircuitOpenError]            -> LlmErrorKind.OTHER,
    classOf[ProcessingError]             -> LlmErrorKind.OTHER,
    classOf[SimpleError]                 -> LlmErrorKind.OTHER,
    classOf[TokenizerError]              -> LlmErrorKind.OTHER,
    classOf[UnknownError]                -> LlmErrorKind.OTHER
  )

  /**
   * The kind of `error`. A provider's error response whose status core's `HttpErrorMapper` gives a class of its own -
   * `401`/`403` an `AuthenticationError`, `429` a `RateLimitError`, `400` a `ValidationError` - has that class's kind
   * even when a client reported it as a `ServiceError`.
   */
  def of(error: LLMError): LlmErrorKind = error match {
    // an embedding provider's error response, which the embedding clients report as an `EmbeddingError` carrying the
    // status as its code, reads as a chat provider's `ServiceError` with that status does; one with no status (a
    // request that never reached the provider, a reply that could not be parsed) is `OTHER`
    case e: EmbeddingError =>
      e.httpStatus.fold(LlmErrorKind.OTHER)(s => byStatus(s).getOrElse(LlmErrorKind.SERVICE))
    case e: ServiceError => byStatus(e.status).getOrElse(LlmErrorKind.SERVICE)
    case _               => byClass.getOrElse(error.getClass, LlmErrorKind.OTHER)
  }

  private def byStatus(status: Int): Option[LlmErrorKind] = status match {
    case 401 | 403 => Some(LlmErrorKind.AUTHENTICATION)
    case 429       => Some(LlmErrorKind.RATE_LIMIT)
    case 400       => Some(LlmErrorKind.VALIDATION)
    case _         => None
  }
}
