package org.llm4s.samples.basic

import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object OllamaExample {
  def main(args: Array[String]): Unit = {
    // Expect: LLM_MODEL=ollama/<local-model> and optional OLLAMA_BASE_URL
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a concise assistant."),
        UserMessage("Name three facts about Scala.")
      )
    )

    val result = for {
      client     <- LLMConnect.fromEnv()
      completion <- client.complete(conversation, CompletionOptions())
      _ = Console.println("Assistant:\n" + completion.message.content)
    } yield ()

    result.fold(err => Console.err.println(s"Error: ${err.formatted}"), identity)

  }
}
