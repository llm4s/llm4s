package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.LLMClientRetry
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

/**
 * Example using [[LLMClientRetry.completeWithRetry]] for completion with automatic retries.
 *
 * Run with Ollama (no API key):
 * {{{
 *   export LLM_MODEL=ollama/llama2
 *   sbt "samples/runMain org.llm4s.samples.basic.RetryCompletionExample"
 * }}}
 */
object RetryCompletionExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    println("RetryCompletionExample: using LLMClientRetry.completeWithRetry (max 3 attempts, 1s base backoff)")
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a concise assistant."),
        UserMessage("Say hello in one sentence.")
      )
    )

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      completion <- LLMClientRetry.completeWithRetry(
        client,
        conversation,
        CompletionOptions(),
        maxAttempts = 3,
        baseDelay = 1.second
      )
      _ =
        logger.info("Assistant: {}", completion.message.content)
    } yield ()

    result.fold(
      err => {
        logger.error("Error after retries: {}", err.formatted)
        sys.exit(1)
      },
      _ => logger.info("Done.")
    )
  }
}
