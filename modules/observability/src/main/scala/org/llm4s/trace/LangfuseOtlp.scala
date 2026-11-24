package org.llm4s.trace

import java.time.Instant

/**
 * Langfuse observations as OpenTelemetry spans, encoded as OTLP/HTTP JSON (#2883).
 *
 * Langfuse removed its legacy batch ingestion API (`POST /api/public/ingestion` trace, span, generation and event
 * objects) from Langfuse Cloud on 2026-11-16; what replaces it is its OpenTelemetry endpoint,
 * `POST /api/public/otel/v1/traces`, which takes an OTLP `ExportTraceServiceRequest` as JSON or protobuf over HTTP. An
 * observation is a span whose `langfuse.*` attributes carry what the legacy object's fields did: its type, input,
 * output, metadata, level, status message, model and usage, and - on the spans that set them - the trace's name,
 * session, user, tags, metadata and visibility. See https://langfuse.com/integrations/native/opentelemetry for the
 * attribute mapping and https://opentelemetry.io/docs/specs/otlp/#json-protobuf-encoding for the encoding: hex ids,
 * integer enums, 64-bit integers as decimal strings.
 *
 * Encoded with ujson: `llm4s-observability` has no OpenTelemetry SDK dependency, and needs none for this.
 */
private[llm4s] object LangfuseOtlp {

  /** Langfuse's OTLP/HTTP traces endpoint, under the host's base URL. */
  val TracesPath = "/api/public/otel/v1/traces"

  /** Langfuse's score creation endpoint, which takes a JSON array of scores. */
  val ScoresPath = "/api/public/scores"

  /** Sent on every trace export: without it Langfuse v4 can take minutes to show what it received. */
  val IngestionVersionHeader: (String, String) = "x-langfuse-ingestion-version" -> "4"

  // The paths a configured URL may already end with: the legacy ingestion endpoint (the documented default until
  // #2883), and the endpoints this module now posts to.
  private val apiSuffixes = Seq("/api/public/ingestion", TracesPath, "/api/public/otel", ScoresPath)

  /**
   * The Langfuse host `url` names, without a trailing slash. A URL that ends with one of Langfuse's API paths -
   * `LANGFUSE_URL` set to the legacy `.../api/public/ingestion` endpoint, as this module documented before #2883, or
   * to the OTel endpoint - names the host it is on.
   */
  def baseUrl(url: String): String = {
    val trimmed = url.trim.stripSuffix("/")
    apiSuffixes.find(trimmed.endsWith).fold(trimmed)(suffix => trimmed.dropRight(suffix.length)).stripSuffix("/")
  }

  def tracesUrl(url: String): String = baseUrl(url) + TracesPath
  def scoresUrl(url: String): String = baseUrl(url) + ScoresPath

  /** Langfuse's observation types this module sends (`langfuse.observation.type`). */
  enum ObservationType(val wire: String) {
    case Span       extends ObservationType("span")
    case Generation extends ObservationType("generation")
    case Event      extends ObservationType("event")
  }

  /**
   * What a span sets on its trace: Langfuse merges these into the trace by id, so a later span that sets them again
   * (a resumed run's) updates the trace.
   */
  final case class TraceAttributes(
    name: String,
    sessionId: Option[String],
    userId: Option[String],
    tags: Seq[String],
    metadata: ujson.Obj,
    public: Boolean
  )

  /**
   * One Langfuse observation: the span `context` names in its trace, under `parentSpanId` when it has a parent.
   * `input` and `output` are sent as they are when they are strings, else as JSON text, which Langfuse parses.
   */
  final case class Observation(
    context: TraceContext,
    parentSpanId: Option[String],
    name: String,
    kind: ObservationType,
    startTime: Instant,
    endTime: Instant,
    input: Option[ujson.Value] = None,
    output: Option[ujson.Value] = None,
    metadata: ujson.Obj = ujson.Obj(),
    level: Option[String] = None,
    statusMessage: Option[String] = None,
    model: Option[String] = None,
    usage: Option[ujson.Obj] = None,
    trace: Option[TraceAttributes] = None
  )

  /** The deployment every observation of an export belongs to: `langfuse.environment`, `.release`, `.version`. */
  final case class Deployment(environment: String, release: String, version: String)

  /** The `ExportTraceServiceRequest` carrying `observations`, as one resource and one instrumentation scope. */
  def request(observations: Seq[Observation], deployment: Deployment, scope: String): ujson.Obj =
    ujson.Obj(
      "resourceSpans" -> ujson.Arr(
        ujson.Obj(
          "resource" -> ujson.Obj("attributes" -> ujson.Arr(attribute("service.name", ujson.Str("llm4s")))),
          "scopeSpans" -> ujson.Arr(
            ujson.Obj(
              "scope" -> ujson.Obj("name" -> scope),
              "spans" -> ujson.Arr.from(observations.map(span(_, deployment)))
            )
          )
        )
      )
    )

  // SpanKind INTERNAL; StatusCode ERROR. OTLP JSON writes enums as their integer values.
  private val InternalKind = 1
  private val ErrorStatus  = 2

  private[trace] def span(o: Observation, deployment: Deployment): ujson.Obj = {
    val attributes = scala.collection.mutable.ArrayBuffer[ujson.Obj](
      attribute("langfuse.observation.type", ujson.Str(o.kind.wire)),
      attribute("langfuse.environment", ujson.Str(deployment.environment)),
      attribute("langfuse.release", ujson.Str(deployment.release)),
      attribute("langfuse.version", ujson.Str(deployment.version))
    )
    o.input.foreach(v => attributes += attribute("langfuse.observation.input", ujson.Str(text(v))))
    o.output.foreach(v => attributes += attribute("langfuse.observation.output", ujson.Str(text(v))))
    attributes ++= flattened("langfuse.observation.metadata", o.metadata)
    o.level.foreach(l => attributes += attribute("langfuse.observation.level", ujson.Str(l)))
    o.statusMessage.foreach(m => attributes += attribute("langfuse.observation.status_message", ujson.Str(m)))
    o.model.foreach(m => attributes += attribute("langfuse.observation.model.name", ujson.Str(m)))
    o.usage.foreach { usage =>
      // Langfuse's usage_details is a JSON object of integer counts; an unknown count is left out
      val counts = usage.value.collect { case (k, ujson.Num(n)) => k -> ujson.Num(n.toLong.toDouble) }
      if (counts.nonEmpty)
        attributes += attribute("langfuse.observation.usage_details", ujson.Str(ujson.Obj.from(counts).render()))
    }
    o.trace.foreach { t =>
      attributes += attribute("langfuse.trace.name", ujson.Str(t.name))
      t.sessionId.foreach(id => attributes += attribute("session.id", ujson.Str(id)))
      t.userId.foreach(id => attributes += attribute("user.id", ujson.Str(id)))
      if (t.tags.nonEmpty) attributes += attribute("langfuse.trace.tags", ujson.Arr.from(t.tags.map(ujson.Str(_))))
      attributes += attribute("langfuse.trace.public", ujson.Bool(t.public))
      attributes ++= flattened("langfuse.trace.metadata", t.metadata)
    }

    val end = if (o.endTime.isBefore(o.startTime)) o.startTime else o.endTime
    val json = ujson.Obj(
      "traceId"           -> o.context.traceId,
      "spanId"            -> o.context.spanId,
      "name"              -> o.name,
      "kind"              -> InternalKind,
      "startTimeUnixNano" -> unixNanos(o.startTime),
      "endTimeUnixNano"   -> unixNanos(end),
      "attributes"        -> ujson.Arr.from(attributes)
    )
    o.parentSpanId.foreach(p => json("parentSpanId") = p)
    if (o.level.contains("ERROR"))
      json("status") = ujson.Obj("code" -> ErrorStatus, "message" -> o.statusMessage.getOrElse(""))
    json
  }

  /** Nanoseconds since the epoch, as OTLP JSON writes a `fixed64`: a decimal string. */
  private[trace] def unixNanos(at: Instant): String =
    (at.getEpochSecond * 1000000000L + at.getNano).toString

  /** A string as it is; anything else as JSON text. */
  private def text(v: ujson.Value): String = v.strOpt.getOrElse(v.render())

  /**
   * `metadata`'s keys as attributes `<prefix>.<key>`, as Langfuse reads `langfuse.*.metadata.*`: a string, number or
   * boolean as that value, an object or array as its JSON text. A null is left out.
   */
  private def flattened(prefix: String, metadata: ujson.Obj): Seq[ujson.Obj] =
    metadata.value.toSeq.collect {
      case (key, v) if !v.isNull =>
        val value = v match {
          case _: ujson.Str | _: ujson.Num | _: ujson.Bool => v
          case other                                       => ujson.Str(other.render())
        }
        attribute(s"$prefix.$key", value)
    }

  private[trace] def attribute(key: String, value: ujson.Value): ujson.Obj =
    ujson.Obj("key" -> key, "value" -> anyValue(value))

  /** An OTLP `AnyValue`. A whole number within a double's exact range is an `intValue`, a decimal string. */
  private[trace] def anyValue(value: ujson.Value): ujson.Obj = value match {
    case ujson.Str(s)  => ujson.Obj("stringValue" -> s)
    case ujson.Bool(b) => ujson.Obj("boolValue" -> b)
    case ujson.Num(n) if n.isWhole && math.abs(n) <= 9007199254740992d =>
      ujson.Obj("intValue" -> n.toLong.toString)
    case ujson.Num(n) if n.isNaN || n.isInfinite => ujson.Obj("stringValue" -> n.toString)
    case ujson.Num(n)                            => ujson.Obj("doubleValue" -> n)
    case ujson.Arr(items) => ujson.Obj("arrayValue" -> ujson.Obj("values" -> ujson.Arr.from(items.map(anyValue))))
    case other            => ujson.Obj("stringValue" -> other.render())
  }
}
