package org.llm4s.samples.migration

import org.llm4s.config.ConfigReader
import org.llm4s.error._
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

object ErrorMigration {

  //TODO this is not used any where. Should be removed
  def currentApproach()(config:ConfigReader): Unit = {
    val client    = LLMConnect.getClient(config)
    val conversation = Conversation(
      Seq(
        SystemMessage("You are helpful"),
        UserMessage("What is Scala?")
      )
    )

    val result: Result[Completion] = client.complete(conversation)

    result match {
      case Right(completion) =>
        println(completion.message.content)

      case Left(error) =>
        println(s"Error: ${error.formatted}") // Rich error formatting

        // Enhanced error handling with recovery logic
        error match {
          case e: RateLimitError =>
            println(s"Rate limited by ${e.provider}, retrying in ${e.retryAfter.getOrElse(60)} seconds")
          // Implement retry logic

          case e: AuthenticationError =>
            println(s"Authentication failed for ${e.provider} - check API key")

          case e: ServiceError =>
            println(s"Service error from ${e.provider} (status: ${e.httpStatus})")
            e.requestId.foreach(id => println(s"Request ID: $id"))

          case e: NetworkError =>
            println(s"Network error connecting to ${e.endpoint}")
            if (error.isRecoverable) {
              println("Will retry with exponential backoff")
            }

          case _ =>
            println("Unrecoverable error occurred")
        }
    }
  }
}
