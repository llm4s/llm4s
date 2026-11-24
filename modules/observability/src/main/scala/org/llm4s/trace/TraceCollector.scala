package org.llm4s.trace

import org.llm4s.trace.model._
import org.llm4s.trace.store.TraceStore
import org.llm4s.trace.model.TraceId
import org.llm4s.types.Result
import org.llm4s.util.LiftToResult

import java.util.UUID

/**
 * A `Tracing` implementation that converts every `TraceEvent` into a `Span`
 *  and persists it in the given `TraceStore[F]`.
 *
 *  Composable with other backends via `TracingComposer.combine()`. The
 *  `traceId` field exposes the identifier assigned to this run so callers can
 *  retrieve spans from the store after execution completes.
 *
 *  @param store          destination for all recorded spans
 *  @param initialTraceId optional fixed trace ID; a random UUID is used when absent
 *  @tparam F             effect type of the backing store; an implicit [[org.llm4s.util.LiftToResult]]
 *                        instance must be available to bridge `F[A]` into `Result[A]`
 */
class TraceCollectorTracing[F[_]](
  store: TraceStore[F],
  initialTraceId: Option[TraceId] = None
)(implicit lift: LiftToResult[F])
    extends Tracing {

  val traceId: TraceId = initialTraceId.getOrElse(TraceId(UUID.randomUUID().toString))

  override def traceEvent(event: TraceEvent): Result[Unit] =
    event.span.fold[Result[Unit]](Right(()))(registered).flatMap(_ => lift(store.saveSpan(eventToSpan(event))))

  // the placed traces this collector has made sure the store holds, with the start it stored; guarded by itself
  private val placedTraces = scala.collection.mutable.Map.empty[String, java.time.Instant]

  /**
   * Makes sure the store holds the trace a placed event is in (design 4.58) before its span is saved, so the trace can
   * be read, queried and deleted like the collector's own: created at the first event seen in it unless the store has
   * it already. Events arrive in any order - a run's own span is traced after its events - so the trace's start is the
   * earliest start seen, and a root span (one without a parent, such as a top-level run's) is recorded as its root and
   * sets the start to its own.
   */
  private def registered(placed: TraceSpan): Result[Unit] = {
    val id   = TraceId(placed.context.traceId)
    val root = if (placed.parent.isEmpty) Some(SpanId(placed.context.spanId)) else None
    // one registration at a time, so two cannot read the trace and overwrite each other's save; remembered only once
    // the store has it, so a failed read or save is tried again with the next event
    placedTraces.synchronized {
      placedTraces.get(id.value) match {
        case Some(start) if root.isEmpty && !placed.startedAt.isBefore(start) => Right(())
        case _ =>
          lift(store.getTrace(id))
            .flatMap { existing =>
              val found = existing.getOrElse(Trace.start(id).copy(startTime = placed.startedAt))
              val trace = root match {
                case Some(r) => found.copy(rootSpanId = Some(r), startTime = placed.startedAt)
                case None    => found.copy(startTime = Seq(found.startTime, placed.startedAt).min)
              }
              lift(store.saveTrace(trace)).map(_ => trace.startTime)
            }
            .map(start => placedTraces.update(id.value, start))
      }
    }
  }

  /**
   * The span an event becomes. An event placed in a trace (design 4.58) keeps its placement: its own trace and span
   * ids, its parent and its start, so the collector holds each run's tree rather than one flat trace; any other event
   * is a span of this collector's trace, as before.
   */
  private def eventToSpan(event: TraceEvent): Span =
    event.span match {
      case Some(placed) =>
        eventToSpan(event, SpanId(placed.context.spanId)).copy(
          traceId = TraceId(placed.context.traceId),
          parentSpanId = placed.parent.map(p => SpanId(p.spanId)),
          startTime = placed.startedAt
        )
      case None => eventToSpan(event, SpanId.generate())
    }

  private[trace] def eventToSpan(event: TraceEvent, spanId: SpanId): Span = {
    val spanName = event.eventType

    // every event is read by field, never by position: a positional pattern stops compiling when an event grows
    event match {
      case e: TraceEvent.CompletionReceived =>
        val attributes = Map(
          "completion_id"    -> SpanValue.StringValue(e.id),
          "model"            -> SpanValue.StringValue(e.model.asString),
          "tool_calls_count" -> SpanValue.LongValue(e.toolCalls.toLong),
          "content"          -> SpanValue.StringValue(e.content)
        ) ++ e.servedTier.map(t => "service_tier" -> SpanValue.StringValue(TraceEvent.serviceTierName(t)))
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.LlmCall,
          startTime = e.timestamp,
          endTime = Some(e.timestamp),
          status = SpanStatus.Ok,
          attributes = attributes
        )

      case e: TraceEvent.ToolExecuted =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.ToolCall,
          startTime = e.timestamp.minusMillis(e.duration.toMillis),
          endTime = Some(e.timestamp),
          status = if (e.success) SpanStatus.Ok else SpanStatus.Error(s"Tool ${e.name} failed"),
          attributes = Map(
            "tool_name"   -> SpanValue.StringValue(e.name),
            "input"       -> SpanValue.StringValue(e.input),
            "output"      -> SpanValue.StringValue(e.output),
            "duration_ms" -> SpanValue.LongValue(e.duration.toMillis),
            "success"     -> SpanValue.BooleanValue(e.success)
          )
        )

      case e: TraceEvent.ErrorOccurred =>
        // a content-filtered reply's billed tokens (#2676)
        val tokens = e.usage.toSeq.flatMap(u =>
          Seq(
            "prompt_tokens"     -> SpanValue.LongValue(u.promptTokens.toLong),
            "completion_tokens" -> SpanValue.LongValue(u.completionTokens.toLong),
            "output_tokens"     -> SpanValue.LongValue(u.totalOutputTokens.toLong),
            "total_tokens"      -> SpanValue.LongValue(u.totalTokens.toLong)
          )
        )
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Internal,
          startTime = e.timestamp,
          endTime = Some(e.timestamp),
          status = SpanStatus.Error(s"${e.errorType}: ${e.message}"),
          attributes = Map(
            "error_type"    -> SpanValue.StringValue(e.errorType),
            "error_message" -> SpanValue.StringValue(e.message),
            "context"       -> SpanValue.StringValue(e.context)
          ) ++ tokens
        )

      case e: TraceEvent.TokenUsageRecorded =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Internal,
          startTime = e.timestamp,
          endTime = Some(e.timestamp),
          status = SpanStatus.Ok,
          attributes = Map(
            "model"             -> SpanValue.StringValue(e.model.asString),
            "operation"         -> SpanValue.StringValue(e.operation),
            "prompt_tokens"     -> SpanValue.LongValue(e.usage.promptTokens.toLong),
            "completion_tokens" -> SpanValue.LongValue(e.usage.completionTokens.toLong),
            "thinking_tokens"   -> SpanValue.LongValue(e.usage.thinkingTokens.getOrElse(0).toLong),
            "output_tokens"     -> SpanValue.LongValue(e.usage.totalOutputTokens.toLong),
            "total_tokens"      -> SpanValue.LongValue(e.usage.totalTokens.toLong)
          )
        )

      case run: TraceEvent.AgentRunEnded =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.AgentCall,
          startTime = run.timestamp,
          endTime = Some(run.timestamp),
          status = SpanStatus.Ok,
          attributes = Map(
            "thread_id"     -> SpanValue.StringValue(run.threadId),
            "run_id"        -> SpanValue.StringValue(run.runId),
            "agent"         -> SpanValue.StringValue(run.agent),
            "status"        -> SpanValue.StringValue(run.status.name),
            "message_count" -> SpanValue.LongValue(run.messages.size.toLong),
            "input_tokens"  -> SpanValue.LongValue(run.usage.inputTokens),
            "output_tokens" -> SpanValue.LongValue(run.usage.outputTokens)
          ) ++ run.parentThreadId.map(id => "parent_thread_id" -> SpanValue.StringValue(id)) ++
            run.parentRunId.map(id => "parent_run_id" -> SpanValue.StringValue(id)) ++
            run.userId.map(id => "user_id" -> SpanValue.StringValue(id)) ++
            run.blockedBy.map(guardrail => "blocked_by" -> SpanValue.StringValue(guardrail)) ++
            run.activeAgent.map(agent => "active_agent" -> SpanValue.StringValue(agent))
        )

      case e: TraceEvent.CustomEvent =>
        val attrs = e.data.obj.map { case (k, v) =>
          k -> (v match {
            case ujson.Str(s)   => SpanValue.StringValue(s)
            case ujson.Num(n)   => SpanValue.DoubleValue(n)
            case ujson.Bool(b)  => SpanValue.BooleanValue(b)
            case ujson.Arr(arr) => SpanValue.StringListValue(arr.collect { case ujson.Str(s) => s }.toList)
            case _              => SpanValue.StringValue(v.toString)
          })
        }.toMap
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = s"custom:${e.name}",
          kind = SpanKind.Internal,
          startTime = e.timestamp,
          endTime = Some(e.timestamp),
          status = SpanStatus.Ok,
          attributes = attrs
        )

      case e: TraceEvent.EmbeddingUsageRecorded =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Embedding,
          startTime = e.timestamp,
          endTime = Some(e.timestamp),
          status = SpanStatus.Ok,
          attributes = Map(
            "model"         -> SpanValue.StringValue(e.model.asString),
            "operation"     -> SpanValue.StringValue(e.operation),
            "input_count"   -> SpanValue.LongValue(e.inputCount.toLong),
            "prompt_tokens" -> SpanValue.LongValue(e.usage.promptTokens.toLong),
            "total_tokens"  -> SpanValue.LongValue(e.usage.totalTokens.toLong)
          )
        )

      case e: TraceEvent.CostRecorded =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Internal,
          startTime = e.timestamp,
          endTime = Some(e.timestamp),
          status = SpanStatus.Ok,
          attributes = Map(
            "cost_usd"    -> SpanValue.DoubleValue(e.costUsd),
            "model"       -> SpanValue.StringValue(e.model.asString),
            "operation"   -> SpanValue.StringValue(e.operation),
            "token_count" -> SpanValue.LongValue(e.tokenCount.toLong),
            "cost_type"   -> SpanValue.StringValue(e.costType)
          )
        )

      case e: TraceEvent.RAGOperationCompleted =>
        val baseAttrs: Map[String, SpanValue] = Map(
          "operation"   -> SpanValue.StringValue(e.operation),
          "duration_ms" -> SpanValue.LongValue(e.duration.toMillis)
        )
        val embeddingAttr  = e.embeddingTokens.map(v => "embedding_tokens" -> SpanValue.LongValue(v.toLong))
        val promptAttr     = e.llmPromptTokens.map(v => "llm_prompt_tokens" -> SpanValue.LongValue(v.toLong))
        val completionAttr = e.llmCompletionTokens.map(v => "llm_completion_tokens" -> SpanValue.LongValue(v.toLong))
        val costAttr       = e.totalCostUsd.map(v => "total_cost_usd" -> SpanValue.DoubleValue(v))
        val stageAttrs = e.stageDurations.map { case (stage, d) =>
          s"stage_${stage}_ms" -> SpanValue.LongValue(d.toMillis)
        }
        val attrs = baseAttrs ++ embeddingAttr ++ promptAttr ++ completionAttr ++ costAttr ++ stageAttrs
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Rag,
          startTime = e.timestamp.minusMillis(e.duration.toMillis),
          endTime = Some(e.timestamp),
          status = SpanStatus.Ok,
          attributes = attrs
        )

      case e: TraceEvent.CacheHit =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Cache,
          startTime = e.timestamp,
          endTime = Some(e.timestamp),
          status = SpanStatus.Ok,
          attributes = Map(
            "similarity" -> SpanValue.DoubleValue(e.similarity),
            "threshold"  -> SpanValue.DoubleValue(e.threshold),
            "hit"        -> SpanValue.BooleanValue(true)
          )
        )

      case e: TraceEvent.CacheMiss =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Cache,
          startTime = e.timestamp,
          endTime = Some(e.timestamp),
          status = SpanStatus.Ok,
          attributes = Map(
            "reason" -> SpanValue.StringValue(e.reason.value),
            "hit"    -> SpanValue.BooleanValue(false)
          )
        )

      case e: TraceEvent.ImageGenerationCompleted =>
        val baseAttrs: Map[String, SpanValue] = Map(
          "model"       -> SpanValue.StringValue(e.model.asString),
          "operation"   -> SpanValue.StringValue(e.operation),
          "image_count" -> SpanValue.LongValue(e.imageCount.toLong),
          "size"        -> SpanValue.StringValue(e.size),
          "quality"     -> SpanValue.StringValue(e.quality),
          "duration_ms" -> SpanValue.LongValue(e.duration.toMillis),
          "success"     -> SpanValue.BooleanValue(e.success)
        )
        val providerAttr = e.provider.map(p => "provider" -> SpanValue.StringValue(p.asString))
        val costAttr     = e.costUsd.map(v => "cost_usd" -> SpanValue.DoubleValue(v))
        val errorAttr    = e.errorMessage.map(v => "error_message" -> SpanValue.StringValue(v))
        val attrs        = baseAttrs ++ providerAttr ++ costAttr ++ errorAttr
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Internal,
          startTime = e.timestamp.minusMillis(e.duration.toMillis),
          endTime = Some(e.timestamp),
          status =
            if (e.success) SpanStatus.Ok else SpanStatus.Error(e.errorMessage.getOrElse("Image generation failed")),
          attributes = attrs
        )
    }
  }
}

object TraceCollectorTracing {

  def apply[F[_]](store: TraceStore[F])(implicit lift: LiftToResult[F]): Result[TraceCollectorTracing[F]] = {
    val tracer = new TraceCollectorTracing[F](store)
    lift(store.saveTrace(Trace.start(tracer.traceId))).map(_ => tracer)
  }

  def apply[F[_]](store: TraceStore[F], traceId: TraceId)(implicit
    lift: LiftToResult[F]
  ): Result[TraceCollectorTracing[F]] = {
    val tracer = new TraceCollectorTracing[F](store, Some(traceId))
    lift(store.saveTrace(Trace.start(tracer.traceId))).map(_ => tracer)
  }
}
