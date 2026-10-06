package org.llm4s.samples.basic

import org.llm4s.core.safety.Safety
import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.config.LangfuseConfigLoader
import org.llm4s.llmconnect.config.LangfuseConfig
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.{ AgentResults, BenchmarkUtil, TracingUtil }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.core.CalculatorTool
import org.llm4s.trace.{ ConsoleTracing, LangfuseTracing, NoOpTracing, Tracing, TracingComposer }
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
      settings <- org.llm4s.config.Llm4sConfig.tracing()
      // Read the Langfuse block whatever TRACING_MODE selects: this demo always combines it
      // with Console. llm4s-observability binds the LANGFUSE_* variables to it.
      langfuse <- LangfuseConfigLoader.default()
      tracing  <- createComprehensiveTracing(langfuse)
      _ = {
        logger.info("🔍 Tracing Configuration:")
        logger.info(s"   • Mode: ${settings.mode}")
        logger.info(s"   • Langfuse URL: ${langfuse.url}")
        logger.info(s"   • Langfuse Public Key: ${langfuse.publicKey.fold("NOT SET")(_ => "SET")}")
        logger.info(s"   • Langfuse Secret Key: ${langfuse.secretKey.fold("NOT SET")(_ => "SET")}")

        logger.info("🧪 Testing tracing...")
        TracingUtil.traceDemoStart(tracing, "Calculator Tool Agent")
        logger.info("🧪 Tracing initialized successfully")
      }
      _ <- demonstrateCalculatorAgent(tracing)
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
  private def createComprehensiveTracing(langfuse: LangfuseConfig): Result[Tracing] = Safety.fromTry {
    Try {
      // Create individual tracers directly: Langfuse from its own config, the others from core
      val langfuseTracing = LangfuseTracing.from(langfuse)
      val consoleTracing  = new ConsoleTracing()
      val noOpTracing     = new NoOpTracing()

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
  private def demonstrateCalculatorAgent(tracing: Tracing) = {
    logger.info("🧮 Calculator Agent Demo")
    logger.info("Testing calculator tool with agent framework")

    val benchmarkResult: BenchmarkUtil.BenchmarkResult[Either[LLMError, AgentExecutionResult]] =
      BenchmarkUtil.timeWithSteps { _ =>
        for {
          providerCfg     <- org.llm4s.config.Llm4sConfig.defaultProvider()
          registryService <- org.llm4s.config.Llm4sConfig.modelRegistryService()
          given org.llm4s.model.ModelRegistryService = registryService
          llmClient <- LLMConnect.getClient(providerCfg)
          agentExecutionResult <- {
            val calcToolResult = CalculatorTool.toolSafe
            calcToolResult.flatMap { calcTool =>
              val tools        = Seq(calcTool)
              val toolRegistry = new ToolRegistry(tools)

              logger.info("🔧 Available Tools:")
              tools.foreach(tool => logger.info("• {}: {}", tool.name, tool.description))
              val query = "Calculate 15 to the power of 3, and then calculate the square root of that result."

              for {
                agent <- Agent
                  .builder("calculator-agent", llmClient)
                  .withTools(toolRegistry)
                  .withTracing(tracing)
                  .withSystemPrompt(
                    "You have access to a calculator tool. Use it to perform mathematical calculations. IMPORTANT: Make only ONE tool call at a time, wait for the result, then make the next tool call if needed."
                  )
                  .build()

                // Trace agent initialization
                _ = TracingUtil.traceAgentInitialization(tracing, query, tools)

                _ = logger.info("🔄 Running calculator agent...")
                _ = logger.info("Query: {}", query)

                result <- agent.run(query)
              } yield summarise(result, tracing)
            }
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

        logger.info("✅ Calculator agent finished in {}ms", duration)

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
   * Print the run's messages (the tool calls and results the loop made) and trace its tool use
   */
  private def summarise(result: AgentResult, tracing: Tracing): AgentExecutionResult = {
    logger.info("🧠 Agent Reasoning Process:")

    val toolCalls = result.messages
      .collect { case m: org.llm4s.llmconnect.model.AssistantMessage => m }
      .flatMap(_.toolCalls.map(_.name))
      .toVector

    result.messages.foreach {
      case toolMsg: org.llm4s.llmconnect.model.ToolMessage =>
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
          case None => logger.info("   📊 Tool result: {}", toolMsg.content)
        }
      case assistant: org.llm4s.llmconnect.model.AssistantMessage if assistant.toolCalls.nonEmpty =>
        logger.info("   🔧 Tool calls detected: {}", assistant.toolCalls.map(_.name).mkString(", "))
      case other => logger.info("   {}: {}", other.role, other.content)
    }

    TracingUtil.traceAgentStateUpdate(tracing, result)

    // One step per model call, i.e. per assistant message
    val steps = result.messages
      .collect { case m: org.llm4s.llmconnect.model.AssistantMessage => m }
      .zipWithIndex
      .map { case (m, i) =>
        val what = if (m.toolCalls.nonEmpty) s"tool calls: ${m.toolCalls.map(_.name).mkString(", ")}" else "answer"
        s"Step ${i + 1}: $what"
      }
      .toVector

    val finalResponse = result.answer.getOrElse(s"Agent execution stopped: ${AgentResults.describe(result.status)}")

    AgentExecutionResult(steps, toolCalls.distinct, finalResponse)
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
