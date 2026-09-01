package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Example demonstrating how to run the same prompt against multiple providers side by side.
 * This highlights the unified API where the same code works across OpenAI, Anthropic, Gemini, etc.
 *
 * To run this example, configure named providers in your application.local.conf:
 * {{{
 * llm4s {
 *   providers {
 *     openai-main {
 *       provider = "openai"
 *       model = "gpt-4o-mini"
 *     }
 *     anthropic-main {
 *       provider = "anthropic"
 *       model = "claude-haiku-4-5-latest"
 *     }
 *     gemini-main {
 *       provider = "gemini"
 *       model = "gemini-2.0-flash"
 *     }
 *   }
 * }
 * }}}
 * Make sure you also set the necessary API keys in your environment (OPENAI_API_KEY, etc.).
 *
 * Run with:
 * sbt "samples/runMain org.llm4s.samples.basic.MultiProviderComparisonExample"
 */
object MultiProviderComparisonExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    // Initialize the model registry service required by LLMConnect
    Llm4sConfig.modelRegistryService() match {
      case Left(err) =>
        logger.error(s"Failed to initialize model registry: ${err.message}")
      case Right(registryService) =>
        given org.llm4s.model.ModelRegistryService = registryService

        // Load multiple configured named providers from application.conf / application.local.conf
        val providers = Seq(
          "openai-main",
          "anthropic-main",
          "gemini-main"
        ).flatMap(name => Llm4sConfig.provider(name).toOption)

        if (providers.isEmpty) {
          logger.warn(
            "No providers configured. Please configure at least one named provider in your application.local.conf."
          )
        } else {
          val prompt = "Explain what makes Scala good for building AI applications in 2 sentences."
          logger.info(s"Prompt: $prompt")

          providers.foreach { config =>
            LLMConnect.getClient(config) match {
              case Right(client) =>
                val start = System.currentTimeMillis()
                client.complete(Conversation(Seq(UserMessage(prompt)))) match {
                  case Right(completion) =>
                    val ms     = System.currentTimeMillis() - start
                    val tokens = completion.usage.map(_.totalTokens).getOrElse(0)
                    logger.info(s"${config.provider}: ${completion.asText.trim} (tokens: $tokens, ${ms}ms)")
                  case Left(err) =>
                    logger.error(s"${config.provider}: ERROR - ${err.message}")
                }
              case Left(err) =>
                logger.error(s"${config.provider}: CLIENT SETUP ERROR - ${err.message}")
            }
          }
        }
    }
}
