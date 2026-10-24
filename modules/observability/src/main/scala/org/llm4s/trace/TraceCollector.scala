package org.llm4s.trace

import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.trace.model._
import org.llm4s.trace.store.TraceStore
import org.llm4s.types.{ Result, TraceId }
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

  override def traceToolCall(toolName: String, input: String, output: String): Result[Unit] =
    lift(
      store.saveSpan(
        Span
          .start(traceId, s"tool:$toolName", SpanKind.ToolCall)
          .withAttribute("tool_name", SpanValue.StringValue(toolName))
          .withAttribute("input", SpanValue.StringValue(input))
          .withAttribute("output", SpanValue.StringValue(output))
          .end()
          .withStatus(SpanStatus.Ok)
      )
    )

  override def traceError(error: Throwable, context: String): Result[Unit] = {
    val errorMessage = Option(error.getMessage).getOrElse("")
    lift(
      store.saveSpan(
        Span
          .start(traceId, "error", SpanKind.Internal)
          .withAttribute("error_type", SpanValue.StringValue(error.getClass.getSimpleName))
          .withAttribute("error_message", SpanValue.StringValue(errorMessage))
          .withAttribute("context", SpanValue.StringValue(context))
          .end()
          .withStatus(SpanStatus.Error(s"${error.getClass.getSimpleName}: $errorMessage"))
      )
    )
  }

  override def traceCompletion(completion: Completion, model: String): Result[Unit] =
    lift(
      store.saveSpan(
        Span
          .start(traceId, s"llm:$model", SpanKind.LlmCall)
          .withAttribute("model", SpanValue.StringValue(model))
          .withAttribute("completion_id", SpanValue.StringValue(completion.id))
          .withAttribute("tool_calls_count", SpanValue.LongValue(completion.message.toolCalls.length.toLong))
          .withAttribute("content_length", SpanValue.LongValue(completion.message.content.length.toLong))
          .end()
          .withStatus(SpanStatus.Ok)
      )
    )

  override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] =
    lift(
      store.saveSpan(
        Span
          .start(traceId, s"tokens:$operation", SpanKind.Internal)
          .withAttribute("model", SpanValue.StringValue(model))
          .withAttribute("operation", SpanValue.StringValue(operation))
          .withAttribute("prompt_tokens", SpanValue.LongValue(usage.promptTokens.toLong))
          .withAttribute("completion_tokens", SpanValue.LongValue(usage.completionTokens.toLong))
          .withAttribute("total_tokens", SpanValue.LongValue(usage.totalTokens.toLong))
          .end()
          .withStatus(SpanStatus.Ok)
      )
    )

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

    event match {
      case TraceEvent.AgentInitialized(query, tools, ts) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.AgentCall,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "query" -> SpanValue.StringValue(query),
            "tools" -> SpanValue.StringListValue(tools.toList)
          )
        )

      case TraceEvent.CompletionReceived(id, model, toolCalls, content, ts, _, _, _, _, _, _, _, _, _) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.LlmCall,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "completion_id"    -> SpanValue.StringValue(id),
            "model"            -> SpanValue.StringValue(model),
            "tool_calls_count" -> SpanValue.LongValue(toolCalls.toLong),
            "content"          -> SpanValue.StringValue(content)
          )
        )

      case TraceEvent.ToolExecuted(name, input, output, duration, success, ts) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.ToolCall,
          startTime = ts.minusMillis(duration.toMillis),
          endTime = Some(ts),
          status = if (success) SpanStatus.Ok else SpanStatus.Error(s"Tool $name failed"),
          attributes = Map(
            "tool_name"   -> SpanValue.StringValue(name),
            "input"       -> SpanValue.StringValue(input),
            "output"      -> SpanValue.StringValue(output),
            "duration_ms" -> SpanValue.LongValue(duration.toMillis),
            "success"     -> SpanValue.BooleanValue(success)
          )
        )

      case TraceEvent.ErrorOccurred(error, context, ts, _, _, _, _, _, _) =>
        val errorMessage = Option(error.getMessage).getOrElse("")
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Internal,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Error(s"${error.getClass.getSimpleName}: $errorMessage"),
          attributes = Map(
            "error_type"    -> SpanValue.StringValue(error.getClass.getSimpleName),
            "error_message" -> SpanValue.StringValue(errorMessage),
            "context"       -> SpanValue.StringValue(context)
          )
        )

      case TraceEvent.TokenUsageRecorded(usage, model, operation, ts, _) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Internal,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "model"             -> SpanValue.StringValue(model),
            "operation"         -> SpanValue.StringValue(operation),
            "prompt_tokens"     -> SpanValue.LongValue(usage.promptTokens.toLong),
            "completion_tokens" -> SpanValue.LongValue(usage.completionTokens.toLong),
            "total_tokens"      -> SpanValue.LongValue(usage.totalTokens.toLong)
          )
        )

      case TraceEvent.AgentRunEnded(
            threadId,
            runId,
            agent,
            status,
            messages,
            usage,
            ts,
            parentThread,
            parentRun,
            _,
            _
          ) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.AgentCall,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "thread_id"     -> SpanValue.StringValue(threadId),
            "run_id"        -> SpanValue.StringValue(runId),
            "agent"         -> SpanValue.StringValue(agent),
            "status"        -> SpanValue.StringValue(status),
            "message_count" -> SpanValue.LongValue(messages.size.toLong),
            "input_tokens"  -> SpanValue.LongValue(usage.inputTokens),
            "output_tokens" -> SpanValue.LongValue(usage.outputTokens)
          ) ++ parentThread.map(id => "parent_thread_id" -> SpanValue.StringValue(id)) ++
            parentRun.map(id => "parent_run_id" -> SpanValue.StringValue(id))
        )

      case TraceEvent.CustomEvent(name, data, ts, _) =>
        val attrs = data.obj.map { case (k, v) =>
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
          name = s"custom:$name",
          kind = SpanKind.Internal,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = attrs
        )

      case TraceEvent.EmbeddingUsageRecorded(usage, model, operation, inputCount, ts, _, _) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Embedding,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "model"         -> SpanValue.StringValue(model),
            "operation"     -> SpanValue.StringValue(operation),
            "input_count"   -> SpanValue.LongValue(inputCount.toLong),
            "prompt_tokens" -> SpanValue.LongValue(usage.promptTokens.toLong),
            "total_tokens"  -> SpanValue.LongValue(usage.totalTokens.toLong)
          )
        )

      case TraceEvent.CostRecorded(costUsd, model, operation, tokenCount, costType, ts) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Internal,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "cost_usd"    -> SpanValue.DoubleValue(costUsd),
            "model"       -> SpanValue.StringValue(model),
            "operation"   -> SpanValue.StringValue(operation),
            "token_count" -> SpanValue.LongValue(tokenCount.toLong),
            "cost_type"   -> SpanValue.StringValue(costType)
          )
        )

      case TraceEvent.RAGOperationCompleted(
            operation,
            duration,
            embeddingTokens,
            llmPromptTokens,
            llmCompletionTokens,
            totalCostUsd,
            ts
          ) =>
        val baseAttrs: Map[String, SpanValue] = Map(
          "operation"   -> SpanValue.StringValue(operation),
          "duration_ms" -> SpanValue.LongValue(duration.toMillis)
        )
        val embeddingAttr  = embeddingTokens.map(v => "embedding_tokens" -> SpanValue.LongValue(v.toLong))
        val promptAttr     = llmPromptTokens.map(v => "llm_prompt_tokens" -> SpanValue.LongValue(v.toLong))
        val completionAttr = llmCompletionTokens.map(v => "llm_completion_tokens" -> SpanValue.LongValue(v.toLong))
        val costAttr       = totalCostUsd.map(v => "total_cost_usd" -> SpanValue.DoubleValue(v))
        val attrs          = baseAttrs ++ embeddingAttr ++ promptAttr ++ completionAttr ++ costAttr
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Rag,
          startTime = ts.minusMillis(duration.toMillis),
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = attrs
        )

      case TraceEvent.CacheHit(similarity, threshold, ts) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Cache,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "similarity" -> SpanValue.DoubleValue(similarity),
            "threshold"  -> SpanValue.DoubleValue(threshold),
            "hit"        -> SpanValue.BooleanValue(true)
          )
        )

      case TraceEvent.CacheMiss(reason, ts) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Cache,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "reason" -> SpanValue.StringValue(reason.value),
            "hit"    -> SpanValue.BooleanValue(false)
          )
        )

      case TraceEvent.ImageGenerationCompleted(
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
            ts
          ) =>
        val baseAttrs: Map[String, SpanValue] = Map(
          "model"       -> SpanValue.StringValue(model),
          "provider"    -> SpanValue.StringValue(provider),
          "operation"   -> SpanValue.StringValue(operation),
          "image_count" -> SpanValue.LongValue(imageCount.toLong),
          "size"        -> SpanValue.StringValue(size),
          "quality"     -> SpanValue.StringValue(quality),
          "duration_ms" -> SpanValue.LongValue(duration.toMillis),
          "success"     -> SpanValue.BooleanValue(success)
        )
        val costAttr  = costUsd.map(v => "cost_usd" -> SpanValue.DoubleValue(v))
        val errorAttr = errorMessage.map(v => "error_message" -> SpanValue.StringValue(v))
        val attrs     = baseAttrs ++ costAttr ++ errorAttr
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Internal,
          startTime = ts.minusMillis(duration.toMillis),
          endTime = Some(ts),
          status = if (success) SpanStatus.Ok else SpanStatus.Error(errorMessage.getOrElse("Image generation failed")),
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
