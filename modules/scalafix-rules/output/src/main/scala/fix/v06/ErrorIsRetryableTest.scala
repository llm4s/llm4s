package fix.v06

import org.llm4s.error.{ LLMError, RateLimitError, ServiceError }
import org.llm4s.types.Result

object ErrorIsRetryableTest {
  def retry(): Unit = ()

  def handle(error: LLMError): Unit = if (error.isRetryable) retry()

  def handleResult(result: Result[String]): Boolean = result.left.exists(e => e.isRetryable)

  def wrapped(result: Result[String]): Boolean = result.left.getOrElse(RateLimitError("p")).isRetryable

  def worthRetrying(errors: List[LLMError]): List[LLMError] = errors.filter(_.isRetryable)

  def transient(error: ServiceError): Boolean = error.isRetryable

  def notFound(error: ServiceError): Boolean = error.status == 404

  def describe(error: LLMError): String = error match {
    case service: ServiceError => s"HTTP ${service.status} from ${service.provider}"
    case other                 => other.message
  }
}
