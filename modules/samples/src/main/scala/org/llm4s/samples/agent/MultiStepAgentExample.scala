package org.llm4s.samples.agent

import org.llm4s.agent.Agent
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole.Tool
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool

/**
 * Example demonstrating complete agent execution with multiple steps
 */
object MultiStepAgentExample {
  def main(args: Array[String]): Unit = {
    // Get a client using environment variables (Result-first)
    val res = for {
      reader <- LLMConfig()
      client <- LLMConnect.getClient(reader)
      toolRegistry        = new ToolRegistry(Seq(WeatherTool.tool))
      agent               = new Agent(client)
      query               = "What's the weather like in London, and is it different from New York?"
      _                   = println(s"User Query: $query\n")
      traceLogPath        = "/Users/rory.graves/workspace/home/llm4s/log/agent-trace.md"
      _                   = println(s"Trace log will be written to: $traceLogPath\n")
      _                   = println("=== Running Multi-Step Agent to Completion ===\n")
      _                   = println("Example 1: Running without a step limit, with trace logging")
      _                   = println("\n\n=== Running Multi-Step Agent with Step Limit ===\n")
      _                   = println("Example 2: Running with a step limit of 1, with trace logging")
      limitedTraceLogPath = "/Users/rory.graves/workspace/home/llm4s/log/agent-trace-limited.md"
      _ = agent.run(query, toolRegistry, Some(1), Some(limitedTraceLogPath), None) match {
        case Right(finalState) =>
          println(s"Final status: ${finalState.status}")

          // Print execution info
          println(s"\nTotal steps executed: ${finalState.logs.size}")

          // Print logs with formatted output
          if (finalState.logs.nonEmpty) {
            println("\nExecution logs:")
            finalState.logs.foreach { log =>
              // Color-coded logs based on type
              val colorCode = log match {
                case l if l.startsWith("[assistant]") => Console.BLUE
                case l if l.startsWith("[tool]")      => Console.GREEN
                case l if l.startsWith("[tools]")     => Console.YELLOW
                case l if l.startsWith("[system]")    => Console.RED
                case _                                => Console.WHITE
              }

              println(s"${colorCode}${log}${Console.RESET}")
            }
          }

          println(s"\nTrace log has been written to: $limitedTraceLogPath")

        case Left(error) =>
          println(s"Error running agent: $error")
      }
      // Example 3: Manual step execution to show the two-phase flow
      _                  = println("\n\n=== Manual Step Execution to Demonstrate Two-Phase Flow ===\n")
      _                  = println("Example 3: Running with manual step execution")
      manualTraceLogPath = "/Users/rory.graves/workspace/home/llm4s/log/agent-trace-manual.md"
      initialState       = agent.initialize(query, toolRegistry)
      _                  = println(s"Initial state: ${initialState.status}")
      _                  = agent.writeTraceLog(initialState, manualTraceLogPath)
      _                  = println(s"Initial state written to trace log: $manualTraceLogPath")
      _                  = println("\nStep 1: Running LLM completion (usually generates tool calls)")
      afterLLMStep <- agent.runStep(initialState)
      _ = agent.writeTraceLog(afterLLMStep, manualTraceLogPath)
      _ = println("\nConversation after LLM step:")
      _ = afterLLMStep.conversation.messages.foreach(msg => println(s"[${msg.role}] ${msg.content}"))
      _ = println("\nStep 2: Processing tool calls")
      afterToolStep <- agent.runStep(afterLLMStep)
      _ = agent.writeTraceLog(afterToolStep, manualTraceLogPath)
      _ = println("\nConversation after tool execution:")
      _ = afterToolStep.conversation.messages.takeRight(2).foreach { msg =>
        println(s"[${msg.role}] ${if (msg.role == Tool) msg.content.take(50) + "..." else msg.content}")
      }
      _ = println("\nThe two-phase flow allows for more control and separation of concerns in the agent execution.")
      _ = println(s"\nManual trace log has been written to: $manualTraceLogPath")
    } yield ()
    res.fold(
      err => println(s"Error: ${err.formatted}"),
      identity
    )
  }
}
