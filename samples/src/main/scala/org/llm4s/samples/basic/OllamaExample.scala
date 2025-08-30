package org.llm4s.samples.basic

import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object OllamaExample {
  def main(args: Array[String]): Unit = {
    // Expect: LLM_MODEL=ollama/<local-model> and optional OLLAMA_BASE_URL
    val messages = Seq(
      SystemMessage("You are a concise assistant."),
      UserMessage("Name three facts about Scala.")
    )

    val result    = LLMConnect.complete(messages)(LLMConfig())
    result match {
      case Right(completion) =>
        println("Assistant:\n" + completion.message.content)
      case Left(err) =>
        Console.err.println("Error: " + err.formatted)
    }
  }
}
