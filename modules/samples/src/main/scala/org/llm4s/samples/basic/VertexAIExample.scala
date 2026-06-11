package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Demonstrates basic usage of the Vertex AI provider.
 *
 * Configure a named provider in `application.local.conf`:
 * {{{
 * llm4s {
 *   providers {
 *     provider = "vertexai-main"
 *
 *     vertexai-main {
 *       provider  = "vertexai"
 *       model     = "gemini-2.0-flash"
 *       endpoint  = "my-gcp-project"   // GCP project ID
 *       organization = "us-central1"   // GCP region (optional, defaults to us-central1)
 *       // apiKey = "/path/to/service-account.json"  // optional credential file path
 *     }
 *   }
 * }
 * }}}
 *
 * Then authenticate via one of:
 *   - `export GOOGLE_ACCESS_TOKEN=$(gcloud auth print-access-token)`
 *   - `gcloud auth application-default login`
 *   - Set `GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json`
 *   - Deploy on GCE/GKE (Workload Identity)
 *
 * Run with:
 *   sbt "samples/runMain org.llm4s.samples.basic.VertexAIExample"
 */
object VertexAIExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {

    val result = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)
      completion <- client.complete(
        Conversation(
          Seq(UserMessage("What is the capital of France? Reply in one sentence."))
        )
      )
    } yield completion

    result match {
      case Right(completion) =>
        logger.info("Vertex AI response: {}", completion.content)
        completion.usage.foreach { u =>
          logger.info(
            "Token usage: prompt={}, completion={}",
            u.promptTokens,
            u.completionTokens
          )
        }
      case Left(error) =>
        logger.error("Error: {}", error.formatted)
        logger.info(
          "Tip: Configure a 'vertexai' named provider in application.local.conf"
        )
        sys.exit(1)
    }
  }
}
