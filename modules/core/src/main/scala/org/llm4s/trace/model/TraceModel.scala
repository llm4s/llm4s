package org.llm4s.trace.model

import org.llm4s.types.TraceId

import java.time.Instant
import java.util.UUID

/** Unique identifier for a single span within a trace. */
final case class SpanId(value: String) extends AnyVal {
  override def toString: String = value
}

object SpanId {
  def generate(): SpanId = SpanId(UUID.randomUUID().toString)
}

/**
 * Typed value stored in a span's attribute map.
 *
 *  Use the `as*` accessors (via `SpanValueOps`) to safely extract a specific type.
 */
sealed trait SpanValue extends Product with Serializable

object SpanValue {
  final case class StringValue(value: String)           extends SpanValue
  final case class LongValue(value: Long)               extends SpanValue
  final case class DoubleValue(value: Double)           extends SpanValue
  final case class BooleanValue(value: Boolean)         extends SpanValue
  final case class StringListValue(value: List[String]) extends SpanValue

  implicit class SpanValueOps(val sv: SpanValue) extends AnyVal {
    def asString: Option[String] = sv match {
      case StringValue(v) => Some(v)
      case _              => None
    }
    def asLong: Option[Long] = sv match {
      case LongValue(v) => Some(v)
      case _            => None
    }
    def asDouble: Option[Double] = sv match {
      case DoubleValue(v) => Some(v)
      case _              => None
    }
    def asBoolean: Option[Boolean] = sv match {
      case BooleanValue(v) => Some(v)
      case _               => None
    }
    def asStringList: Option[List[String]] = sv match {
      case StringListValue(v) => Some(v)
      case _                  => None
    }
  }
}

/** Lifecycle status of a span or trace. */
sealed trait SpanStatus extends Product with Serializable

object SpanStatus {
  case object Ok                          extends SpanStatus
  final case class Error(message: String) extends SpanStatus
  case object Running                     extends SpanStatus
}

/** Semantic category of a span, used for filtering and cost attribution. */
sealed trait SpanKind extends Product with Serializable

object SpanKind {
  case object Internal      extends SpanKind
  case object LlmCall       extends SpanKind
  case object ToolCall      extends SpanKind
  case object AgentCall     extends SpanKind
  case object NodeExecution extends SpanKind
  case object Embedding     extends SpanKind
  case object Rag           extends SpanKind
  case object Cache         extends SpanKind

  val all: List[SpanKind] = List(Internal, LlmCall, ToolCall, AgentCall, NodeExecution, Embedding, Rag, Cache)
}

/**
 * A point-in-time annotation attached to a span.
 *
 *  @param name       short label for the event (e.g. `"cache-lookup-started"`)
 *  @param timestamp  when the event occurred
 *  @param attributes additional typed key/value data
 */
final case class SpanEvent(
  name: String,
  timestamp: Instant,
  attributes: Map[String, SpanValue] = Map.empty
)

object SpanEvent {
  def apply(name: String): SpanEvent                                     = SpanEvent(name, Instant.now())
  def apply(name: String, attributes: Map[String, SpanValue]): SpanEvent = SpanEvent(name, Instant.now(), attributes)
}

/**
 * A single unit of work within a trace.
 *
 *  @param spanId       unique identifier for this span
 *  @param traceId      identifier of the containing trace
 *  @param parentSpanId parent span, or `None` for the root span
 *  @param name         human-readable operation name
 *  @param kind         semantic category of the operation
 *  @param startTime    when the span started
 *  @param endTime      when the span ended; `None` while still running
 *  @param status       outcome of the operation
 *  @param attributes   typed key/value metadata attached to the span
 *  @param events       ordered list of point-in-time annotations within the span
 */
final case class Span(
  spanId: SpanId,
  traceId: TraceId,
  parentSpanId: Option[SpanId],
  name: String,
  kind: SpanKind,
  startTime: Instant,
  endTime: Option[Instant],
  status: SpanStatus,
  attributes: Map[String, SpanValue] = Map.empty,
  events: List[SpanEvent] = List.empty
) {
  def withAttribute(key: String, value: SpanValue): Span = copy(attributes = attributes + (key -> value))
  def withEvent(event: SpanEvent): Span                  = copy(events = events :+ event)
  def end(endTime: Instant = Instant.now()): Span        = copy(endTime = Some(endTime))
  def withStatus(status: SpanStatus): Span               = copy(status = status)
}

object Span {
  def start(
    traceId: TraceId,
    name: String,
    kind: SpanKind,
    parentSpanId: Option[SpanId] = None
  ): Span = Span(
    spanId = SpanId.generate(),
    traceId = traceId,
    parentSpanId = parentSpanId,
    name = name,
    kind = kind,
    startTime = Instant.now(),
    endTime = None,
    status = SpanStatus.Running
  )
}

/**
 * Top-level container for a logical operation, grouping one or more spans.
 *
 *  @param traceId    unique identifier for this trace
 *  @param rootSpanId the entry-point span, or `None` before the first span is recorded
 *  @param metadata   arbitrary string key/value tags (e.g. experiment name, user ID)
 *  @param startTime  when the trace started
 *  @param endTime    when the trace ended; `None` while still running
 *  @param status     overall outcome of the trace
 */
final case class Trace(
  traceId: TraceId,
  rootSpanId: Option[SpanId],
  metadata: Map[String, String] = Map.empty,
  startTime: Instant,
  endTime: Option[Instant],
  status: SpanStatus
) {
  def end(endTime: Instant = Instant.now()): Trace    = copy(endTime = Some(endTime))
  def withStatus(status: SpanStatus): Trace           = copy(status = status)
  def withMetadata(key: String, value: String): Trace = copy(metadata = metadata + (key -> value))
}

object Trace {
  def start(traceId: TraceId = TraceId(UUID.randomUUID().toString)): Trace = Trace(
    traceId = traceId,
    rootSpanId = None,
    metadata = Map.empty,
    startTime = Instant.now(),
    endTime = None,
    status = SpanStatus.Running
  )

  def start(metadata: Map[String, String]): Trace = Trace(
    traceId = TraceId(UUID.randomUUID().toString),
    rootSpanId = None,
    metadata = metadata,
    startTime = Instant.now(),
    endTime = None,
    status = SpanStatus.Running
  )
}
