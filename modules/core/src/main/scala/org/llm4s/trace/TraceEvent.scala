package org.llm4s.trace

import org.llm4s.annotation.Stable
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.{ CompletionOptions, EmbeddingUsage, Message, ServiceTier, TokenUsage, UsageSummary }
import org.llm4s.types.ProviderModelTypes.ProviderId

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Type-safe, sealed hierarchy of trace events emitted during LLM agent execution.
 *
 * Each subtype carries the data relevant to one observable moment in the
 * agent lifecycle (completion received, tool executed, token usage recorded,
 * etc.).  All subtypes are serialisable to JSON via `toJson`, which produces a
 * flat object containing at minimum `"event_type"` and `"timestamp"` fields.
 *
 * == Which events are here ==
 * An event is a case of this hierarchy when a backend gives it a shape of its own - a span kind, the GenAI attributes,
 * a Langfuse trace - whichever module emits it. The hierarchy is the contract between the code that emits events and
 * the backends that record them, and both depend only on core: so [[TraceEvent.RAGOperationCompleted]],
 * [[TraceEvent.ImageGenerationCompleted]] and [[TraceEvent.AgentRunEnded]] are here although `llm4s-rag`,
 * `llm4s-image` and `llm4s-agent` emit them, and their fields name no type of those modules. Everything else is a
 * [[TraceEvent.CustomEvent]], which a backend records by its name and data: an application's own events, and an
 * open set such as an agent run's durable events (`graph.*`, `agent.*`), to which a graph or a middleware adds its
 * own. The Langfuse and OpenTelemetry backends report two of those, `agent.model_call_completed` and
 * `agent.tool_executed`, as `chat` and `execute_tool` spans: they recognise the name and read the event's data, a
 * convention between `llm4s-agent` and those backends that this hierarchy does not type.
 *
 * == Error truncation ==
 * `ErrorOccurred.toJson` includes only the first 5 frames of its cause's stack trace
 * to limit payload size.
 *
 * == Timestamp default ==
 * Every event's companion `apply` defaults `timestamp` to `Instant.now()` at
 * construction time.  Override explicitly when replaying historical events or
 * writing deterministic tests.
 *
 * == Growth safety ==
 * Each event is a `final case class` with a private constructor and `copy`:
 * build one with its companion `apply` and change a field with its `with*`
 * setter (an `Option` field's setter takes the value or an `Option`), so a
 * field can be added without breaking the code that builds one. Reading is
 * growth-safe only by field: match an event by its type
 * (`case e: TraceEvent.ToolExecuted => e.name`), never by position
 * (`case TraceEvent.ToolExecuted(name, _, _, _, _, _)`), because a positional
 * pattern names every field and stops compiling when one is added.
 *
 * == Identifiers ==
 * A provider is a [[org.llm4s.types.ProviderModelTypes.ProviderId ProviderId]] wherever an event names one. A model
 * is a `String`, as it is on `Completion` and in a provider's configuration; a thread, run or agent id is the
 * `String` value of a type `llm4s-agent` defines, which core cannot name; and `operation` is an open label the
 * emitter chooses.
 *
 * @see [[Tracing]] for the interface that consumes these events
 */
@Stable
sealed trait TraceEvent {
  def timestamp: Instant
  def eventType: String
  def toJson: ujson.Value

  /**
   * Where this event sits in a trace, when its emitter places it in one (design 4.58): the span it is, that span's
   * parent and when it started; the event's `timestamp` is its end. `None` for an event traced on its own. Only
   * [[TraceEvent.CustomEvent]], [[TraceEvent.TokenUsageRecorded]], [[TraceEvent.ErrorOccurred]] and
   * [[TraceEvent.AgentRunEnded]] carry one.
   */
  def span: Option[TraceSpan] = None
}

object TraceEvent {

  /**
   * A model returned a completion. A client wrapped in [[TracingLLMClient]] fills in the call's details as well, which
   * a backend that follows the OpenTelemetry GenAI conventions reports on the call's `chat <model>` span (#2275).
   *
   * @param id            the completion's id (`gen_ai.response.id`)
   * @param model         the requested model (`gen_ai.request.model`)
   * @param toolCalls     how many tool calls the completion asked for
   * @param content       the completion's text: message content, which backends record only when told to
   * @param provider      the canonical id of the provider called (`openai`, `anthropic`, ...), when known
   * @param serverAddress the host the request went to, when known
   * @param streamed      whether the completion was streamed (`streamComplete`)
   * @param duration      how long the call took; the event's `timestamp` is its end
   * @param responseModel the model that answered, when the provider reported it
   * @param finishReasons why the model stopped: the `FinishReason`'s name (`stop`, `length`, `tool_calls`, or a raw value)
   * @param usage         the call's token usage, when the provider reported it
   * @param options       the options the request was sent with
   * @param input         the request's messages: message content, which backends record only when told to; not
   *                      included in [[toJson]]
   * @param servedTier    the tier the provider reports serving the call at (`Completion.servedTier`, #2750), when it
   *                      reported one
   */
  final case class CompletionReceived private (
    id: String,
    model: String,
    toolCalls: Int,
    content: String,
    timestamp: Instant,
    provider: Option[ProviderId],
    serverAddress: Option[String],
    streamed: Boolean,
    duration: Option[FiniteDuration],
    responseModel: Option[String],
    finishReasons: Seq[String],
    usage: Option[TokenUsage],
    options: Option[CompletionOptions],
    input: Seq[Message],
    servedTier: Option[ServiceTier]
  ) extends TraceEvent {
    def withId(id: String): CompletionReceived                               = copy(id = id)
    def withModel(model: String): CompletionReceived                         = copy(model = model)
    def withToolCalls(toolCalls: Int): CompletionReceived                    = copy(toolCalls = toolCalls)
    def withContent(content: String): CompletionReceived                     = copy(content = content)
    def withTimestamp(timestamp: Instant): CompletionReceived                = copy(timestamp = timestamp)
    def withProvider(provider: ProviderId): CompletionReceived               = copy(provider = Some(provider))
    def withProvider(provider: Option[ProviderId]): CompletionReceived       = copy(provider = provider)
    def withServerAddress(serverAddress: String): CompletionReceived         = copy(serverAddress = Some(serverAddress))
    def withServerAddress(serverAddress: Option[String]): CompletionReceived = copy(serverAddress = serverAddress)
    def withStreamed(streamed: Boolean): CompletionReceived                  = copy(streamed = streamed)
    def withDuration(duration: FiniteDuration): CompletionReceived           = copy(duration = Some(duration))
    def withDuration(duration: Option[FiniteDuration]): CompletionReceived   = copy(duration = duration)
    def withResponseModel(responseModel: String): CompletionReceived         = copy(responseModel = Some(responseModel))
    def withResponseModel(responseModel: Option[String]): CompletionReceived = copy(responseModel = responseModel)
    def withFinishReasons(finishReasons: Seq[String]): CompletionReceived    = copy(finishReasons = finishReasons)
    def withUsage(usage: TokenUsage): CompletionReceived                     = copy(usage = Some(usage))
    def withUsage(usage: Option[TokenUsage]): CompletionReceived             = copy(usage = usage)
    def withOptions(options: CompletionOptions): CompletionReceived          = copy(options = Some(options))
    def withOptions(options: Option[CompletionOptions]): CompletionReceived  = copy(options = options)
    def withInput(input: Seq[Message]): CompletionReceived                   = copy(input = input)
    def withServedTier(servedTier: ServiceTier): CompletionReceived          = copy(servedTier = Some(servedTier))
    def withServedTier(servedTier: Option[ServiceTier]): CompletionReceived  = copy(servedTier = servedTier)

    def eventType: String = "completion_received"
    def toJson: ujson.Value = {
      val json = ujson.Obj(
        "event_type"    -> eventType,
        "timestamp"     -> timestamp.toString,
        "completion_id" -> id,
        "model"         -> model,
        "tool_calls"    -> toolCalls,
        "content"       -> content
      )
      provider.foreach(p => json("provider") = p.asString)
      serverAddress.foreach(a => json("server_address") = a)
      if (streamed) json("streamed") = true
      duration.foreach(d => json("duration_ms") = d.toMillis.toDouble)
      responseModel.foreach(m => json("response_model") = m)
      if (finishReasons.nonEmpty) json("finish_reasons") = ujson.Arr(finishReasons.map(ujson.Str(_)): _*)
      usage.foreach { u =>
        json("prompt_tokens") = u.promptTokens
        json("completion_tokens") = u.completionTokens
        u.thinkingTokens.foreach(t => json("thinking_tokens") = t)
      }
      servedTier.foreach(t => json("served_tier") = TraceEvent.serviceTierName(t))
      json
    }
  }

  object CompletionReceived {
    def apply(
      id: String,
      model: String,
      toolCalls: Int,
      content: String,
      timestamp: Instant = Instant.now(),
      provider: Option[ProviderId] = None,
      serverAddress: Option[String] = None,
      streamed: Boolean = false,
      duration: Option[FiniteDuration] = None,
      responseModel: Option[String] = None,
      finishReasons: Seq[String] = Seq.empty,
      usage: Option[TokenUsage] = None,
      options: Option[CompletionOptions] = None,
      input: Seq[Message] = Seq.empty,
      servedTier: Option[ServiceTier] = None
    ): CompletionReceived =
      new CompletionReceived(
        id,
        model,
        toolCalls,
        content,
        timestamp,
        provider,
        serverAddress,
        streamed,
        duration,
        responseModel,
        finishReasons,
        usage,
        options,
        input,
        servedTier
      )

  }

  final case class ToolExecuted private (
    name: String,
    input: String,
    output: String,
    duration: FiniteDuration,
    success: Boolean,
    timestamp: Instant
  ) extends TraceEvent {
    def withName(name: String): ToolExecuted                 = copy(name = name)
    def withInput(input: String): ToolExecuted               = copy(input = input)
    def withOutput(output: String): ToolExecuted             = copy(output = output)
    def withDuration(duration: FiniteDuration): ToolExecuted = copy(duration = duration)
    def withSuccess(success: Boolean): ToolExecuted          = copy(success = success)
    def withTimestamp(timestamp: Instant): ToolExecuted      = copy(timestamp = timestamp)

    def eventType: String = "tool_executed"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"  -> eventType,
      "timestamp"   -> timestamp.toString,
      "tool_name"   -> name,
      "input"       -> input,
      "output"      -> output,
      "duration_ms" -> duration.toMillis,
      "success"     -> success
    )
  }

  object ToolExecuted {
    def apply(
      name: String,
      input: String,
      output: String,
      duration: FiniteDuration,
      success: Boolean,
      timestamp: Instant = Instant.now()
    ): ToolExecuted =
      new ToolExecuted(name, input, output, duration, success, timestamp)
  }

  /**
   * An error: an [[org.llm4s.error.LLMError]], which is not an exception, or a `Throwable` - build one from either
   * with [[ErrorOccurred.of]]. When it is a failed model call - a [[TracingLLMClient]]'s, or an
   * [[org.llm4s.llmconnect.EmbeddingClient]]'s - `operation` names the call (`chat`, `embeddings`) and the call's
   * details are set, which a backend that follows the OpenTelemetry GenAI conventions reports as the call's own span
   * with `error.type` (#2275).
   *
   * @param message   what went wrong: the `LLMError`'s message, or the exception's
   * @param errorType a short, low-cardinality name of the failure: the `LLMError`'s type (`RateLimitError`), or the
   *                  exception's class
   * @param context   where it happened, as the emitter describes it
   * @param cause     the exception behind the error, when there is one: the `Throwable` itself for an error built
   *                  from one, `None` for an `LLMError` unless the emitter adds it. A backend that records exceptions
   *                  records this one
   * @param operation the GenAI operation that failed (`chat`, `embeddings`); `None` for any other error
   * @param model     the requested model of the failed call
   * @param provider  the canonical id of the provider called
   * @param duration  how long the failed call took; the event's `timestamp` is its end
   * @param usage     the tokens the provider reported, and billed, for the failed call: a content-filtered reply's
   *                  ([[org.llm4s.error.ContentFilteredError]]'s `usage`, #2676); `None` when it reported none
   */
  final case class ErrorOccurred private (
    message: String,
    errorType: String,
    context: String,
    cause: Option[Throwable],
    timestamp: Instant,
    override val span: Option[TraceSpan],
    operation: Option[String],
    model: Option[String],
    provider: Option[ProviderId],
    duration: Option[FiniteDuration],
    usage: Option[TokenUsage]
  ) extends TraceEvent {
    def withMessage(message: String): ErrorOccurred                   = copy(message = message)
    def withErrorType(errorType: String): ErrorOccurred               = copy(errorType = errorType)
    def withContext(context: String): ErrorOccurred                   = copy(context = context)
    def withCause(cause: Throwable): ErrorOccurred                    = copy(cause = Some(cause))
    def withCause(cause: Option[Throwable]): ErrorOccurred            = copy(cause = cause)
    def withTimestamp(timestamp: Instant): ErrorOccurred              = copy(timestamp = timestamp)
    def withSpan(span: TraceSpan): ErrorOccurred                      = copy(span = Some(span))
    def withSpan(span: Option[TraceSpan]): ErrorOccurred              = copy(span = span)
    def withOperation(operation: String): ErrorOccurred               = copy(operation = Some(operation))
    def withOperation(operation: Option[String]): ErrorOccurred       = copy(operation = operation)
    def withModel(model: String): ErrorOccurred                       = copy(model = Some(model))
    def withModel(model: Option[String]): ErrorOccurred               = copy(model = model)
    def withProvider(provider: ProviderId): ErrorOccurred             = copy(provider = Some(provider))
    def withProvider(provider: Option[ProviderId]): ErrorOccurred     = copy(provider = provider)
    def withDuration(duration: FiniteDuration): ErrorOccurred         = copy(duration = Some(duration))
    def withDuration(duration: Option[FiniteDuration]): ErrorOccurred = copy(duration = duration)
    def withUsage(usage: TokenUsage): ErrorOccurred                   = copy(usage = Some(usage))
    def withUsage(usage: Option[TokenUsage]): ErrorOccurred           = copy(usage = usage)

    def eventType: String = "error_occurred"
    def toJson: ujson.Value = {
      val json = ujson.Obj(
        "event_type"    -> eventType,
        "timestamp"     -> timestamp.toString,
        "error_type"    -> errorType,
        "error_message" -> message,
        "context"       -> context,
        "stack_trace"   -> cause.fold("")(_.getStackTrace.take(5).mkString("\n"))
      )
      operation.foreach(o => json("operation") = o)
      model.foreach(m => json("model") = m)
      provider.foreach(p => json("provider") = p.asString)
      duration.foreach(d => json("duration_ms") = d.toMillis.toDouble)
      usage.foreach { u =>
        json("prompt_tokens") = u.promptTokens
        json("completion_tokens") = u.completionTokens
        u.thinkingTokens.foreach(t => json("thinking_tokens") = t)
      }
      TraceSpan.annotate(json, span)
    }
  }

  object ErrorOccurred {
    def apply(
      message: String,
      errorType: String,
      context: String,
      cause: Option[Throwable] = None,
      timestamp: Instant = Instant.now(),
      span: Option[TraceSpan] = None,
      operation: Option[String] = None,
      model: Option[String] = None,
      provider: Option[ProviderId] = None,
      duration: Option[FiniteDuration] = None,
      usage: Option[TokenUsage] = None
    ): ErrorOccurred =
      new ErrorOccurred(
        message,
        errorType,
        context,
        cause,
        timestamp,
        span,
        operation,
        model,
        provider,
        duration,
        usage
      )

    /** `error` in `context`: its message, and its type as `errorType`. An `LLMError` is not an exception: no cause. */
    def of(error: LLMError, context: String): ErrorOccurred =
      apply(error.message, error.getClass.getSimpleName, context)

    /**
     * The exception `error` in `context`: its message (empty when it has none, or when reading it throws), its class
     * as `errorType`, and itself as the cause. Nothing else of the exception is read here.
     */
    def of(error: Throwable, context: String): ErrorOccurred =
      apply(
        Try(error.getMessage).toOption.flatMap(Option(_)).getOrElse(""),
        error.getClass.getSimpleName,
        context,
        Some(error)
      )
  }

  final case class TokenUsageRecorded private (
    usage: TokenUsage,
    model: String,
    operation: String,
    timestamp: Instant,
    override val span: Option[TraceSpan]
  ) extends TraceEvent {
    def withUsage(usage: TokenUsage): TokenUsageRecorded      = copy(usage = usage)
    def withModel(model: String): TokenUsageRecorded          = copy(model = model)
    def withOperation(operation: String): TokenUsageRecorded  = copy(operation = operation)
    def withTimestamp(timestamp: Instant): TokenUsageRecorded = copy(timestamp = timestamp)
    def withSpan(span: TraceSpan): TokenUsageRecorded         = copy(span = Some(span))
    def withSpan(span: Option[TraceSpan]): TokenUsageRecorded = copy(span = span)

    def eventType: String = "token_usage_recorded"
    def toJson: ujson.Value = {
      val json = ujson.Obj(
        "event_type"        -> eventType,
        "timestamp"         -> timestamp.toString,
        "model"             -> model,
        "operation"         -> operation,
        "prompt_tokens"     -> usage.promptTokens,
        "completion_tokens" -> usage.completionTokens,
        "total_tokens"      -> usage.totalTokens
      )
      // the raw counts: thinking_tokens beside completion_tokens, which never includes it
      usage.thinkingTokens.foreach(t => json("thinking_tokens") = t)
      TraceSpan.annotate(json, span)
    }
  }

  object TokenUsageRecorded {
    def apply(
      usage: TokenUsage,
      model: String,
      operation: String,
      timestamp: Instant = Instant.now(),
      span: Option[TraceSpan] = None
    ): TokenUsageRecorded =
      new TokenUsageRecorded(usage, model, operation, timestamp, span)
  }

  /**
   * How an agent run ended: the one vocabulary of a run's end, which the run's trace ([[AgentRunEnded.status]]) and the
   * agent runtime's run metrics (`llm4s.agent.runs.ended`'s `status` label) both report by [[name]].
   */
  enum RunStatus(val name: String) {

    /** The turn ended with an answer. */
    case Completed extends RunStatus("completed")

    /** The turn reached its step limit. */
    case StepLimitReached extends RunStatus("step_limit_reached")

    /** The turn reached its run's token budget. */
    case TokenLimitReached extends RunStatus("token_limit_reached")

    /** The turn's answer was cut off at the model's output limit. */
    case Truncated extends RunStatus("truncated")

    /** The run suspended, waiting for an approval or an answer. */
    case Suspended extends RunStatus("suspended")

    /** A guardrail blocked the turn; [[AgentRunEnded.blockedBy]] names it. */
    case Blocked extends RunStatus("blocked")

    /** The run was cancelled. */
    case Cancelled extends RunStatus("cancelled")

    /** The run exceeded its deadline. */
    case TimedOut extends RunStatus("timed_out")

    /** The run failed. */
    case Failed extends RunStatus("failed")

    /**
     * Whether the run did not end by its own logic - it failed, was cancelled or timed out - which backends report as
     * an error. A block and a limit are outcomes, not errors.
     */
    def isError: Boolean = this match {
      case Cancelled | TimedOut | Failed => true
      case _                             => false
    }
  }

  /**
   * An agent run ended. Emitted once per run, from the run's own final state, by the agent's
   * tracing; it carries the turn's conversation for backends that record it (Langfuse turns it into
   * a trace with one span per message). A blocked turn has been removed from the thread by then, so
   * a blocked run carries no messages.
   *
   * @param threadId the conversation's thread; backends use it as the session
   * @param runId    this run
   * @param agent    the agent the run was started on - its root agent - which backends name the run's span after
   *                 (`invoke_agent <agent>`), whichever agent a handoff left active
   * @param status   how the run ended
   * @param messages this turn's messages, from its user message on; empty when blocked, cancelled,
   *                 timed out or failed. Not included in [[toJson]], which stays a flat summary.
   * @param usage    this run's own token usage and cost, per model: the model calls the run made,
   *                 never the thread's earlier runs, so a backend can report it per run (as
   *                 OpenTelemetry's per-operation `gen_ai.usage.*`) without double counting
   * @param parentThreadId for a delegated child run, the thread of the parent whose tool call started it;
   *                 `None` for a top-level run
   * @param parentRunId for a delegated child run, the parent run that started it; `None` for a top-level run
   * @param span     the run's own span (design 4.58): its context, its parent - a child run's is the `task` tool call
   *                 that started it, a continued run's the context it continued - and when the run started
   * @param provider the canonical id of the provider of the run's model calls (the first call's, when they used
   *                 several), when its client reported one; OpenTelemetry's `gen_ai.provider.name`
   * @param userId   who the run was for, as the application names them: the agent's tracing sets it to the run's
   *                 `RunConfig.principal`. `None` when the run has none; a backend then names no user (#2828)
   * @param blockedBy the guardrail that blocked the turn, when `status` is [[RunStatus.Blocked]]; `None` otherwise
   * @param activeAgent the agent active when the run ended: `agent`, or the one a handoff moved the turn to. `None`
   *                 when the emitter does not say
   */
  final case class AgentRunEnded private (
    threadId: String,
    runId: String,
    agent: String,
    status: RunStatus,
    messages: Seq[Message],
    usage: UsageSummary,
    timestamp: Instant,
    parentThreadId: Option[String],
    parentRunId: Option[String],
    override val span: Option[TraceSpan],
    provider: Option[ProviderId],
    userId: Option[String],
    blockedBy: Option[String],
    activeAgent: Option[String]
  ) extends TraceEvent {
    def withThreadId(threadId: String): AgentRunEnded                     = copy(threadId = threadId)
    def withRunId(runId: String): AgentRunEnded                           = copy(runId = runId)
    def withAgent(agent: String): AgentRunEnded                           = copy(agent = agent)
    def withStatus(status: RunStatus): AgentRunEnded                      = copy(status = status)
    def withMessages(messages: Seq[Message]): AgentRunEnded               = copy(messages = messages)
    def withUsage(usage: UsageSummary): AgentRunEnded                     = copy(usage = usage)
    def withTimestamp(timestamp: Instant): AgentRunEnded                  = copy(timestamp = timestamp)
    def withParentThreadId(parentThreadId: String): AgentRunEnded         = copy(parentThreadId = Some(parentThreadId))
    def withParentThreadId(parentThreadId: Option[String]): AgentRunEnded = copy(parentThreadId = parentThreadId)
    def withParentRunId(parentRunId: String): AgentRunEnded               = copy(parentRunId = Some(parentRunId))
    def withParentRunId(parentRunId: Option[String]): AgentRunEnded       = copy(parentRunId = parentRunId)
    def withSpan(span: TraceSpan): AgentRunEnded                          = copy(span = Some(span))
    def withSpan(span: Option[TraceSpan]): AgentRunEnded                  = copy(span = span)
    def withProvider(provider: ProviderId): AgentRunEnded                 = copy(provider = Some(provider))
    def withProvider(provider: Option[ProviderId]): AgentRunEnded         = copy(provider = provider)
    def withUserId(userId: String): AgentRunEnded                         = copy(userId = Some(userId))
    def withUserId(userId: Option[String]): AgentRunEnded                 = copy(userId = userId)
    def withBlockedBy(blockedBy: String): AgentRunEnded                   = copy(blockedBy = Some(blockedBy))
    def withBlockedBy(blockedBy: Option[String]): AgentRunEnded           = copy(blockedBy = blockedBy)
    def withActiveAgent(activeAgent: String): AgentRunEnded               = copy(activeAgent = Some(activeAgent))
    def withActiveAgent(activeAgent: Option[String]): AgentRunEnded       = copy(activeAgent = activeAgent)

    def eventType: String = "agent_run_ended"
    def toJson: ujson.Value = {
      val json = ujson.Obj(
        "event_type"    -> eventType,
        "timestamp"     -> timestamp.toString,
        "thread_id"     -> threadId,
        "run_id"        -> runId,
        "agent"         -> agent,
        "status"        -> status.name,
        "message_count" -> messages.size,
        "input_tokens"  -> usage.inputTokens.toDouble,
        "output_tokens" -> usage.outputTokens.toDouble,
        "total_cost"    -> usage.totalCost.toDouble
      )
      parentThreadId.foreach(id => json("parent_thread_id") = id)
      parentRunId.foreach(id => json("parent_run_id") = id)
      provider.foreach(p => json("provider") = p.asString)
      userId.foreach(id => json("user_id") = id)
      blockedBy.foreach(guardrail => json("blocked_by") = guardrail)
      activeAgent.foreach(active => json("active_agent") = active)
      TraceSpan.annotate(json, span)
    }
  }

  object AgentRunEnded {
    def apply(
      threadId: String,
      runId: String,
      agent: String,
      status: RunStatus,
      messages: Seq[Message],
      usage: UsageSummary,
      timestamp: Instant = Instant.now(),
      parentThreadId: Option[String] = None,
      parentRunId: Option[String] = None,
      span: Option[TraceSpan] = None,
      provider: Option[ProviderId] = None,
      userId: Option[String] = None,
      blockedBy: Option[String] = None,
      activeAgent: Option[String] = None
    ): AgentRunEnded =
      new AgentRunEnded(
        threadId,
        runId,
        agent,
        status,
        messages,
        usage,
        timestamp,
        parentThreadId,
        parentRunId,
        span,
        provider,
        userId,
        blockedBy,
        activeAgent
      )
  }

  final case class CustomEvent private (
    name: String,
    data: ujson.Value,
    timestamp: Instant,
    override val span: Option[TraceSpan]
  ) extends TraceEvent {
    def withName(name: String): CustomEvent            = copy(name = name)
    def withData(data: ujson.Value): CustomEvent       = copy(data = data)
    def withTimestamp(timestamp: Instant): CustomEvent = copy(timestamp = timestamp)
    def withSpan(span: TraceSpan): CustomEvent         = copy(span = Some(span))
    def withSpan(span: Option[TraceSpan]): CustomEvent = copy(span = span)

    def eventType: String = "custom_event"
    def toJson: ujson.Value = TraceSpan.annotate(
      ujson.Obj(
        "event_type" -> eventType,
        "timestamp"  -> timestamp.toString,
        "name"       -> name,
        "data"       -> data
      ),
      span
    )
  }

  object CustomEvent {
    def apply(
      name: String,
      data: ujson.Value,
      timestamp: Instant = Instant.now(),
      span: Option[TraceSpan] = None
    ): CustomEvent =
      new CustomEvent(name, data, timestamp, span)
  }

  /**
   * Tracks embedding token usage for cost analysis. An [[org.llm4s.llmconnect.EmbeddingClient]] emits one per call, which
   * a backend that follows the OpenTelemetry GenAI conventions reports as the call's `embeddings <model>` span (#2275).
   *
   * @param usage Token usage statistics from embedding operation
   * @param model Embedding model name (e.g., "text-embedding-3-small")
   * @param operation Type of operation: "indexing", "query", "evaluation"
   * @param inputCount Number of texts embedded in this operation
   * @param provider the canonical id of the provider called (`openai`, `voyage`, ...), when known
   * @param duration how long the call took; the event's `timestamp` is its end
   */
  final case class EmbeddingUsageRecorded private (
    usage: EmbeddingUsage,
    model: String,
    operation: String,
    inputCount: Int,
    timestamp: Instant,
    provider: Option[ProviderId],
    duration: Option[FiniteDuration]
  ) extends TraceEvent {
    def withUsage(usage: EmbeddingUsage): EmbeddingUsageRecorded               = copy(usage = usage)
    def withModel(model: String): EmbeddingUsageRecorded                       = copy(model = model)
    def withOperation(operation: String): EmbeddingUsageRecorded               = copy(operation = operation)
    def withInputCount(inputCount: Int): EmbeddingUsageRecorded                = copy(inputCount = inputCount)
    def withTimestamp(timestamp: Instant): EmbeddingUsageRecorded              = copy(timestamp = timestamp)
    def withProvider(provider: ProviderId): EmbeddingUsageRecorded             = copy(provider = Some(provider))
    def withProvider(provider: Option[ProviderId]): EmbeddingUsageRecorded     = copy(provider = provider)
    def withDuration(duration: FiniteDuration): EmbeddingUsageRecorded         = copy(duration = Some(duration))
    def withDuration(duration: Option[FiniteDuration]): EmbeddingUsageRecorded = copy(duration = duration)

    def eventType: String = "embedding_usage_recorded"
    def toJson: ujson.Value = {
      val json = ujson.Obj(
        "event_type"    -> eventType,
        "timestamp"     -> timestamp.toString,
        "model"         -> model,
        "operation"     -> operation,
        "input_count"   -> inputCount,
        "prompt_tokens" -> usage.promptTokens,
        "total_tokens"  -> usage.totalTokens
      )
      provider.foreach(p => json("provider") = p.asString)
      duration.foreach(d => json("duration_ms") = d.toMillis.toDouble)
      json
    }
  }

  object EmbeddingUsageRecorded {
    def apply(
      usage: EmbeddingUsage,
      model: String,
      operation: String,
      inputCount: Int,
      timestamp: Instant = Instant.now(),
      provider: Option[ProviderId] = None,
      duration: Option[FiniteDuration] = None
    ): EmbeddingUsageRecorded =
      new EmbeddingUsageRecorded(usage, model, operation, inputCount, timestamp, provider, duration)
  }

  /**
   * Tracks cost in USD for any model operation.
   *
   * @param costUsd Estimated cost in US dollars
   * @param model Model name used for pricing lookup
   * @param operation Type of operation: "embedding", "completion", "evaluation"
   * @param tokenCount Total tokens consumed
   * @param costType Category: "embedding", "completion", "total"
   */
  final case class CostRecorded private (
    costUsd: Double,
    model: String,
    operation: String,
    tokenCount: Int,
    costType: String,
    timestamp: Instant
  ) extends TraceEvent {
    def withCostUsd(costUsd: Double): CostRecorded      = copy(costUsd = costUsd)
    def withModel(model: String): CostRecorded          = copy(model = model)
    def withOperation(operation: String): CostRecorded  = copy(operation = operation)
    def withTokenCount(tokenCount: Int): CostRecorded   = copy(tokenCount = tokenCount)
    def withCostType(costType: String): CostRecorded    = copy(costType = costType)
    def withTimestamp(timestamp: Instant): CostRecorded = copy(timestamp = timestamp)

    def eventType: String = "cost_recorded"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"  -> eventType,
      "timestamp"   -> timestamp.toString,
      "cost_usd"    -> costUsd,
      "model"       -> model,
      "operation"   -> operation,
      "token_count" -> tokenCount,
      "cost_type"   -> costType
    )
  }

  object CostRecorded {
    def apply(
      costUsd: Double,
      model: String,
      operation: String,
      tokenCount: Int,
      costType: String,
      timestamp: Instant = Instant.now()
    ): CostRecorded =
      new CostRecorded(costUsd, model, operation, tokenCount, costType, timestamp)
  }

  /**
   * Tracks completion of a RAG operation with full metrics.
   *
   * @param operation Type: "index", "search", "answer", "evaluate"
   * @param duration Wall-clock duration
   * @param embeddingTokens Optional token count for embedding operations
   * @param llmPromptTokens Optional prompt tokens for LLM operations
   * @param llmCompletionTokens Optional completion tokens for LLM operations
   * @param totalCostUsd Optional accumulated cost in USD
   * @param stageDurations How long each stage took, by name (a RAG query's `rewrite`, `embedding`, `search`,
   *                       `rerank`, `answer`); empty when not measured
   */
  final case class RAGOperationCompleted private (
    operation: String,
    duration: FiniteDuration,
    embeddingTokens: Option[Int],
    llmPromptTokens: Option[Int],
    llmCompletionTokens: Option[Int],
    totalCostUsd: Option[Double],
    timestamp: Instant,
    stageDurations: Map[String, FiniteDuration]
  ) extends TraceEvent {
    def withOperation(operation: String): RAGOperationCompleted          = copy(operation = operation)
    def withDuration(duration: FiniteDuration): RAGOperationCompleted    = copy(duration = duration)
    def withEmbeddingTokens(embeddingTokens: Int): RAGOperationCompleted = copy(embeddingTokens = Some(embeddingTokens))
    def withEmbeddingTokens(embeddingTokens: Option[Int]): RAGOperationCompleted =
      copy(embeddingTokens = embeddingTokens)
    def withLlmPromptTokens(llmPromptTokens: Int): RAGOperationCompleted = copy(llmPromptTokens = Some(llmPromptTokens))
    def withLlmPromptTokens(llmPromptTokens: Option[Int]): RAGOperationCompleted =
      copy(llmPromptTokens = llmPromptTokens)
    def withLlmCompletionTokens(llmCompletionTokens: Int): RAGOperationCompleted =
      copy(llmCompletionTokens = Some(llmCompletionTokens))
    def withLlmCompletionTokens(llmCompletionTokens: Option[Int]): RAGOperationCompleted =
      copy(llmCompletionTokens = llmCompletionTokens)
    def withTotalCostUsd(totalCostUsd: Double): RAGOperationCompleted         = copy(totalCostUsd = Some(totalCostUsd))
    def withTotalCostUsd(totalCostUsd: Option[Double]): RAGOperationCompleted = copy(totalCostUsd = totalCostUsd)
    def withTimestamp(timestamp: Instant): RAGOperationCompleted              = copy(timestamp = timestamp)
    def withStageDurations(stageDurations: Map[String, FiniteDuration]): RAGOperationCompleted =
      copy(stageDurations = stageDurations)

    def eventType: String = "rag_operation_completed"
    def toJson: ujson.Value = {
      val base = ujson.Obj(
        "event_type"  -> eventType,
        "timestamp"   -> timestamp.toString,
        "operation"   -> operation,
        "duration_ms" -> duration.toMillis
      )
      embeddingTokens.foreach(t => base("embedding_tokens") = t)
      llmPromptTokens.foreach(t => base("llm_prompt_tokens") = t)
      llmCompletionTokens.foreach(t => base("llm_completion_tokens") = t)
      totalCostUsd.foreach(c => base("total_cost_usd") = c)
      if (stageDurations.nonEmpty) base("stage_durations_ms") = TraceEvent.stageDurationsJson(stageDurations)
      base
    }
  }

  /** Stage durations as a JSON object of milliseconds, by stage name in order. */
  private[llm4s] def stageDurationsJson(stageDurations: Map[String, FiniteDuration]): ujson.Obj =
    ujson.Obj.from(stageDurations.toSeq.sortBy(_._1).map { case (k, d) => k -> ujson.Num(d.toMillis.toDouble) })

  /**
   * A served tier as backends write it: `auto`, `default`, `flex` or `priority`, and an `Other` as the provider wrote
   * it (#2750).
   */
  private[llm4s] def serviceTierName(tier: ServiceTier): String = tier match {
    case ServiceTier.Auto       => "auto"
    case ServiceTier.Default    => "default"
    case ServiceTier.Flex       => "flex"
    case ServiceTier.Priority   => "priority"
    case ServiceTier.Other(raw) => raw
  }

  object RAGOperationCompleted {
    def apply(
      operation: String,
      duration: FiniteDuration,
      embeddingTokens: Option[Int] = None,
      llmPromptTokens: Option[Int] = None,
      llmCompletionTokens: Option[Int] = None,
      totalCostUsd: Option[Double] = None,
      timestamp: Instant = Instant.now(),
      stageDurations: Map[String, FiniteDuration] = Map.empty
    ): RAGOperationCompleted =
      new RAGOperationCompleted(
        operation,
        duration,
        embeddingTokens,
        llmPromptTokens,
        llmCompletionTokens,
        totalCostUsd,
        timestamp,
        stageDurations
      )
  }

  /**
   * Tracks completion of an image generation operation.
   *
   * @param model Image generation model name (e.g., "gpt-image-1", "dall-e-3")
   * @param operation Type of operation: "generate", "edit"
   * @param imageCount Number of images generated
   * @param size Image size descriptor (e.g., "1024x1024")
   * @param quality Quality level (e.g., "standard", "hd")
   * @param duration Wall-clock duration
   * @param costUsd Estimated cost in USD, if available
   * @param success Whether the operation succeeded
   * @param errorMessage Error message if the operation failed
   * @param provider the id of the provider called (`openai`, `stability`, ...), when known
   */
  final case class ImageGenerationCompleted private (
    model: String,
    operation: String,
    imageCount: Int,
    size: String,
    quality: String,
    duration: FiniteDuration,
    costUsd: Option[Double],
    success: Boolean,
    errorMessage: Option[String],
    timestamp: Instant,
    provider: Option[ProviderId]
  ) extends TraceEvent {
    def withModel(model: String): ImageGenerationCompleted               = copy(model = model)
    def withOperation(operation: String): ImageGenerationCompleted       = copy(operation = operation)
    def withImageCount(imageCount: Int): ImageGenerationCompleted        = copy(imageCount = imageCount)
    def withSize(size: String): ImageGenerationCompleted                 = copy(size = size)
    def withQuality(quality: String): ImageGenerationCompleted           = copy(quality = quality)
    def withDuration(duration: FiniteDuration): ImageGenerationCompleted = copy(duration = duration)
    def withCostUsd(costUsd: Double): ImageGenerationCompleted           = copy(costUsd = Some(costUsd))
    def withCostUsd(costUsd: Option[Double]): ImageGenerationCompleted   = copy(costUsd = costUsd)
    def withSuccess(success: Boolean): ImageGenerationCompleted          = copy(success = success)
    def withErrorMessage(errorMessage: String): ImageGenerationCompleted = copy(errorMessage = Some(errorMessage))
    def withErrorMessage(errorMessage: Option[String]): ImageGenerationCompleted = copy(errorMessage = errorMessage)
    def withTimestamp(timestamp: Instant): ImageGenerationCompleted              = copy(timestamp = timestamp)
    def withProvider(provider: ProviderId): ImageGenerationCompleted             = copy(provider = Some(provider))
    def withProvider(provider: Option[ProviderId]): ImageGenerationCompleted     = copy(provider = provider)

    def eventType: String = "image_generation_completed"
    def toJson: ujson.Value = {
      val base = ujson.Obj(
        "event_type"  -> eventType,
        "timestamp"   -> timestamp.toString,
        "model"       -> model,
        "operation"   -> operation,
        "image_count" -> imageCount,
        "size"        -> size,
        "quality"     -> quality,
        "duration_ms" -> duration.toMillis.toDouble,
        "success"     -> success
      )
      provider.foreach(p => base("provider") = p.asString)
      costUsd.foreach(c => base("cost_usd") = c)
      errorMessage.foreach(m => base("error_message") = m)
      base
    }
  }

  object ImageGenerationCompleted {
    def apply(
      model: String,
      operation: String,
      imageCount: Int,
      size: String,
      quality: String,
      duration: FiniteDuration,
      costUsd: Option[Double] = None,
      success: Boolean = true,
      errorMessage: Option[String] = None,
      timestamp: Instant = Instant.now(),
      provider: Option[ProviderId] = None
    ): ImageGenerationCompleted =
      new ImageGenerationCompleted(
        model,
        operation,
        imageCount,
        size,
        quality,
        duration,
        costUsd,
        success,
        errorMessage,
        timestamp,
        provider
      )
  }

  /**
   * Cache hit event for semantic caching
   */
  final case class CacheHit private (
    similarity: Double,
    threshold: Double,
    timestamp: Instant
  ) extends TraceEvent {
    def withSimilarity(similarity: Double): CacheHit = copy(similarity = similarity)
    def withThreshold(threshold: Double): CacheHit   = copy(threshold = threshold)
    def withTimestamp(timestamp: Instant): CacheHit  = copy(timestamp = timestamp)

    def eventType: String = "cache_hit"
    def toJson: ujson.Value = ujson.Obj(
      "event_type" -> eventType,
      "timestamp"  -> timestamp.toString,
      "similarity" -> similarity,
      "threshold"  -> threshold
    )
  }

  object CacheHit {
    def apply(
      similarity: Double,
      threshold: Double,
      timestamp: Instant = Instant.now()
    ): CacheHit =
      new CacheHit(similarity, threshold, timestamp)
  }

  /**
   * Cache miss reason ADT
   */
  sealed trait CacheMissReason {
    def value: String
  }
  object CacheMissReason {
    case object LowSimilarity extends CacheMissReason {
      def value: String = "low_similarity"
    }
    case object TtlExpired extends CacheMissReason {
      def value: String = "ttl_expired"
    }
    case object OptionsMismatch extends CacheMissReason {
      def value: String = "options_mismatch"
    }
  }

  /**
   * Cache miss event for semantic caching
   */
  final case class CacheMiss private (
    reason: CacheMissReason,
    timestamp: Instant
  ) extends TraceEvent {
    def withReason(reason: CacheMissReason): CacheMiss = copy(reason = reason)
    def withTimestamp(timestamp: Instant): CacheMiss   = copy(timestamp = timestamp)

    def eventType: String = "cache_miss"
    def toJson: ujson.Value = ujson.Obj(
      "event_type" -> eventType,
      "timestamp"  -> timestamp.toString,
      "reason"     -> reason.value
    )
  }

  object CacheMiss {
    def apply(
      reason: CacheMissReason,
      timestamp: Instant = Instant.now()
    ): CacheMiss =
      new CacheMiss(reason, timestamp)
  }

}
