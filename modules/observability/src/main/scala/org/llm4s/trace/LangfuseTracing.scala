package org.llm4s.trace

import org.llm4s.security.Secret
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, SystemMessage, TokenUsage, UserMessage }
import org.llm4s.trace.LangfuseOtlp.{ Observation, ObservationType, TraceAttributes }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
 * Langfuse [[Tracing]] implementation for production observability.
 *
 * Sends each trace event to Langfuse as OpenTelemetry spans, over OTLP/HTTP with JSON, to Langfuse's OpenTelemetry
 * endpoint `<url>/api/public/otel/v1/traces` (#2883). Each span is a Langfuse observation - a span, a generation or
 * an event - whose `langfuse.*` attributes carry its input, output, metadata, level, model and usage; a run's span
 * also sets the trace's name, session, user, tags, metadata and visibility.
 *
 * == Ids ==
 *
 * An event placed in a trace (`TraceEvent.span`, design 4.58) keeps its placement's W3C ids, so it lands in that
 * trace under its parent. An ended agent run that is not placed is the trace `TraceContext.rootFor(runId)`: both its
 * ids derive from the run id, so the same run always names the same trace. Each message of a run is a span whose id
 * derives from the run's span and the message's index ([[TraceContext.spanFor]]). Any other event that is not placed
 * is a trace of its own, with random ids.
 *
 * == Configuration ==
 *
 * {{{
 * // Environment variables, read through llm4s.tracing.langfuse
 * LANGFUSE_PUBLIC_KEY=pk-lf-...
 * LANGFUSE_SECRET_KEY=sk-lf-...
 * LANGFUSE_URL=https://cloud.langfuse.com (optional)
 *
 * // Or programmatic configuration
 * val tracing = new LangfuseTracing(
 *   langfuseUrl = "https://cloud.langfuse.com",
 *   publicKey = Secret("pk-lf-..."),
 *   secretKey = Secret("sk-lf-..."),
 *   environment = "production",
 *   release = "1.0.0",
 *   version = "1.0.0"
 * )
 * }}}
 *
 * Each call to `traceEvent` is one POST, carrying every span the event becomes. Nothing is retried: a failed export
 * is logged, with any response body redacted, and returned as a `Left`; an empty key skips the export.
 *
 * @param langfuseUrl the Langfuse host (default: https://cloud.langfuse.com); a URL that ends in one of Langfuse's API
 *                    paths, such as the legacy `/api/public/ingestion`, names the host it is on
 * @param publicKey Langfuse public key for authentication
 * @param secretKey Langfuse secret key for authentication
 * @param environment Environment name (e.g., "production", "staging"): `langfuse.environment` on every span
 * @param release Release identifier: `langfuse.release` on every span
 * @param version Version: `langfuse.version` on every span
 * @param publicTraces whether each trace is `public` (`langfuse.trace.public`). Langfuse serves a public trace to
 *                     anyone holding its URL, signed in or not, and an agent run's trace carries the turn's messages,
 *                     so this is false unless `llm4s.tracing.langfuse.public` is `true`. A trace's user is the run's
 *                     (`TraceEvent.AgentRunEnded.userId`, which the agent takes from `RunConfig.principal`); a trace
 *                     of a run without one names no user (#2828)
 *
 * @see [[ConsoleTracing]] for local development
 * @see [[NoOpTracing]] for disabled tracing
 * @see [[TracingMode]] for configuration modes
 */
class LangfuseTracing(
  langfuseUrl: String,
  publicKey: Secret,
  secretKey: Secret,
  environment: String,
  release: String,
  version: String,
  httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
  restoreInterrupt: () => Unit = () => Thread.currentThread().interrupt(),
  publicTraces: Boolean = false
) extends Tracing {

  private val logger     = LoggerFactory.getLogger(getClass)
  private val deployment = LangfuseOtlp.Deployment(environment, release, version)

  /** Posts one OTLP export request to Langfuse's OpenTelemetry endpoint. */
  protected def sendBatch(request: ujson.Obj): Result[Unit] =
    LangfuseExport.send(
      LangfuseExport.traces(langfuseUrl, request),
      publicKey,
      secretKey,
      httpClient,
      restoreInterrupt,
      logger
    )

  def traceEvent(event: TraceEvent): Result[Unit] =
    sendBatch(LangfuseOtlp.request(observations(event), deployment, LangfuseTracing.Scope))

  /** The observations `event` becomes. */
  private[trace] def observations(event: TraceEvent): Seq[Observation] = event match {
    // A run placed in a trace (design 4.58) is an observation in that trace: a child run's in its parent's.
    case e: TraceEvent.AgentRunEnded => runObservations(e)
    case e: TraceEvent.CustomEvent if e.span.isDefined && LangfuseTracing.spanEvents.contains(e.name) =>
      Seq(placedCall(e, e.span.get))
    case other => Seq(single(other))
  }

  /**
   * An agent's model call or tool call placed in a trace, as a Langfuse observation in it: a generation (`chat
   * <model>`, with its model and usage) or a span (`execute_tool <tool>`), with the placement's ids, parent and times.
   * The event's data - which an agent's events keep free of message content - is its metadata.
   */
  private def placedCall(e: TraceEvent.CustomEvent, span: TraceSpan): Observation = {
    val payload                   = e.data.objOpt.flatMap(_.get("payload")).flatMap(_.objOpt)
    def text(key: String): String = payload.flatMap(_.get(key)).flatMap(_.strOpt).getOrElse("")
    val base = LangfuseTracing
      .placed(span, e.timestamp, "", ObservationType.Span)
      .copy(metadata = e.data.objOpt.fold(ujson.Obj("data" -> e.data))(ujson.Obj.from(_)))
    if (e.name == LangfuseTracing.ModelCallCompleted) {
      val usage = payload.flatMap(_.get("usage")).flatMap(_.objOpt).map { usage =>
        def tokens(key: String): ujson.Value = usage.get(key).getOrElse(ujson.Null)
        // output as billed: the completion plus any thinking, which completionTokens never includes
        val output = usage.get("completionTokens").flatMap(_.numOpt).fold(ujson.Null: ujson.Value) { completion =>
          ujson.Num(completion + usage.get("thinkingTokens").flatMap(_.numOpt).getOrElse(0d))
        }
        ujson.Obj("input" -> tokens("promptTokens"), "output" -> output, "total" -> tokens("totalTokens"))
      }
      base.copy(
        name = s"chat ${text("model")}",
        kind = ObservationType.Generation,
        model = Some(text("model")),
        usage = usage
      )
    } else {
      val errored = payload.flatMap(_.get("outcome")).exists(_.toString.contains("Errored"))
      base.copy(name = s"execute_tool ${text("tool")}", level = Option.when(errored)("ERROR"))
    }
  }

  /**
   * An ended run. A top-level run sets the trace's attributes - name, session (the thread), user, tags, metadata,
   * visibility - on its own span; a delegated child run sets none, so it stays in its parent's trace, under the
   * `execute_tool task` that started it. A placed run is the span `invoke_agent <agent>` with the placement's ids and
   * times; a run that is not placed is the root span `LLM4S Agent Run` of the trace `TraceContext.rootFor(runId)`.
   * Either way each message of the turn is a span under the run.
   */
  private def runObservations(run: TraceEvent.AgentRunEnded): Seq[Observation] = {
    val isChild  = run.span.isDefined && run.parentRunId.isDefined
    val metadata = LangfuseTracing.runMetadata(run)
    val input    = ujson.Str(run.messages.collectFirst { case m: UserMessage => m.content }.getOrElse(""))
    val output = ujson.Str(
      run.messages.reverseIterator.collectFirst { case m: AssistantMessage => m.content }.getOrElse("")
    )
    val traceAttributes = Option.when(!isChild)(
      TraceAttributes(
        name = LangfuseTracing.RunTraceName,
        sessionId = Some(run.threadId),
        userId = run.userId,
        tags = Seq("llm4s", "agent", "conversation"),
        metadata = metadata,
        public = publicTraces
      )
    )
    val runObservation = run.span match {
      case Some(span) =>
        // a top-level run's parent, if any, is its submitter's span, which this trace does not hold
        LangfuseTracing
          .placed(
            if (isChild) span else span.withParent(None),
            run.timestamp,
            s"invoke_agent ${run.agent}",
            ObservationType.Span
          )
      case None =>
        Observation(
          TraceContext.rootFor(run.runId),
          None,
          LangfuseTracing.RunTraceName,
          ObservationType.Span,
          run.timestamp,
          run.timestamp
        )
    }
    val runSpan = runObservation.copy(
      input = Some(input),
      output = Some(output),
      metadata = metadata,
      level = Option.when(run.status.isError)("ERROR"),
      statusMessage = Option.when(run.status.isError)(run.status.name),
      trace = traceAttributes
    )

    val messages = run.messages.zipWithIndex.map { case (message, index) =>
      val role = message.getClass.getSimpleName.replace("Message", "").toLowerCase
      Observation(
        runSpan.context.spanFor(s"message:${runSpan.context.spanId}:$index"),
        Some(runSpan.context.spanId),
        s"${LangfuseTracing.messageName(message)} $index",
        ObservationType.Span,
        run.timestamp,
        run.timestamp,
        input = Some(ujson.Obj("content" -> message.content, "role" -> role)),
        output = message match {
          case _: AssistantMessage => Some(ujson.Obj("content" -> message.content, "role" -> "assistant"))
          case _                   => None
        },
        metadata = ujson.Obj(
          "framework"     -> "llm4s",
          "message_index" -> index,
          "message_type"  -> message.getClass.getSimpleName,
          "parent_trace"  -> runSpan.context.traceId
        )
      )
    }
    runSpan +: messages
  }

  /** Every other event: one observation, placed in its trace when the event is, else a trace of its own. */
  private def single(event: TraceEvent): Observation = {
    // the observation's shape; its ids and times come after
    def obs(
      name: String,
      kind: ObservationType,
      input: ujson.Value = ujson.Null,
      output: ujson.Value = ujson.Null,
      metadata: ujson.Obj,
      duration: Option[FiniteDuration] = None,
      level: Option[String] = None,
      statusMessage: Option[String] = None,
      model: Option[String] = None,
      usage: Option[ujson.Obj] = None
    ): Observation = {
      val shape = Observation(
        context = TraceContext.random(),
        parentSpanId = None,
        name = name,
        kind = kind,
        startTime = duration.fold(event.timestamp)(d => event.timestamp.minusNanos(d.toNanos)),
        endTime = event.timestamp,
        input = Option.when(!input.isNull)(input),
        output = Option.when(!output.isNull)(output),
        metadata = metadata,
        level = level,
        statusMessage = statusMessage,
        model = model,
        usage = usage
      )
      // an event placed in a trace (design 4.58) keeps its shape, inside that trace, at its recorded times
      event.span.fold(shape) { span =>
        val at = LangfuseTracing.placed(span, event.timestamp, name, kind)
        shape.copy(context = at.context, parentSpanId = at.parentSpanId, startTime = at.startTime)
      }
    }

    event match {
      case e: TraceEvent.CompletionReceived =>
        val metadata = ujson.Obj("completion_id" -> e.id, "tool_calls" -> e.toolCalls)
        // the tier the provider served the call at (#2750)
        e.servedTier.foreach(t => metadata("service_tier") = TraceEvent.serviceTierName(t))
        obs(
          "LLM Completion",
          ObservationType.Generation,
          input = ujson.Arr(ujson.Obj("role" -> "system", "content" -> "LLM4S completion request")),
          output = ujson.Obj("role" -> "assistant", "content" -> e.content),
          metadata = metadata,
          duration = e.duration,
          model = Some(e.model),
          usage = e.usage.map(LangfuseTracing.usageDetails)
        )

      case e: TraceEvent.ToolExecuted =>
        obs(
          s"Tool Execution: ${e.name}",
          ObservationType.Span,
          input = ujson.Obj("arguments" -> e.input),
          output = ujson.Obj("result" -> e.output),
          metadata = ujson.Obj("tool_name" -> e.name, "duration" -> e.duration.toMillis, "success" -> e.success),
          duration = Some(e.duration)
        )

      case e: TraceEvent.ErrorOccurred =>
        val metadata = ujson.Obj(
          "error_type"  -> e.errorType,
          "context"     -> e.context,
          "stack_trace" -> e.cause.fold("")(_.getStackTrace.take(5).mkString("\n"))
        )
        // a content-filtered reply's billed tokens (#2676)
        e.usage.foreach(u => metadata("usage") = LangfuseTracing.usageCounts(u))
        obs(
          "Error",
          ObservationType.Event,
          input = ujson.Obj("error" -> e.message),
          metadata = metadata,
          level = Some("ERROR"),
          statusMessage = Some(e.message)
        )

      case e: TraceEvent.TokenUsageRecorded =>
        obs(
          s"Token Usage - ${e.operation}",
          ObservationType.Event,
          input = ujson.Obj("model" -> e.model, "operation" -> e.operation),
          output = LangfuseTracing.usageCounts(e.usage),
          metadata = ujson.Obj("model" -> e.model, "operation" -> e.operation, "token_type" -> "usage")
        )

      // `observations` sends an ended run through `runObservations`; this keeps the match exhaustive
      case e: TraceEvent.AgentRunEnded =>
        obs(e.eventType, ObservationType.Event, metadata = LangfuseTracing.runMetadata(e))

      case e: TraceEvent.CustomEvent =>
        obs(e.name, ObservationType.Event, input = e.data, metadata = ujson.Obj("source" -> "custom_event"))

      case e: TraceEvent.EmbeddingUsageRecorded =>
        obs(
          s"Embedding Usage - ${e.operation}",
          ObservationType.Event,
          input = ujson.Obj("model" -> e.model, "operation" -> e.operation, "input_count" -> e.inputCount),
          output = ujson.Obj("prompt_tokens" -> e.usage.promptTokens, "total_tokens" -> e.usage.totalTokens),
          metadata = ujson.Obj(
            "model"       -> e.model,
            "operation"   -> e.operation,
            "input_count" -> e.inputCount,
            "token_type"  -> "embedding_usage"
          )
        )

      case e: TraceEvent.CostRecorded =>
        obs(
          s"Cost - ${e.operation}",
          ObservationType.Event,
          input = ujson.Obj(
            "model"       -> e.model,
            "operation"   -> e.operation,
            "token_count" -> e.tokenCount,
            "cost_type"   -> e.costType
          ),
          output = ujson.Obj("cost_usd" -> e.costUsd),
          metadata = ujson.Obj(
            "model"       -> e.model,
            "operation"   -> e.operation,
            "token_count" -> e.tokenCount,
            "cost_type"   -> e.costType,
            "cost_usd"    -> e.costUsd
          ),
          level = Some("DEFAULT")
        )

      case e: TraceEvent.CacheHit =>
        obs(
          "Cache Hit",
          ObservationType.Span,
          input = ujson.Obj("similarity" -> e.similarity, "threshold" -> e.threshold),
          output = ujson.Obj("result" -> "hit"),
          metadata = ujson.Obj("similarity" -> e.similarity, "threshold" -> e.threshold),
          level = Some("DEFAULT")
        )

      case e: TraceEvent.CacheMiss =>
        obs(
          "Cache Miss",
          ObservationType.Span,
          input = ujson.Obj("reason" -> e.reason.value),
          output = ujson.Obj("result" -> "miss"),
          metadata = ujson.Obj("reason" -> e.reason.value),
          level = Some("DEFAULT")
        )

      case e: TraceEvent.ImageGenerationCompleted =>
        val outputObj = ujson.Obj(
          "model"       -> e.model,
          "operation"   -> e.operation,
          "image_count" -> e.imageCount,
          "size"        -> e.size,
          "quality"     -> e.quality,
          "duration_ms" -> e.duration.toMillis,
          "success"     -> e.success
        )
        e.provider.foreach(p => outputObj("provider") = p.asString)
        e.costUsd.foreach(c => outputObj("cost_usd") = c)
        e.errorMessage.foreach(m => outputObj("error_message") = m)
        obs(
          "Image Generation",
          ObservationType.Span,
          input = ujson.Obj("model" -> e.model, "operation" -> e.operation, "size" -> e.size, "quality" -> e.quality),
          output = outputObj,
          metadata = outputObj,
          duration = Some(e.duration),
          level = Some(if (e.success) "DEFAULT" else "ERROR"),
          statusMessage = if (e.success) None else e.errorMessage
        )

      case e: TraceEvent.RAGOperationCompleted =>
        val metadataObj = ujson.Obj("operation" -> e.operation, "duration_ms" -> e.duration.toMillis)
        e.embeddingTokens.foreach(t => metadataObj("embedding_tokens") = t)
        e.llmPromptTokens.foreach(t => metadataObj("llm_prompt_tokens") = t)
        e.llmCompletionTokens.foreach(t => metadataObj("llm_completion_tokens") = t)
        e.totalCostUsd.foreach(c => metadataObj("total_cost_usd") = c)
        val outputObj = ujson.Obj.from(metadataObj.value)
        if (e.stageDurations.nonEmpty) outputObj("stage_durations_ms") = TraceEvent.stageDurationsJson(e.stageDurations)
        obs(
          s"RAG ${e.operation}",
          ObservationType.Span,
          input = ujson.Obj("operation" -> e.operation),
          output = outputObj,
          metadata = metadataObj,
          duration = Some(e.duration)
        )
    }
  }
}

/**
 * Factory methods for [[LangfuseTracing]].
 */
object LangfuseTracing {

  /** The instrumentation scope of the spans this backend sends. */
  private[trace] val Scope = "org.llm4s.trace.LangfuseTracing"

  /** The name of the trace a top-level agent run sets. */
  private[trace] val RunTraceName = "LLM4S Agent Run"

  private[trace] val ModelCallCompleted = "agent.model_call_completed"
  private[trace] val ToolExecuted       = "agent.tool_executed"

  /** The agent events a placed one of becomes an observation with a duration, rather than a point event. */
  private[trace] val spanEvents: Set[String] = Set(ModelCallCompleted, ToolExecuted)

  /**
   * An observation placed where `span` says (design 4.58): its ids are the span's, its parent the span's parent, and
   * it runs from the span's start to `end`, the event's time.
   */
  private[trace] def placed(span: TraceSpan, end: Instant, name: String, kind: ObservationType): Observation =
    Observation(span.context, span.parent.map(_.spanId), name, kind, span.startedAt, end)

  private[trace] def messageName(message: Message): String = message match {
    case _: SystemMessage    => "System Message"
    case _: UserMessage      => "User Input"
    case _: AssistantMessage => "LLM Generation"
    case _                   => message.getClass.getSimpleName
  }

  /** A usage's counts as the legacy events sent them, output tokens as billed (thinking included). */
  private[trace] def usageCounts(u: TokenUsage): ujson.Obj =
    ujson.Obj(
      "prompt_tokens"     -> u.promptTokens,
      "completion_tokens" -> u.completionTokens,
      "thinking_tokens"   -> u.thinkingTokens.getOrElse(0),
      "output_tokens"     -> u.totalOutputTokens,
      "total_tokens"      -> u.totalTokens
    )

  /** A usage as Langfuse's `usage_details`: input, output as billed, total. */
  private[trace] def usageDetails(u: TokenUsage): ujson.Obj =
    ujson.Obj("input" -> u.promptTokens, "output" -> u.totalOutputTokens, "total" -> u.totalTokens)

  /**
   * The metadata of an ended agent run: its agent, status, thread and totals, and - for a delegated child run -
   * `parent_thread_id` and `parent_run_id`, which link it to the parent run's trace.
   */
  private[trace] def runMetadata(run: TraceEvent.AgentRunEnded): ujson.Obj = {
    val metadata = ujson.Obj(
      "framework"     -> "llm4s",
      "agent"         -> run.agent,
      "status"        -> run.status.name,
      "thread_id"     -> run.threadId,
      "run_id"        -> run.runId,
      "message_count" -> run.messages.size,
      "input_tokens"  -> run.usage.inputTokens.toDouble,
      "output_tokens" -> run.usage.outputTokens.toDouble,
      "total_cost"    -> run.usage.totalCost.toDouble
    )
    run.parentThreadId.foreach(id => metadata("parent_thread_id") = id)
    run.parentRunId.foreach(id => metadata("parent_run_id") = id)
    run.blockedBy.foreach(guardrail => metadata("blocked_by") = guardrail)
    run.activeAgent.foreach(agent => metadata("active_agent") = agent)
    metadata
  }

  /**
   * Create a LangfuseTracing instance from configuration.
   *
   * @param cfg Langfuse configuration with URL, keys, and metadata
   * @return Configured LangfuseTracing instance
   */
  def from(
    cfg: org.llm4s.llmconnect.config.LangfuseConfig,
    httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
  ): LangfuseTracing =
    new LangfuseTracing(
      langfuseUrl = cfg.url,
      publicKey = cfg.publicKey.getOrElse(Secret("")),
      secretKey = cfg.secretKey.getOrElse(Secret("")),
      environment = cfg.env,
      release = cfg.release,
      version = cfg.version,
      httpClient = httpClient,
      publicTraces = cfg.publicTraces
    )
}
