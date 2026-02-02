
package org.llm4s.samples.basic

import org.llm4s.llmconnect._
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.config._

/**
 * Provider fallback example demonstrating multi-provider support in LLM4S.
 *
 * This example shows:
 * - Configuring multiple LLM providers in code
 * - Attempting a request across providers using fallback logic
 * - Running the same prompt across providers without changing application code
 * - Using the first provider that successfully generates a response
 * 
 * == Quick Start ==
 *
 * 1. Set multiple providers (order = priority):
 *    {{{
 *    export LLM_MODEL=openai/gpt-4o,anthropic/claude-3-5-haiku,ollama/llama3
 *    }}}
 *
 * 2. (Optional) Set API keys for cloud providers:
 *    {{{
 *    export OPENAI_API_KEY=sk-...
 *    export ANTHROPIC_API_KEY=sk-ant-...
 *    }}}
 *
 *    If API keys are not provided, LLM4S will automatically
 *    fall back to the next available provider (e.g. Ollama).
 *    Ensure that ollama is running locally if you intend to use it.
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

  // Provider configurations in priority order
  val providerConfigs = List(
    ("OpenAI", OpenAIConfig(
      apiKey = sys.env.getOrElse("OPENAI_API_KEY", ""),
      model = "gpt-4o-mini",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )),
    ("Anthropic", AnthropicConfig(
      apiKey = sys.env.getOrElse("ANTHROPIC_API_KEY", ""),
      model = "claude-3-5-haiku",
      baseUrl = "https://api.anthropic.com/v1",
      contextWindow = 200000,
      reserveCompletion = 4096
    )),
    ("Ollama", OllamaConfig(
      baseUrl = "http://localhost:11434",
      model = "llama3",  
      contextWindow = 8192,
      reserveCompletion = 4096
    ))
  )

  val providers = providerConfigs.flatMap {
    case (name, config) =>
      LLMConnect.getClient(config).map(name -> _).toOption
  }
  def completeWithFallback(prompt: String): Either[String, String] = {
    val conversation = Conversation(Seq(UserMessage(prompt)))
    val options = CompletionOptions()

    providers.foldLeft[Either[String, String]](Left("All providers failed")) {
      case (success @ Right(_), _) => success
      case (Left(_), (name, client)) =>
        client.complete(conversation, options) match {
          case Right(completion) => Right(completion.message.content)
          case Left(error) =>
            println(s"[FALLBACK] $name failed: ${error.message}")
            Left(s"$name failed")
        }
    }
  }
  val result = completeWithFallback("Hello, world! Which provider am I talking to?")
  result match {
    case Right(text) => println(s"[SUCCESS] Response:\n$text")
    case Left(error) => println(s"[FAILURE] $error")
  }
}