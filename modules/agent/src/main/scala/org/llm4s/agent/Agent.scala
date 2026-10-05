package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  GuardrailMiddleware,
  MiddlewareId,
  ToolCallRequest as MiddlewareToolCall
}
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome, ToolSet }
import org.llm4s.agent.graph.toolloop.*
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.{ LLMError, UnknownError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ ToolCallRequest, ToolExecutionConfig, ToolExecutionStrategy, ToolRegistry }
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.concurrent.ExecutionContext

/**
 * Cross-cutting concerns for one agent run: tracing, debug logging, the trace file, and how tool calls execute.
 *
 * @param tracing Optional tracing backend; trace failures never affect the run.
 * @param debug Enables verbose debug logging (conversation size, tool and handoff decisions).
 * @param traceLogPath Path to write a markdown execution trace after each turn; useful for post-run inspection
 *                     without a full tracing backend.
 * @param toolExecutionConfig Optional per-tool timeout and retry; when None, tools run with no timeout and no retry
 *                            (default).
 * @param toolExecutionStrategy How the tool calls of one model reply run: `Sequential` one at a time (default and
 *                              safest), `Parallel` together, `ParallelWithLimit(n)` at most `n` at once.
 */
case class AgentContext(
  tracing: Option[Tracing] = None,
  debug: Boolean = false,
  traceLogPath: Option[String] = None,
  toolExecutionConfig: Option[ToolExecutionConfig] = None,
  toolExecutionStrategy: ToolExecutionStrategy = ToolExecutionStrategy.Sequential
)

object AgentContext {

  /** No-op defaults: no tracing, debug logging off, trace log not written, tools one at a time. */
  val Default: AgentContext = AgentContext()
}

/**
 * Orchestrates LLM interactions with tool calling, on the graph runtime ([[org.llm4s.agent.graph.GraphRuntime]]).
 *
 * Each turn is a run of a [[org.llm4s.agent.graph.toolloop.ToolLoop]] on its own durable thread: the model is called,
 * its tool calls run as parallel tasks behind a barrier, their results go back to the model, and the loop ends on a
 * final answer. Guardrails run as middleware at the run's boundaries; a guardrail Block ends the turn with the
 * guardrail's own error and leaves nothing of the blocked turn behind.
 *
 * The conversation is an [[AgentThread]], plain data. [[run]] returns one; [[continueConversation]] takes one back,
 * seeds a thread from it and runs the next turn, in this `Agent` or any other; [[AgentThread.saveToFile]] and
 * [[AgentThread.loadFromFile]] persist it. What cannot be data - tools, handoffs, guardrails, middleware - is supplied
 * again on each call.
 *
 * == Basic Usage ==
 * {{{
 * for {
 *   providerConfig <- Llm4sConfig.defaultProvider()
 *   client <- LLMConnect.getClient(providerConfig)
 *   agent = new Agent(client)
 *   tools = new ToolRegistry(Seq(myTool))
 *   thread <- agent.run("What is 2+2?", tools)
 * } yield thread.answer
 * }}}
 *
 * == Multi-turn ==
 * {{{
 * for {
 *   first  <- agent.run("What's the weather in Paris?", tools)
 *   second <- agent.continueConversation(first, "And in London?", tools)
 * } yield second
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
 * == Security ==
 * By default a turn makes at most [[Agent.DefaultMaxSteps]] model calls, so a model that keeps requesting tools cannot
 * loop forever. Override with `maxSteps`; `None` removes the cap.
 *
 * == Durability ==
 * Every turn is checkpointed in `runtime`. The default is a new in-memory runtime per `Agent`, which keeps every turn's
 * thread for the life of the `Agent`; pass a runtime over your own [[org.llm4s.agent.graph.Checkpointer]] to bound or
 * persist them. A turn that suspends on an approval, or fails with a recoverable error, continues with [[resume]] and
 * [[recover]].
 *
 * @param client The LLM client for making completion requests
 * @param runtime The graph runtime the turns run on; defaults to a new in-memory one
 * @see [[AgentThread]] for the conversation as data
 * @see [[Handoff]] for agent-to-agent delegation
 */
class Agent(client: LLMClient, runtime: GraphRuntime = GraphRuntime.inMemory()) {

  import Agent.Setup

  private val logger = LoggerFactory.getLogger(getClass)

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
              logger.debug(s"Tracing failed: $msg", cause)
            case _ =>
              logger.debug("Tracing failed: {}", error)
          }
        case Right(_) =>
          ()
      }
    }

  // ------------------------------------------------------------------------------------------------------------------
  // The model step and the tool pipeline
  // ------------------------------------------------------------------------------------------------------------------

  /** The loop's model call: the client, with the system message injected and every completion traced. */
  final private class AgentModel(setup: Setup) extends ModelStep {
    def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] =
      complete(messages, tools).map(_.message)

    override def complete(messages: Vector[Message], tools: ToolSet): Result[ModelReply] = {
      val context = setup.context
      if (context.debug) {
        logger.debug("Running completion step with tools: {}", tools.tools.map(_.spec.name).mkString(", "))
        logger.debug("Conversation history: {} messages", messages.size)
      }
      client.complete(
        Conversation(setup.systemMessage.toSeq ++ messages),
        setup.completionOptions.withTools(tools.toolFunctions)
      ) match {
        case Right(completion) =>
          safeTrace(context.tracing)(_.traceCompletion(completion, completion.model))
          completion.usage.foreach { usage =>
            safeTrace(context.tracing)(_.traceTokenUsage(usage, completion.model, "agent_completion"))
          }
          Right(ModelReply(completion.message, completion.model, completion.usage, completion.estimatedCost))
        case Left(error) =>
          safeTrace(context.tracing)(_.traceError(new RuntimeException(error.message), "agent_completion"))
          Left(error)
      }
    }
  }

  /** Traces every tool call's input and result, as the tool pipeline has always done. */
  final private class ToolTracing(tracing: Tracing) extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("agent-tool-tracing")

    override def wrapToolCall(request: MiddlewareToolCall, context: ToolContext)(
      next: () => ToolOutcome
    ): ToolOutcome = {
      val outcome = next()
      val result = outcome match {
        case ToolOutcome.Success(ujson.Str(text), _) => text
        case ToolOutcome.Success(content, _)         => content.render()
        case ToolOutcome.Error(message, _)           => message
        case ToolOutcome.NeedsApproval(reason)       => s"needs approval: $reason"
        case ToolOutcome.Ask(_)                      => "asked a question"
        case ToolOutcome.Fatal(error)                => error.message
      }
      safeTrace(Some(tracing))(_.traceToolCall(request.call.name, request.call.arguments.render(), result))
      outcome
    }
  }

  /** The registry's tools as agent tools, each call going through the registry with the context's timeout and retry. */
  private def registryTools(registry: ToolRegistry, context: AgentContext): Vector[AgentTool[ujson.Value]] = {
    given ExecutionContext = ExecutionContext.global
    val config             = context.toolExecutionConfig.getOrElse(ToolExecutionConfig())
    registry.tools.toVector.map { function =>
      AgentTool.fromToolFunction(function, args => registry.execute(ToolCallRequest(function.name, args), config))
    }
  }

  /** The tool loop for `setup`: registry tools, handoff tools, typed tools, and guardrails and tracing as middleware. */
  private def loopFor(setup: Setup): Result[ToolLoop] = {
    val fromRegistry = registryTools(setup.tools, setup.context)
    val registered   = (fromRegistry.map(_.spec.name) ++ setup.agentTools.map(_.spec.name)).toSet
    for {
      handoffTools <- HandoffTools.create(setup.handoffs, registered)
      toolSet      <- ToolSet.of((fromRegistry ++ handoffTools ++ setup.agentTools)*)
      guardrails = Option.when(setup.inputGuardrails.nonEmpty || setup.outputGuardrails.nonEmpty)(
        new GuardrailMiddleware(setup.inputGuardrails, setup.outputGuardrails)
      )
      tracing = setup.context.tracing.map(new ToolTracing(_))
      loop <- ToolLoop.build(
        Agent.LoopId,
        Agent.LoopVersion,
        new AgentModel(setup),
        toolSet,
        guardrails.toSeq ++ tracing.toSeq ++ setup.middleware
      )
    } yield loop
  }

  private def runConfig(setup: Setup, withInput: Boolean = true): RunConfig = {
    val concurrency = setup.context.toolExecutionStrategy match {
      case ToolExecutionStrategy.Sequential               => 1
      case ToolExecutionStrategy.Parallel                 => Agent.ParallelConcurrency
      case ToolExecutionStrategy.ParallelWithLimit(limit) => math.max(1, limit)
    }
    // `maxSteps` is a number of model calls. The loop spends one superstep on its input and three on each model call
    // that asks for tools (the call, the tools, the barrier), so the next model call is the first superstep past
    // `1 + 3 * maxSteps`: the call that would exceed the cap is the one refused. A recovered or resumed run has no
    // input superstep left to spend, so its budget is `3 * maxSteps`.
    val supersteps = setup.maxSteps.fold(Int.MaxValue)(n => math.max(1, (if (withInput) 1 else 0) + 3 * n))
    RunConfig().withBudgets(RunBudgets(maxSupersteps = supersteps, maxConcurrency = concurrency))
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Turns
  // ------------------------------------------------------------------------------------------------------------------

  private def seedUpdate(history: Vector[Message], usage: UsageSummary): StateUpdate = {
    val messages = history.zipWithIndex.foldLeft(StateUpdate.empty) { case (update, (message, index)) =>
      update.combine(StateUpdate.update(Messages.key, MessageUpdate.Append(StoredMessage(s"seed/$index", message))))
    }
    if (usage == UsageSummary()) messages else messages.combine(StateUpdate.update(ToolLoop.usage, usage))
  }

  /**
   * One turn over `history`: a new thread, seeded with `history` and its usage, started with `text` (an empty text
   * continues from the history with no new user message), awaited and turned into an [[AgentThread]].
   */
  private def execute(
    setup: Setup,
    history: Vector[Message],
    usage: UsageSummary,
    text: String
  ): Result[AgentThread] =
    for {
      loop <- loopFor(setup)
      threadId = ThreadId(UUID.randomUUID().toString)
      _ <-
        if (history.isEmpty && usage == UsageSummary()) Right(())
        else runtime.seed(threadId, loop.graph, seedUpdate(history, usage))
      handle <- runtime.start(threadId, loop.graph, text, runConfig(setup))
      result <- awaitRun(handle)
      thread <- settle(setup, threadId, result)
    } yield thread

  /**
   * Waits for the run. A caller interrupted while it waits - a cancelled fiber, a timed-out future - cancels the run too,
   * which interrupts the model call in flight, and returns once the run has stopped, with its interrupt flag set again:
   * the run does not outlive its caller.
   */
  private def awaitRun[O](handle: RunHandle[O]): Result[RunResult[O]] = {
    val result = handle.await()
    if (Thread.currentThread().isInterrupted) {
      handle.cancel()
      Thread.interrupted()
      handle.await()
      Thread.currentThread().interrupt()
    }
    result
  }

  /** What a run's result means for the conversation: a thread, a handoff run, or the run's error. */
  private def settle(setup: Setup, threadId: ThreadId, result: RunResult[String]): Result[AgentThread] = {
    val outcome: Result[AgentThread] = result match {
      case RunResult.Completed(state, _, _) =>
        for {
          thread <- threadOf(setup, threadId, state, ThreadStatus.Completed)
          asked  <- state.get(ToolLoop.handoff)
          done   <- asked.fold[Result[AgentThread]](Right(thread))(handoff(setup, thread, _))
        } yield done

      case RunResult.Failed(state, GraphError.SuperstepLimitExceeded(_)) =>
        // the run stopped before a model call it was not allowed: the conversation, minus an unanswered tool request
        threadOf(setup, threadId, state, ThreadStatus.Failed(Agent.StepLimitMessage)).map(withoutUnansweredToolCalls)

      case RunResult.Failed(_, error) =>
        Left(surface(error))

      case RunResult.Suspended(state, interrupts, _) =>
        val on = interrupts.map(i => SuspendedOn(i.id.value, i.resumeNode.value, i.question))
        threadOf(setup, threadId, state, ThreadStatus.Suspended(on))
    }
    outcome.map { thread =>
      safeTrace(setup.context.tracing)(_.traceEvent(thread.toTraceEvent))
      setup.context.traceLogPath.foreach(path => AgentTraceFormatter.writeTraceLog(thread, path))
      thread
    }
  }

  /** The run's state as a thread; the loop's usage key already holds the conversation's whole usage. */
  private def threadOf(
    setup: Setup,
    threadId: ThreadId,
    state: ThreadState,
    status: ThreadStatus
  ): Result[AgentThread] =
    for {
      stored <- state.get(Messages.key)
      usage  <- state.get(ToolLoop.usage)
    } yield AgentThread(
      threadId = threadId.value,
      messages = stored.map(_.message),
      systemMessage = setup.systemMessage,
      completionOptions = setup.completionOptions,
      usage = usage,
      status = status
    )

  /**
   * A thread cut off by the step limit may end on an assistant message whose tool calls were never run; a conversation
   * that ends there cannot continue (a model refuses it), so that message goes.
   */
  private def withoutUnansweredToolCalls(thread: AgentThread): AgentThread =
    thread.messages.lastOption match {
      case Some(assistant: AssistantMessage) if assistant.toolCalls.nonEmpty =>
        thread.withMessages(thread.messages.init)
      case _ => thread
    }

  /**
   * The error a run failed with, as the caller should see it: the error a node returned (the model's, say) rather
   * than the `NodeFailed` the runtime wraps it in.
   */
  private def surface(error: LLMError): LLMError = error match {
    case GraphError.NodeFailed(_, _, cause) => cause
    case other                              => other
  }

  /**
   * Runs the handoff a tool asked for: the target agent, with no tools of its own, answers the conversation
   * transferred to it ([[Handoff.preserveContext]], [[Handoff.transferSystemMessage]]). The handoff is found by its
   * stable id among the handoffs this call was given. The result is the target's thread with the source's usage merged.
   */
  private def handoff(setup: Setup, source: AgentThread, asked: HandoffRequest): Result[AgentThread] =
    setup.handoffs.find(_.id == asked.handoffId) match {
      case None =>
        Left(ValidationError("handoff", s"The model asked for handoff '${asked.handoffId}', which was not offered"))
      case Some(handoff) =>
        if (setup.context.debug) {
          logger.debug("Handoff requested: {} ({})", handoff.handoffName, asked.reason)
          logger.debug("preserveContext: {}", handoff.preserveContext)
          logger.debug("transferSystemMessage: {}", handoff.transferSystemMessage)
        } else {
          logger.info("Handoff requested: {}", handoff.handoffName)
        }
        val transferred =
          if (handoff.preserveContext) source.messages
          else source.messages.findLast(_.role == MessageRole.User).toVector
        val target = Setup(
          tools = ToolRegistry.empty,
          agentTools = Seq.empty,
          middleware = Seq.empty,
          inputGuardrails = Seq.empty,
          outputGuardrails = Seq.empty,
          handoffs = Seq.empty,
          maxSteps = setup.maxSteps,
          systemMessage = if (handoff.transferSystemMessage) source.systemMessage else None,
          completionOptions = CompletionOptions(),
          context = setup.context
        )
        handoff.targetAgent
          .execute(target, transferred, UsageSummary(), "")
          .map(done => done.withUsage(source.usage.merge(done.usage)))
    }

  // ------------------------------------------------------------------------------------------------------------------
  // Public API
  // ------------------------------------------------------------------------------------------------------------------

  /**
   * Runs a new query to completion, failure, or the step limit.
   *
   * The pipeline: input guardrails run on `query` before any model call; the model and its tools run until a final
   * answer, a handoff, a suspension or the step limit; output guardrails run on the final answer, and may change it.
   *
   * @param query               The user message to process.
   * @param tools               Tools the LLM may invoke during this run.
   * @param inputGuardrails     Applied to `query` before any LLM call; default none.
   * @param outputGuardrails    Applied to the final assistant message; default none.
   * @param handoffs            Agents to delegate to; each becomes a callable tool.
   * @param maxSteps            Maximum number of model calls; defaults to [[Agent.DefaultMaxSteps]]. Pass `None` to
   *                            remove the cap (use with caution in production).
   * @param systemPromptAddition Text appended to the built-in system prompt.
   * @param completionOptions   LLM parameters forwarded on every call.
   * @param context             Tracing, debug logging, trace file path, and tool execution.
   * @param middleware          Further middleware, after the guardrails and tracing: approval, retry, policy.
   * @param agentTools          Typed tools, offered alongside `tools`.
   * @return `Right(thread)` when the turn completes, runs out of steps (`Failed`) or suspends; `Left` on a guardrail
   *         Block (the guardrail's own error), a handoff or tool configuration error, or an error from the model -
   *         exactly the error the client returned.
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
    context: AgentContext = AgentContext.Default,
    middleware: Seq[AgentMiddleware] = Seq.empty,
    agentTools: Seq[AgentTool[?]] = Seq.empty
  ): Result[AgentThread] = {
    if (context.debug) {
      logger.debug("Starting Agent.run: {} tools, {} handoffs", tools.tools.size, handoffs.length)
    }
    val setup = Setup(
      tools,
      agentTools,
      middleware,
      inputGuardrails,
      outputGuardrails,
      handoffs,
      maxSteps,
      Some(Agent.systemMessage(systemPromptAddition)),
      completionOptions,
      context
    )
    execute(setup, Vector.empty, UsageSummary(), query)
  }

  /**
   * Appends a new user message to a conversation and runs the agent to completion.
   *
   * The turn runs on a new thread seeded from `previous`, so the model sees the whole conversation, with the system
   * message, completion options and usage `previous` carries. When `contextWindowConfig` is supplied, the history is
   * pruned before the model call. Tools, handoffs, guardrails and middleware are supplied again: a thread holds none.
   *
   * `previous` must be `Completed` or `Failed`. A thread that is `Suspended` is not finished: a `ValidationError` comes
   * back at once, without running the agent (answer it with [[resume]]).
   *
   * @param previous            The thread returned by an earlier call, or loaded from its JSON.
   * @param newUserMessage      The follow-up message to process.
   * @param tools               Tools for this turn.
   * @param inputGuardrails     Applied to `newUserMessage`; default none.
   * @param outputGuardrails    Applied to the final assistant message; default none.
   * @param handoffs            Agents to delegate to this turn.
   * @param maxSteps            Maximum number of model calls for this turn; `None` for unlimited.
   * @param contextWindowConfig When set, prunes the history to keep the conversation within the model's token budget.
   * @param context             Tracing, debug logging, trace file path, and tool execution.
   * @param middleware          Further middleware.
   * @param agentTools          Typed tools, offered alongside `tools`.
   * @return `Right(thread)` on success; `Left(ValidationError)` when `previous` is suspended, or `Left` on a guardrail
   *         Block or an error from the model.
   */
  def continueConversation(
    previous: AgentThread,
    newUserMessage: String,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    handoffs: Seq[Handoff] = Seq.empty,
    maxSteps: Option[Int] = None,
    contextWindowConfig: Option[ContextWindowConfig] = None,
    context: AgentContext = AgentContext.Default,
    middleware: Seq[AgentMiddleware] = Seq.empty,
    agentTools: Seq[AgentTool[?]] = Seq.empty
  ): Result[AgentThread] =
    if (!previous.isFinished) {
      Left(
        ValidationError.invalid(
          "agentThread",
          "Cannot continue from an incomplete conversation. " +
            "Previous thread must be Completed or Failed. " +
            s"Current status: ${previous.status}"
        )
      )
    } else if (newUserMessage.isEmpty) {
      Left(ValidationError.invalid("newUserMessage", "A follow-up message must not be empty"))
    } else {
      val base = contextWindowConfig.fold(previous)(previous.pruned(_))
      val setup = Setup(
        tools,
        agentTools,
        middleware,
        inputGuardrails,
        outputGuardrails,
        handoffs,
        maxSteps,
        previous.systemMessage,
        previous.completionOptions,
        context
      )
      execute(setup, base.messages, previous.usage, newUserMessage)
    }

  /**
   * Runs multiple conversation turns sequentially: the first with [[run]], each follow-up with
   * [[continueConversation]]. It stops at the first turn that fails.
   *
   * @param initialQuery        The first user message
   * @param followUpQueries     Additional user messages to process in sequence
   * @param tools               Tools for every turn
   * @param maxStepsPerTurn     Model-call limit per turn (default: [[Agent.DefaultMaxSteps]]); `None` for unlimited
   * @param systemPromptAddition Optional system prompt addition
   * @param completionOptions   Completion options
   * @param contextWindowConfig Optional configuration for automatic context pruning
   * @param context             Cross-cutting concerns
   * @return The thread after the last turn
   *
   * @example
   * {{{
   * val result = agent.runMultiTurn(
   *   initialQuery = "What's the weather in Paris?",
   *   followUpQueries = Seq("And in London?", "Which is warmer?"),
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
  ): Result[AgentThread] = {
    val firstTurn = run(
      query = initialQuery,
      tools = tools,
      maxSteps = maxStepsPerTurn,
      systemPromptAddition = systemPromptAddition,
      completionOptions = completionOptions,
      context = context
    )

    followUpQueries.foldLeft(firstTurn) { (threadResult, query) =>
      threadResult.flatMap { thread =>
        continueConversation(
          previous = thread,
          newUserMessage = query,
          tools = tools,
          maxSteps = maxStepsPerTurn,
          contextWindowConfig = contextWindowConfig,
          context = context
        )
      }
    }
  }

  /**
   * Continues a turn that was interrupted - a recoverable error, a cancellation, the step limit - from its last
   * checkpoint, in the runtime that ran it: completed tasks are not run again. The configuration must be the turn's own:
   * the same tools, handoffs, guardrails and middleware.
   *
   * @param thread The thread an interrupted turn returned; its `threadId` names the runtime thread.
   * @return The thread after the continued turn, or the runtime's refusal - a thread that finished or was never run
   *         here has nothing to recover.
   */
  def recover(
    thread: AgentThread,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    handoffs: Seq[Handoff] = Seq.empty,
    maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
    context: AgentContext = AgentContext.Default,
    middleware: Seq[AgentMiddleware] = Seq.empty,
    agentTools: Seq[AgentTool[?]] = Seq.empty
  ): Result[AgentThread] = {
    val setup = Setup(
      tools,
      agentTools,
      middleware,
      inputGuardrails,
      outputGuardrails,
      handoffs,
      maxSteps,
      thread.systemMessage,
      thread.completionOptions,
      context
    )
    for {
      loop   <- loopFor(setup)
      handle <- runtime.recover(ThreadId(thread.threadId), loop.graph, runConfig(setup, withInput = false))
      result <- awaitRun(handle)
      done   <- settle(setup, ThreadId(thread.threadId), result)
    } yield done
  }

  /**
   * Answers some of the interrupts a suspended thread is parked on - approvals, tool questions - and continues the
   * turn. Encode an approval with `upickle.default.writeJs(ApprovalDecision.Approve)`; the interrupt ids and their
   * requests are in [[AgentThread.status]] and [[AgentThread.approvals]]. Unanswered interrupts stay parked, and the
   * returned thread is `Suspended` again if nothing else can proceed. The configuration must be the turn's own.
   *
   * @param thread  The `Suspended` thread a turn returned.
   * @param answers Answers, by interrupt id.
   */
  def resume(
    thread: AgentThread,
    answers: Map[String, ujson.Value],
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    handoffs: Seq[Handoff] = Seq.empty,
    maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
    context: AgentContext = AgentContext.Default,
    middleware: Seq[AgentMiddleware] = Seq.empty,
    agentTools: Seq[AgentTool[?]] = Seq.empty
  ): Result[AgentThread] = {
    val setup = Setup(
      tools,
      agentTools,
      middleware,
      inputGuardrails,
      outputGuardrails,
      handoffs,
      maxSteps,
      thread.systemMessage,
      thread.completionOptions,
      context
    )
    for {
      loop <- loopFor(setup)
      handle <- runtime.resume(
        ThreadId(thread.threadId),
        loop.graph,
        answers.map((id, answer) => InterruptId(id) -> answer),
        runConfig(setup, withInput = false)
      )
      result <- awaitRun(handle)
      done   <- settle(setup, ThreadId(thread.threadId), result)
    } yield done
  }

  /**
   * Renders the thread as a human-readable markdown document, delegating to [[AgentTraceFormatter]].
   *
   * Intended for debugging and post-run inspection. The output format is not stable across library versions; do not
   * parse the result programmatically.
   */
  def formatThreadAsMarkdown(thread: AgentThread): String =
    AgentTraceFormatter.formatThreadAsMarkdown(thread)

  /**
   * Overwrites `traceLogPath` with the markdown-formatted thread, delegating to [[AgentTraceFormatter]].
   *
   * File-write failures are swallowed: the error is logged at ERROR level via SLF4J but is not surfaced to the caller,
   * so that tracing never affects agent control flow.
   */
  def writeTraceLog(thread: AgentThread, traceLogPath: String): Unit =
    AgentTraceFormatter.writeTraceLog(thread, traceLogPath)
}

object Agent {

  /** What a turn is configured with, apart from its conversation: everything that is live, so never part of a thread. */
  final private[agent] case class Setup(
    tools: ToolRegistry,
    agentTools: Seq[AgentTool[?]],
    middleware: Seq[AgentMiddleware],
    inputGuardrails: Seq[InputGuardrail],
    outputGuardrails: Seq[OutputGuardrail],
    handoffs: Seq[Handoff],
    maxSteps: Option[Int],
    systemMessage: Option[SystemMessage],
    completionOptions: CompletionOptions,
    context: AgentContext
  )

  /**
   * Default maximum number of model calls for a turn.
   * This prevents infinite loops when the LLM repeatedly requests tool calls.
   */
  val DefaultMaxSteps: Int = 50

  /** The status message of a turn that ran out of steps. */
  val StepLimitMessage: String = "Maximum step limit reached"

  private[agent] val LoopId: String      = "agent"
  private[agent] val LoopVersion: String = "v1"

  /** How many tool calls `ToolExecutionStrategy.Parallel` runs at once. */
  private[agent] val ParallelConcurrency: Int = 1024

  /**
   * The system message of a new conversation: step-by-step tool-use instructions, with `addition` appended when given.
   * It is held on the [[AgentThread]] and injected at every model call, never stored among the messages, so that
   * context-window pruning can never drop the system instructions.
   */
  private[agent] def systemMessage(addition: Option[String]): SystemMessage = {
    val base = """You are a helpful assistant with access to tools.
        |Follow these steps:
        |1. Analyze the user's question and determine which tools you need to use
        |2. Use tools ONE AT A TIME - make one tool call, wait for the result, then decide if you need more tools
        |3. Use the results from previous tool calls in subsequent tool calls when needed
        |4. When you have enough information, provide a helpful final answer
        |5. Think step by step and be thorough""".stripMargin

    SystemMessage(addition.fold(base)(extra => s"$base\n\n$extra"))
  }
}
