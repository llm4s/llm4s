package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

object OllamaExample {
  private val logger = LoggerFactory.getLogger(getClass)

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
      _ = {
        logger.info("Assistant:")
        logger.info("{}", completion.message.content)
      }
    } yield ()

    result.fold(err => logger.error("Error: {}", err.formatted), identity)
  }
}
