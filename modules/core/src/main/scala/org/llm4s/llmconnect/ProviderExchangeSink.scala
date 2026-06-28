package org.llm4s.llmconnect

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.types.TryOps

import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.{ Files, Path, StandardOpenOption }
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import org.llm4s.util.Redaction

import scala.annotation.unused

/**
 * Sink for completed provider exchanges.
 *
 * Library code emits completed exchanges here when exchange logging is
 * enabled. Sinks are deliberately simple and synchronous in the first version;
 * callers can always wrap them later if they want batching or async behavior.
 */
trait ProviderExchangeSink:
  def record(exchange: ProviderExchange): Unit

object ProviderExchangeSink:
  val noop: ProviderExchangeSink = new ProviderExchangeSink:
    override def record(@unused exchange: ProviderExchange): Unit =
      ()

  private val timestampFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC)

  def createRunScopedJsonl(
    directory: Path,
    startedAt: Instant = Instant.now()
  ): Result[JsonlProviderExchangeSink] =
    for
      _    <- prepareDirectory(directory)
      sink <- createJsonlSink(directory, startedAt)
    yield sink

  private def prepareDirectory(directory: Path): Result[Path] =
    if Files.exists(directory) then
      if Files.isDirectory(directory) then Right(directory)
      else Left(configurationError(directory, s"Path is not a directory: $directory"))
    else
      Try(Files.createDirectories(directory)).toResult.left
        .map(error => configurationError(directory, error.message))
        .map(_ => directory)

  private def createJsonlSink(directory: Path, startedAt: Instant): Result[JsonlProviderExchangeSink] =
    val timestamp = timestampFormatter.format(startedAt)
    val baseName  = s"provider-exchanges-$timestamp"

    def candidate(index: Int): Path =
      val fileName =
        if index == 1 then s"$baseName.jsonl"
        else s"$baseName-$index.jsonl"
      directory.resolve(fileName)

    def createAt(index: Int): Result[JsonlProviderExchangeSink] =
      val path = candidate(index)
      Try(Files.createFile(path)) match
        case Success(_)                             => Right(JsonlProviderExchangeSink(path))
        case Failure(_: FileAlreadyExistsException) => createAt(index + 1)
        case Failure(error)                         => Left(configurationError(directory, error.getMessage))

    createAt(1)

  private def configurationError(directory: Path, detail: String): ConfigurationError =
    ConfigurationError(
      s"Failed to create provider exchange log file in directory: ${directory.toAbsolutePath.normalize()}. $detail"
    )

/**
 * Appends provider exchanges as JSON Lines to a local file.
 *
 * This is intended as the first practical debugging sink for development. It
 * creates parent directories on demand and writes one redacted JSON object per
 * line.
 */
final case class JsonlProviderExchangeSink(path: Path) extends ProviderExchangeSink:
  override def record(exchange: ProviderExchange): Unit =
    val parent = path.getParent
    if parent != null then Files.createDirectories(parent)

    val line = ujson.write(ProviderExchangeJson.toJson(exchange)) + System.lineSeparator()
    Files.write(
      path,
      line.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.APPEND
    )

private object ProviderExchangeJson:
  def toJson(exchange: ProviderExchange): ujson.Obj =
    ujson.Obj(
      "exchange_id"    -> exchange.exchangeId,
      "provider"       -> exchange.provider,
      "model"          -> exchange.model.getOrElse(""),
      "request_id"     -> exchange.requestId.getOrElse(""),
      "correlation_id" -> exchange.correlationId.getOrElse(""),
      "started_at"     -> exchange.startedAt.toString,
      "completed_at"   -> exchange.completedAt.toString,
      "duration_ms"    -> exchange.durationMs,
      "outcome"        -> exchange.outcome.toString,
      "request_body"   -> Redaction.redactForLogging(exchange.requestBody),
      "response_body"  -> exchange.responseBody.map(body => Redaction.redactForLogging(body)).getOrElse(""),
      "error_message"  -> exchange.errorMessage.map(message => Redaction.redactForLogging(message)).getOrElse("")
    )
