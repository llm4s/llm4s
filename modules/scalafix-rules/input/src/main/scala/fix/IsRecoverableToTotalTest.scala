/*
rule = IsRecoverableToTotal
*/
package fix

import org.llm4s.error.RateLimitError

object IsRecoverableToTotalTest {
  def retry(): Unit = ()

  def handle(err: org.llm4s.error.LLMError): Unit =
    if (err.isRecoverable) retry()

  def rateLimited(err: RateLimitError): Boolean = err.isRecoverable
}
