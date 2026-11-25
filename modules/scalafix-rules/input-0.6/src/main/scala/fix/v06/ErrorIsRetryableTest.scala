/*
rule = ErrorIsRetryable
*/
package fix.v06

import org.llm4s.error.{ LLMError, RateLimitError, ServiceError }
import org.llm4s.types.Result

object ErrorIsRetryableTest {
  def retry(): Unit = ()

  def handle(error: LLMError): Unit = if (LLMError.isRecoverable(error)) retry()

  def handleResult(result: Result[String]): Boolean = result.left.exists(e => LLMError.isRecoverable(e))

  def wrapped(result: Result[String], fallback: RateLimitError): Boolean = LLMError.isRecoverable(result.left.getOrElse(fallback))

  def worthRetrying(errors: List[LLMError]): List[LLMError] = LLMError.recoverableErrors(errors)

  def transient(error: ServiceError): Boolean = error.isRecoverableStatus

  def notFound(error: ServiceError): Boolean = error.httpStatus == 404

  def describe(error: LLMError): String = error match {
    case service: ServiceError => s"HTTP ${service.httpStatus} from ${service.provider}"
    case other                 => other.message
  }
}
