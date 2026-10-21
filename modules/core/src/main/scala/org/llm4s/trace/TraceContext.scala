package org.llm4s.trace

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets
import java.security.{ MessageDigest, SecureRandom }
import java.util.Locale

/**
 * A position in a distributed trace, in W3C Trace Context terms (design 4.58): the trace (`traceId`, 32 lowercase hex
 * digits), one span in it (`spanId`, 16 lowercase hex digits) and whether the trace is sampled. Neither id is all
 * zeros. It is the serializable carrier a caller stores or sends so that work done elsewhere - another thread, another
 * process, another machine - continues the same trace: written as a `traceparent` header value
 * ([[traceParent]], `00-<traceId>-<spanId>-<flags>`) and read back with [[TraceContext.parse]]; its JSON form is
 * that string.
 *
 * Whoever continues a context makes it the *remote parent* of the span it opens, so that span keeps the context's
 * trace id. A backend that cannot express a parent ignores it.
 */
@Stable
final case class TraceContext private (traceId: String, spanId: String, sampled: Boolean):

  /** This context as a W3C `traceparent` value: version `00`, the ids, and flags `01` when sampled, else `00`. */
  def traceParent: String = s"00-$traceId-$spanId-${if sampled then "01" else "00"}"

  /**
   * The context of the span `key` names in this trace: the same trace id and sampling, and a span id derived from the
   * trace id and `key` alone (the first 8 bytes of a SHA-256), so every process that names the same span the same way
   * computes the same id, with no coordination. The new span's parent is whatever its emitter says; it need not be
   * this context's span.
   */
  def spanFor(key: String): TraceContext =
    new TraceContext(traceId, TraceContext.nonZero(TraceContext.digest(s"$traceId/$key"), 16), sampled)

  def withSampled(s: Boolean): TraceContext = new TraceContext(traceId, spanId, s)

  override def toString: String = traceParent

object TraceContext:

  private val entropy = new SecureRandom()

  /** Throws `IllegalArgumentException` for an invalid id; use [[of]] for untrusted input. */
  def apply(traceId: String, spanId: String, sampled: Boolean = true): TraceContext =
    of(traceId, spanId, sampled).fold(e => throw new IllegalArgumentException(e.message), identity)

  /** A context with these ids, or a `ValidationError` naming what is wrong with them. Upper-case hex is lower-cased. */
  def of(traceId: String, spanId: String, sampled: Boolean = true): Result[TraceContext] =
    val trace = Option(traceId).fold("")(_.toLowerCase(Locale.ROOT))
    val span  = Option(spanId).fold("")(_.toLowerCase(Locale.ROOT))
    val problems = List(
      Option.when(!hex(trace, 32))(s"traceId must be 32 hex digits, not all zero, was '$traceId'"),
      Option.when(!hex(span, 16))(s"spanId must be 16 hex digits, not all zero, was '$spanId'")
    ).flatten
    if problems.isEmpty then Right(new TraceContext(trace, span, sampled))
    else Left(ValidationError("traceContext", problems.mkString("; ")))

  /**
   * Reads a W3C `traceparent` value: `<version>-<traceId>-<spanId>-<flags>`, two hex digits of version (not `ff`) and of
   * flags, whose lowest bit is the sampled flag. A version above `00` may carry more fields after the flags, which are
   * ignored, as the W3C specification asks.
   */
  def parse(traceParent: String): Result[TraceContext] =
    def invalid(why: String) = Left(ValidationError("traceparent", s"'$traceParent' is not a traceparent value: $why"))
    Option(traceParent).map(_.trim.split("-", -1).toList) match
      case Some(version :: trace :: span :: flags :: rest)
          if hex(version, 2, zeroOk = true) && version != "ff" && hex(flags, 2, zeroOk = true) &&
            (rest.isEmpty || version != "00") =>
        of(trace, span, (Integer.parseInt(flags, 16) & 1) == 1).left.flatMap(e => invalid(e.message))
      case _ => invalid("expected 00-<32 hex>-<16 hex>-<2 hex>")

  /** A context with a new random trace and span, sampled. */
  def random(): TraceContext =
    def ids(length: Int): String =
      val bytes = new Array[Byte](length / 2)
      entropy.nextBytes(bytes)
      nonZero(bytes.map(b => f"${b & 0xff}%02x").mkString, length)
    new TraceContext(ids(32), ids(16), sampled = true)

  /**
   * The root context `key` names: a trace id and a span id both derived from `key` (a SHA-256), sampled. For a unit of
   * work whose trace must be computed the same way by several parties without one of them choosing it at random: the
   * same `key` always names the same trace.
   */
  def rootFor(key: String): TraceContext =
    val d = digest(s"root/$key")
    new TraceContext(nonZero(d.take(32), 32), nonZero(d.slice(32, 48), 16), sampled = true)

  given ReadWriter[TraceContext] =
    upickle.default
      .readwriter[String]
      .bimap(_.traceParent, s => parse(s).fold(e => throw new IllegalArgumentException(e.message), identity))

  private def hex(s: String, length: Int, zeroOk: Boolean = false): Boolean =
    s.length == length && s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) && (zeroOk || s.exists(
      _ != '0'
    ))

  private def digest(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  // an all-zero id is invalid; a digest or random draw that produces one (2^-64 at worst) is moved off it
  private def nonZero(id: String, length: Int): String =
    val cut = id.take(length)
    if cut.exists(_ != '0') then cut else cut.dropRight(1) + "1"

/**
 * Where a traced event sits in a trace (design 4.58): the span it is ([[context]]), that span's parent, if any - which
 * may be a span of another process, a remote parent - and when the span started. The event's own `timestamp` is
 * when it ended. A backend that builds spans uses all three; one that does not ignores them.
 */
@Stable
final case class TraceSpan private (context: TraceContext, parent: Option[TraceContext], startedAt: java.time.Instant):
  def withContext(c: TraceContext): TraceSpan         = new TraceSpan(c, parent, startedAt)
  def withParent(p: TraceContext): TraceSpan          = new TraceSpan(context, Some(p), startedAt)
  def withParent(p: Option[TraceContext]): TraceSpan  = new TraceSpan(context, p, startedAt)
  def withStartedAt(at: java.time.Instant): TraceSpan = new TraceSpan(context, parent, at)

object TraceSpan:
  def apply(context: TraceContext, parent: Option[TraceContext], startedAt: java.time.Instant): TraceSpan =
    new TraceSpan(context, parent, startedAt)

  /** The span's ids as flat JSON fields - `trace_id`, `span_id` and, with a parent, `parent_span_id` - for `toJson`. */
  private[trace] def annotate(json: ujson.Obj, span: Option[TraceSpan]): ujson.Obj =
    span.foreach { s =>
      json("trace_id") = s.context.traceId
      json("span_id") = s.context.spanId
      s.parent.foreach(p => json("parent_span_id") = p.spanId)
    }
    json
