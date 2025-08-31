package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLM
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool

/**
 * Example demonstrating step-by-step agent execution for debugging
 */
object SingleStepAgentExample {
  def main(args: Array[String]): Unit = {
    // Get a client using environment variables
    val client    = LLM.client(LLMConfig())

    // Create a tool registry
    val toolRegistry = new ToolRegistry(Seq(WeatherTool.tool))

    // Create an agent
    val agent = new Agent(client)

    // Define the user's query
    val query = "I'm planning a trip to Paris. What's the weather like there now?"

    // Define trace log path
    val traceLogPath = "/Users/rory.graves/workspace/home/llm4s/log/single-step-trace.md"
    println(s"Trace log will be written to: $traceLogPath\n")

    println(s"User Query: $query\n")
    println("=== Running Step-by-Step ===\n")

    var state = agent.initialize(query, toolRegistry)
    println(s"Initial state initialized with ${state.conversation.messages.length} messages")
    
    // Write initial state to trace log
    agent.writeTraceLog(state, traceLogPath)

    var stepCount = 0
    while (state.status == AgentStatus.InProgress && stepCount < 5) {
      println(s"\nRunning step ${stepCount + 1}...")

      agent.runStep(state) match {
        case Right(newState) =>
          state = newState
          println(s"Step completed with status: ${state.status}")
          // Print the most recent message
          state.conversation.messages.lastOption.foreach { msg =>
            println(
              s"Last message (${msg.role}): ${msg.content.take(100)}${if (msg.content.length > 100) "..." else ""}"
            )
          }
          
          // Update trace log after each step
          agent.writeTraceLog(state, traceLogPath)

        case Left(error) =>
          println(s"Error running step: $error")
          state = state.withStatus(AgentStatus.Failed(error.toString))
          
          // Write error to trace log
          agent.writeTraceLog(state, traceLogPath)
      }

      stepCount += 1
    }

    println("\n=== Step-by-Step Run Complete ===\n")
    println(s"Final status: ${state.status}")
    println(s"Total messages: ${state.conversation.messages.length}")
    println(s"Trace log has been written to: $traceLogPath")

    // Dump the complete agent state for debugging
    println("\n=== Complete Agent State Dump ===\n")
    state.dump()
  }
}
