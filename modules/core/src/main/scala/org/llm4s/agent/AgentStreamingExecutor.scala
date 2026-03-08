package org.llm4s.agent

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.toolapi.{ ToolExecutionStrategy, ToolRegistry }
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }

/**
 * Streaming and strategy-based agent execution variants.
 *
 * Extracts the `runWithEvents` / `runWithStrategy` family from [[Agent]] so
 * the core orchestration loop in [[Agent]] stays focused on the
 * `InProgress → WaitingForTools → Complete` state machine.
 *
 * == Why streaming and batch share the same [[AgentState]] machine ==
 *
 * Whether or not an `onEvent` callback is attached, the agent transitions
 * through identical status values (`InProgress`, `WaitingForTools`, `Complete`,
 * `Failed`, `HandoffRequested`).  Using the same state machine for both paths
 * guarantees that observable state transitions are identical — a caller that
 * switches from `run` to `runWithEvents` sees the same sequence of states and
 * the same final result.
 *
 * == Why `runCollectingEvents` exists alongside `runWithEvents` ==
 *
 * `runWithEvents` is designed for real-time UIs that need each event as it
 * occurs.  `runCollectingEvents` is designed for '''testing and logging''': the
 * caller only cares about the final state and the complete event sequence, not
 * about real-time delivery.  Collecting all events into a `Seq` also makes
 * assertions in tests much easier than inspecting a mutable buffer.
 *
 * @param client         The LLM client forwarded from the owning [[Agent]].
 * @param runStep        Reference to [[Agent.runStep]] so the strategy loop can
 *                       reuse the core state-machine step without duplicating it.
 * @param initializeSafe Reference to [[Agent.initializeSafe]] so this executor
 *                       can prepare the initial state without holding a reference
 *                       to the owning [[Agent]] instance.
 */
final private[agent] class AgentStreamingExecutor(
  client: LLMClient,
  runStep: (AgentState, AgentContext) => Result[AgentState],
  initializeSafe: (String, ToolRegistry, Seq[Handoff], Option[String], CompletionOptions) => Result[AgentState]
) {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Best-effort tracing — failures must never affect agent control flow. */
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

  /** Accumulates token usage from a completion into the agent state. */
  private def accumulateUsage(state: AgentState, completion: Completion): AgentState =
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

  // ============================================================
  // Streaming Event-based Execution
  // ============================================================

  /**
   * Runs the agent with streaming events for real-time progress tracking.
   *
   * Pipeline:
   * 1. Input guardrail events emitted and guardrails evaluated.
   * 2. [[AgentEvent.AgentStarted]] emitted.
   * 3. State initialised via [[initializeSafe]].
   * 4. Streaming execution loop runs via [[runWithEventsInternal]].
   * 5. Output guardrail events emitted and guardrails evaluated.
   *
   * @param query               User message to process.
   * @param tools               Available tools.
   * @param onEvent             Callback invoked for each event.
   * @param inputGuardrails     Applied to `query`; default none.
   * @param outputGuardrails    Applied to the final assistant message; default none.
   * @param handoffs            Agents to delegate to; default none.
   * @param maxSteps            Step cap; default [[Agent.DefaultMaxSteps]].
   * @param systemPromptAddition Text appended to the built-in system prompt.
   * @param completionOptions   LLM parameters forwarded on every call.
   * @param context             Cross-cutting concerns.
   * @return `Right(state)` on success; `Left` on guardrail or LLM failure.
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

    inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailStarted(g.name, Instant.now())))

    val inputValidationResult = GuardrailApplicator.validateInput(query, inputGuardrails)

    inputValidationResult match {
      case Right(_) =>
        inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailCompleted(g.name, passed = true, Instant.now())))
      case Left(_) =>
        inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailCompleted(g.name, passed = false, Instant.now())))
    }

    inputValidationResult.flatMap { validatedQuery =>
      onEvent(AgentEvent.agentStarted(validatedQuery, tools.tools.size))

      initializeSafe(validatedQuery, tools, handoffs, systemPromptAddition, completionOptions).flatMap { initialState =>
        runWithEventsInternal(
          initialState,
          onEvent,
          maxSteps,
          0,
          startTime,
          context
        ).flatMap { finalState =>
          outputGuardrails.foreach(g => onEvent(AgentEvent.OutputGuardrailStarted(g.name, Instant.now())))

          val outputValidationResult = GuardrailApplicator.validateOutput(finalState, outputGuardrails)

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
   *
   * Drives the state machine step by step, emitting events at each transition.
   * Uses `client.streamComplete` so text tokens are forwarded to `onEvent` as
   * [[AgentEvent.TextDelta]] in real time.
   *
   * @param state       Current agent state.
   * @param onEvent     Callback invoked for each event.
   * @param maxSteps    Maximum steps remaining.
   * @param currentStep Current step counter (0-based).
   * @param startTime   Wall-clock start time in milliseconds.
   * @param context     Cross-cutting concerns.
   * @return `Right(state)` on success; `Left` on LLM failure.
   */
  def runWithEventsInternal(
    state: AgentState,
    onEvent: AgentEvent => Unit,
    maxSteps: Option[Int],
    currentStep: Int,
    startTime: Long,
    context: AgentContext
  ): Result[AgentState] = {

    val stepLimitReached = maxSteps.exists(max => currentStep >= max)
    if (stepLimitReached && (state.status == AgentStatus.InProgress || state.status == AgentStatus.WaitingForTools)) {
      val failedState = state.withStatus(AgentStatus.Failed("Maximum step limit reached"))
      onEvent(
        AgentEvent.agentFailed(
          org.llm4s.error.ProcessingError("agent-execution", "Maximum step limit reached"),
          Some(currentStep)
        )
      )
      context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(failedState, path))
      return Right(failedState)
    }

    state.status match {
      case AgentStatus.InProgress =>
        onEvent(AgentEvent.stepStarted(currentStep))

        val options     = state.completionOptions.copy(tools = state.tools.tools)
        val accumulator = StreamingAccumulator.create()

        val streamResult = client.streamComplete(
          state.toApiConversation,
          options,
          onChunk = { chunk =>
            chunk.content.foreach(delta => onEvent(AgentEvent.textDelta(delta)))
            accumulator.addChunk(chunk)
          }
        )

        streamResult match {
          case Right(completion) =>
            val stateWithUsage = accumulateUsage(state, completion)

            if (completion.content.nonEmpty) {
              onEvent(AgentEvent.textComplete(completion.content))
            }

            val updatedState = stateWithUsage
              .log(s"[assistant] text: ${completion.content}")
              .addMessage(completion.message)

            safeTrace(context.tracing)(tracer => tracer.traceCompletion(completion, completion.model))
            completion.usage.foreach { usage =>
              safeTrace(context.tracing) { tracer =>
                tracer.traceTokenUsage(usage, completion.model, "agent_stream_completion")
              }
            }

            completion.message.toolCalls match {
              case Seq() =>
                val totalDuration = System.currentTimeMillis() - startTime
                onEvent(AgentEvent.stepCompleted(currentStep, hasToolCalls = false))

                val finalState = updatedState.withStatus(AgentStatus.Complete)
                onEvent(AgentEvent.agentCompleted(finalState, currentStep + 1, totalDuration))

                context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(finalState, path))
                safeTrace(context.tracing)(_.traceAgentState(finalState))
                Right(finalState)

              case toolCalls =>
                onEvent(AgentEvent.stepCompleted(currentStep, hasToolCalls = true))

                val stateAfterTools = ToolProcessor.processToolCallsWithEvents(
                  updatedState.withStatus(AgentStatus.WaitingForTools),
                  toolCalls,
                  onEvent,
                  context
                )

                HandoffExecutor.detectHandoff(stateAfterTools) match {
                  case Some((handoff, reason)) =>
                    onEvent(
                      AgentEvent.HandoffStarted(
                        handoff.handoffName,
                        Some(reason),
                        handoff.preserveContext,
                        Instant.now()
                      )
                    )
                    val handoffResult =
                      HandoffExecutor.executeHandoff(
                        stateAfterTools,
                        handoff,
                        Some(reason),
                        maxSteps.map(_ - currentStep),
                        context
                      )
                    onEvent(AgentEvent.HandoffCompleted(handoff.handoffName, handoffResult.isRight, Instant.now()))
                    handoffResult

                  case None =>
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
            safeTrace(context.tracing) { tracer =>
              tracer.traceError(new RuntimeException(error.message), "agent_stream_completion")
            }
            Left(error)
        }

      case AgentStatus.Complete | AgentStatus.Failed(_) =>
        Right(state)

      case AgentStatus.WaitingForTools =>
        Right(state)

      case AgentStatus.HandoffRequested(handoff, reason) =>
        onEvent(AgentEvent.HandoffStarted(handoff.handoffName, reason, handoff.preserveContext, Instant.now()))
        val handoffResult =
          HandoffExecutor.executeHandoff(state, handoff, reason, maxSteps.map(_ - currentStep), context)
        onEvent(AgentEvent.HandoffCompleted(handoff.handoffName, handoffResult.isRight, Instant.now()))
        handoffResult
    }
  }

  /**
   * Continue a conversation with streaming events.
   *
   * @param previousState  Previous agent state (must be `Complete` or `Failed`).
   * @param newUserMessage Follow-up user message.
   * @param onEvent        Callback invoked for each event.
   * @param inputGuardrails  Applied to `newUserMessage`.
   * @param outputGuardrails Applied to the final assistant message.
   * @param maxSteps         Step cap for this turn.
   * @param contextWindowConfig When set, prunes oldest messages before running.
   * @param context          Cross-cutting concerns.
   * @return `Right(state)` on success; `Left` on validation or LLM failure.
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

    inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailStarted(g.name, Instant.now())))

    val inputValidationResult = GuardrailApplicator.validateInput(newUserMessage, inputGuardrails)

    inputValidationResult match {
      case Right(_) =>
        inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailCompleted(g.name, passed = true, Instant.now())))
      case Left(_) =>
        inputGuardrails.foreach(g => onEvent(AgentEvent.InputGuardrailCompleted(g.name, passed = false, Instant.now())))
    }

    inputValidationResult.flatMap { validatedMessage =>
      val stateResult: Result[AgentState] = previousState.status match {
        case AgentStatus.Complete | AgentStatus.Failed(_) =>
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
        outputGuardrails.foreach(g => onEvent(AgentEvent.OutputGuardrailStarted(g.name, Instant.now())))

        val outputValidationResult = GuardrailApplicator.validateOutput(finalState, outputGuardrails)

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
   * Runs the agent and collects all emitted events into a sequence.
   *
   * Convenience method for testing and logging use cases where the caller
   * needs the complete event sequence after the run completes.
   *
   * @param query               User message to process.
   * @param tools               Available tools.
   * @param maxSteps            Step cap.
   * @param systemPromptAddition Text appended to the built-in system prompt.
   * @param completionOptions   LLM parameters.
   * @param context             Cross-cutting concerns.
   * @return `Right((state, events))` on success; `Left` on failure.
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

  // ============================================================
  // Async Tool Execution with Configurable Strategy
  // ============================================================

  /**
   * Runs the agent with a configurable tool execution strategy.
   *
   * @param query                 User message to process.
   * @param tools                 Available tools.
   * @param toolExecutionStrategy Strategy for executing multiple tool calls.
   * @param inputGuardrails       Applied to `query`; default none.
   * @param outputGuardrails      Applied to the final assistant message; default none.
   * @param handoffs              Agents to delegate to; default none.
   * @param maxSteps              Step cap.
   * @param systemPromptAddition  Text appended to the built-in system prompt.
   * @param completionOptions     LLM parameters.
   * @param context               Cross-cutting concerns.
   * @param ec                    ExecutionContext for async operations.
   * @return `Right(state)` on success; `Left` on guardrail or LLM failure.
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
      validatedQuery <- GuardrailApplicator.validateInput(query, inputGuardrails)

      _ = if (context.debug) {
        logger.info("[DEBUG] Initializing agent with tool execution strategy: {}", toolExecutionStrategy)
        logger.info("[DEBUG] Query: {}", validatedQuery)
        logger.info("[DEBUG] Tools: {}", tools.tools.map(_.name).mkString(", "))
      }
      initialState <- initializeSafe(validatedQuery, tools, handoffs, systemPromptAddition, completionOptions)
      finalState <- runWithStrategyInternal(
        initialState,
        strategy = toolExecutionStrategy,
        maxSteps = maxSteps,
        context = context
      )

      validatedState <- GuardrailApplicator.validateOutput(finalState, outputGuardrails)
    } yield validatedState

  /**
   * Internal strategy-based execution loop.
   *
   * Drives the state machine using [[runStep]] for LLM calls and
   * [[ToolProcessor.processToolCallsAsync]] for parallel tool execution.
   *
   * @param initialState Initial (or current) agent state.
   * @param strategy     Tool execution strategy.
   * @param maxSteps     Maximum steps remaining.
   * @param context      Cross-cutting concerns.
   * @param ec           ExecutionContext for async tool execution.
   * @return `Right(state)` on success; `Left` on LLM failure.
   */
  def runWithStrategyInternal(
    initialState: AgentState,
    strategy: ToolExecutionStrategy,
    maxSteps: Option[Int],
    context: AgentContext
  )(implicit ec: ExecutionContext): Result[AgentState] = {
    if (context.debug) {
      logger.info("[DEBUG] Starting runWithStrategy")
      logger.info("[DEBUG] Strategy: {}", strategy)
      logger.info("[DEBUG] Max steps: {}", maxSteps.getOrElse("unlimited"))
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
            logger.warn("[DEBUG] Step limit reached!")
          }
          val updatedState =
            state.log("[system] Step limit reached").withStatus(AgentStatus.Failed("Maximum step limit reached"))
          context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(updatedState, path))
          Right(updatedState)

        case (AgentStatus.InProgress, _) =>
          if (context.debug) {
            logger.info("[DEBUG] ITERATION {}: InProgress -> requesting LLM completion", iteration)
          }

          runStep(state, context) match {
            case Right(newState) =>
              val shouldDecrement = newState.status == AgentStatus.WaitingForTools
              val updatedSteps    = if (shouldDecrement) stepsRemaining.map(_ - 1) else stepsRemaining
              context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(newState, path))
              safeTrace(context.tracing)(_.traceAgentState(newState))
              runUntilCompletion(newState, updatedSteps, iteration + 1)

            case Left(error) =>
              if (context.debug) {
                logger.error("[DEBUG] LLM completion failed: {}", error.message)
              }
              Left(error)
          }

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
              }

              Try {
                ToolProcessor.processToolCallsAsync(
                  state.log(s"[tools] executing ${assistantMessage.toolCalls.size} tools ($toolNames) with $strategy"),
                  assistantMessage.toolCalls,
                  strategy,
                  context
                )
              } match {
                case Success(newState) =>
                  HandoffExecutor.detectHandoff(newState) match {
                    case Some((handoff, reason)) =>
                      if (context.debug) {
                        logger.info("[DEBUG] Handoff detected: {}", handoff.handoffName)
                      }
                      Right(newState.withStatus(AgentStatus.HandoffRequested(handoff, Some(reason))))

                    case None =>
                      if (context.debug) {
                        logger.info("[DEBUG] Tools processed -> InProgress")
                      }
                      context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(newState, path))
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
          context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(state, path))
          HandoffExecutor.executeHandoff(state, handoff, reason, maxSteps, context)

        case (_, _) =>
          if (context.debug) {
            logger.info("[DEBUG] Agent completed with status: {}", state.status)
          }
          context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(state, path))
          Right(state)
      }

    runUntilCompletion(initialState)
  }

  /**
   * Continue a conversation with a configurable tool execution strategy.
   *
   * @param previousState         Previous agent state (must be `Complete` or `Failed`).
   * @param newUserMessage        Follow-up user message.
   * @param toolExecutionStrategy Strategy for executing multiple tool calls.
   * @param inputGuardrails       Applied to `newUserMessage`.
   * @param outputGuardrails      Applied to the final assistant message.
   * @param maxSteps              Step cap for this turn.
   * @param contextWindowConfig   When set, prunes oldest messages before running.
   * @param context               Cross-cutting concerns.
   * @param ec                    ExecutionContext for async operations.
   * @return `Right(state)` on success; `Left` on validation or LLM failure.
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
      validatedMessage <- GuardrailApplicator.validateInput(newUserMessage, inputGuardrails)

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

      validatedState <- GuardrailApplicator.validateOutput(finalState, outputGuardrails)
    } yield validatedState
  }
}
