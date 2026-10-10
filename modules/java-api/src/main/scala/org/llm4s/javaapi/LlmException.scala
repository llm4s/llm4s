package org.llm4s.javaapi

import org.llm4s.error.*

import java.time.Duration
import java.util.{ Optional, OptionalInt }
import scala.jdk.DurationConverters.*

/**
 * A checked-free runtime exception wrapping an [[LLMError]], thrown only when
 * Java callers dereference a failed [[LlmResult]] via [[LlmResult#get]].
 *
 * If the wrapped error carries an underlying `Throwable` (for example a
 * `cause: Option[Throwable]` field), it is exposed through `getCause`.
 *
 * The failure can be read without Scala types: [[getKind]] is a Java enum to `switch` on, [[isRecoverable]] says
 * whether trying again may help, and [[getRetryAfter]] and [[getStatusCode]] report what the provider said.
 *
 * {{{
 * LlmException e = result.getError();
 * switch (e.getKind()) {
 *     case RATE_LIMIT -> wait(e.getRetryAfter().orElse(Duration.ofSeconds(30)));
 *     case AUTHENTICATION, CONFIGURATION -> alertOperator(e.getMessage());
 *     default -> { if (e.isRecoverable()) retry(); else report(e); }
 * }
 * }}}
 */
final class LlmException(val error: LLMError) extends RuntimeException(error.message, LlmException.causeOf(error)) {

  /** What kind of failure this is; [[LlmErrorKind.OTHER]] for an error the library does not know. */
  def getKind: LlmErrorKind = LlmErrorKinds.of(error)

  /**
   * Whether the call may succeed if tried again, perhaps after the caller does something first (waits, re-reads,
   * corrects the request): core's `LLMError.isRecoverable`, which is `true` exactly for a `RecoverableError`. It is
   * wider than what is worth retrying unchanged: a provider's answer with a client-error status, such as `400`, is
   * recoverable but needs a corrected request.
   */
  def isRecoverable: Boolean = LLMError.isRecoverable(error)

  /**
   * How long the provider asked the caller to wait before trying again - its `Retry-After` header - or empty when it
   * did not say. Only a rate limit (`RATE_LIMIT`) or a provider's error response (`SERVICE`) carries one.
   */
  def getRetryAfter: Optional[Duration] = LlmErrorKinds.retryAfter(error).fold(Optional.empty[Duration]())(Optional.of)

  /**
   * The HTTP status of the provider's error response, or empty when the error carries none. Present for a provider's
   * error response that kept its status (a `ServiceError`, or an `APIError` that has one), whose kind is `SERVICE` -
   * or, for `400`, `401`, `403` and `429`, the kind that status means (see [[getKind]]).
   */
  def getStatusCode: OptionalInt = LlmErrorKinds.statusCode(error).fold(OptionalInt.empty())(OptionalInt.of)
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
 * How [[LlmException]] reads an [[LLMError]]: its kind, its retry delay and its HTTP status.
 *
 * Every concrete error class in `org.llm4s.error` has an entry in [[byClass]], and `LlmErrorKindSpec` scans that
 * package and fails when one has not, so a new error class is given a kind on purpose rather than falling to
 * [[LlmErrorKind.OTHER]] by accident.
 */
private[javaapi] object LlmErrorKinds {

  /** The kind of each concrete error class of `org.llm4s.error`. */
  val byClass: Map[Class[? <: LLMError], LlmErrorKind] = Map(
    classOf[AuthenticationError]   -> LlmErrorKind.AUTHENTICATION,
    classOf[RateLimitError]        -> LlmErrorKind.RATE_LIMIT,
    classOf[TimeoutError]          -> LlmErrorKind.TIMEOUT,
    classOf[NetworkError]          -> LlmErrorKind.NETWORK,
    classOf[ServiceError]          -> LlmErrorKind.SERVICE,
    classOf[APIError]              -> LlmErrorKind.SERVICE,
    classOf[ValidationError]       -> LlmErrorKind.VALIDATION,
    classOf[InvalidInputError]     -> LlmErrorKind.VALIDATION,
    classOf[ConfigurationError]    -> LlmErrorKind.CONFIGURATION,
    classOf[CancelledError]        -> LlmErrorKind.CANCELLED,
    classOf[ContextError]          -> LlmErrorKind.OTHER,
    classOf[ExecutionError]        -> LlmErrorKind.OTHER,
    classOf[NotFoundError]         -> LlmErrorKind.OTHER,
    classOf[OptimisticLockFailure] -> LlmErrorKind.OTHER,
    classOf[ProcessingError]       -> LlmErrorKind.OTHER,
    classOf[SimpleError]           -> LlmErrorKind.OTHER,
    classOf[SystemError]           -> LlmErrorKind.OTHER,
    classOf[TokenizerError]        -> LlmErrorKind.OTHER,
    classOf[UnknownError]          -> LlmErrorKind.OTHER
  )

  /**
   * The kind of `error`. A provider's error response whose status core's `HttpErrorMapper` gives a class of its own -
   * `401`/`403` an `AuthenticationError`, `429` a `RateLimitError`, `400` a `ValidationError` - has that class's kind
   * even when a client reported it as a `ServiceError` or an `APIError`.
   */
  def of(error: LLMError): LlmErrorKind =
    statusCode(error).flatMap(byStatus).getOrElse(byClass.getOrElse(error.getClass, LlmErrorKind.OTHER))

  private def byStatus(status: Int): Option[LlmErrorKind] = status match {
    case 401 | 403 => Some(LlmErrorKind.AUTHENTICATION)
    case 429       => Some(LlmErrorKind.RATE_LIMIT)
    case 400       => Some(LlmErrorKind.VALIDATION)
    case _         => None
  }

  /** The HTTP status of a provider's error response. */
  def statusCode(error: LLMError): Option[Int] = error match {
    case e: ServiceError => Some(e.httpStatus)
    case e: APIError     => e.statusCode
    case _               => None
  }

  /** The delay the provider asked for, not the library's default backoff. */
  def retryAfter(error: LLMError): Option[Duration] = error match {
    case e: RateLimitError => e.retryAfter.map(_.toJava)
    case e: ServiceError   => e.retryAfter.map(_.toJava)
    case _                 => None
  }
}
