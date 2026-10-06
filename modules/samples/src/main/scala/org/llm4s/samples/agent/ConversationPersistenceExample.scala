package org.llm4s.samples.agent

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.{ RunConfig, ThreadId }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.llm4s.llmconnect.model.{ Message, MessageRole }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.llm4s.types.{ Result, TryOps }
import org.slf4j.LoggerFactory
import upickle.default.{ read, write }

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import scala.util.Try

/**
 * Example demonstrating conversation persistence.
 *
 * Shows how to save a run's messages to disk and load them as the `history` of a new thread,
 * enabling conversation resumption across sessions. Only a completed run is saved.
 */
object ConversationPersistenceExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Conversation Persistence Example ===")

    val savePath = ".log/conversation-state.json"

    // Part 1: Start a conversation and save its messages
    val saveResult = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      agent <- Agent
        .builder("persistence-agent", client)
        .withTools(new ToolRegistry(Seq(weatherTool)))
        .build()

      _ = logger.info("Part 1: Starting conversation and saving messages")
      _ = logger.info("Query: What's the weather in Paris?")

      result1 <- agent.run("What's the weather in Paris?")
      _ = logger.info("Assistant: {}", AgentResults.answerOrStatus(result1))

      _ <- AgentResults.requireCompleted(result1) // save only a run that completed
      _ = logger.info("Saving messages to: {}", savePath)
      _ <- saveMessages(result1.messages, savePath)
      _ = logger.info("Messages saved successfully!")

    } yield result1

    // Part 2: Load the messages into a new thread and continue the conversation
    val continueResult = for {
      _               <- saveResult // Wait for save to complete
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client       <- LLMConnect.getClient(providerCfg)
      weatherTool2 <- WeatherTool.toolSafe
      agent <- Agent
        .builder("persistence-agent", client)
        .withTools(new ToolRegistry(Seq(weatherTool2)))
        .build()

      _ = logger.info("--- Simulating New Session ---")
      _ = logger.info("Part 2: Loading messages from: {}", savePath)

      loaded <- loadMessages(savePath)
      _ = logger.info("Messages loaded! Conversation has {} messages", loaded.length)

      _         = logger.info("Continuing conversation with: 'And what about London?'")
      newThread = ThreadId(java.util.UUID.randomUUID().toString)
      result2 <- agent.run(newThread, "And what about London?", RunConfig(), history = loaded)
      _ = logger.info("Assistant: {}", AgentResults.answerOrStatus(result2))

      _ = logger.info("=== Final Statistics ===")
      _ = logger.info("Total messages: {}", result2.messages.length)
      _ = logger.info("Status: {}", result2.status)

    } yield result2

    continueResult.fold(
      error => logger.error("Error: {}", error.formatted),
      _ => logger.info("Success! Conversation persisted and resumed.")
    )
  }

  /** Messages are what a thread's history is made of: persist them as JSON. */
  private def saveMessages(messages: Seq[Message], path: String): Result[Unit] =
    Try {
      val file = Paths.get(path)
      Option(file.getParent).foreach(Files.createDirectories(_))
      Files.write(file, write(messages, indent = 2).getBytes(StandardCharsets.UTF_8))
      ()
    }.toResult

  /**
   * Load saved messages for use as history. A system prompt belongs to the agent, which supplies
   * its own, so system messages are not imported.
   */
  private def loadMessages(path: String): Result[Vector[Message]] =
    Try {
      read[Vector[Message]](new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8))
        .filterNot(_.role == MessageRole.System)
    }.toResult
}
