package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object OllamaExample {
  def main(args: Array[String]): Unit = {
    // Configuration is loaded via Typesafe Config (reference.conf + application.conf + optional overlays).
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a concise assistant."),
        UserMessage("Name three facts about Scala.")
      )
    )

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      completion  <- client.complete(conversation, CompletionOptions())
      _ = Console.println("Assistant:\n" + completion.message.content)
    } yield ()

    result.fold(err => Console.err.println(s"Error: ${err.formatted}"), identity)
  }
}
