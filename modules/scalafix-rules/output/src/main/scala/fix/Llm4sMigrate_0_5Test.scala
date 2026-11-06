package fix

import org.llm4s.config.Llm4sConfig
import org.llm4s.error.{ LLMError, RateLimitError }
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Message, UserMessage }
import org.llm4s.rag.loader.CrawlerConfig
import org.llm4s.config.ToolsConfigLoader
import scala.concurrent.duration.*

// The example of #2330 and the snippets of docs/migrations/0-4-1-to-0-5-0.md sections 6 and 7, as 0.4.1 code.
object Llm4sMigrate_0_5Test {
  def retry(): Unit = ()

  val opts = CompletionOptions().withMaxTokens(500).withTemperature(0.2)
  val cfg  = CrawlerConfig(delay = 500.millis, timeout = 30.seconds)
  def handle(err: LLMError): Unit = if (LLMError.isRecoverable(err)) retry()

  val rateLimited = Left(RateLimitError("openai", 1.second))

  def answer(message: AssistantMessage, text: String): AssistantMessage = message.withContent(text)

  def userText(message: Message): String = message match {
    case userMessage: UserMessage => userMessage.content
    case _                    => ""
  }

  val brave = ToolsConfigLoader.loadBraveSearchTool()
}

