package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect._
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.config._
import org.slf4j.LoggerFactory

/**
 * Provider fallback example demonstrating multi-provider support in LLM4S.
 *
 * This example shows:
 * - Loading configuration via Llm4sConfig.defaultProvider()
 * - Attempting a request across providers using fallback logic
 * - Running the same prompt across providers without changing application code
 * - Using the first provider that successfully generates a response
 *
 * == Quick Start ==
 *
 * 1. Set `LLM_MODEL` and the corresponding API key for your primary provider:
 *    {{{
 *    export LLM_MODEL=openai/gpt-4o-mini
 *    export OPENAI_API_KEY=sk-...
 *    }}}
 *
 *    If the primary provider fails, the example falls back to a local
 *    Ollama instance. Ensure that ollama is running locally if you intend
 *    to use it as a fallback.
 *
 * 3. Run the example:
 *    {{{
 *    sbt "samples/runMain org.llm4s.samples.basic.ProviderFallbackExample"
 *    }}}
 *
 * == Expected Output ==
 * The example prints the model/provider that successfully handled the request,
 * followed by the generated response. If earlier providers fail due to missing
 * configuration or network errors, fallback occurs transparently.
 *
 * == Supported Providers ==
 * - '''OpenAI''': `LLM_MODEL=openai/<model>`, requires `OPENAI_API_KEY`
 * - '''Anthropic''': `LLM_MODEL=anthropic/<model>`, requires `ANTHROPIC_API_KEY`
 * - '''Ollama''': `LLM_MODEL=ollama/<model>`, no API key required (local)
 */

object ProviderFallbackExample extends App {

  val logger = LoggerFactory.getLogger(this.getClass)

  // Build a list of candidate providers:
  //   1. The provider configured via Llm4sConfig (LLM_MODEL env var)
  //   2. A local Ollama fallback that requires no API key
  val configuredProvider: List[(String, ProviderConfig)] =
    Llm4sConfig.defaultProvider() match {
      case Right(cfg) => List("Configured" -> cfg)
      case Left(err) =>
        logger.info(s"No primary provider configured: ${err.formatted}")
        Nil
    }

  val ollamaFallback: List[(String, ProviderConfig)] = List(
    (
      "Ollama",
      OllamaConfig(
        baseUrl = "http://localhost:11434",
        model = "llama3",
        contextWindow = 8192,
        reserveCompletion = 4096
      )
    )
  )

  val providerConfigs: List[(String, ProviderConfig)] =
    configuredProvider ++ ollamaFallback

  val providers: Seq[(String, LLMClient)] =
    Llm4sConfig.modelRegistryService() match {
      case Left(error) =>
        logger.info(s"Failed to initialize model registry service: ${error.formatted}")
        Seq.empty
      case Right(registryService) =>
        given org.llm4s.model.ModelRegistryService = registryService
        providerConfigs.flatMap { case (name, config) =>
          LLMConnect.getClient(config) match {
            case Right(client) =>
              Some(name -> client)
            case Left(error) =>
              logger.info(
                s"Failed to initialize provider: $name - ${error.formatted}"
              )
              None
          }
        }
    }

  def completeWithFallback(prompt: String): Either[String, String] = {
    val conversation = Conversation(Seq(UserMessage(prompt)))
    val options      = CompletionOptions()
    providers.foldLeft[Either[String, String]](Left("All providers failed")) {
      case (success @ Right(_), _) =>
        success
      case (Left(_), (name, client)) =>
        client.complete(conversation, options) match {
          case Right(completion) =>
            Right(completion.message.content)

          case Left(error) =>
            logger.info(s"[FALLBACK] $name failed: ${error.message}")
            Left(s"$name failed")
        }
    }
  }

  val result =
    completeWithFallback("Hello, world! Which provider am I talking to?")
  result match {
    case Right(text) =>
      logger.info(s"[SUCCESS] Response:\n$text")
    case Left(error) =>
      logger.info(s"[FAILED] $error")
  }
}
