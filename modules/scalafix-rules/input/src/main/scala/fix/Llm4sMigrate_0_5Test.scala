/*
rule = Llm4sMigrate_0_5
*/
package fix

import org.llm4s.config.Llm4sConfig
import org.llm4s.error.{ LLMError, RateLimitError }
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Message, UserMessage }
import org.llm4s.rag.loader.CrawlerConfig

// The example of #2330 and the snippets of docs/migrations/0-4-1-to-0-5-0.md sections 6 and 7, as 0.4.1 code.
object Llm4sMigrate_0_5Test {
  def retry(): Unit = ()

  val opts = CompletionOptions().copy(maxTokens = Some(500), temperature = 0.2)
  val cfg  = CrawlerConfig(delayMs = 500, timeoutMs = 30000)
  def handle(err: LLMError): Unit = if (err.isRecoverable) retry()

  val rateLimited = Left(RateLimitError("openai", 1000L))

  def answer(message: AssistantMessage, text: String): AssistantMessage = message.copy(contentOpt = Some(text))

  def userText(message: Message): String = message match {
    case UserMessage(content) => content
    case _                    => ""
  }

  val brave = Llm4sConfig.loadBraveSearchTool()
}
