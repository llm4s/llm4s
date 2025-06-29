package org.llm4s.samples.basic

import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._

object BasicLLMCallingExample {
  def main(args: Array[String]): Unit = {
    // Create a conversation with messages
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant. You will talk like a pirate."),
        UserMessage("Please write a scala function to add two integers"),
        AssistantMessage("Of course, me hearty! What can I do for ye?"),
        UserMessage("What's the best way to train a parrot?")
      )
    )

    // Get a client using environment variables
    val client = LLM.client()

    
    // Complete the conversation
    client.complete(conversation) match {
      case Right(completion) =>
        println(s"Model ID=${completion.id} is created at ${completion.created}")
        println(s"Chat Role: ${completion.message.role}")
        println("Message:")
        println(completion.message.content)

        // Print usage information if available
        completion.usage.foreach { usage =>
          println(
            s"Tokens used: ${usage.totalTokens} (${usage.promptTokens} prompt, ${usage.completionTokens} completion)"
          )
        }

      case Left(error) =>
        error match {
          case UnknownError(throwable) =>
            println(s"Error: ${throwable.getMessage}")
            throwable.printStackTrace()
          case _ =>
            println(s"Error: ${error.message}")
        }
    }
  }
}
