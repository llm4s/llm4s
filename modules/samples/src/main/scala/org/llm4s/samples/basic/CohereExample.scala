package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

/**
 * Basic example for Cohere Command-R models.
 *
 * == Quick Start ==
 * 1. Set provider and model:
 *    {{{
 *    export LLM_MODEL=cohere/command-r-plus
 *    }}}
 *
 * 2. Set API key:
 *    {{{
 *    export COHERE_API_KEY=your-api-key
 *    }}}
 *
 * 3. Run:
 *    {{{
 *    sbt "samples/runMain org.llm4s.samples.basic.CohereExample"
 *    }}}
 */
object CohereExample {
  def main(args: Array[String]): Unit = {
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a concise assistant."),
        UserMessage("Summarize the benefits of type-safe APIs in 2 sentences.")
      )
    )

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      completion  <- client.complete(conversation)
    } yield completion

    result match {
      case Right(completion) =>
        println("=== Cohere Example ===")
        println(completion.content)
        completion.usage.foreach { usage =>
          println(s"Tokens: ${usage.promptTokens} in, ${usage.completionTokens} out")
        }
      case Left(error) =>
        println(s"Error: ${error.formatted}")
        println("Check LLM_MODEL and COHERE_API_KEY in your environment.")
        sys.exit(1)
    }
  }
}
