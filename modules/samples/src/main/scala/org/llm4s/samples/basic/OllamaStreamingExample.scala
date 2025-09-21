package org.llm4s.samples.basic

import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object OllamaStreamingExample {
  def main(args: Array[String]): Unit = {
    // Works with any configured provider; optimized for local Ollama
    // Env example:
    //   export LLM_MODEL=ollama/llama3.1
    //   export OLLAMA_BASE_URL=http://localhost:11434

    val conversation = Conversation(
      Seq(
        SystemMessage("You are a concise assistant."),
        UserMessage("Stream a short haiku about Scala.")
      )
    )

    val buffer = new StringBuilder

    val result = for {
      reader <- LLMConfig()
      client <- LLMConnect.getClient(reader)
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
        println("\n--- Final Message ---\n" + completion.message.content)
        completion.usage.foreach(u =>
          println(s"Tokens: prompt=${u.promptTokens}, completion=${u.completionTokens}, total=${u.totalTokens}")
        )
      }
    } yield ()

    result.fold(err => Console.err.println(s"Error: ${err.formatted}"), identity)
  }
}
