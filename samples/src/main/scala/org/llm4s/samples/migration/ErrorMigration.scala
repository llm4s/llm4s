package org.llm4s.samples.migration

import org.llm4s.llmconnect.LLMConnectV2
import org.llm4s.llmconnect.model._
import org.llm4s.error.LLMError
import org.llm4s.types.Result

object ErrorMigration {

  /**
   * OLD CODE (still works, but uses simple error types):
   */
  def oldApproach(): Unit = {
    val client = org.llm4s.llmconnect.LLMConnect.getClient()
    val conversation = Conversation(
      Seq(
        SystemMessage("You are helpful"),
        UserMessage("What is Scala?")
      )
    )

    val result: Either[org.llm4s.llmconnect.model.LLMError, Completion] =
      client.complete(conversation)

    result match {
      case Right(completion) => println(completion.message.content)
      case Left(error)       => println(s"Error: ${error.message}") // Limited error info
    }
  }

  /**
   * NEW CODE (enhanced error handling with rich context):
   */
  def newApproach(): Unit = {
    val enhancedClient = LLMConnectV2.enhancedClient()
    val conversation = Conversation(
      Seq(
        SystemMessage("You are helpful"),
        UserMessage("What is Scala?")
      )
    )

    val result: Result[Completion] = enhancedClient.complete(conversation)

    result match {
      case Right(completion) =>
        println(completion.message.content)

      case Left(error) =>
        println(s"Error: ${error.formatted}") // Rich error formatting

        // Enhanced error handling with recovery logic
        error match {
          case LLMError.RateLimitError(_, retryAfter, provider, _, _) =>
            println(s"Rate limited by $provider, retrying in ${retryAfter.getOrElse(60)} seconds")
          // Implement retry logic

          case LLMError.AuthenticationError(_, provider, _) =>
            println(s"Authentication failed for $provider - check API key")

          case LLMError.ServiceError(_, status, provider, requestId) =>
            println(s"Service error from $provider (status: $status)")
            requestId.foreach(id => println(s"Request ID: $id"))

          case LLMError.NetworkError(_, _, endpoint) =>
            println(s"Network error connecting to $endpoint")
            if (error.isRecoverable) {
              println("Will retry with exponential backoff")
            }

          case _ =>
            println("Unrecoverable error occurred")
        }
    }
  }
}
