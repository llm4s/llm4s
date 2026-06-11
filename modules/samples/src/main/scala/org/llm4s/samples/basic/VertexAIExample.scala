package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }

/**
 * Demonstrates basic usage of the Vertex AI provider.
 *
 * Prerequisites:
 *   - Set `LLM_PROVIDER_0_PROVIDER=vertexai` (or use `fromValues` directly)
 *   - Set `LLM_PROVIDER_0_ENDPOINT=<your-gcp-project-id>`
 *   - Authenticate via one of:
 *     - `export GOOGLE_ACCESS_TOKEN=$(gcloud auth print-access-token)`
 *     - `gcloud auth application-default login`
 *     - Set `GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json`
 *     - Deploy on GCE/GKE (Workload Identity)
 *
 * Run with:
 *   sbt "samples/runMain org.llm4s.samples.basic.VertexAIExample"
 */
object VertexAIExample extends App {

  val result = for {
    registry   <- Llm4sConfig.modelRegistryService()
    config      = VertexAIConfig(
                    projectId = sys.env.getOrElse("VERTEX_PROJECT_ID", "my-gcp-project"),
                    location = sys.env.getOrElse("VERTEX_LOCATION", "us-central1"),
                    model = sys.env.getOrElse("VERTEX_MODEL", "gemini-2.0-flash"),
                    credentialFilePath = sys.env.get("GOOGLE_APPLICATION_CREDENTIALS"),
                    contextWindow = 1048576,
                    reserveCompletion = 8192
                  )
    client     <- LLMConnect.getClient(config)(using registry)
    completion <- client.complete(
                    Conversation(Seq(UserMessage("What is the capital of France? Reply in one sentence."))),
                    CompletionOptions()
                  )
  } yield completion

  result match {
    case Right(completion) =>
      println(s"Vertex AI response: ${completion.content}")
      completion.usage.foreach(u =>
        println(s"Token usage: prompt=${u.promptTokens}, completion=${u.completionTokens}")
      )
    case Left(error) =>
      println(s"Error: ${error.message}")
      sys.exit(1)
  }
}
