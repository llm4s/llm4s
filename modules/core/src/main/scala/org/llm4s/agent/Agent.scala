package org.llm4s.agent

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
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
 * Bundles cross-cutting execution parameters for [[Agent]] methods.
 *
 * Replaces the separate `debug`, `tracing`, and `traceLogPath` parameters
 * that appeared on every `run` overload before v0.3.0. Pass [[AgentContext.Default]]
 * when none of these concerns are needed.
 *
 * @param tracing      Optional tracer; spans are emitted for each LLM call, tool
 *                     execution, and state update. Use [[org.llm4s.trace.Tracing]] implementations
 *                     such as `ConsoleTracing` or `TraceCollectorTracing` to capture spans.
 * @param debug        Enables verbose INFO-level debug logging of every state-machine
 *                     transition, tool argument, and LLM response via SLF4J.
 * @param traceLogPath Path to write a markdown execution trace after each step;
 *                     useful for post-run inspection without a full tracing backend.
 */
case class AgentContext(
  tracing: Option[Tracing] = None,
  debug: Boolean = false,
  traceLogPath: Option[String] = None
)

object AgentContext {

  /** No-op defaults: no tracing, debug logging off, trace log not written. */
  val Default: AgentContext = AgentContext()
}

/**
 * Core agent implementation for orchestrating LLM interactions with tool calling.
 *
 * The Agent class coordinates five concerns that are each handled by a
 * dedicated module:
 *  - '''Guardrail validation''' — [[GuardrailApplicator]]
 *  - '''Trace formatting and file I/O''' — [[AgentTraceFormatter]]
 *  - '''Handoff delegation''' — [[HandoffExecutor]]
 *  - '''Tool execution''' — [[ToolProcessor]]
 *  - '''Streaming / strategy execution''' — [[AgentStreamingExecutor]]
 *
 * This class is the primary orchestration entry point.  It initialises agent
 * state, drives the `InProgress → WaitingForTools → Complete` state machine
 * via [[runStep]] and [[run]], and delegates each concern to the appropriate
 * module.
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

  /** Lazy reference to the streaming/strategy executor. */
  private lazy val streamingExecutor: AgentStreamingExecutor =
    new AgentStreamingExecutor(
      client,
      (s, c) => runStep(s, c),
      (q, t, h, sp, co) => initializeSafe(q, t, h, sp, co)
    )

  /**
   * Initializes a new [[AgentState]] ready to be driven by [[runStep]] or [[run]].
   *
   * Synthesizes a built-in system prompt (step-by-step tool-use instructions) and
   * appends `systemPromptAddition` when provided. Each [[Handoff]] in `handoffs` is
   * converted into a synthetic tool registered alongside the caller-supplied `tools`,
   * so the LLM can trigger a handoff just like any other tool call.
   *
   * The system prompt is stored in [[AgentState.systemMessage]] rather than
   * as the first message in [[AgentState.conversation]].  This separation
   * allows the system prompt to be injected at every LLM API call without
   * polluting the mutable conversation history — important for context-window
   * pruning, where we must never drop the system instructions.
   *
   * @param query               The user message that opens the conversation.
   * @param tools               Tools available for the agent to invoke during this run.
   * @param handoffs            Agents to delegate to; each becomes a callable tool.
   * @param systemPromptAddition Text appended to the default system prompt; use this to
   *                            inject domain-specific instructions without replacing the
   *                            built-in tool-use guidance.
   * @param completionOptions   LLM parameters (temperature, maxTokens, reasoning effort,
   *                            etc.) forwarded on every call in this run.
   * @return the initialized state, or `Left` when synthetic handoff-tool creation fails
   *         (e.g. invalid tool name or schema).
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
    val initialMessages = Seq(
      UserMessage(query)
    )

    for {
      handoffTools <- HandoffExecutor.createHandoffTools(handoffs)
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
   * Advances the agent by exactly one state-machine transition.
   *
   * One ''step'' is either:
   *  - An LLM call (in `InProgress` state), which transitions the agent to
   *    `WaitingForTools` when tools were requested or `Complete` when no
   *    tool calls were made.  One LLM call = one billing unit.
   *  - A tool-execution batch (in `WaitingForTools` state), which processes
   *    all pending tool calls and transitions back to `InProgress` (or to
   *    `HandoffRequested`).
   *
   * Counting LLM call + tool execution together as ''one logical step'' ensures
   * consistent billing semantics and prevents `maxSteps` from being exhausted
   * by tool executions rather than LLM reasoning turns.
   *
   * States that are already terminal (`Complete`, `Failed`, `HandoffRequested`) are
   * returned unchanged — callers do not need to guard against double-stepping.
   *
   * @param state   Current agent state; its `.status` field determines the transition.
   * @param context Cross-cutting concerns (tracing, debug logging, trace file path).
   * @return the state after the transition, or `Left` when the LLM call fails.
   *         Tool execution failures are captured as `AgentStatus.Failed` inside a
   *         `Right`, not as a `Left`, so they are visible in the final state.
   */
  def runStep(state: AgentState, context: AgentContext = AgentContext.Default): Result[AgentState] =
    state.status match {
      case AgentStatus.InProgress =>
        val options = state.completionOptions.copy(tools = state.tools.tools)

        if (context.debug) {
          logger.info("[DEBUG] Running completion step")
          logger.info("[DEBUG] Status: InProgress -> requesting LLM completion")
          logger.info("[DEBUG] Available tools: {}", state.tools.tools.map(_.name).mkString(", "))
          logger.info("[DEBUG] Conversation history: {} messages", state.conversation.messages.size)
        } else {
          logger.debug("Running completion step with tools: {}", state.tools.tools.map(_.name).mkString(", "))
        }

        client.complete(state.toApiConversation, options) match {
          case Right(completion) =>
            val stateWithUsage = accumulateUsage(state, completion)

            val logMessage = completion.message.toolCalls match {
              case Seq() => s"[assistant] text: ${completion.message.content}"
              case toolCalls =>
                val toolNames = toolCalls.map(_.name).mkString(", ")
                s"[assistant] tools: ${toolCalls.size} tool calls requested ($toolNames)"
            }

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
                if (context.debug) {
                  logger.info("[DEBUG] Status: InProgress -> Complete (no tool calls)")
                }
                Right(updatedState.withStatus(AgentStatus.Complete))

              case _ =>
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
            safeTrace(context.tracing)(tracer =>
              tracer.traceError(new RuntimeException(error.message), "agent_completion")
            )
            Left(error)
        }

      case AgentStatus.WaitingForTools =>
        val assistantMessageOpt = state.conversation.messages.reverse
          .collectFirst { case msg: AssistantMessage if msg.toolCalls.nonEmpty => msg }

        assistantMessageOpt match {
          case Some(assistantMessage) =>
            val toolNames    = assistantMessage.toolCalls.map(_.name).mkString(", ")
            val logMessage   = s"[tools] executing ${assistantMessage.toolCalls.size} tools ($toolNames)"
            val stateWithLog = state.log(logMessage)

            if (context.debug) {
              logger.info("[DEBUG] Status: WaitingForTools -> processing tools")
              logger.info("[DEBUG] Processing {} tool calls: {}", assistantMessage.toolCalls.size, toolNames)
            }

            Try {
              if (context.debug) {
                logger.info("[DEBUG] Calling processToolCalls with {} tools", assistantMessage.toolCalls.size)
              } else {
                logger.debug("Processing {} tool calls", assistantMessage.toolCalls.size)
              }
              ToolProcessor.processToolCalls(stateWithLog, assistantMessage.toolCalls, context)
            } match {
              case Success(newState) =>
                if (context.debug) {
                  logger.info("[DEBUG] Tool processing successful")
                }

                HandoffExecutor.detectHandoff(newState) match {
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
                safeTrace(context.tracing)(tracer => tracer.traceError(error, "agent_tool_execution"))
                Right(stateWithLog.withStatus(AgentStatus.Failed(error.getMessage)))
            }

          case None =>
            if (context.debug) {
              logger.error("[DEBUG] No tool calls found in conversation - this should not happen!")
            }
            Right(state.withStatus(AgentStatus.Failed("No tool calls found in conversation")))
        }

      case _ =>
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
   * Renders the agent state as a human-readable markdown document.
   *
   * Delegates to [[AgentTraceFormatter.formatStateAsMarkdown]].
   *
   * Intended for debugging and post-run inspection. The output format is not
   * stable across library versions; do not parse the result programmatically.
   *
   * @param state Agent state to render.
   * @return markdown string covering the conversation transcript, tool arguments,
   *         tool results, and execution log entries.
   */
  def formatStateAsMarkdown(state: AgentState): String =
    AgentTraceFormatter.formatStateAsMarkdown(state)

  /**
   * Overwrites `traceLogPath` with the markdown-formatted agent state.
   *
   * Delegates to [[AgentTraceFormatter.writeTraceLog]].
   *
   * File-write failures are swallowed: the error is logged at ERROR level via
   * SLF4J but is not surfaced to the caller. The method always returns `Unit`
   * so that tracing never affects agent control flow.
   *
   * @param state        Agent state to render and persist.
   * @param traceLogPath Absolute or relative path to the output file; the file
   *                     is created or truncated on each call.
   */
  def writeTraceLog(state: AgentState, traceLogPath: String): Unit =
    AgentTraceFormatter.writeTraceLog(state, traceLogPath)

  /**
   * Drives an already-initialized state to completion, failure, or the step limit.
   *
   * One ''logical step'' = LLM call + subsequent tool execution.  The
   * `InProgress→WaitingForTools` transition and the `WaitingForTools→InProgress`
   * transition together consume one step from the budget.  A final LLM call
   * with no tool calls (→ `Complete`) does not consume an extra step.
   *
   * The loop is implemented as a tail-recursive local function.  This avoids
   * stack overflow on long-running agents that perform many reasoning turns;
   * a chain of 50+ steps would otherwise accumulate 50+ stack frames for a
   * non-tail-recursive implementation.
   *
   * When a [[AgentStatus.HandoffRequested]] status is detected, control is
   * transferred to the target agent with the same `maxSteps` budget.
   *
   * @param initialState State produced by [[initializeSafe]] or a previous [[run]].
   * @param maxSteps     Maximum number of LLM+tool round-trips before the run is
   *                     aborted with `AgentStatus.Failed("Maximum step limit reached")`.
   *                     `None` removes the limit — this is an explicit opt-out
   *                     intended for bounded workflows such as unit tests where
   *                     mock clients never loop.  Omit `None` in production.
   * @param context      Cross-cutting concerns for this run.
   * @return `Right(state)` when the run reaches `Complete` or `Failed`; `Left` only
   *         when an LLM call returns an error before any terminal state is reached.
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

    context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(initialState, path))

    @tailrec
    def runUntilCompletion(
      state: AgentState,
      stepsRemaining: Option[Int] = maxSteps,
      iteration: Int = 1
    ): Result[AgentState] =
      (state.status, stepsRemaining) match {
        case (s, Some(0)) if s == AgentStatus.InProgress || s == AgentStatus.WaitingForTools =>
          if (context.debug) {
            logger.warn("[DEBUG] ========================================")
            logger.warn("[DEBUG] ITERATION {}: Step limit reached!", iteration)
            logger.warn("[DEBUG] ========================================")
          }
          val updatedState =
            state.log("[system] Step limit reached").withStatus(AgentStatus.Failed("Maximum step limit reached"))

          context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(updatedState, path))
          Right(updatedState)

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

              context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(newState, path))
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
          context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(state, path))

          HandoffExecutor.executeHandoff(state, handoff, reason, maxSteps, context)

        case (_, _) =>
          if (context.debug) {
            logger.info("[DEBUG] ========================================")
            logger.info("[DEBUG] Agent completed")
            logger.info("[DEBUG] Final status: {}", state.status)
            logger.info("[DEBUG] Total iterations: {}", iteration)
            logger.info("[DEBUG] ========================================")
          }
          context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(state, path))
          Right(state)
      }

    runUntilCompletion(initialState)
  }

  /**
   * Runs a new query to completion, failure, or the step limit.
   *
   * Combines [[initializeSafe]] and [[run]] into a single call with input and
   * output guardrail support. The full pipeline is:
   * 1. Input guardrails are evaluated; the first failure short-circuits to `Left`.
   * 2. State is initialised via [[initializeSafe]]; `Left` on handoff-tool creation failure.
   * 3. The agent loop runs until a terminal status or `maxSteps` is exhausted.
   * 4. Output guardrails are evaluated on the final assistant message; the first
   *    failure short-circuits to `Left`.
   *
   * @param query               The user message to process.
   * @param tools               Tools the LLM may invoke during this run.
   * @param inputGuardrails     Applied to `query` before any LLM call; default none.
   * @param outputGuardrails    Applied to the final assistant message; default none.
   * @param handoffs            Agents to delegate to; each becomes a callable tool.
   * @param maxSteps            Maximum LLM+tool round-trips; defaults to
   *                            [[Agent.DefaultMaxSteps]]. Pass `None` to remove the
   *                            cap (use with caution in production).
   * @param systemPromptAddition Text appended to the built-in system prompt.
   * @param completionOptions   LLM parameters forwarded on every call.
   * @param context             Tracing, debug logging, and trace file path.
   * @return `Right(state)` when the pipeline completes; `Left` on guardrail failure,
   *         handoff-tool creation failure, or a non-recoverable LLM error.
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
      validatedQuery <- GuardrailApplicator.validateInput(query, inputGuardrails)

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

      validatedState <- GuardrailApplicator.validateOutput(finalState, outputGuardrails)
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
   * Appends a new user message to an existing conversation and runs the agent to completion.
   *
   * Preserves the full conversation history from `previousState` (tools,
   * system message, prior messages) so the LLM has context from earlier turns.
   * When `contextWindowConfig` is supplied, the history is pruned before the LLM
   * call to avoid exceeding the model's token limit.
   *
   * `previousState` must be in `Complete` or `Failed` status. Calling with
   * `InProgress`, `WaitingForTools`, or `HandoffRequested` is a programming error
   * and returns a `ValidationError` immediately without running the agent.
   *
   * @param previousState       State returned by a prior [[run]] or [[continueConversation]] call;
   *                            must be `Complete` or `Failed`.
   * @param newUserMessage      The follow-up message to process.
   * @param inputGuardrails     Applied to `newUserMessage`; default none.
   * @param outputGuardrails    Applied to the final assistant message; default none.
   * @param maxSteps            Step cap for this turn; `None` for unlimited.
   * @param contextWindowConfig When set, prunes the oldest messages to keep the
   *                            conversation within the model's token budget.
   * @param context             Tracing, debug logging, and trace file path.
   * @return `Right(state)` on success; `Left(ValidationError)` when `previousState`
   *         is not terminal, or `Left` on guardrail or LLM failure.
   *
   * @example
   * {{{
   * val result = for {
   *   providerCfg <- /* load provider config */
   *   client      <- org.llm4s.llmconnect.LLMConnect.getClient(providerCfg)
   *   tool        <- WeatherTool.toolSafe
   *   tools       = new ToolRegistry(Seq(tool))
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
      validatedMessage <- GuardrailApplicator.validateInput(newUserMessage, inputGuardrails)

      finalState <- previousState.status match {
        case AgentStatus.Complete | AgentStatus.Failed(_) =>
          val stateWithNewMessage = previousState.copy(
            conversation = previousState.conversation.addMessage(UserMessage(validatedMessage)),
            status = AgentStatus.InProgress,
            logs = Seq.empty
          )

          val stateToRun = contextWindowConfig match {
            case Some(config) =>
              AgentState.pruneConversation(stateWithNewMessage, config)
            case None =>
              stateWithNewMessage
          }

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

      validatedState <- GuardrailApplicator.validateOutput(finalState, outputGuardrails)
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
   * @param context Cross-cutting concerns
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
  // Streaming Event-based Execution — delegates to AgentStreamingExecutor
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
   * @param systemPromptAddition Optional additional text to append to the default system prompt
   * @param completionOptions Optional completion options for LLM calls
   * @param context Cross-cutting concerns
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
  ): Result[AgentState] =
    streamingExecutor.runWithEvents(
      query,
      tools,
      onEvent,
      inputGuardrails,
      outputGuardrails,
      handoffs,
      maxSteps,
      systemPromptAddition,
      completionOptions,
      context
    )

  /**
   * Continue a conversation with streaming events.
   *
   * @param previousState The previous agent state (must be Complete or Failed)
   * @param newUserMessage The new user message to process
   * @param onEvent Callback for streaming events
   * @param inputGuardrails Validate new message before processing
   * @param outputGuardrails Validate response before returning
   * @param maxSteps Optional limit on reasoning steps
   * @param contextWindowConfig Optional configuration for context pruning
   * @param context Cross-cutting concerns
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
  ): Result[AgentState] =
    streamingExecutor.continueConversationWithEvents(
      previousState,
      newUserMessage,
      onEvent,
      inputGuardrails,
      outputGuardrails,
      maxSteps,
      contextWindowConfig,
      context
    )

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
   * @param context Cross-cutting concerns
   * @return Tuple of (final state, all events)
   */
  def runCollectingEvents(
    query: String,
    tools: ToolRegistry,
    maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions(),
    context: AgentContext = AgentContext.Default
  ): Result[(AgentState, Seq[AgentEvent])] =
    streamingExecutor.runCollectingEvents(
      query,
      tools,
      maxSteps,
      systemPromptAddition,
      completionOptions,
      context
    )

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
  // Async Tool Execution with Configurable Strategy — delegates to AgentStreamingExecutor
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
   * @param systemPromptAddition Optional additional text to append to the default system prompt
   * @param completionOptions Optional completion options for LLM calls
   * @param context Cross-cutting concerns
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
    streamingExecutor.runWithStrategy(
      query,
      tools,
      toolExecutionStrategy,
      inputGuardrails,
      outputGuardrails,
      handoffs,
      maxSteps,
      systemPromptAddition,
      completionOptions,
      context
    )

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
   * Continue a conversation with a configurable tool execution strategy.
   *
   * @param previousState The previous agent state (must be Complete or Failed)
   * @param newUserMessage The new user message to process
   * @param toolExecutionStrategy Strategy for executing multiple tool calls
   * @param inputGuardrails Validate new message before processing
   * @param outputGuardrails Validate response before returning
   * @param maxSteps Optional limit on reasoning steps for this turn
   * @param contextWindowConfig Optional configuration for automatic context pruning
   * @param context Cross-cutting concerns
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
  )(implicit ec: ExecutionContext): Result[AgentState] =
    streamingExecutor.continueConversationWithStrategy(
      previousState,
      newUserMessage,
      toolExecutionStrategy,
      inputGuardrails,
      outputGuardrails,
      maxSteps,
      contextWindowConfig,
      context
    )

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
