package org.llm4s.trace

import org.llm4s.agent.AgentState
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
    lift(store.saveSpan(eventToSpan(event)))

  override def traceAgentState(state: AgentState): Result[Unit] =
    lift(
      store.saveSpan(
        Span
          .start(traceId, "agent-state-update", SpanKind.AgentCall)
          .withAttribute("status", SpanValue.StringValue(state.status.toString))
          .withAttribute("message_count", SpanValue.LongValue(state.conversation.messages.length.toLong))
          .withAttribute("log_count", SpanValue.LongValue(state.logs.length.toLong))
          .end()
          .withStatus(SpanStatus.Ok)
      )
    )

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

  private def eventToSpan(event: TraceEvent): Span = {
    val spanName = event.eventType

    event match {
      case TraceEvent.AgentInitialized(query, tools, ts) =>
        Span(
          spanId = SpanId.generate(),
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

      case TraceEvent.CompletionReceived(id, model, toolCalls, content, ts) =>
        Span(
          spanId = SpanId.generate(),
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
          spanId = SpanId.generate(),
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.ToolCall,
          startTime = ts.minusMillis(duration),
          endTime = Some(ts),
          status = if (success) SpanStatus.Ok else SpanStatus.Error(s"Tool $name failed"),
          attributes = Map(
            "tool_name"   -> SpanValue.StringValue(name),
            "input"       -> SpanValue.StringValue(input),
            "output"      -> SpanValue.StringValue(output),
            "duration_ms" -> SpanValue.LongValue(duration),
            "success"     -> SpanValue.BooleanValue(success)
          )
        )

      case TraceEvent.ErrorOccurred(error, context, ts) =>
        val errorMessage = Option(error.getMessage).getOrElse("")
        Span(
          spanId = SpanId.generate(),
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

      case TraceEvent.TokenUsageRecorded(usage, model, operation, ts) =>
        Span(
          spanId = SpanId.generate(),
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

      case TraceEvent.AgentStateUpdated(status, messageCount, logCount, ts) =>
        Span(
          spanId = SpanId.generate(),
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.AgentCall,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "status"        -> SpanValue.StringValue(status),
            "message_count" -> SpanValue.LongValue(messageCount.toLong),
            "log_count"     -> SpanValue.LongValue(logCount.toLong)
          )
        )

      case TraceEvent.CustomEvent(name, data, ts) =>
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
          spanId = SpanId.generate(),
          traceId = traceId,
          parentSpanId = None,
          name = s"custom:$name",
          kind = SpanKind.Internal,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = attrs
        )

      case TraceEvent.EmbeddingUsageRecorded(usage, model, operation, inputCount, ts) =>
        Span(
          spanId = SpanId.generate(),
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
          spanId = SpanId.generate(),
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
            durationMs,
            embeddingTokens,
            llmPromptTokens,
            llmCompletionTokens,
            totalCostUsd,
            ts
          ) =>
        val baseAttrs: Map[String, SpanValue] = Map(
          "operation"   -> SpanValue.StringValue(operation),
          "duration_ms" -> SpanValue.LongValue(durationMs)
        )
        val embeddingAttr  = embeddingTokens.map(v => "embedding_tokens" -> SpanValue.LongValue(v.toLong))
        val promptAttr     = llmPromptTokens.map(v => "llm_prompt_tokens" -> SpanValue.LongValue(v.toLong))
        val completionAttr = llmCompletionTokens.map(v => "llm_completion_tokens" -> SpanValue.LongValue(v.toLong))
        val costAttr       = totalCostUsd.map(v => "total_cost_usd" -> SpanValue.DoubleValue(v))
        val attrs          = baseAttrs ++ embeddingAttr ++ promptAttr ++ completionAttr ++ costAttr
        Span(
          spanId = SpanId.generate(),
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.Rag,
          startTime = ts.minusMillis(durationMs),
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = attrs
        )

      case TraceEvent.CacheHit(similarity, threshold, ts) =>
        Span(
          spanId = SpanId.generate(),
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
          spanId = SpanId.generate(),
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
