package org.llm4s.samples.agent

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin._
import org.llm4s.toolapi.builtin.filesystem._
import org.llm4s.toolapi.builtin.shell._
import org.slf4j.LoggerFactory

/**
 * Example demonstrating the built-in tools with an LLM agent.
 *
 * This example shows how to give an agent access to:
 * - File system operations (read, list, info)
 * - Shell commands (read-only)
 * - Calculator and date/time utilities
 * - Web search
 *
 * The agent can then use these tools autonomously to answer questions
 * like "What's in my Downloads folder?" or "What's 15% of 85?".
 *
 * @example
 * {{{
 * export LLM_MODEL=openai/gpt-4o
 * export OPENAI_API_KEY=sk-...
 * sbt "samples/runMain org.llm4s.samples.agent.BuiltinToolsAgentExample"
 * }}}
 */
object BuiltinToolsAgentExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Built-in Tools Agent Example ===\n")

    val homeDir = System.getProperty("user.home")
    val fileConfig = FileConfig(
      allowedPaths = Some(Seq(homeDir, "/tmp")),
      blockedPaths = Seq("/etc", "/var", "/sys", "/proc")
    )

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      tools <- BuiltinTools.customSafe(
        fileConfig = Some(fileConfig),
        shellConfig = Some(ShellConfig.readOnly())
      )
    } yield (client, tools)

    result match {
      case Left(error) =>
        logger.error("Failed to initialise agent: {}", error.formatted)
        logger.error("Make sure LLM_MODEL and appropriate API key are set")

      case Right((client, tools)) =>
        logger.info("LLM client created successfully")
        logger.info("Available tools: {}", tools.map(_.name).mkString(", "))

        val registry = new ToolRegistry(tools)
        val agent    = new Agent(client)

        // Example queries that use different built-in tools
        val queries = Seq(
          "What is today's date and what day of the week is it?",
          "Calculate 15% of 850 and also compute the square root of 144",
          s"List the files in $homeDir and tell me how many there are",
          "Generate 3 UUIDs for me",
          "What is the current working directory?"
        )

        for (query <- queries) {
          logger.info("\n--- Query: {} ---", query)

          agent.run(query, registry) match {
            case Left(error) =>
              logger.error("Agent error: {}", error.formatted)

            case Right(state) =>
              // Get the final assistant response from the conversation
              val lastAssistantMsg = state.conversation.messages
                .filter(_.role == org.llm4s.llmconnect.model.MessageRole.Assistant)
                .lastOption
                .map(_.content)
                .getOrElse("No response")

              // Count tool messages to see which tools were used
              val toolMsgCount = state.conversation.messages.count(
                _.role == org.llm4s.llmconnect.model.MessageRole.Tool
              )

              logger.info("Agent response: {}", lastAssistantMsg)
              logger.info("Tool calls made: {}", toolMsgCount)
              logger.info("Status: {}", state.status)
          }
        }

        logger.info("\n=== Example Complete ===")
    }
  }
}
