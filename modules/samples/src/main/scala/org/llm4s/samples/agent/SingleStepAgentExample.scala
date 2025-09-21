package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool

/**
 * Example demonstrating step-by-step agent execution for debugging
 */
object SingleStepAgentExample {
  def main(args: Array[String]): Unit = {
    // Get a client using environment variables (Result-first)
    val result = for {
      reader <- LLMConfig()
      client <- LLMConnect.getClient(reader)
      toolRegistry = new ToolRegistry(Seq(WeatherTool.tool))
      agent        = new Agent(client)
      traceLogPath = "/Users/rory.graves/workspace/home/llm4s/log/single-step-trace.md"
      query        = "I'm planning a trip to Paris. What's the weather like there now?"
      _            = println(s"Trace log will be written to: $traceLogPath\n")
      _            = println(s"User Query: $query\n")
      _            = println("=== Running Step-by-Step ===\n")
      state        = agent.initialize(query, toolRegistry)
      _            = println(s"Initial state initialized with ${state.conversation.messages.length} messages")
      _            = agent.writeTraceLog(state, traceLogPath)
      _ = {
        var stepCount = 0
        var stat      = state
        while (stat.status == AgentStatus.InProgress && stepCount < 5) {
          println(s"\nRunning step ${stepCount + 1}...")
          agent.runStep(state) match {
            case Right(newState) =>
              stat = newState
              println(s"Step completed with status: ${state.status}")
              // Print the most recent message
              stat.conversation.messages.lastOption.foreach { msg =>
                println(
                  s"Last message (${msg.role}): ${msg.content.take(100)}${if (msg.content.length > 100) "..." else ""}"
                )
              }
              agent.writeTraceLog(stat, traceLogPath)
            case Left(error) =>
              println(s"Error running step: $error")
              stat = stat.withStatus(AgentStatus.Failed(error.toString))
              agent.writeTraceLog(state, traceLogPath)
          }
          stepCount += 1
        }
        println("\n=== Step-by-Step Run Complete ===\n")
        println(s"Final status: ${stat.status}")
        println(s"Total messages: ${stat.conversation.messages.length}")
        println(s"Trace log has been written to: $traceLogPath")
      }
      _ = println("\n=== Complete Agent State Dump ===\n")
      _ = state.dump()
    } yield ()

    result.fold(
      err => println(s"Error: ${err.formatted}"),
      identity
    )
  }
}
