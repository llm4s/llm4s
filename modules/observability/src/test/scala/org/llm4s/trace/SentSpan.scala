package org.llm4s.trace

import java.time.Instant
import scala.util.Try

/**
 * One span of an OTLP/JSON export request, decoded for assertions: its ids, name, times, status and attributes, the
 * attributes' `AnyValue`s read back to plain JSON (a string, a number, a boolean, an array).
 */
final case class SentSpan(
  traceId: String,
  spanId: String,
  parentSpanId: Option[String],
  name: String,
  startTime: Instant,
  endTime: Instant,
  attributes: Map[String, ujson.Value],
  status: Option[ujson.Value]
) {
  def has(key: String): Boolean      = attributes.contains(key)
  def attr(key: String): ujson.Value = attributes.getOrElse(key, throw new NoSuchElementException(s"$name has no $key"))
  def str(key: String): String       = attr(key).str
  def kind: String                   = str("langfuse.observation.type")
  def level: Option[String]          = attributes.get("langfuse.observation.level").map(_.str)
  def statusMessage: Option[String]  = attributes.get("langfuse.observation.status_message").map(_.str)
  def model: Option[String]          = attributes.get("langfuse.observation.model.name").map(_.str)
  def input: ujson.Value             = SentSpan.json(str("langfuse.observation.input"))
  def output: ujson.Value            = SentSpan.json(str("langfuse.observation.output"))
  def hasOutput: Boolean             = has("langfuse.observation.output")
  def usage: ujson.Value             = ujson.read(str("langfuse.observation.usage_details"))
  def meta(key: String): ujson.Value = attr(s"langfuse.observation.metadata.$key")
  def hasMeta(key: String): Boolean  = has(s"langfuse.observation.metadata.$key")
  def traceMeta(key: String): ujson.Value = attr(s"langfuse.trace.metadata.$key")
  def setsTrace: Boolean                  = has("langfuse.trace.name")
}

object SentSpan {

  /** Every span of the export request `body`, in order. */
  def all(body: String): Seq[SentSpan] =
    for {
      resource <- ujson.read(body)("resourceSpans").arr.toSeq
      scope    <- resource("scopeSpans").arr.toSeq
      span     <- scope("spans").arr.toSeq
    } yield SentSpan(
      span("traceId").str,
      span("spanId").str,
      span.obj.get("parentSpanId").map(_.str),
      span("name").str,
      instant(span("startTimeUnixNano").str),
      instant(span("endTimeUnixNano").str),
      span("attributes").arr.map(a => a("key").str -> value(a("value"))).toMap,
      span.obj.get("status")
    )

  /** An OTLP `AnyValue` as plain JSON. */
  def value(any: ujson.Value): ujson.Value = {
    val o = any.obj
    o.get("stringValue")
      .orElse(o.get("boolValue"))
      .orElse(o.get("intValue").map(v => ujson.Num(v.str.toLong.toDouble)))
      .orElse(o.get("doubleValue"))
      .orElse(o.get("arrayValue").map(a => ujson.Arr.from(a("values").arr.map(value))))
      .getOrElse(throw new IllegalArgumentException(s"not an AnyValue: $any"))
  }

  private def instant(nanos: String): Instant = {
    val n = BigInt(nanos)
    Instant.ofEpochSecond((n / 1000000000).toLong, (n % 1000000000).toLong)
  }

  /** An input or output attribute: JSON text read back, or the plain string it was. */
  def json(text: String): ujson.Value =
    Try(ujson.read(text)).toOption.filter(!_.isInstanceOf[ujson.Num]).getOrElse(ujson.Str(text))
}
