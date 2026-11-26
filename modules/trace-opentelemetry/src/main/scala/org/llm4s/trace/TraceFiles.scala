// scalafix:off DisableSyntax.NoKeywordFinally, DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
package org.llm4s.trace

import io.opentelemetry.context.Context
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.{ ReadWriteSpan, ReadableSpan, SpanProcessor }
import io.opentelemetry.sdk.trace.`export`.SpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{ Files, Path, StandardOpenOption }
import java.time.{ Instant, ZoneOffset }
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.{ ConcurrentHashMap, ThreadLocalRandom }
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/** The file mode's files (#2948): where a span goes and how a line is appended. */
private[trace] object TraceFiles {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  private val StartFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

  /**
   * The shared file of one tracing's unplaced events: `unplaced-20261125T101500Z-1a2b3c4d.jsonl`. It begins with a
   * letter no hex trace id has, so it cannot collide with a trace's file, and unplaced files sort by start time.
   */
  def unplacedFileName(start: Instant): String =
    f"unplaced-${StartFormat.format(start)}-${ThreadLocalRandom.current().nextInt()}%08x.jsonl"

  /** A placed trace's file: its 32-hex-character trace id. */
  def traceFileName(traceId: String): String = s"$traceId.jsonl"

  /** `dir`, created when missing; a [[ConfigurationError]] when it cannot be, is not a directory or is not writable. */
  def usableDirectory(dir: Path): Result[Path] = {
    def refused(why: String) =
      ConfigurationError(s"llm4s.tracing.file.dir '$dir' $why: file tracing needs a writable directory")
    Try(Files.createDirectories(dir)).toEither.left
      .map(e => refused(s"cannot be created (${e.getClass.getSimpleName}: ${e.getMessage})"))
      .flatMap { _ =>
        if (!Files.isDirectory(dir)) Left(refused("is not a directory"))
        else if (!Files.isWritable(dir)) Left(refused("is not writable"))
        else Right(dir)
      }
  }

  /** One OTLP/JSON `ExportTraceServiceRequest` holding `spans`, compact, UTF-8, ending in a newline. */
  def line(spans: java.util.Collection[SpanData]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    TraceRequestMarshaler.create(spans).writeJsonTo(out)
    out.write('\n')
    out.toByteArray
  }

  // Every append in this JVM takes this lock, so two tracings sharing a directory never write one file at once (a
  // FileChannel lock held twice in one JVM throws OverlappingFileLockException rather than waiting).
  private val jvmWide = new Object

  /**
   * Appends `bytes` - one whole line - to `file`, creating it and `dir` when missing. The channel is opened `APPEND`,
   * the line written by one `write` (looping only if the OS writes part of it), under this JVM's lock and, where the
   * file system supports one, an exclusive lock on the file, so another process appending to the same file under the
   * same lock never interleaves with the line.
   */
  def append(dir: Path, file: Path, bytes: Array[Byte]): Unit =
    jvmWide.synchronized {
      Files.createDirectories(dir)
      val channel =
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
      try {
        // a file system without locks (some network mounts) still gets the line, appended in one write
        val lock = Try(channel.lock()).toOption
        try {
          val buffer = ByteBuffer.wrap(bytes)
          while (buffer.hasRemaining) channel.write(buffer)
        } finally lock.foreach(l => Try(l.release()))
      } finally channel.close()
    }

  def logFailure(file: Path, error: Throwable): Unit =
    logger.warn(s"File tracing could not append to $file: ${error.getClass.getSimpleName}: ${error.getMessage}")
}

/**
 * Appends each batch of spans to the trace directory as OTLP JSON Lines (#2948): the spans of one placed trace as one
 * line of `<dir>/<trace-id>.jsonl`, and those `isPlaced` refuses - unplaced events, each a trace of its own - as one
 * line of the shared `<dir>/<unplacedFile>`. Nothing is buffered: a batch is on disk when `export` returns.
 */
final private[trace] class OtlpJsonFileSpanExporter(
  dir: Path,
  unplacedFile: String,
  isPlaced: SpanData => Boolean
) extends SpanExporter {

  def `export`(batch: java.util.Collection[SpanData]): CompletableResultCode = {
    val groups = batch.asScala.toVector.groupBy { span =>
      if (isPlaced(span)) TraceFiles.traceFileName(span.getTraceId) else unplacedFile
    }
    val failures = groups.toVector.flatMap { case (name, spans) =>
      val file = dir.resolve(name)
      try {
        TraceFiles.append(dir, file, TraceFiles.line(spans.asJavaCollection))
        None
      } catch {
        case NonFatal(e) =>
          TraceFiles.logFailure(file, e)
          Some(e)
      }
    }
    failures.headOption.fold(CompletableResultCode.ofSuccess())(CompletableResultCode.ofExceptionalFailure)
  }

  def flush(): CompletableResultCode    = CompletableResultCode.ofSuccess()
  def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/**
 * Writes each sampled span as it ends, as `SimpleSpanProcessor` does, so a trace's file grows while it runs and a
 * crash loses at most the spans not yet ended; `forceFlush` and `shutdown` have nothing buffered to write.
 *
 * It also tells a placed span from an unplaced one, which the span itself cannot: a placed root span and an unplaced
 * event's span both lack a parent. [[PlacedIds.placing]] says which is starting when the SDK calls [[onStart]],
 * within `startSpan` on the starting thread; a placed span's ids are kept from then until it ends, so the set holds
 * only the spans in flight and records nothing on the span itself.
 */
final private[trace] class TraceFileSpanProcessor(ids: PlacedIds, dir: Path, unplacedFile: String)
    extends SpanProcessor {

  private val placed = ConcurrentHashMap.newKeySet[String]()

  private def key(traceId: String, spanId: String): String = s"$traceId/$spanId"

  private val exporter =
    new OtlpJsonFileSpanExporter(dir, unplacedFile, span => placed.contains(key(span.getTraceId, span.getSpanId)))

  def onStart(parentContext: Context, span: ReadWriteSpan): Unit =
    if (ids.placing) placed.add(key(span.getSpanContext.getTraceId, span.getSpanContext.getSpanId))

  def isStartRequired: Boolean = true

  def onEnd(span: ReadableSpan): Unit = {
    val context = span.getSpanContext
    try if (context.isSampled) exporter.`export`(java.util.List.of(span.toSpanData))
    finally placed.remove(key(context.getTraceId, context.getSpanId))
  }

  def isEndRequired: Boolean = true

  override def forceFlush(): CompletableResultCode = exporter.flush()
  override def shutdown(): CompletableResultCode   = exporter.shutdown()
}
