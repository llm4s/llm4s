package $package;
format = "package" $

import com.typesafe.scalalogging.LazyLogging
import org.llm4s.llmconnect.{LLM, LLMClient}
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ Completion, Conversation, LLMError, SystemMessage, UserMessage }
import org.llm4s.llmconnect.provider.LLMProvider

/**
 * This code is part of the Giter8 template llm4s.g8 in llm4s project, which provides a set
 * standard template/archetype for improve developer onboarding, creating new projects using the
 * llm4s library.
 */

/**
 * The PromptExecutor object is responsible for executing prompts against the OpenAI LLM.
 * It initializes the LLM client with the necessary configuration and provides a method to run
 * prompts, returning the assistant's response or an error message.
 */

object PromptExecutor extends LazyLogging {
  // Create the provider config
  private val config: OpenAIConfig = OpenAIConfig(
    apiKey = sys.env.getOrElse("OPENAI_API_KEY", "your-api-key-here"),
    model = "gpt-3.5-turbo",
    baseUrl = sys.env.getOrElse("OPENAI_BASE_URL", "https://api.openai.com/v1"),
  )

  // Default client using OpenAI; Build the client via LLM factory using provider enum
  private val defaultClient: LLMClient = LLM.client(LLMProvider.OpenAI, config)

  def run(prompt: String, clientOpt: Option[LLMClient] = None): String = {
    val client = clientOpt.getOrElse(defaultClient)

    // Build a conversation
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage(prompt),
      )
    )

    // Perform synchronous completion
    val completion: Either[LLMError, Completion] = client.complete(conversation)

    completion match {
      case Right(comp) =>
        val completionMessage = comp.message.content
        logger.info("✅ Assistant response: " + completionMessage)
        completionMessage
      case Left(err) =>
        val errorMsg = err.message
        logger.error("❌ Error: " + errorMsg)
        errorMsg
    }
  }
}
