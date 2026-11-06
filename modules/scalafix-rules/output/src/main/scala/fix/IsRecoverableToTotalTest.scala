package fix

import org.llm4s.error.RateLimitError
import org.llm4s.error.LLMError

object IsRecoverableToTotalTest {
  def retry(): Unit = ()

  def handle(err: org.llm4s.error.LLMError): Unit =
    if (LLMError.isRecoverable(err)) retry()

  def rateLimited(err: RateLimitError): Boolean = LLMError.isRecoverable(err)
}

