package org.llm4s.samples.types

import org.llm4s.types._
import org.llm4s.AsyncResult
import org.llm4s.error.ConfigurationError

import scala.concurrent.Future

/**
 * Example usage of the types defined in this package.
 * This object contains example methods demonstrating how to use type-safe IDs,
 * Result types, async patterns, and future extensibility.
 * It serves as a reference for users to understand how to work with the types defined in this package.
 */

object TypeUsage {

  // Example: Using type-safe IDs
  def exampleTypeSafeIds(): Unit = {
    val modelName = ModelName.GPT_4
    val provider  = ProviderName.OPENAI
    val apiKey    = ApiKey("sk-test123").getOrElse(throw new RuntimeException("Invalid API key"))

    println(s"Using model $modelName from provider $provider")
    println(s"API key: $apiKey") // Safely prints masked version
  }

  // Example: Using Result types
  def exampleResultTypes(): Result[String] =
    for {
      modelName <- ModelName("gpt-4")
      provider  <- ProviderName.create("openai")
      apiKey    <- ApiKey.fromEnvironment("OPENAI_API_KEY")
    } yield s"Configuration: $provider/$modelName with key ${apiKey.masked}"

  // Example: Using async patterns
  def exampleAsyncPatterns()(implicit ec: scala.concurrent.ExecutionContext): AsyncResult[String] = {
    val futureResult = Future {
      // Some async operation
      "Hello from async operation"
    }

    AsyncResult.fromFuture(futureResult)
  }

  // Example: Future extensibility
  def exampleFutureTypes(): Unit = {
    val imagePrompt = ImagePrompt("A sunset over mountains")
    val agentId     = AgentId("agent-123")
    val workflowId  = WorkflowId("workflow-456")

    println(s"Future features: image generation with prompt '$imagePrompt'")
    println(s"Agent system with agent $agentId in workflow $workflowId")
  }
}
