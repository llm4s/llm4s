package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient, RetryPolicy }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

/**
 * A completion with automatic retries: the client wrapped in a [[org.llm4s.reliability.ReliableClient]], which
 * retries a transient failure (a rate limit, a timeout, a 5xx) and returns any other error at once.
 *
 * Run with Ollama (no API key) - the samples' default `ollama-local` section:
 * {{{
 *   export OLLAMA_MODEL=llama2
 *   sbt "samples/runMain org.llm4s.samples.basic.RetryCompletionExample"
 * }}}
 */
object RetryCompletionExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    println("RetryCompletionExample: using ReliableClient (max 3 attempts, 1s base backoff)")
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a concise assistant."),
        UserMessage("Say hello in one sentence.")
      )
    )
    val reliability = ReliabilityConfig(
      retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.second)
    )

    val result = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)
      reliable = ReliableClient(client, providerCfg.providerId.asString, reliability)
      completion <- reliable.complete(conversation, CompletionOptions())
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
