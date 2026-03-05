package org.llm4s.samples.types

import org.llm4s.AsyncResult
import org.llm4s.types._
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
 * Example usage of the types defined in this package.
 * This object contains example methods demonstrating how to use type-safe IDs,
 * Result types, async patterns, and future extensibility.
 * It serves as a reference for users to understand how to work with the types defined in this package.
 */

object TypeUsage {

  private val logger = LoggerFactory.getLogger(getClass)

  // Example: Using type-safe IDs
  def exampleTypeSafeIds(): Unit = {
    val modelName = ModelName.GPT_4
    val provider  = ProviderName.OPENAI
    val apiKey = ApiKey("sk-test123") match {
      case Right(k) => k
      case Left(_) =>
        logger.error("Invalid API key")
        return
    }

    logger.info("Using model {} from provider {}", modelName, provider)
    logger.info("API key: {}", apiKey) // Safely prints masked version
  }

  // Example: Using Result types
  def exampleResultTypes(): Result[String] =
    for {
      modelName <- ModelName("gpt-4")
      provider  <- ProviderName.create("openai")
      apiKey    <- ApiKey("sk-example123") // local validation example
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

    logger.info("Future features: image generation with prompt '{}'", imagePrompt)
    logger.info("Agent system with agent {} in workflow {}", agentId, workflowId)
  }
}
