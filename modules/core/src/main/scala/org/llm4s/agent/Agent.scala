package org.llm4s.agent

import org.llm4s.agent.guardrails.{ CompositeGuardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.core.safety.Safety
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.toolapi._
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import cats.implicits._
import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

/**
 * Result type for handoff tool invocations.
 * This is defined at module level to work around Scala 2.13 upickle macro limitations (SI-7567).
 */
private[agent] case class HandoffResult(handoff_requested: Boolean, handoff_id: String, reason: String)

private[agent] object HandoffResult {
  import upickle.default._
  implicit val handoffResultRW: ReadWriter[HandoffResult] = macroRW[HandoffResult]
}

/**
 * Bundles cross-cutting parameters for Agent execution.
 *
 * @param tracing Optional tracer for observability
 * @param debug Enable debug logging
 * @param traceLogPath Optional path for trace log files
 */
case class AgentContext(
  tracing: Option[Tracing] = None,
  debug: Boolean = false,
  traceLogPath: Option[String] = None
)

object AgentContext {
  val Default: AgentContext = AgentContext()
}

/**
 * Core agent implementation for orchestrating LLM interactions with tool calling.
 *
 * The Agent class provides a flexible framework for running LLM-powered workflows
 * with support for tools, guardrails, handoffs, and streaming events.
 *
 * == Key Features ==
 *  - '''Tool Calling''': Automatically executes tools requested by the LLM
 *  - '''Multi-turn Conversations''': Maintains conversation state across interactions
 *  - '''Handoffs''': Delegates to specialist agents when appropriate
 *  - '''Guardrails''': Input/output validation with composable guardrail chains
 *  - '''Streaming Events''': Real-time event callbacks during execution
 *
 * == Security ==
 * By default, agents have a maximum step limit of 50 to prevent infinite loops.
 * This can be overridden by setting `maxSteps` explicitly.
 *
 * == Basic Usage ==
 * {{{
 * for {
 *   providerConfig <- Llm4sConfig.provider()
 *   client <- LLMConnect.getClient(providerConfig)
 *   agent = new Agent(client)
 *   tools = new ToolRegistry(Seq(myTool))
 *   state <- agent.run("What is 2+2?", tools)
 * } yield state.conversation.messages.last.content
 * }}}
 *
 * == With Guardrails ==
 * {{{
 * agent.run(
 *   query = "Generate JSON",
 *   tools = tools,
 *   inputGuardrails = Seq(new LengthCheck(1, 10000)),
 *   outputGuardrails = Seq(new JSONValidator())
 * )
 * }}}
 *
 * == With Streaming Events ==
 * {{{
 * agent.runWithEvents("Query", tools) { event =>
 *   event match {
 *     case AgentEvent.TextDelta(text, _) => print(text)
 *     case AgentEvent.ToolCallCompleted(name, result, _, _, _, _) =>
 *       println(s"Tool $$name returned: $$result")
 *     case _ => ()
 *   }
 * }
 * }}}
 *
 * @param client The LLM client for making completion requests
 * @see [[AgentState]] for the state management during execution
 * @see [[Handoff]] for agent-to-agent delegation
 * @see [[org.llm4s.agent.guardrails.InputGuardrail]] for input validation
 * @see [[org.llm4s.agent.guardrails.OutputGuardrail]] for output validation
 */
class Agent(client: LLMClient) {

  private val logger = LoggerFactory.getLogger(getClass)

  private def accumulateUsage(
    state: AgentState,
    completion: Completion
  ): AgentState =
    completion.usage match {
      case Some(usage) =>
        state.copy(
          usageSummary = state.usageSummary.add(
            completion.model,
            usage,
            completion.estimatedCost
          )
        )
      case None => state
    }

  /**
   * Best-effort tracing helper.
   *
   * Tracing failures must never impact agent control flow, so this helper
   * swallows errors and logs them at debug level.
   */
  private def safeTrace(tracing: Option[Tracing])(f: Tracing => Result[Unit]): Unit =
    tracing.foreach { tracer =>
      f(tracer) match {
        case Left(error) =>
          error match {
            case UnknownError(msg, cause) =>
              logger.debug("Tracing failed: " + msg, cause)
            case _ =>
              logger.debug("Tracing failed: {}", error)
          }
        case Right(_) =>
          ()
      }
    }

  /**
   * Initializes a new agent state with the given query
   *
   * @param query The user query to process
   * @param tools The registry of available tools
   * @param handoffs Available handoffs (default: none)
   * @param systemPromptAddition Optional additional text to append to the default system prompt
   * @param completionOptions Optional completion options for LLM calls (temperature, maxTokens, etc.)
   * @return A Result containing a new AgentState initialized with the query and tools
   */
  def initializeSafe(
    query: String,
    tools: ToolRegistry,
    handoffs: Seq[Handoff] = Seq.empty,
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions()
  ): Result[AgentState] = {
    val baseSystemPrompt = """You are a helpful assistant with access to tools.
        |Follow these steps:
        |1. Analyze the user's question and determine which tools you need to use
        |2. Use tools ONE AT A TIME - make one tool call, wait for the result, then decide if you need more tools
        |3. Use the results from previous tool calls in subsequent tool calls when needed
        |4. When you have enough information, provide a helpful final answer
        |5. Think step by step and be thorough""".stripMargin

    val fullSystemPrompt = systemPromptAddition match {
      case Some(addition) => s"$baseSystemPrompt\n\n$addition"
      case None           => baseSystemPrompt
    }

    val systemMsg = SystemMessage(fullSystemPrompt)
    // Only store user message in conversation - system message is now config, not history
    val initialMessages = Seq(
      UserMessage(query)
    )

    // Convert handoffs to tools and combine with regular tools
    for {
      handoffTools <- createHandoffTools(handoffs)
    } yield {
      val allTools = new ToolRegistry(tools.tools ++ handoffTools)

      AgentState(
        conversation = Conversation(initialMessages),
        tools = allTools,
        initialQuery = Some(query),
        systemMessage = Some(systemMsg),
        completionOptions = completionOptions,
        availableHandoffs = handoffs
      )
    }
  }

  /**
   * Initializes a new agent state with the given query.
   *
   * @param query The user query to process
   * @param tools The registry of available tools
   * @param handoffs Available handoffs (default: none)
   * @param systemPromptAddition Optional additional text to append to the default system prompt
   * @param completionOptions Optional completion options for LLM calls (temperature, maxTokens, etc.)
   * @return A new AgentState initialized with the query and tools
   * @throws IllegalStateException if initialization fails
   */
  @deprecated("Use initializeSafe() which returns Result[AgentState] for safe error handling", "0.2.9")
  def initialize(
    query: String,
    tools: ToolRegistry,
    handoffs: Seq[Handoff] = Seq.empty,
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions()
  ): AgentState =
    initializeSafe(query, tools, handoffs, systemPromptAddition, completionOptions) match {
      case Right(state) => state
      case Left(e)      => throw new IllegalStateException(s"Agent.initialize failed: ${e.formatted}")
    }

  /**
   * Runs a single step of the agent's reasoning process
   */
  def runStep(state: AgentState, context: AgentContext = AgentContext.Default): Result[AgentState] =
    state.status match {
      case AgentStatus.InProgress =>
        // Get tools from registry and merge with completion options from state
        val options = state.completionOptions.copy(tools = state.tools.tools)

        if (context.debug) {
          logger.info("[DEBUG] Running completion step")
          logger.info("[DEBUG] Status: InProgress -> requesting LLM completion")
          logger.info("[DEBUG] Available tools: {}", state.tools.tools.map(_.name).mkString(", "))
          logger.info("[DEBUG] Conversation history: {} messages", state.conversation.messages.size)
        } else {
          logger.debug("Running completion step with tools: {}", state.tools.tools.map(_.name).mkString(", "))
        }

        // Request next step from LLM using system message injection
        client.complete(state.toApiConversation, options) match {
          case Right(completion) =>
            val stateWithUsage = accumulateUsage(state, completion)

            val logMessage = completion.message.toolCalls match {
              case Seq() => s"[assistant] text: ${completion.message.content}"
              case toolCalls =>
                val toolNames = toolCalls.map(_.name).mkString(", ")
                s"[assistant] tools: ${toolCalls.size} tool calls requested ($toolNames)"
            }

            // Trace successful completion and token usage (best-effort)
            safeTrace(context.tracing)(tracer => tracer.traceCompletion(completion, completion.model))
            completion.usage.foreach { usage =>
              safeTrace(context.tracing)(tracer => tracer.traceTokenUsage(usage, completion.model, "agent_completion"))
            }

            if (context.debug) {
              logger.info("[DEBUG] LLM response received")
              logger.info(
                "[DEBUG] Response type: {}",
                if (completion.message.toolCalls.isEmpty) "text" else "tool_calls"
              )
              if (completion.message.content != null && completion.message.content.nonEmpty) {
                logger.info("[DEBUG] Response content: {}", completion.message.content)
              }
              if (completion.message.toolCalls.nonEmpty) {
                logger.info("[DEBUG] Tool calls requested: {}", completion.message.toolCalls.size)
                completion.message.toolCalls.foreach { tc =>
                  logger.info("[DEBUG]   - Tool: {}", tc.name)
                  logger.info("[DEBUG]     ID: {}", tc.id)
                  logger.info("[DEBUG]     Arguments (raw): {}", tc.arguments)
                  logger.info("[DEBUG]     Arguments type: {}", tc.arguments.getClass.getSimpleName)
                }
              }
            }

            val updatedState = stateWithUsage
              .log(logMessage)
              .addMessage(completion.message)

            completion.message.toolCalls match {
              case Seq() =>
                // No tool calls - agent is ready to answer
                if (context.debug) {
                  logger.info("[DEBUG] Status: InProgress -> Complete (no tool calls)")
                }
                Right(updatedState.withStatus(AgentStatus.Complete))

              case _ =>
                // Don't process tools yet, just mark as waiting
                if (context.debug) {
                  logger.info("[DEBUG] Status: InProgress -> WaitingForTools")
                } else {
                  logger.debug("Tool calls identified, setting state to waiting for tools")
                }
                Right(updatedState.withStatus(AgentStatus.WaitingForTools))
            }

          case Left(error) =>
            if (context.debug) {
              logger.error("[DEBUG] LLM completion failed: {}", error.message)
            }
            // Trace completion error (best-effort)
            safeTrace(context.tracing)(tracer =>
              tracer.traceError(new RuntimeException(error.message), "agent_completion")
            )
            Left(error)
        }

      case AgentStatus.WaitingForTools =>
        // Get the latest assistant message with tool calls
        val assistantMessageOpt = state.conversation.messages.reverse
          .collectFirst { case msg: AssistantMessage if msg.toolCalls.nonEmpty => msg }

        assistantMessageOpt match {
          case Some(assistantMessage) =>
            // Log summary of tools to be processed
            val toolNames    = assistantMessage.toolCalls.map(_.name).mkString(", ")
            val logMessage   = s"[tools] executing ${assistantMessage.toolCalls.size} tools ($toolNames)"
            val stateWithLog = state.log(logMessage)

            if (context.debug) {
              logger.info("[DEBUG] Status: WaitingForTools -> processing tools")
              logger.info("[DEBUG] Processing {} tool calls: {}", assistantMessage.toolCalls.size, toolNames)
            }

            // Process the tool calls
            Try {
              if (context.debug) {
                logger.info("[DEBUG] Calling processToolCalls with {} tools", assistantMessage.toolCalls.size)
              } else {
                logger.debug("Processing {} tool calls", assistantMessage.toolCalls.size)
              }
              processToolCalls(stateWithLog, assistantMessage.toolCalls, context)
            } match {
              case Success(newState) =>
                if (context.debug) {
                  logger.info("[DEBUG] Tool processing successful")
                }

                // Check if a handoff was requested
                detectHandoff(newState) match {
                  case Some((handoff, reason)) =>
                    if (context.debug) {
                      logger.info("[DEBUG] Handoff detected: {}", handoff.handoffName)
                      logger.info("[DEBUG] Status: WaitingForTools -> HandoffRequested")
                    } else {
                      logger.info("Handoff requested: {}", handoff.handoffName)
                    }
                    Right(newState.withStatus(AgentStatus.HandoffRequested(handoff, Some(reason))))

                  case None =>
                    if (context.debug) {
                      logger.info("[DEBUG] Status: WaitingForTools -> InProgress")
                    } else {
                      logger.debug("Tool processing successful - continuing")
                    }
                    Right(newState.withStatus(AgentStatus.InProgress))
                }

              case Failure(error) =>
                logger.error("Tool processing failed: {}", error.getMessage)
                if (context.debug) {
                  logger.error("[DEBUG] Status: WaitingForTools -> Failed")
                  logger.error("[DEBUG] Error: {}", error.getMessage)
                }
                // Trace unexpected tool processing failure (best-effort)
                safeTrace(context.tracing)(tracer => tracer.traceError(error, "agent_tool_execution"))
                Right(stateWithLog.withStatus(AgentStatus.Failed(error.getMessage)))
            }

          case None =>
            // Shouldn't happen, but handle gracefully
            if (context.debug) {
              logger.error("[DEBUG] No tool calls found in conversation - this should not happen!")
            }
            Right(state.withStatus(AgentStatus.Failed("No tool calls found in conversation")))
        }

      case _ =>
        // If the agent is already complete or failed, don't do anything
        if (context.debug) {
          logger.info("[DEBUG] Agent already in terminal state: {}", state.status)
        }
        Right(state)
    }

  @deprecated("Use runStep(state, context)", "0.3.0")
  def runStep(state: AgentState, debug: Boolean): Result[AgentState] =
    runStep(state, AgentContext(debug = debug))

  @deprecated("Use runStep(state, context)", "0.3.0")
  def runStep(state: AgentState, tracing: Option[Tracing], debug: Boolean): Result[AgentState] =
    runStep(state, AgentContext(tracing = tracing, debug = debug))

  /**
   * Process tool calls and add the results to the conversation
   */
  private def processToolCalls(
    state: AgentState,
    toolCalls: Seq[ToolCall],
    context: AgentContext
  ): AgentState = {
    val toolRegistry = state.tools

    if (context.debug) {
      logger.info("[DEBUG] processToolCalls: Processing {} tool calls", toolCalls.size)
    }

    // Process each tool call, threading state through to capture logs
    val (finalState, toolMessages) = toolCalls.zipWithIndex.foldLeft((state, Seq.empty[ToolMessage])) {
      case ((currentState, messages), (toolCall, index)) =>
        val startTime = System.currentTimeMillis()

        if (context.debug) {
          logger.info("[DEBUG] Tool call {}/{}: {}", index + 1, toolCalls.size, toolCall.name)
          logger.info("[DEBUG]   Tool call ID: {}", toolCall.id)
          logger.info("[DEBUG]   Arguments (raw JSON): {}", toolCall.arguments)
          logger.info("[DEBUG]   Arguments type: {}", toolCall.arguments.getClass.getSimpleName)
        } else {
          logger.info("Executing tool: {} with arguments: {}", toolCall.name, toolCall.arguments)
        }

        val request = ToolCallRequest(toolCall.name, toolCall.arguments)

        if (context.debug) {
          logger.info("[DEBUG]   Created ToolCallRequest")
          logger.info("[DEBUG]   Executing via ToolRegistry...")
        }

        val result = toolRegistry.execute(request)

        val endTime  = System.currentTimeMillis()
        val duration = endTime - startTime

        val resultContent = result match {
          case Right(json) =>
            val jsonStr = json.render()
            if (context.debug) {
              logger.info("[DEBUG]   Tool {} SUCCESS in {}ms", toolCall.name, duration)
              logger.info("[DEBUG]   Result (raw JSON): {}", jsonStr)
              logger.info("[DEBUG]   Result type: {}", json.getClass.getSimpleName)
            } else {
              logger.info("Tool {} completed successfully in {}ms. Result: {}", toolCall.name, duration, jsonStr)
            }
            // Trace successful tool call (best-effort)
            safeTrace(context.tracing)(tracer =>
              tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), jsonStr)
            )
            jsonStr
          case Left(error) =>
            val errorMessage = error.getFormattedMessage
            if (context.debug) {
              logger.error("[DEBUG]   Tool {} FAILED in {}ms", toolCall.name, duration)
              logger.error("[DEBUG]   Error type: {}", error.getClass.getSimpleName)
              logger.error("[DEBUG]   Error message: {}", errorMessage)
            }
            // Build structured JSON error using ujson (no manual escaping needed)
            val errorJson = ToolCallErrorJson.toJson(error).render()
            if (!context.debug) {
              logger.warn("Tool {} failed in {}ms with error: {}", toolCall.name, duration, errorMessage)
            }
            // Trace failed tool call (best-effort)
            safeTrace(context.tracing)(tracer =>
              tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), errorJson)
            )
            errorJson
        }

        if (context.debug) {
          logger.info("[DEBUG]   Creating ToolMessage with ID: {}", toolCall.id)
        }

        val stateWithLog = currentState.log(s"[tool] ${toolCall.name} (${duration}ms): $resultContent")
        val toolMessage  = ToolMessage(resultContent, toolCall.id)
        (stateWithLog, messages :+ toolMessage)
    }

    if (context.debug) {
      logger.info("[DEBUG] All {} tool calls processed successfully", toolCalls.size)
      logger.info("[DEBUG] Adding {} tool messages to conversation", toolMessages.size)
    }

    // Add the tool messages to the conversation
    finalState.addMessages(toolMessages)
  }

  /**
   * Process tool calls asynchronously with configurable execution strategy.
   *
   * @param state Current agent state
   * @param toolCalls Tool calls to process
   * @param strategy Execution strategy (Sequential, Parallel, ParallelWithLimit)
   * @param debug Enable debug logging
   * @param ec ExecutionContext for async execution
   * @return Updated agent state with tool results
   */
  private def processToolCallsAsync(
    state: AgentState,
    toolCalls: Seq[ToolCall],
    strategy: ToolExecutionStrategy,
    context: AgentContext
  )(implicit ec: ExecutionContext): AgentState = {
    val toolRegistry = state.tools

    if (context.debug) {
      logger.info("[DEBUG] processToolCallsAsync: Processing {} tool calls with strategy {}", toolCalls.size, strategy)
    }

    // Create requests
    val requests   = toolCalls.map(tc => ToolCallRequest(tc.name, tc.arguments))
    val startTimes = toolCalls.map(_ => System.currentTimeMillis())

    // Execute with strategy
    val resultsFuture = toolRegistry.executeAll(requests, strategy)

    // Wait for results (with a reasonable timeout)
    val timeout = 5.minutes
    val results = Await.result(resultsFuture, timeout)

    // Create tool messages from results
    val toolMessages = toolCalls.zip(results).zipWithIndex.map { case ((toolCall, result), index) =>
      val duration = System.currentTimeMillis() - startTimes(index)

      val resultContent = result match {
        case Right(json) =>
          val jsonStr = json.render()
          if (context.debug) {
            logger.info("[DEBUG] Tool {} SUCCESS in {}ms", toolCall.name, duration)
          } else {
            logger.info("Tool {} completed successfully in {}ms", toolCall.name, duration)
          }
          // Trace successful tool call (best-effort)
          safeTrace(context.tracing)(tracer =>
            tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), jsonStr)
          )
          jsonStr

        case Left(error) =>
          val errorMessage = error.getFormattedMessage
          if (context.debug) {
            logger.error("[DEBUG] Tool {} FAILED in {}ms: {}", toolCall.name, duration, errorMessage)
          }
          // Build structured JSON error using ujson (no manual escaping needed)
          val errorJson = ToolCallErrorJson.toJson(error).render()
          // Trace failed tool call (best-effort)
          safeTrace(context.tracing)(tracer =>
            tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), errorJson)
          )
          errorJson
      }

      ToolMessage(resultContent, toolCall.id)
    }

    if (context.debug) {
      logger.info("[DEBUG] All {} tool calls processed with strategy {}", toolCalls.size, strategy)
    }

    state.addMessages(toolMessages)
  }

  /**
   * Create tool functions for handoffs.
   * Each handoff becomes a tool that the LLM can invoke.
   */
  private def createHandoffTools(handoffs: Seq[Handoff]): Result[Seq[ToolFunction[_, _]]] = {
    import org.llm4s.toolapi.{ ToolBuilder, Schema }
    import HandoffResult._ // Import implicit ReadWriter

    handoffs.traverse { handoff =>
      val toolName = handoff.handoffId
      val toolDescription = handoff.transferReason.fold(
        "Hand off this query to a specialist agent."
      )(reason => s"Hand off this query to a specialist agent. $reason")

      // Create object schema with a reason parameter
      val schema = Schema
        .`object`[Map[String, Any]]("Handoff parameters")
        .withRequiredField("reason", Schema.string("Reason for the handoff"))

      // Create tool function that marks the handoff in the result
      ToolBuilder[Map[String, Any], HandoffResult](
        toolName,
        toolDescription,
        schema
      ).withHandler { extractor =>
        extractor.getString("reason").map { reason =>
          HandoffResult(
            handoff_requested = true,
            handoff_id = handoff.handoffId,
            reason = reason
          )
        }
      }.buildSafe()
    }
  }

  /**
   * Detect if the completion contains a handoff tool call.
   *
   * @param state Current agent state (contains available handoffs)
   * @return Optional handoff and reason if handoff was requested
   */
  private def detectHandoff(state: AgentState): Option[(Handoff, String)] = {
    // Find the latest assistant message with tool calls
    val latestAssistantMessage = state.conversation.messages.reverse
      .collectFirst { case msg: AssistantMessage if msg.toolCalls.nonEmpty => msg }

    latestAssistantMessage.flatMap { assistantMessage =>
      // Find handoff tool calls
      val handoffToolCalls = assistantMessage.toolCalls.filter(tc => tc.name.startsWith("handoff_to_agent_"))

      handoffToolCalls.headOption.flatMap { toolCall =>
        // Parse handoff reason from arguments
        val reasonOpt = Try {
          val args = ujson.read(toolCall.arguments)
          args.obj.get("reason").map(_.str).getOrElse("No reason provided")
        }.toOption

        // Find matching handoff by ID
        val handoffId  = toolCall.name
        val handoffOpt = state.availableHandoffs.find(_.handoffId == handoffId)

        handoffOpt.flatMap(handoff => reasonOpt.map(reason => (handoff, reason)))
      }
    }
  }

  /**
   * Build the initial state for the handoff target agent.
   *
   * @param sourceState State from source agent
   * @param handoff The handoff configuration
   * @param reason Optional handoff reason
   * @return Initial state for target agent
   */
  private def buildHandoffState(
    sourceState: AgentState,
    handoff: Handoff,
    reason: Option[String]
  ): AgentState = {
    // Determine which messages to transfer
    val transferredMessages = if (handoff.preserveContext) {
      sourceState.conversation.messages
    } else {
      // Only transfer the last user message
      sourceState.conversation.messages
        .findLast(_.role == MessageRole.User)
        .toVector
    }

    // Build conversation
    val conversation = Conversation(transferredMessages)

    // Determine system message
    val systemMessage = if (handoff.transferSystemMessage) {
      sourceState.systemMessage
    } else {
      None
    }

    // Build logs
    val handoffLog = s"[handoff] Received handoff from agent" +
      reason.map(r => s" (Reason: $r)").getOrElse("")

    // Create target state with empty tools (will be set by target agent's run)
    AgentState(
      conversation = conversation,
      tools = ToolRegistry.empty,
      initialQuery = sourceState.initialQuery,
      status = AgentStatus.InProgress,
      logs = Vector(handoffLog),
      systemMessage = systemMessage,
      availableHandoffs = Seq.empty // Target agent starts with no handoffs
    )
  }

  /**
   * Execute a handoff to another agent.
   *
   * Tracing is propagated to the target agent when provided, preserving
   * end-to-end observability across multi-agent flows.
   *
   * @param sourceState The state from the source agent
   * @param handoff The handoff to execute
   * @param reason Optional reason provided by the LLM
   * @param maxSteps Maximum steps for target agent
   * @param traceLogPath Optional trace log file
   * @param debug Enable debug logging
   * @param tracing Optional tracer; when provided, propagated to target agent
   * @return Result from target agent
   */
  private def executeHandoff(
    sourceState: AgentState,
    handoff: Handoff,
    reason: Option[String],
    maxSteps: Option[Int],
    context: AgentContext
  ): Result[AgentState] = {
    // Log handoff
    val logEntry = s"[handoff] Executing handoff: ${handoff.handoffName}" +
      reason.map(r => s" (Reason: $r)").getOrElse("")

    if (context.debug) {
      logger.info("[DEBUG] {}", logEntry)
      logger.info("[DEBUG] preserveContext: {}", handoff.preserveContext)
      logger.info("[DEBUG] transferSystemMessage: {}", handoff.transferSystemMessage)
    }

    // Build target state
    val targetState = buildHandoffState(sourceState, handoff, reason)

    if (context.debug) {
      logger.info("[DEBUG] Target state conversation messages: {}", targetState.conversation.messages.length)
      logger.info("[DEBUG] Target state system message: {}", targetState.systemMessage.isDefined)
    }

    // Run target agent from the prepared state; propagate context
    handoff.targetAgent.run(targetState, maxSteps, context).map { targetFinalState =>
      targetFinalState.copy(
        usageSummary = sourceState.usageSummary.merge(targetFinalState.usageSummary)
      )
    }
  }

  /**
   * Formats the agent state as a markdown document for tracing
   *
   * @param state The agent state to format as markdown
   * @return A markdown string representation of the agent state
   */
  def formatStateAsMarkdown(state: AgentState): String = {
    val sb = new StringBuilder()

    // Add header
    sb.append("# Agent Execution Trace\n\n")
    state.initialQuery.foreach(q => sb.append(s"**Initial Query:** $q\n"))
    sb.append(s"**Status:** ${state.status}\n")
    sb.append(s"**Tools Available:** ${state.tools.tools.map(_.name).mkString(", ")}\n\n")

    // Add conversation
    sb.append("## Conversation Flow\n\n")

    state.conversation.messages.zipWithIndex.foreach { case (message, index) =>
      val step = index + 1

      message.role match {
        case MessageRole.System =>
          sb.append(s"### Step $step: System Message\n\n")
          sb.append("```\n")
          sb.append(message.content)
          sb.append("\n```\n\n")

        case MessageRole.User =>
          sb.append(s"### Step $step: User Message\n\n")
          sb.append(message.content)
          sb.append("\n\n")

        case MessageRole.Assistant =>
          sb.append(s"### Step $step: Assistant Message\n\n")

          message match {
            case msg: AssistantMessage if msg.toolCalls.nonEmpty =>
              // Show content if it exists
              if (msg.content != null)
                if (msg.content.trim.nonEmpty) {
                  sb.append(msg.content)
                  sb.append("\n\n")
                } else
                  sb.append("--NO CONTENT--\n\n")

              sb.append("**Tool Calls:**\n\n")

              msg.toolCalls.foreach { tc =>
                sb.append(s"Tool: **${tc.name}**\n\n")
                sb.append("Arguments:\n")
                sb.append("```json\n")
                sb.append(tc.arguments)
                sb.append("\n```\n\n")
              }

            case _ =>
              sb.append(message.content)
              sb.append("\n\n")
          }

        case MessageRole.Tool =>
          message match {
            case msg: ToolMessage =>
              sb.append(s"### Step $step: Tool Response\n\n")
              sb.append(s"Tool Call ID: `${msg.toolCallId}`\n\n")
              sb.append("Result:\n")
              sb.append("```json\n")
              sb.append(msg.content)
              sb.append("\n```\n\n")

            case _ =>
              sb.append(s"### Step $step: Tool Response\n\n")
              sb.append("```\n")
              sb.append(message.content)
              sb.append("\n```\n\n")
          }

      }
    }

    // Add logs
    if (state.logs.nonEmpty) {
      sb.append("## Execution Logs\n\n")

      state.logs.zipWithIndex.foreach { case (log, index) =>
        sb.append(s"${index + 1}. ")

        // Format logs with code blocks for tool outputs
        log match {
          case l if l.startsWith("[assistant]") =>
            sb.append(s"**Assistant:** ${l.stripPrefix("[assistant] ")}\n")

          case l if l.startsWith("[tool]") =>
            val content = l.stripPrefix("[tool] ")
            sb.append(s"**Tool Output:** ${content}\n")

          case l if l.startsWith("[tools]") =>
            sb.append(s"**Tools:** ${l.stripPrefix("[tools] ")}\n")

          case l if l.startsWith("[system]") =>
            sb.append(s"**System:** ${l.stripPrefix("[system] ")}\n")

          case _ =>
            sb.append(s"$log\n")
        }
      }
    }

    sb.toString
  }

  /**
   * Writes the current state to a markdown trace file
   *
   * @param state The agent state to write to the trace log
   * @param traceLogPath The path to write the trace log to
   */
  def writeTraceLog(state: AgentState, traceLogPath: String): Unit = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{ Files, Paths }

    Safety
      .fromTry(Try {
        val content = formatStateAsMarkdown(state)
        Files.write(Paths.get(traceLogPath), content.getBytes(StandardCharsets.UTF_8))
      })
      .left
      .foreach(err => logger.error("Failed to write trace log: {}", err.message))
  }

  /**
   * Validate input using guardrails.
   *
   * If no guardrails are provided, input passes through unchanged.
   * If guardrails are provided, they are all evaluated and must all pass.
   *
   * @param query The input to validate
   * @param guardrails The guardrails to apply
   * @return Right(query) if valid, Left(error) if validation fails
   */
  private def validateInput(
    query: String,
    guardrails: Seq[InputGuardrail]
  ): Result[String] =
    if (guardrails.isEmpty) {
      Right(query)
    } else {
      // Run guardrails and aggregate results
      val composite = CompositeGuardrail.all(guardrails)
      composite.validate(query)
    }

  /**
   * Validate output using guardrails.
   *
   * If no guardrails are provided, output passes through unchanged.
   * If guardrails are provided, they are all evaluated and must all pass.
   *
   * @param state The agent state containing the output to validate
   * @param guardrails The guardrails to apply
   * @return Right(state) if valid, Left(error) if validation fails
   */
  private def validateOutput(
    state: AgentState,
    guardrails: Seq[OutputGuardrail]
  ): Result[AgentState] =
    if (guardrails.isEmpty) {
      Right(state)
    } else {
      // Extract final assistant message
      val finalMessage = state.conversation.messages
        .findLast(_.role == MessageRole.Assistant)
        .map(_.content)
        .getOrElse("")

      // Validate final message
      val composite = CompositeGuardrail.all(guardrails)
      composite.validate(finalMessage).map(_ => state)
    }

  /**
   * Runs the agent from an existing state until completion, failure, or step limit is reached
   *
   * @param initialState The initial agent state to run from
   * @param maxSteps Optional limit on the number of steps to execute
   * @param traceLogPath Optional path to write a markdown trace file
   * @param debug Enable detailed debug logging for tool calls and agent loop iterations
   * @return Either an error or the final agent state
   */
  def run(
    initialState: AgentState,
    maxSteps: Option[Int],
    context: AgentContext
  ): Result[AgentState] = {
    if (context.debug) {
      logger.info("[DEBUG] ========================================")
      logger.info("[DEBUG] Starting Agent.run")
      logger.info("[DEBUG] Max steps: {}", maxSteps.getOrElse("unlimited"))
      logger.info("[DEBUG] Trace log: {}", context.traceLogPath.getOrElse("disabled"))
      logger.info("[DEBUG] Initial status: {}", initialState.status)
      logger.info("[DEBUG] ========================================")
    }

    // Write initial state if tracing is enabled
    context.traceLogPath.foreach(path => writeTraceLog(initialState, path))

    @tailrec
    def runUntilCompletion(
      state: AgentState,
      stepsRemaining: Option[Int] = maxSteps,
      iteration: Int = 1
    ): Result[AgentState] =
      (state.status, stepsRemaining) match {
        // Check for step limit before executing either type of step
        case (s, Some(0)) if s == AgentStatus.InProgress || s == AgentStatus.WaitingForTools =>
          if (context.debug) {
            logger.warn("[DEBUG] ========================================")
            logger.warn("[DEBUG] ITERATION {}: Step limit reached!", iteration)
            logger.warn("[DEBUG] ========================================")
          }
          // Step limit reached
          val updatedState =
            state.log("[system] Step limit reached").withStatus(AgentStatus.Failed("Maximum step limit reached"))

          // Write final state if tracing is enabled
          context.traceLogPath.foreach(path => writeTraceLog(updatedState, path))
          Right(updatedState)

        // Continue if we're in progress or waiting for tools
        case (s, _) if s == AgentStatus.InProgress || s == AgentStatus.WaitingForTools =>
          if (context.debug) {
            logger.info("[DEBUG] ========================================")
            logger.info("[DEBUG] ITERATION {}", iteration)
            logger.info("[DEBUG] Current status: {}", state.status)
            logger.info("[DEBUG] Steps remaining: {}", stepsRemaining.map(_.toString).getOrElse("unlimited"))
            logger.info("[DEBUG] Conversation messages: {}", state.conversation.messages.size)
            logger.info("[DEBUG] ========================================")
          }

          runStep(state, context) match {
            case Right(newState) =>
              // Only decrement steps when going from InProgress to WaitingForTools or back to InProgress
              // This means one "logical step" includes both the LLM call and tool execution
              val shouldDecrementStep =
                (state.status == AgentStatus.InProgress && newState.status == AgentStatus.WaitingForTools) ||
                  (state.status == AgentStatus.WaitingForTools && newState.status == AgentStatus.InProgress)

              val nextSteps = if (shouldDecrementStep) stepsRemaining.map(_ - 1) else stepsRemaining

              if (context.debug && shouldDecrementStep) {
                logger.info(
                  "[DEBUG] Step completed. Next steps remaining: {}",
                  nextSteps.map(_.toString).getOrElse("unlimited")
                )
              }

              // Write updated state if tracing is enabled
              context.traceLogPath.foreach(path => writeTraceLog(newState, path))
              // Trace updated agent state (best-effort)
              safeTrace(context.tracing)(_.traceAgentState(newState))

              runUntilCompletion(newState, nextSteps, iteration + 1)

            case Left(error) =>
              if (context.debug) {
                logger.error("[DEBUG] ========================================")
                logger.error("[DEBUG] ITERATION {}: Agent failed with error", iteration)
                logger.error("[DEBUG] Error: {}", error.message)
                logger.error("[DEBUG] ========================================")
              }
              Left(error)
          }

        case (AgentStatus.HandoffRequested(handoff, reason), _) =>
          if (context.debug) {
            logger.info("[DEBUG] ========================================")
            logger.info("[DEBUG] Handoff requested - executing handoff")
            logger.info("[DEBUG] Handoff: {}", handoff.handoffName)
            logger.info("[DEBUG] ========================================")
          }
          // Write state before handoff if tracing is enabled
          context.traceLogPath.foreach(path => writeTraceLog(state, path))

          // Execute handoff
          executeHandoff(state, handoff, reason, maxSteps, context)

        case (_, _) =>
          if (context.debug) {
            logger.info("[DEBUG] ========================================")
            logger.info("[DEBUG] Agent completed")
            logger.info("[DEBUG] Final status: {}", state.status)
            logger.info("[DEBUG] Total iterations: {}", iteration)
            logger.info("[DEBUG] ========================================")
          }
          // Write final state if tracing is enabled
          context.traceLogPath.foreach(path => writeTraceLog(state, path))
          Right(state) // Complete or Failed
      }

    runUntilCompletion(initialState)
  }

  /**
   * Runs the agent with a new query until completion, failure, or step limit is reached
   *
   * @param query The user query to process
   * @param tools The registry of available tools
   * @param inputGuardrails Validate query before processing (default: none)
   * @param outputGuardrails Validate response before returning (default: none)
   * @param handoffs Available handoffs (default: none)
   * @param maxSteps Limit on the number of steps to execute (default: Agent.DefaultMaxSteps for safety).
   *                 Set to None for unlimited steps (use with caution).
   * @param traceLogPath Optional path to write a markdown trace file
   * @param systemPromptAddition Optional additional text to append to the default system prompt
   * @param completionOptions Optional completion options for LLM calls (temperature, maxTokens, etc.)
   * @param debug Enable detailed debug logging for tool calls and agent loop iterations
   * @return Either an error or the final agent state
   */
  def run(
    query: String,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    handoffs: Seq[Handoff] = Seq.empty,
    maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions(),
    context: AgentContext = AgentContext.Default
  ): Result[AgentState] =
    runWithContext(
      query,
      tools,
      inputGuardrails,
      outputGuardrails,
      handoffs,
      maxSteps,
      systemPromptAddition,
      completionOptions,
      context
    )

  private def runWithContext(
    query: String,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail],
    outputGuardrails: Seq[OutputGuardrail],
    handoffs: Seq[Handoff],
    maxSteps: Option[Int],
    systemPromptAddition: Option[String],
    completionOptions: CompletionOptions,
    context: AgentContext
  ): Result[AgentState] =
    for {
      // 1. Validate input
      validatedQuery <- validateInput(query, inputGuardrails)

      // 2. Initialize and run agent
      _ = if (context.debug) {
        logger.info("[DEBUG] ========================================")
        logger.info("[DEBUG] Initializing new agent with query")
        logger.info("[DEBUG] Query: {}", validatedQuery)
        logger.info("[DEBUG] Tools: {}", tools.tools.map(_.name).mkString(", "))
        logger.info("[DEBUG] Input guardrails: {}", inputGuardrails.map(_.name).mkString(", "))
        logger.info("[DEBUG] Output guardrails: {}", outputGuardrails.map(_.name).mkString(", "))
        logger.info("[DEBUG] Handoffs: {}", handoffs.length)
        logger.info("[DEBUG] ========================================")
      }
      initialState <- initializeSafe(validatedQuery, tools, handoffs, systemPromptAddition, completionOptions)
      finalState   <- run(initialState, maxSteps, context)

      // 3. Validate output
      validatedState <- validateOutput(finalState, outputGuardrails)
    } yield validatedState

  @deprecated("Use run(..., context = AgentContext(...))", "0.3.0")
  def run(
    query: String,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail],
    outputGuardrails: Seq[OutputGuardrail],
    handoffs: Seq[Handoff],
    maxSteps: Option[Int],
    traceLogPath: Option[String],
    systemPromptAddition: Option[String],
    completionOptions: CompletionOptions,
    debug: Boolean,
    tracing: Option[Tracing]
  ): Result[AgentState] =
    runWithContext(
      query,
      tools,
      inputGuardrails,
      outputGuardrails,
      handoffs,
      maxSteps,
      systemPromptAddition,
      completionOptions,
      AgentContext(tracing = tracing, debug = debug, traceLogPath = traceLogPath)
    )

  /**
   * Continue an agent conversation with a new user message.
   * This is the functional way to handle multi-turn conversations.
   *
   * The previous state must be in Complete or Failed status - cannot continue from InProgress or WaitingForTools.
   * This ensures a clean turn boundary and prevents inconsistent state.
   *
   * @param previousState The previous agent state (must be Complete or Failed)
   * @param newUserMessage The new user message to process
   * @param inputGuardrails Validate new message before processing
   * @param outputGuardrails Validate response before returning
   * @param maxSteps Optional limit on reasoning steps for this turn
   * @param traceLogPath Optional path for trace logging
   * @param contextWindowConfig Optional configuration for automatic context pruning
   * @param debug Enable debug logging
   * @return Result containing the new agent state after processing the message
   *
   * @example
   * {{{
   * val result = for {
   *   providerCfg <- /* load provider config */
   *   client      <- org.llm4s.llmconnect.LLMConnect.getClient(providerCfg)
   *   tools       = new ToolRegistry(Seq(WeatherTool.tool))
   *   agent       = new Agent(client)
   *   state1     <- agent.run("What's the weather in Paris?", tools)
   *   state2     <- agent.continueConversation(state1, "And in London?")
   *   state3     <- agent.continueConversation(state2, "Which is warmer?")
   * } yield state3
   * }}}
   */
  def continueConversation(
    previousState: AgentState,
    newUserMessage: String,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = None,
    contextWindowConfig: Option[ContextWindowConfig] = None,
    context: AgentContext = AgentContext.Default
  ): Result[AgentState] = {
    import org.llm4s.error.ValidationError

    for {
      // 1. Validate input
      validatedMessage <- validateInput(newUserMessage, inputGuardrails)

      // 2. Validate previous state and continue
      finalState <- previousState.status match {
        case AgentStatus.Complete | AgentStatus.Failed(_) =>
          // Prepare new state by adding user message and resetting status
          val stateWithNewMessage = previousState.copy(
            conversation = previousState.conversation.addMessage(UserMessage(validatedMessage)),
            status = AgentStatus.InProgress,
            logs = Seq.empty // Reset logs for new turn
          )

          // Optionally prune before running
          val stateToRun = contextWindowConfig match {
            case Some(config) =>
              AgentState.pruneConversation(stateWithNewMessage, config)
            case None =>
              stateWithNewMessage
          }

          // Run from the new state
          run(stateToRun, maxSteps, context)

        case AgentStatus.InProgress | AgentStatus.WaitingForTools | AgentStatus.HandoffRequested(_, _) =>
          Left(
            ValidationError.invalid(
              "agentState",
              "Cannot continue from an incomplete conversation. " +
                "Previous state must be Complete or Failed. " +
                s"Current status: ${previousState.status}"
            )
          )
      }

      // 3. Validate output
      validatedState <- validateOutput(finalState, outputGuardrails)
    } yield validatedState
  }

  @deprecated("Use continueConversation(..., context = AgentContext(...))", "0.3.0")
  def continueConversation(
    previousState: AgentState,
    newUserMessage: String,
    inputGuardrails: Seq[InputGuardrail],
    outputGuardrails: Seq[OutputGuardrail],
    maxSteps: Option[Int],
    traceLogPath: Option[String],
    contextWindowConfig: Option[ContextWindowConfig],
    debug: Boolean,
    tracing: Option[Tracing]
  ): Result[AgentState] =
    continueConversation(
      previousState,
      newUserMessage,
      inputGuardrails,
      outputGuardrails,
      maxSteps,
      contextWindowConfig,
      AgentContext(tracing = tracing, debug = debug, traceLogPath = traceLogPath)
    )

  /**
   * Run multiple conversation turns sequentially.
   * Each turn waits for the previous to complete before starting.
   * This is a convenience method for running a complete multi-turn conversation.
   *
   * @param initialQuery The first user message
   * @param followUpQueries Additional user messages to process in sequence
   * @param tools Tool registry for the conversation
   * @param maxStepsPerTurn Step limit per turn (default: Agent.DefaultMaxSteps for safety).
   *                        Set to None for unlimited steps (use with caution).
   * @param systemPromptAddition Optional system prompt addition
   * @param completionOptions Completion options
   * @param contextWindowConfig Optional configuration for automatic context pruning
   * @param debug Enable debug logging
   * @return Result containing the final agent state after all turns
   *
   * @example
   * {{{
   * val result = agent.runMultiTurn(
   *   initialQuery = "What's the weather in Paris?",
   *   followUpQueries = Seq(
   *     "And in London?",
   *     "Which is warmer?"
   *   ),
   *   tools = tools
   * )
   * }}}
   */
  def runMultiTurn(
    initialQuery: String,
    followUpQueries: Seq[String],
    tools: ToolRegistry,
    maxStepsPerTurn: Option[Int] = Some(Agent.DefaultMaxSteps),
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions(),
    contextWindowConfig: Option[ContextWindowConfig] = None,
    context: AgentContext = AgentContext.Default
  ): Result[AgentState] = {
    // Run first turn
    val firstTurn = run(
      query = initialQuery,
      tools = tools,
      inputGuardrails = Seq.empty,
      outputGuardrails = Seq.empty,
      handoffs = Seq.empty,
      maxSteps = maxStepsPerTurn,
      systemPromptAddition = systemPromptAddition,
      completionOptions = completionOptions,
      context = context
    )

    // Fold over follow-up queries, threading state through
    followUpQueries.foldLeft(firstTurn) { (stateResult, query) =>
      stateResult.flatMap { state =>
        continueConversation(
          previousState = state,
          newUserMessage = query,
          inputGuardrails = Seq.empty,
          outputGuardrails = Seq.empty,
          maxSteps = maxStepsPerTurn,
          contextWindowConfig = contextWindowConfig,
          context = context
        )
      }
    }
  }

  @deprecated("Use runMultiTurn(..., context = AgentContext(...))", "0.3.0")
  def runMultiTurn(
    initialQuery: String,
    followUpQueries: Seq[String],
    tools: ToolRegistry,
    maxStepsPerTurn: Option[Int],
    systemPromptAddition: Option[String],
    completionOptions: CompletionOptions,
    contextWindowConfig: Option[ContextWindowConfig],
    debug: Boolean,
    tracing: Option[Tracing]
  ): Result[AgentState] =
    runMultiTurn(
      initialQuery,
      followUpQueries,
      tools,
      maxStepsPerTurn,
      systemPromptAddition,
      completionOptions,
      contextWindowConfig,
      AgentContext(tracing = tracing, debug = debug)
    )

  // ============================================================
  // Streaming Event-based Execution
  // ============================================================

  /**
   * Runs the agent with streaming events for real-time progress tracking.
   *
   * This method provides fine-grained visibility into agent execution through
   * a callback that receives [[org.llm4s.agent.streaming.AgentEvent]] instances as they occur. Events include:
   * - Token-level streaming during LLM generation
   * - Tool call start/complete notifications
   * - Agent lifecycle events (start, step, complete, fail)
   *
   * @param query The user query to process
   * @param tools The registry of available tools
   * @param onEvent Callback invoked for each event during execution
   * @param inputGuardrails Validate query before processing (default: none)
   * @param outputGuardrails Validate response before returning (default: none)
   * @param handoffs Available handoffs (default: none)
   * @param maxSteps Limit on the number of steps to execute (default: Agent.DefaultMaxSteps for safety).
   *                 Set to None for unlimited steps (use with caution).
   * @param traceLogPath Optional path to write a markdown trace file
   * @param systemPromptAddition Optional additional text to append to the default system prompt
   * @param completionOptions Optional completion options for LLM calls
   * @param debug Enable detailed debug logging
   * @return Either an error or the final agent state
   *
   * @example
   * {{{
   * import org.llm4s.agent.streaming.AgentEvent._
   *
   * agent.runWithEvents(
   *   query = "What's the weather?",
   *   tools = weatherTools,
   *   onEvent = {
   *     case TextDelta(delta, _) => print(delta)
   *     case ToolCallStarted(_, name, _, _) => println(s"[Calling $$name]")
   *     case AgentCompleted(_, steps, ms, _) => println(s"Done in $$steps steps")
   *     case _ =>
   *   }
   * )
   * }}}
   */
  def runWithEvents(
    query: String,
    tools: ToolRegistry,
    onEvent: AgentEvent => Unit,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    handoffs: Seq[Handoff] = Seq.empty,
    maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions(),
    context: AgentContext = AgentContext.Default
  ): Result[AgentState] = {
    val startTime = System.currentTimeMillis()

    // Emit input guardrail events before validation
    inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailStarted(g.name, Instant.now())))

    val inputValidationResult = validateInput(query, inputGuardrails)

    // Emit input guardrail completion events based on validation result
    inputValidationResult match {
      case Right(_) =>
        inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailCompleted(g.name, passed = true, Instant.now())))
      case Left(_) =>
        inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailCompleted(g.name, passed = false, Instant.now())))
    }

    inputValidationResult.flatMap { validatedQuery =>
      // Emit start event
      onEvent(AgentEvent.agentStarted(validatedQuery, tools.tools.size))

      // Initialize and run with streaming
      initializeSafe(validatedQuery, tools, handoffs, systemPromptAddition, completionOptions).flatMap { initialState =>
        runWithEventsInternal(
          initialState,
          onEvent,
          maxSteps,
          0,
          startTime,
          context
        ).flatMap { finalState =>
          // Emit output guardrail events
          outputGuardrails.foreach(g => onEvent(AgentEvent.OutputGuardrailStarted(g.name, Instant.now())))

          val outputValidationResult = validateOutput(finalState, outputGuardrails)

          // Emit output guardrail completion events based on validation result
          outputValidationResult match {
            case Right(_) =>
              outputGuardrails.foreach { g =>
                onEvent(AgentEvent.OutputGuardrailCompleted(g.name, passed = true, Instant.now()))
              }
            case Left(_) =>
              outputGuardrails.foreach { g =>
                onEvent(AgentEvent.OutputGuardrailCompleted(g.name, passed = false, Instant.now()))
              }
          }

          outputValidationResult
        }
      }
    }
  }

  /**
   * Internal streaming execution loop.
   */
  private def runWithEventsInternal(
    state: AgentState,
    onEvent: AgentEvent => Unit,
    maxSteps: Option[Int],
    currentStep: Int,
    startTime: Long,
    context: AgentContext
  ): Result[AgentState] = {

    // Check step limit - only check if maxSteps is defined (None means unlimited, matching non-streaming behavior)
    val stepLimitReached = maxSteps.exists(max => currentStep >= max)
    if (stepLimitReached && (state.status == AgentStatus.InProgress || state.status == AgentStatus.WaitingForTools)) {
      val failedState = state.withStatus(AgentStatus.Failed("Maximum step limit reached"))
      onEvent(
        AgentEvent.agentFailed(
          org.llm4s.error.ProcessingError("agent-execution", "Maximum step limit reached"),
          Some(currentStep)
        )
      )
      context.traceLogPath.foreach(path => writeTraceLog(failedState, path))
      return Right(failedState)
    }

    state.status match {
      case AgentStatus.InProgress =>
        // Emit step started
        onEvent(AgentEvent.stepStarted(currentStep))

        // Run streaming completion
        val options     = state.completionOptions.copy(tools = state.tools.tools)
        val accumulator = StreamingAccumulator.create()

        val streamResult = client.streamComplete(
          state.toApiConversation,
          options,
          onChunk = { chunk =>
            // Emit text deltas
            chunk.content.foreach(delta => onEvent(AgentEvent.textDelta(delta)))
            // Note: Tool calls are typically emitted when complete, not incrementally
            accumulator.addChunk(chunk)
          }
        )

        streamResult match {
          case Right(completion) =>
            val stateWithUsage = accumulateUsage(state, completion)

            // Emit text complete
            if (completion.content.nonEmpty) {
              onEvent(AgentEvent.textComplete(completion.content))
            }

            val updatedState = stateWithUsage
              .log(s"[assistant] text: ${completion.content}")
              .addMessage(completion.message)

            // Trace successful completion and token usage (best-effort)
            safeTrace(context.tracing)(tracer => tracer.traceCompletion(completion, completion.model))
            completion.usage.foreach { usage =>
              safeTrace(context.tracing) { tracer =>
                tracer.traceTokenUsage(usage, completion.model, "agent_stream_completion")
              }
            }

            completion.message.toolCalls match {
              case Seq() =>
                // No tool calls - complete
                val totalDuration = System.currentTimeMillis() - startTime
                onEvent(AgentEvent.stepCompleted(currentStep, hasToolCalls = false))

                val finalState = updatedState.withStatus(AgentStatus.Complete)
                onEvent(AgentEvent.agentCompleted(finalState, currentStep + 1, totalDuration))

                context.traceLogPath.foreach(path => writeTraceLog(finalState, path))
                // Trace final agent state (best-effort)
                safeTrace(context.tracing)(_.traceAgentState(finalState))
                Right(finalState)

              case toolCalls =>
                // Process tool calls with events
                onEvent(AgentEvent.stepCompleted(currentStep, hasToolCalls = true))

                val stateAfterTools = processToolCallsWithEvents(
                  updatedState.withStatus(AgentStatus.WaitingForTools),
                  toolCalls,
                  onEvent,
                  context
                )

                // Check for handoffs
                detectHandoff(stateAfterTools) match {
                  case Some((handoff, reason)) =>
                    onEvent(
                      AgentEvent.HandoffStarted(
                        handoff.handoffName,
                        Some(reason),
                        handoff.preserveContext,
                        Instant.now()
                      )
                    )
                    // Execute handoff (note: target agent won't have streaming unless it also uses runWithEvents)
                    val handoffResult =
                      executeHandoff(
                        stateAfterTools,
                        handoff,
                        Some(reason),
                        maxSteps.map(_ - currentStep),
                        context
                      )
                    onEvent(AgentEvent.HandoffCompleted(handoff.handoffName, handoffResult.isRight, Instant.now()))
                    handoffResult

                  case None =>
                    // Continue to next step
                    runWithEventsInternal(
                      stateAfterTools.withStatus(AgentStatus.InProgress),
                      onEvent,
                      maxSteps,
                      currentStep + 1,
                      startTime,
                      context
                    )
                }
            }

          case Left(error) =>
            onEvent(AgentEvent.agentFailed(error, Some(currentStep)))
            // Trace streaming completion error (best-effort)
            safeTrace(context.tracing) { tracer =>
              tracer.traceError(new RuntimeException(error.message), "agent_stream_completion")
            }
            Left(error)
        }

      case AgentStatus.Complete | AgentStatus.Failed(_) =>
        // Already done
        Right(state)

      case AgentStatus.WaitingForTools =>
        // Shouldn't happen in this flow, but handle it
        Right(state)

      case AgentStatus.HandoffRequested(handoff, reason) =>
        // Handle handoff
        onEvent(AgentEvent.HandoffStarted(handoff.handoffName, reason, handoff.preserveContext, Instant.now()))
        val handoffResult =
          executeHandoff(state, handoff, reason, maxSteps.map(_ - currentStep), context)
        onEvent(AgentEvent.HandoffCompleted(handoff.handoffName, handoffResult.isRight, Instant.now()))
        handoffResult
    }
  }

  /**
   * Process tool calls with event emission.
   */
  private def processToolCallsWithEvents(
    state: AgentState,
    toolCalls: Seq[ToolCall],
    onEvent: AgentEvent => Unit,
    context: AgentContext
  ): AgentState = {
    val toolRegistry = state.tools

    val toolMessages = toolCalls.map { toolCall =>
      val toolStartTime = System.currentTimeMillis()

      // Emit tool started
      onEvent(AgentEvent.toolStarted(toolCall.id, toolCall.name, toolCall.arguments.render()))

      val request = ToolCallRequest(toolCall.name, toolCall.arguments)
      val result  = toolRegistry.execute(request)

      val toolEndTime = System.currentTimeMillis()
      val duration    = toolEndTime - toolStartTime

      val (resultContent, success) = result match {
        case Right(json) =>
          val jsonStr = json.render()
          if (context.debug) {
            logger.info("[DEBUG] Tool {} SUCCESS in {}ms", toolCall.name, duration)
          }
          // Trace successful tool call (best-effort)
          safeTrace(context.tracing)(tracer =>
            tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), jsonStr)
          )
          (jsonStr, true)

        case Left(error) =>
          val errorMessage = error.getFormattedMessage
          // Build structured JSON error using ujson (no manual escaping needed)
          val errorJson = ToolCallErrorJson.toJson(error).render()
          if (context.debug) {
            logger.error("[DEBUG] Tool {} FAILED in {}ms: {}", toolCall.name, duration, errorMessage)
          }
          // Trace failed tool call (best-effort)
          safeTrace(context.tracing)(tracer =>
            tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), errorJson)
          )
          (errorJson, false)
      }

      // Emit tool completed or failed
      if (success) {
        onEvent(AgentEvent.toolCompleted(toolCall.id, toolCall.name, resultContent, success = true, duration))
      } else {
        onEvent(AgentEvent.ToolCallFailed(toolCall.id, toolCall.name, resultContent, Instant.now()))
      }

      ToolMessage(resultContent, toolCall.id)
    }

    state.addMessages(toolMessages)
  }

  /**
   * Continue a conversation with streaming events.
   *
   * @param previousState The previous agent state (must be Complete or Failed)
   * @param newUserMessage The new user message to process
   * @param onEvent Callback for streaming events
   * @param inputGuardrails Validate new message before processing
   * @param outputGuardrails Validate response before returning
   * @param maxSteps Optional limit on reasoning steps
   * @param traceLogPath Optional path for trace logging
   * @param contextWindowConfig Optional configuration for context pruning
   * @param debug Enable debug logging
   * @return Result containing the new agent state
   */
  def continueConversationWithEvents(
    previousState: AgentState,
    newUserMessage: String,
    onEvent: AgentEvent => Unit,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = None,
    contextWindowConfig: Option[ContextWindowConfig] = None,
    context: AgentContext = AgentContext.Default
  ): Result[AgentState] = {
    import org.llm4s.error.ValidationError

    val startTime = System.currentTimeMillis()

    // Emit input guardrail events
    inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailStarted(g.name, Instant.now())))

    val inputValidationResult = validateInput(newUserMessage, inputGuardrails)

    // Emit input guardrail completion events based on validation result
    inputValidationResult match {
      case Right(_) =>
        inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailCompleted(g.name, passed = true, Instant.now())))
      case Left(_) =>
        inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailCompleted(g.name, passed = false, Instant.now())))
    }

    inputValidationResult.flatMap { validatedMessage =>
      // Validate state and continue
      val stateResult: Result[AgentState] = previousState.status match {
        case AgentStatus.Complete | AgentStatus.Failed(_) =>
          // Emit start event
          onEvent(AgentEvent.agentStarted(validatedMessage, previousState.tools.tools.size))

          val stateWithNewMessage = previousState.copy(
            conversation = previousState.conversation.addMessage(UserMessage(validatedMessage)),
            status = AgentStatus.InProgress,
            logs = Seq.empty
          )

          val stateToRun = contextWindowConfig match {
            case Some(config) => AgentState.pruneConversation(stateWithNewMessage, config)
            case None         => stateWithNewMessage
          }

          runWithEventsInternal(
            stateToRun,
            onEvent,
            maxSteps,
            0,
            startTime,
            context
          )

        case _ =>
          Left(
            ValidationError.invalid(
              "agentState",
              s"Cannot continue from incomplete state: ${previousState.status}"
            )
          )
      }

      stateResult.flatMap { finalState =>
        // Emit output guardrail events
        outputGuardrails.foreach(g => onEvent(AgentEvent.OutputGuardrailStarted(g.name, Instant.now())))

        val outputValidationResult = validateOutput(finalState, outputGuardrails)

        // Emit output guardrail completion events based on validation result
        outputValidationResult match {
          case Right(_) =>
            outputGuardrails.foreach { g =>
              onEvent(AgentEvent.OutputGuardrailCompleted(g.name, passed = true, Instant.now()))
            }
          case Left(_) =>
            outputGuardrails.foreach { g =>
              onEvent(AgentEvent.OutputGuardrailCompleted(g.name, passed = false, Instant.now()))
            }
        }

        outputValidationResult
      }
    }
  }

  /**
   * Collect all events during execution into a sequence.
   *
   * Convenience method that runs the agent and returns both the final state
   * and all events that were emitted during execution.
   *
   * @param query The user query to process
   * @param tools The registry of available tools
   * @param maxSteps Limit on the number of steps (default: 50 for safety).
   *                 Set to None for unlimited steps (use with caution).
   * @param systemPromptAddition Optional system prompt addition
   * @param completionOptions Completion options
   * @param debug Enable debug logging
   * @return Tuple of (final state, all events)
   */
  def runCollectingEvents(
    query: String,
    tools: ToolRegistry,
    maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions(),
    context: AgentContext = AgentContext.Default
  ): Result[(AgentState, Seq[AgentEvent])] = {
    val events = scala.collection.mutable.ArrayBuffer[AgentEvent]()

    runWithEvents(
      query = query,
      tools = tools,
      onEvent = events += _,
      maxSteps = maxSteps,
      systemPromptAddition = systemPromptAddition,
      completionOptions = completionOptions,
      context = context
    ).map(state => (state, events.toSeq))
  }

  @deprecated("Use runWithEvents(..., context = AgentContext(...))", "0.3.0")
  def runWithEvents(
    query: String,
    tools: ToolRegistry,
    onEvent: AgentEvent => Unit,
    inputGuardrails: Seq[InputGuardrail],
    outputGuardrails: Seq[OutputGuardrail],
    handoffs: Seq[Handoff],
    maxSteps: Option[Int],
    traceLogPath: Option[String],
    systemPromptAddition: Option[String],
    completionOptions: CompletionOptions,
    debug: Boolean,
    tracing: Option[Tracing]
  ): Result[AgentState] =
    runWithEvents(
      query,
      tools,
      onEvent,
      inputGuardrails,
      outputGuardrails,
      handoffs,
      maxSteps,
      systemPromptAddition,
      completionOptions,
      AgentContext(tracing = tracing, debug = debug, traceLogPath = traceLogPath)
    )

  @deprecated("Use continueConversationWithEvents(..., context = AgentContext(...))", "0.3.0")
  def continueConversationWithEvents(
    previousState: AgentState,
    newUserMessage: String,
    onEvent: AgentEvent => Unit,
    inputGuardrails: Seq[InputGuardrail],
    outputGuardrails: Seq[OutputGuardrail],
    maxSteps: Option[Int],
    traceLogPath: Option[String],
    contextWindowConfig: Option[ContextWindowConfig],
    debug: Boolean,
    tracing: Option[Tracing]
  ): Result[AgentState] =
    continueConversationWithEvents(
      previousState,
      newUserMessage,
      onEvent,
      inputGuardrails,
      outputGuardrails,
      maxSteps,
      contextWindowConfig,
      AgentContext(tracing = tracing, debug = debug, traceLogPath = traceLogPath)
    )

  @deprecated("Use runCollectingEvents(..., context = AgentContext(...))", "0.3.0")
  def runCollectingEvents(
    query: String,
    tools: ToolRegistry,
    maxSteps: Option[Int],
    systemPromptAddition: Option[String],
    completionOptions: CompletionOptions,
    debug: Boolean,
    tracing: Option[Tracing]
  ): Result[(AgentState, Seq[AgentEvent])] =
    runCollectingEvents(
      query,
      tools,
      maxSteps,
      systemPromptAddition,
      completionOptions,
      AgentContext(tracing = tracing, debug = debug)
    )

  // ============================================================
  // Async Tool Execution with Configurable Strategy
  // ============================================================

  /**
   * Runs the agent with a configurable tool execution strategy.
   *
   * This method enables parallel or rate-limited execution of multiple tool calls,
   * which can significantly improve performance when the LLM requests multiple
   * independent tool calls (e.g., fetching weather for multiple cities).
   *
   * @param query The user query to process
   * @param tools The registry of available tools
   * @param toolExecutionStrategy Strategy for executing multiple tool calls:
   *                              - Sequential: One at a time (default, safest)
   *                              - Parallel: All tools simultaneously
   *                              - ParallelWithLimit(n): Max n tools concurrently
   * @param inputGuardrails Validate query before processing (default: none)
   * @param outputGuardrails Validate response before returning (default: none)
   * @param handoffs Available handoffs (default: none)
   * @param maxSteps Limit on the number of steps to execute (default: Agent.DefaultMaxSteps for safety).
   *                 Set to None for unlimited steps (use with caution).
   * @param traceLogPath Optional path to write a markdown trace file
   * @param systemPromptAddition Optional additional text to append to the default system prompt
   * @param completionOptions Optional completion options for LLM calls
   * @param debug Enable detailed debug logging
   * @param ec ExecutionContext for async operations
   * @return Either an error or the final agent state
   *
   * @example
   * {{{
   * import scala.concurrent.ExecutionContext.Implicits.global
   *
   * // Execute weather lookups in parallel
   * val result = agent.runWithStrategy(
   *   query = "Get weather in London, Paris, and Tokyo",
   *   tools = weatherTools,
   *   toolExecutionStrategy = ToolExecutionStrategy.Parallel
   * )
   *
   * // Limit concurrency to avoid rate limits
   * val result = agent.runWithStrategy(
   *   query = "Search for 10 topics",
   *   tools = searchTools,
   *   toolExecutionStrategy = ToolExecutionStrategy.ParallelWithLimit(3)
   * )
   * }}}
   */
  def runWithStrategy(
    query: String,
    tools: ToolRegistry,
    toolExecutionStrategy: ToolExecutionStrategy = ToolExecutionStrategy.Sequential,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    handoffs: Seq[Handoff] = Seq.empty,
    maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions(),
    context: AgentContext = AgentContext.Default
  )(implicit ec: ExecutionContext): Result[AgentState] =
    for {
      // 1. Validate input
      validatedQuery <- validateInput(query, inputGuardrails)

      // 2. Initialize and run agent with strategy
      _ = if (context.debug) {
        logger.info("[DEBUG] ========================================")
        logger.info("[DEBUG] Initializing agent with tool execution strategy: {}", toolExecutionStrategy)
        logger.info("[DEBUG] Query: {}", validatedQuery)
        logger.info("[DEBUG] Tools: {}", tools.tools.map(_.name).mkString(", "))
        logger.info("[DEBUG] ========================================")
      }
      initialState <- initializeSafe(validatedQuery, tools, handoffs, systemPromptAddition, completionOptions)
      finalState <- runWithStrategyInternal(
        initialState,
        strategy = toolExecutionStrategy,
        maxSteps = maxSteps,
        context = context
      )

      // 3. Validate output
      validatedState <- validateOutput(finalState, outputGuardrails)
    } yield validatedState

  @deprecated("Use runWithStrategy(..., context = AgentContext(...))", "0.3.0")
  def runWithStrategy(
    query: String,
    tools: ToolRegistry,
    toolExecutionStrategy: ToolExecutionStrategy,
    inputGuardrails: Seq[InputGuardrail],
    outputGuardrails: Seq[OutputGuardrail],
    handoffs: Seq[Handoff],
    maxSteps: Option[Int],
    traceLogPath: Option[String],
    systemPromptAddition: Option[String],
    completionOptions: CompletionOptions,
    debug: Boolean,
    tracing: Option[Tracing]
  )(implicit ec: ExecutionContext): Result[AgentState] =
    runWithStrategy(
      query,
      tools,
      toolExecutionStrategy,
      inputGuardrails,
      outputGuardrails,
      handoffs,
      maxSteps,
      systemPromptAddition,
      completionOptions,
      AgentContext(tracing = tracing, debug = debug, traceLogPath = traceLogPath)
    )

  /**
   * Internal method for running agent with a specific tool execution strategy.
   */
  private def runWithStrategyInternal(
    initialState: AgentState,
    strategy: ToolExecutionStrategy,
    maxSteps: Option[Int],
    context: AgentContext
  )(implicit ec: ExecutionContext): Result[AgentState] = {
    if (context.debug) {
      logger.info("[DEBUG] ========================================")
      logger.info("[DEBUG] Starting Agent.runWithStrategy")
      logger.info("[DEBUG] Strategy: {}", strategy)
      logger.info("[DEBUG] Max steps: {}", maxSteps.getOrElse("unlimited"))
      logger.info("[DEBUG] ========================================")
    }

    // Write initial state if tracing is enabled
    context.traceLogPath.foreach(path => writeTraceLog(initialState, path))

    @tailrec
    def runUntilCompletion(
      state: AgentState,
      stepsRemaining: Option[Int] = maxSteps,
      iteration: Int = 1
    ): Result[AgentState] =
      (state.status, stepsRemaining) match {
        // Check for step limit
        case (s, Some(0)) if s == AgentStatus.InProgress || s == AgentStatus.WaitingForTools =>
          if (context.debug) {
            logger.warn("[DEBUG] Step limit reached!")
          }
          val updatedState =
            state.log("[system] Step limit reached").withStatus(AgentStatus.Failed("Maximum step limit reached"))
          context.traceLogPath.foreach(path => writeTraceLog(updatedState, path))
          Right(updatedState)

        // InProgress: Request LLM completion
        case (AgentStatus.InProgress, _) =>
          if (context.debug) {
            logger.info("[DEBUG] ITERATION {}: InProgress -> requesting LLM completion", iteration)
          }

          runStep(state, context) match {
            case Right(newState) =>
              val shouldDecrement = newState.status == AgentStatus.WaitingForTools
              val updatedSteps    = if (shouldDecrement) stepsRemaining.map(_ - 1) else stepsRemaining
              context.traceLogPath.foreach(path => writeTraceLog(newState, path))
              // Trace updated agent state (best-effort)
              safeTrace(context.tracing)(_.traceAgentState(newState))
              runUntilCompletion(newState, updatedSteps, iteration + 1)

            case Left(error) =>
              if (context.debug) {
                logger.error("[DEBUG] LLM completion failed: {}", error.message)
              }
              Left(error)
          }

        // WaitingForTools: Process tools with configured strategy
        case (AgentStatus.WaitingForTools, _) =>
          val assistantMessageOpt = state.conversation.messages.reverse
            .collectFirst { case msg: AssistantMessage if msg.toolCalls.nonEmpty => msg }

          assistantMessageOpt match {
            case Some(assistantMessage) =>
              val toolNames = assistantMessage.toolCalls.map(_.name).mkString(", ")

              if (context.debug) {
                logger.info(
                  "[DEBUG] ITERATION {}: WaitingForTools -> processing {} tools with {}",
                  iteration,
                  assistantMessage.toolCalls.size,
                  strategy
                )
                logger.info("[DEBUG] Tools: {}", toolNames)
              }

              Try {
                processToolCallsAsync(
                  state.log(s"[tools] executing ${assistantMessage.toolCalls.size} tools ($toolNames) with $strategy"),
                  assistantMessage.toolCalls,
                  strategy,
                  context
                )
              } match {
                case Success(newState) =>
                  // Check for handoffs
                  detectHandoff(newState) match {
                    case Some((handoff, reason)) =>
                      if (context.debug) {
                        logger.info("[DEBUG] Handoff detected: {}", handoff.handoffName)
                      }
                      Right(newState.withStatus(AgentStatus.HandoffRequested(handoff, Some(reason))))

                    case None =>
                      if (context.debug) {
                        logger.info("[DEBUG] Tools processed -> InProgress")
                      }
                      context.traceLogPath.foreach(path => writeTraceLog(newState, path))
                      runUntilCompletion(newState.withStatus(AgentStatus.InProgress), stepsRemaining, iteration + 1)
                  }

                case Failure(error) =>
                  logger.error("Tool processing failed: {}", error.getMessage)
                  Right(state.withStatus(AgentStatus.Failed(error.getMessage)))
              }

            case None =>
              Right(state.withStatus(AgentStatus.Failed("No tool calls found in conversation")))
          }

        case (AgentStatus.HandoffRequested(handoff, reason), _) =>
          if (context.debug) {
            logger.info("[DEBUG] Executing handoff: {}", handoff.handoffName)
          }
          context.traceLogPath.foreach(path => writeTraceLog(state, path))
          executeHandoff(state, handoff, reason, maxSteps, context)

        case (_, _) =>
          if (context.debug) {
            logger.info("[DEBUG] Agent completed with status: {}", state.status)
          }
          context.traceLogPath.foreach(path => writeTraceLog(state, path))
          Right(state)
      }

    runUntilCompletion(initialState)
  }

  /**
   * Continue a conversation with a configurable tool execution strategy.
   *
   * @param previousState The previous agent state (must be Complete or Failed)
   * @param newUserMessage The new user message to process
   * @param toolExecutionStrategy Strategy for executing multiple tool calls
   * @param inputGuardrails Validate new message before processing
   * @param outputGuardrails Validate response before returning
   * @param maxSteps Optional limit on reasoning steps for this turn
   * @param traceLogPath Optional path for trace logging
   * @param contextWindowConfig Optional configuration for automatic context pruning
   * @param debug Enable debug logging
   * @param ec ExecutionContext for async operations
   * @return Result containing the new agent state after processing the message
   */
  def continueConversationWithStrategy(
    previousState: AgentState,
    newUserMessage: String,
    toolExecutionStrategy: ToolExecutionStrategy = ToolExecutionStrategy.Sequential,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = None,
    contextWindowConfig: Option[ContextWindowConfig] = None,
    context: AgentContext = AgentContext.Default
  )(implicit ec: ExecutionContext): Result[AgentState] = {
    import org.llm4s.error.ValidationError

    for {
      // 1. Validate input
      validatedMessage <- validateInput(newUserMessage, inputGuardrails)

      // 2. Validate previous state and continue
      finalState <- previousState.status match {
        case AgentStatus.Complete | AgentStatus.Failed(_) =>
          val stateWithNewMessage = previousState.copy(
            conversation = previousState.conversation.addMessage(UserMessage(validatedMessage)),
            status = AgentStatus.InProgress,
            logs = Seq.empty
          )

          val stateToRun = contextWindowConfig match {
            case Some(config) => AgentState.pruneConversation(stateWithNewMessage, config)
            case None         => stateWithNewMessage
          }

          runWithStrategyInternal(stateToRun, toolExecutionStrategy, maxSteps, context)

        case AgentStatus.InProgress | AgentStatus.WaitingForTools | AgentStatus.HandoffRequested(_, _) =>
          Left(
            ValidationError.invalid(
              "agentState",
              s"Cannot continue from incomplete state: ${previousState.status}"
            )
          )
      }

      // 3. Validate output
      validatedState <- validateOutput(finalState, outputGuardrails)
    } yield validatedState
  }

  @deprecated("Use continueConversationWithStrategy(..., context = AgentContext(...))", "0.3.0")
  def continueConversationWithStrategy(
    previousState: AgentState,
    newUserMessage: String,
    toolExecutionStrategy: ToolExecutionStrategy,
    inputGuardrails: Seq[InputGuardrail],
    outputGuardrails: Seq[OutputGuardrail],
    maxSteps: Option[Int],
    traceLogPath: Option[String],
    contextWindowConfig: Option[ContextWindowConfig],
    debug: Boolean,
    tracing: Option[Tracing]
  )(implicit ec: ExecutionContext): Result[AgentState] =
    continueConversationWithStrategy(
      previousState,
      newUserMessage,
      toolExecutionStrategy,
      inputGuardrails,
      outputGuardrails,
      maxSteps,
      contextWindowConfig,
      AgentContext(tracing = tracing, debug = debug, traceLogPath = traceLogPath)
    )
}

object Agent {

  /**
   * Default maximum number of steps for agent execution.
   * This prevents infinite loops when the LLM repeatedly requests tool calls.
   */
  val DefaultMaxSteps: Int = 50
}
