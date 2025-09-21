package org.llm4s.samples.basic

import org.llm4s.core.safety.Safety
import org.llm4s.agent.{ Agent, AgentState, AgentStatus }
import org.llm4s.config.ConfigReader
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.{ BenchmarkUtil, TracingUtil }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.CalculatorTool
import org.llm4s.trace.{ EnhancedTracing, TracingComposer, TracingMode }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * Enhanced example demonstrating the difference between basic LLM calls and the Agent framework
 *
 * This example shows:
 * 1. Basic LLM call (simple request → response)
 * 2. Agent framework (complex reasoning → tool usage → enhanced response)
 * 3. Real LLM4S tracing with all modes combined (Langfuse + Console + NoOp)
 * 4. Performance metrics and comparison
 *
 * To enable Langfuse tracing, set these environment variables:
 * - LANGFUSE_URL: Your Langfuse instance URL (default: https://cloud.langfuse.com/api/public/ingestion)
 * - LANGFUSE_PUBLIC_KEY: Your Langfuse public key
 * - LANGFUSE_SECRET_KEY: Your Langfuse secret key
 * - LANGFUSE_ENV: Environment name (default: production)
 * - LANGFUSE_RELEASE: Release version (default: 1.0.0)
 * - LANGFUSE_VERSION: API version (default: 1.0.0)
 */
object AgentLLMCallingExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("🧮 Calculator Tool Agent Demo with Tracing")
    logger.info("=" * 50)
    val result = for {
      config  <- LLMConfig()
      tracing <- createComprehensiveTracing()(config)
      _ = {
        logger.info("🔍 Tracing Configuration:")
        logger.info("   • Mode: {}", config.getOrElse("TRACING_MODE", "console"))
        logger.info("   • Langfuse URL: {}", config.getOrElse("LANGFUSE_URL", "default"))
        logger.info("   • Langfuse Public Key: {}", if (config.get("LANGFUSE_PUBLIC_KEY").isDefined) "SET" else "NOT SET")
        logger.info("   • Langfuse Secret Key: {}", if (config.get("LANGFUSE_SECRET_KEY").isDefined) "SET" else "NOT SET")

        logger.info("🧪 Testing tracing...")
        TracingUtil.traceDemoStart(tracing, "Calculator Tool Agent")
        logger.info("🧪 Tracing initialized successfully")
      }
      _ <- demonstrateCalculatorAgent(tracing)(config)
      _ = {
        logger.info("=" * 50)
        logger.info("✨ Calculator Demo Complete!")
      }
    } yield ()

    result.fold(err => logger.error("Error: {}", err.formatted), identity)

  }

  /**
   * Create comprehensive tracing with all three modes combined
   */
  private def createComprehensiveTracing()(config: ConfigReader): Result[EnhancedTracing] = Safety.fromTry {
    Try {
      // Create individual tracers
      val langfuseTracing = EnhancedTracing.create(TracingMode.Langfuse)(config)
      val consoleTracing  = EnhancedTracing.create(TracingMode.Console)(config)
      val noOpTracing     = EnhancedTracing.create(TracingMode.NoOp)(config)

      logger.info("✅ All tracing modes initialized successfully")

      // Combine all tracers into one comprehensive tracer
      val combinedTracing = TracingComposer.combine(
        langfuseTracing, // Primary: sends to Langfuse
        consoleTracing,  // Secondary: shows in console
        noOpTracing      // Tertiary: no-op (for performance monitoring)
      )

      logger.info("🔗 Combined tracing modes: Langfuse + Console + NoOp")
      combinedTracing
    }
  }

  /**
   * Simple Calculator Agent Demo
   */
  private def demonstrateCalculatorAgent(tracing: EnhancedTracing)(config: ConfigReader) = {
    logger.info("🧮 Calculator Agent Demo")
    logger.info("Testing calculator tool with agent framework")

    val benchmarkResult: BenchmarkUtil.BenchmarkResult[Either[LLMError, AgentExecutionResult]] =
      BenchmarkUtil.timeWithSteps { timer =>
        for {
          llmClient <- LLMConnect.getClient(config)
          agent = new Agent(llmClient)
          agentExecutionResult = {
            val tools        = Seq(CalculatorTool.tool)
            val toolRegistry = new ToolRegistry(tools)

            logger.info("🔧 Available Tools:")
            tools.foreach(tool => logger.info("• {}: {}", tool.name, tool.description))
            // Initialize agent state with tools and query
            val query = "Calculate 15 to the power of 3, and then calculate the square root of that result."

            val agentState = agent.initialize(
              query = query,
              tools = toolRegistry,
              systemPromptAddition = Some(
                "You have access to a calculator tool. Use it to perform mathematical calculations. IMPORTANT: Make only ONE tool call at a time, wait for the result, then make the next tool call if needed."
              )
            )

            // Trace agent initialization
            TracingUtil.traceAgentInitialization(tracing, query, tools)

            logger.info("🔄 Running calculator agent...")
            logger.info("Query: {}", query)

            // Execute agent with real step-by-step execution
            executeAgentWithRealTracing(agent, agentState, tracing, timer)
          }

        } yield agentExecutionResult
      }

    for {
      agentResult <- benchmarkResult.result
      duration: Long = benchmarkResult.durationMs
      _ = {
        TracingUtil.traceAgentCompletion(
          tracing,
          duration,
          agentResult.steps,
          agentResult.toolsUsed,
          agentResult.finalResponse.length
        )

        logger.info("✅ Calculator agent completed in {}ms", duration)

        // Display final response
        logger.info("🎯 Final Agent Response:")
        logger.info(agentResult.finalResponse)

        // Performance metrics
        logger.info("📊 Performance Metrics:")
        logger.info("• Total Execution Time: {}ms", duration)
        logger.info("• Reasoning Steps: {}", agentResult.steps.length)
        logger.info("• Tools Used: {}", agentResult.toolsUsed.length)
      }
    } yield ()
  }

  /**
   * Execute agent with real LLM4S tracing and step-by-step display
   */
  private def executeAgentWithRealTracing(
    agent: Agent,
    agentState: AgentState,
    tracing: EnhancedTracing,
    timer: BenchmarkUtil.Timer
  ): AgentExecutionResult = {

    case class ExecutionState(
      agentState: AgentState,
      steps: Vector[String],
      toolsUsed: Vector[String],
      processedToolMessages: Int
    )

    logger.info("🧠 Agent Reasoning Process:")

    // Trace initial agent state
    TracingUtil.traceAgentStateUpdate(tracing, agentState)

    // Recursive function to execute agent steps
    def executeStep(state: ExecutionState, stepCount: Int, maxSteps: Int): ExecutionState =
      if (state.agentState.status != AgentStatus.InProgress || stepCount >= maxSteps) {
        state
      } else {
        val currentStep = stepCount + 1
        val stepTimer   = timer.stepTimer()

        logger.info("{}. Running agent step...", currentStep)

        // Run the actual agent step
        val updatedState = agent.runStep(state.agentState) match {
          case Right(newAgentState) =>
            // Continue processing until stable state
            val processedState = processUntilStable(
              state.copy(agentState = newAgentState),
              tracing,
              currentStep
            )

            // Extract tool calls from messages
            val toolCallsFromMessages = extractToolCalls(processedState.agentState)

            val newSteps     = processedState.steps :+ s"Step $currentStep: ${processedState.agentState.status}"
            val newToolsUsed = processedState.toolsUsed ++ toolCallsFromMessages

            processedState.copy(
              steps = newSteps,
              toolsUsed = newToolsUsed
            )

          case Left(error) =>
            logger.error("   ❌ Step failed: {}", error.message)
            TracingUtil.traceAgentStepError(tracing, currentStep, error.message)

            state.copy(
              agentState = state.agentState.withStatus(AgentStatus.Failed(error.message)),
              steps = state.steps :+ s"Step $currentStep: Failed - ${error.message}"
            )
        }

        val stepDuration = stepTimer.elapsedMs
        logger.info("   ⏱️  Step completed in {}ms", stepDuration)

        // Check if we're done
        if (updatedState.agentState.status == AgentStatus.Complete) {
          logger.info("   🎯 Agent completed successfully!")
          updatedState
        } else {
          executeStep(updatedState, currentStep, maxSteps)
        }
      }

    // Process agent state until it reaches a stable state (not WaitingForTools or InProgress)
    def processUntilStable(state: ExecutionState, tracing: EnhancedTracing, stepCount: Int): ExecutionState =
      if (
        state.agentState.status != AgentStatus.WaitingForTools &&
        state.agentState.status != AgentStatus.InProgress
      ) {
        TracingUtil.traceAgentStateUpdate(tracing, state.agentState)
        state
      } else {
        agent.runStep(state.agentState) match {
          case Right(nextState) =>
            logger.info("   ↳ Continued to: {}", nextState.status)

            // Process new tool messages
            val processedState = processToolMessages(state.copy(agentState = nextState), tracing)

            // Continue processing
            processUntilStable(processedState, tracing, stepCount)

          case Left(error) =>
            logger.error("   ❌ Continuation failed: {}", error.message)
            TracingUtil.traceAgentStateUpdate(tracing, state.agentState)
            state.copy(
              agentState = state.agentState.withStatus(AgentStatus.Failed(error.message))
            )
        }
      }

    // Process tool messages and update state
    def processToolMessages(state: ExecutionState, tracing: EnhancedTracing): ExecutionState = {
      val allToolMessages = state.agentState.conversation.messages.collect {
        case toolMsg: org.llm4s.llmconnect.model.ToolMessage => toolMsg
      }

      val newToolMessages = allToolMessages.drop(state.processedToolMessages)

      val (updatedToolsUsed, newProcessedCount) =
        newToolMessages.foldLeft((state.toolsUsed, state.processedToolMessages)) { case ((tools, count), toolMsg) =>
          TracingUtil.parseToolResult(toolMsg.content) match {
            case Some(toolResult) =>
              logger.info("   📊 Tool result captured: {} = {}", toolResult.expression, toolResult.result)

              TracingUtil.traceToolExecution(
                tracing,
                toolResult.operation,
                toolResult.operation,
                toolResult.parameters,
                toolResult.result,
                toolResult.expression
              )

              (tools :+ toolResult.operation, count + 1)

            case None =>
              logger.info("   📊 Tool result: {}", toolMsg.content)
              (tools, count + 1)
          }
        }

      state.copy(
        toolsUsed = updatedToolsUsed,
        processedToolMessages = newProcessedCount
      )
    }

    // Extract tool calls from the last assistant message
    def extractToolCalls(agentState: AgentState): Vector[String] =
      agentState.conversation.messages.lastOption
        .collect {
          case assistantMsg: org.llm4s.llmconnect.model.AssistantMessage if assistantMsg.toolCalls.nonEmpty =>
            logger.info("   🔧 Tool calls detected: {}", assistantMsg.toolCalls.map(_.name).mkString(", "))
            assistantMsg.toolCalls.map(_.name).toVector
        }
        .getOrElse(Vector.empty)

    // Execute the agent
    val initialState = ExecutionState(
      agentState = agentState,
      steps = Vector.empty,
      toolsUsed = Vector.empty,
      processedToolMessages = 0
    )

    val finalState = executeStep(initialState, 0, 10)

    // Generate final response
    val finalResponse = if (finalState.agentState.status == AgentStatus.Complete) {
      finalState.agentState.conversation.messages.lastOption.map(_.content).getOrElse("No final response generated")
    } else {
      s"Agent execution stopped with status: ${finalState.agentState.status}"
    }

    logger.info("🎯 Final Response Generated Successfully!")

    AgentExecutionResult(finalState.steps, finalState.toolsUsed.distinct, finalResponse)
  }

  /**
   * Case class to hold agent execution results
   */
  case class AgentExecutionResult(
    steps: Vector[String],
    toolsUsed: Vector[String],
    finalResponse: String
  )
}
