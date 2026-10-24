package org.llm4s.trace

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.{ CompletionOptions, EmbeddingUsage, Message, TokenUsage, UsageSummary }
import org.llm4s.types.ProviderModelTypes.ProviderId

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
 * Type-safe, sealed hierarchy of trace events emitted during LLM agent execution.
 *
 * Each subtype carries the data relevant to one observable moment in the
 * agent lifecycle (completion received, tool executed, token usage recorded,
 * etc.).  All subtypes are serialisable to JSON via `toJson`, which produces a
 * flat object containing at minimum `"event_type"` and `"timestamp"` fields.
 *
 * == Error truncation ==
 * `ErrorOccurred.toJson` includes only the first 5 frames of the stack trace
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
 * field can be added later without breaking binary compatibility.
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

  final case class AgentInitialized private (
    query: String,
    tools: Vector[String],
    timestamp: Instant
  ) extends TraceEvent {
    def withQuery(query: String): AgentInitialized          = copy(query = query)
    def withTools(tools: Vector[String]): AgentInitialized  = copy(tools = tools)
    def withTimestamp(timestamp: Instant): AgentInitialized = copy(timestamp = timestamp)

    def eventType: String = "agent_initialized"
    def toJson: ujson.Value = ujson.Obj(
      "event_type" -> eventType,
      "timestamp"  -> timestamp.toString,
      "query"      -> query,
      "tools"      -> ujson.Arr(tools.map(ujson.Str(_)): _*)
    )
  }

  object AgentInitialized {
    def apply(
      query: String,
      tools: Vector[String],
      timestamp: Instant = Instant.now()
    ): AgentInitialized =
      new AgentInitialized(query, tools, timestamp)
  }

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
   * @param finishReasons why the model stopped, as the provider reported it
   * @param usage         the call's token usage, when the provider reported it
   * @param options       the options the request was sent with
   * @param input         the request's messages: message content, which backends record only when told to; not
   *                      included in [[toJson]]
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
    input: Seq[Message]
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
      }
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
      input: Seq[Message] = Seq.empty
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
        input
      )

    def apply(id: String, model: String, toolCalls: Int, content: String, timestamp: Instant): CompletionReceived =
      apply(id, model, toolCalls, content, timestamp, None)
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
   * An error. When it is a failed model call - a [[TracingLLMClient]]'s, or an [[org.llm4s.llmconnect.EmbeddingClient]]'s -
   * `operation` names the call (`chat`, `embeddings`) and the call's details are set, which a backend that follows the
   * OpenTelemetry GenAI conventions reports as the call's own span with `error.type` (#2275).
   *
   * @param operation the GenAI operation that failed (`chat`, `embeddings`); `None` for any other error
   * @param model     the requested model of the failed call
   * @param provider  the canonical id of the provider called
   * @param errorType a short, low-cardinality name of the failure (the `LLMError`'s type, e.g. `RateLimitError`);
   *                  `None` takes the exception's class name
   * @param duration  how long the failed call took; the event's `timestamp` is its end
   */
  final case class ErrorOccurred private (
    error: Throwable,
    context: String,
    timestamp: Instant,
    override val span: Option[TraceSpan],
    operation: Option[String],
    model: Option[String],
    provider: Option[ProviderId],
    errorType: Option[String],
    duration: Option[FiniteDuration]
  ) extends TraceEvent {
    def withError(error: Throwable): ErrorOccurred                    = copy(error = error)
    def withContext(context: String): ErrorOccurred                   = copy(context = context)
    def withTimestamp(timestamp: Instant): ErrorOccurred              = copy(timestamp = timestamp)
    def withSpan(span: TraceSpan): ErrorOccurred                      = copy(span = Some(span))
    def withSpan(span: Option[TraceSpan]): ErrorOccurred              = copy(span = span)
    def withOperation(operation: String): ErrorOccurred               = copy(operation = Some(operation))
    def withOperation(operation: Option[String]): ErrorOccurred       = copy(operation = operation)
    def withModel(model: String): ErrorOccurred                       = copy(model = Some(model))
    def withModel(model: Option[String]): ErrorOccurred               = copy(model = model)
    def withProvider(provider: ProviderId): ErrorOccurred             = copy(provider = Some(provider))
    def withProvider(provider: Option[ProviderId]): ErrorOccurred     = copy(provider = provider)
    def withErrorType(errorType: String): ErrorOccurred               = copy(errorType = Some(errorType))
    def withErrorType(errorType: Option[String]): ErrorOccurred       = copy(errorType = errorType)
    def withDuration(duration: FiniteDuration): ErrorOccurred         = copy(duration = Some(duration))
    def withDuration(duration: Option[FiniteDuration]): ErrorOccurred = copy(duration = duration)

    def eventType: String = "error_occurred"
    def toJson: ujson.Value = {
      val json = ujson.Obj(
        "event_type"    -> eventType,
        "timestamp"     -> timestamp.toString,
        "error_type"    -> errorType.getOrElse(error.getClass.getSimpleName),
        "error_message" -> error.getMessage,
        "context"       -> context,
        "stack_trace"   -> error.getStackTrace.take(5).mkString("\n")
      )
      operation.foreach(o => json("operation") = o)
      model.foreach(m => json("model") = m)
      provider.foreach(p => json("provider") = p.asString)
      duration.foreach(d => json("duration_ms") = d.toMillis.toDouble)
      TraceSpan.annotate(json, span)
    }
  }

  object ErrorOccurred {
    def apply(
      error: Throwable,
      context: String,
      timestamp: Instant = Instant.now(),
      span: Option[TraceSpan] = None,
      operation: Option[String] = None,
      model: Option[String] = None,
      provider: Option[ProviderId] = None,
      errorType: Option[String] = None,
      duration: Option[FiniteDuration] = None
    ): ErrorOccurred =
      new ErrorOccurred(error, context, timestamp, span, operation, model, provider, errorType, duration)

    def apply(error: Throwable, context: String, timestamp: Instant, span: Option[TraceSpan]): ErrorOccurred =
      apply(error, context, timestamp, span, None)
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
    def toJson: ujson.Value = TraceSpan.annotate(
      ujson.Obj(
        "event_type"        -> eventType,
        "timestamp"         -> timestamp.toString,
        "model"             -> model,
        "operation"         -> operation,
        "prompt_tokens"     -> usage.promptTokens,
        "completion_tokens" -> usage.completionTokens,
        "total_tokens"      -> usage.totalTokens
      ),
      span
    )
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
   * An agent run ended. Emitted once per run, from the run's own final state, by the agent's
   * tracing; it carries the turn's conversation for backends that record it (Langfuse turns it into
   * a trace with one span per message). A blocked turn has been removed from the thread by then, so
   * a blocked run carries no messages.
   *
   * @param threadId the conversation's thread; backends use it as the session
   * @param runId    this run
   * @param agent    the agent active when the run ended
   * @param status   `completed`, `suspended`, `step_limit_reached`, `blocked:<guardrail>`,
   *                 `cancelled`, `timed_out` or `failed`
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
   */
  final case class AgentRunEnded private (
    threadId: String,
    runId: String,
    agent: String,
    status: String,
    messages: Seq[Message],
    usage: UsageSummary,
    timestamp: Instant,
    parentThreadId: Option[String],
    parentRunId: Option[String],
    override val span: Option[TraceSpan],
    provider: Option[ProviderId]
  ) extends TraceEvent {
    def withThreadId(threadId: String): AgentRunEnded                     = copy(threadId = threadId)
    def withRunId(runId: String): AgentRunEnded                           = copy(runId = runId)
    def withAgent(agent: String): AgentRunEnded                           = copy(agent = agent)
    def withStatus(status: String): AgentRunEnded                         = copy(status = status)
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

    def eventType: String = "agent_run_ended"
    def toJson: ujson.Value = {
      val json = ujson.Obj(
        "event_type"    -> eventType,
        "timestamp"     -> timestamp.toString,
        "thread_id"     -> threadId,
        "run_id"        -> runId,
        "agent"         -> agent,
        "status"        -> status,
        "message_count" -> messages.size,
        "input_tokens"  -> usage.inputTokens.toDouble,
        "output_tokens" -> usage.outputTokens.toDouble,
        "total_cost"    -> usage.totalCost.toDouble
      )
      parentThreadId.foreach(id => json("parent_thread_id") = id)
      parentRunId.foreach(id => json("parent_run_id") = id)
      provider.foreach(p => json("provider") = p.asString)
      TraceSpan.annotate(json, span)
    }
  }

  object AgentRunEnded {
    def apply(
      threadId: String,
      runId: String,
      agent: String,
      status: String,
      messages: Seq[Message],
      usage: UsageSummary,
      timestamp: Instant = Instant.now(),
      parentThreadId: Option[String] = None,
      parentRunId: Option[String] = None,
      span: Option[TraceSpan] = None,
      provider: Option[ProviderId] = None
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
        provider
      )

    def apply(
      threadId: String,
      runId: String,
      agent: String,
      status: String,
      messages: Seq[Message],
      usage: UsageSummary,
      timestamp: Instant,
      parentThreadId: Option[String],
      parentRunId: Option[String],
      span: Option[TraceSpan]
    ): AgentRunEnded =
      apply(threadId, runId, agent, status, messages, usage, timestamp, parentThreadId, parentRunId, span, None)
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

    def apply(
      usage: EmbeddingUsage,
      model: String,
      operation: String,
      inputCount: Int,
      timestamp: Instant
    ): EmbeddingUsageRecorded =
      apply(usage, model, operation, inputCount, timestamp, None)
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
   */
  final case class RAGOperationCompleted private (
    operation: String,
    duration: FiniteDuration,
    embeddingTokens: Option[Int],
    llmPromptTokens: Option[Int],
    llmCompletionTokens: Option[Int],
    totalCostUsd: Option[Double],
    timestamp: Instant
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
      base
    }
  }

  object RAGOperationCompleted {
    def apply(
      operation: String,
      duration: FiniteDuration,
      embeddingTokens: Option[Int] = None,
      llmPromptTokens: Option[Int] = None,
      llmCompletionTokens: Option[Int] = None,
      totalCostUsd: Option[Double] = None,
      timestamp: Instant = Instant.now()
    ): RAGOperationCompleted =
      new RAGOperationCompleted(
        operation,
        duration,
        embeddingTokens,
        llmPromptTokens,
        llmCompletionTokens,
        totalCostUsd,
        timestamp
      )
  }

  /**
   * Tracks completion of an image generation operation.
   *
   * @param model Image generation model name (e.g., "gpt-image-1", "dall-e-3")
   * @param provider Provider name (e.g., "openai", "stability-ai")
   * @param operation Type of operation: "generate", "edit"
   * @param imageCount Number of images generated
   * @param size Image size descriptor (e.g., "1024x1024")
   * @param quality Quality level (e.g., "standard", "hd")
   * @param duration Wall-clock duration
   * @param costUsd Estimated cost in USD, if available
   * @param success Whether the operation succeeded
   * @param errorMessage Error message if the operation failed
   */
  final case class ImageGenerationCompleted private (
    model: String,
    provider: String,
    operation: String,
    imageCount: Int,
    size: String,
    quality: String,
    duration: FiniteDuration,
    costUsd: Option[Double],
    success: Boolean,
    errorMessage: Option[String],
    timestamp: Instant
  ) extends TraceEvent {
    def withModel(model: String): ImageGenerationCompleted               = copy(model = model)
    def withProvider(provider: String): ImageGenerationCompleted         = copy(provider = provider)
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

    def eventType: String = "image_generation_completed"
    def toJson: ujson.Value = {
      val base = ujson.Obj(
        "event_type"  -> eventType,
        "timestamp"   -> timestamp.toString,
        "model"       -> model,
        "provider"    -> provider,
        "operation"   -> operation,
        "image_count" -> imageCount,
        "size"        -> size,
        "quality"     -> quality,
        "duration_ms" -> duration.toMillis.toDouble,
        "success"     -> success
      )
      costUsd.foreach(c => base("cost_usd") = c)
      errorMessage.foreach(m => base("error_message") = m)
      base
    }
  }

  object ImageGenerationCompleted {
    def apply(
      model: String,
      provider: String,
      operation: String,
      imageCount: Int,
      size: String,
      quality: String,
      duration: FiniteDuration,
      costUsd: Option[Double] = None,
      success: Boolean = true,
      errorMessage: Option[String] = None,
      timestamp: Instant = Instant.now()
    ): ImageGenerationCompleted =
      new ImageGenerationCompleted(
        model,
        provider,
        operation,
        imageCount,
        size,
        quality,
        duration,
        costUsd,
        success,
        errorMessage,
        timestamp
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
