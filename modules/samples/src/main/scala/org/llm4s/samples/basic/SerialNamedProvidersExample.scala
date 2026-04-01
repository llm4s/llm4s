package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.*
import org.slf4j.LoggerFactory

/**
 * Demonstrates sending the same prompt to a list of named providers serially.
 *
 * Usage:
 *   sbt "samples/runMain org.llm4s.samples.basic.SerialNamedProvidersExample"
 */
object SerialNamedProvidersExample:
  def main(args: Array[String]): Unit =
    val prompt = "Reply in one short sentence: who are you?"

    val providerNames = List(
      "openai-main",
      "anthropic-main",
      "gemini-main",
      "deepseek-main",
      "mistral-main",
      "ollama-local"
    )

    val logger       = LoggerFactory.getLogger("org.llm4s.samples.basic.SerialNamedProvidersExample")
    val conversation = Conversation(Seq(UserMessage(prompt)))

    logger.info("=== Serial Named Providers Example ===")
    logger.info("Prompt: {}", prompt)
    logger.info("Provider names: {}", providerNames.mkString(", "))

    providerNames.foreach: providerName =>
      val result = for
        providerCfg <- Llm4sConfig.provider(providerName)
        client      <- LLMConnect.getClient(providerCfg)
        completion  <- client.complete(conversation)
      yield (providerCfg, completion)

      result.fold(
        err =>
          logger.info("")
          logger.info("=== {} ===", providerName)
          logger.error("[{}] Failed: {}", providerName, err.formatted)
        ,
        { case (cfg, completion) =>
          logger.info("")
          logger.info("=== {} ===", providerName)
          logger.info("[{}] Configured model: {}", providerName, cfg.model)
          logger.info("[{}] Completion model: {}", providerName, completion.model)
          logger.info("[{}] Response: {}", providerName, completion.message.content)
          completion.usage.foreach: usage =>
            logger.info(
              "[{}] Tokens used: {} ({} prompt + {} completion)",
              providerName,
              usage.totalTokens,
              usage.promptTokens,
              usage.completionTokens
            )
        }
      )
