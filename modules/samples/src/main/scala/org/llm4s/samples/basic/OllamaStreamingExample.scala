package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMConnect, LlmClientOptions, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

import java.nio.file.Paths

object OllamaStreamingExample {
  private val logger        = LoggerFactory.getLogger(getClass)
  private val defaultLogDir = "/tmp/llm4s/provider-exchanges"

  def main(args: Array[String]): Unit = {
    // Works with any configured provider; optimized for local Ollama
    // Env example:
    //   export LLM_MODEL=ollama/llama3.1
    //   export OLLAMA_BASE_URL=http://localhost:11434
    val logDir = args.headOption.getOrElse(defaultLogDir)

    val conversation = Conversation(
      Seq(
        SystemMessage("You are a concise assistant."),
        UserMessage("Stream a short haiku about Scala.")
      )
    )

    val buffer = new StringBuilder

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      sink        <- ProviderExchangeSink.createRunScopedJsonl(Paths.get(logDir))
      options = LlmClientOptions(
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      )
      _ = logger.info("Provider exchange logs: {}", sink.path.toAbsolutePath.normalize())
      client <- LLMConnect.getClient(providerCfg, options)
      completion <- client.streamComplete(
        conversation,
        CompletionOptions(),
        chunk =>
          chunk.content.foreach { text =>
            // Print incremental content as it arrives
            print(text)
            buffer.append(text)
          }
      )
      _ = {
        // Ensure line break after streaming
        println()
        logger.info("--- Final Message ---")
        logger.info("{}", completion.message.content)

        completion.usage.foreach(u =>
          logger.info("Tokens: prompt={}, completion={}, total={}", u.promptTokens, u.completionTokens, u.totalTokens)
        )
      }
    } yield ()

    result.fold(err => logger.error("Error: {}", err.formatted), identity)
  }
}
