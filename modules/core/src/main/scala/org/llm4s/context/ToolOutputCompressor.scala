package org.llm4s.context

import org.llm4s.error.ContextError
import org.llm4s.llmconnect.model.{ Message, ToolMessage }
import org.llm4s.types.{ ArtifactKey, ContentSize, ExternalizationThreshold, ExternalizedContent, Result }
import org.slf4j.LoggerFactory
import ujson._

import scala.util.Try

/**
 * Handles intelligent compression and externalization of tool outputs.
 * Implements content-addressed storage for large outputs and schema-aware
 * compression for structured data.
 */
object ToolOutputCompressor {
  private val logger = LoggerFactory.getLogger(getClass)

  private val DefaultExternalizationThreshold: ExternalizationThreshold = 8192L // 8KB
  private val MaxInlineToolOutput                                       = 2048L // 2KB max for inline content

  /**
   * Compress tool messages using externalization and schema-aware strategies
   */
  def compressToolOutputs(
    messages: Seq[Message],
    artifactStore: ArtifactStore,
    threshold: ExternalizationThreshold = DefaultExternalizationThreshold
  ): Result[Seq[Message]] = {
    val processed = messages.map {
      case tool: ToolMessage => compressToolMessage(tool, artifactStore, threshold)
      case other             => Right(other)
    }

    // Collect any errors and successful results
    val errors    = processed.collect { case Left(error) => error }
    val successes = processed.collect { case Right(msg) => msg }

    errors match {
      case Nil  => Right(successes)
      case errs => Left(errs.head) // Return first error
    }
  }

  private def compressToolMessage(
    message: ToolMessage,
    artifactStore: ArtifactStore,
    threshold: ExternalizationThreshold
  ): Result[Message] = {
    val contentSize = ContentSize.fromString(message.content)
    val contentType = detectContentType(message.content)

    contentSize.bytes match {
      case size if size > threshold =>
        externalizeToolOutput(message, artifactStore, contentType)
      case size if size > MaxInlineToolOutput =>
        compressInlineContent(message, contentType)
      case _ =>
        Right(message)
    }
  }

  private def externalizeToolOutput(
    message: ToolMessage,
    artifactStore: ArtifactStore,
    contentType: String
  ): Result[ToolMessage] = {
    val key         = ArtifactKey.fromContent(message.content)
    val contentSize = ContentSize.fromString(message.content)
    val summary     = generateContentSummary(message.content, contentType)

    for {
      _ <- artifactStore.store(key, message.content).left.map { error =>
        ContextError.artifactStoreFailed("store", key.value, error.message)
      }
      externalizedInfo = ExternalizedContent(key, contentSize.bytes, contentType, summary)
      pointer          = createContentPointer(externalizedInfo)
    } yield message.copy(content = pointer)
  }

  private def compressInlineContent(message: ToolMessage, contentType: String): Result[ToolMessage] = {
    val compressed = contentType match {
      case "json" | "yaml" => compressStructuredData(message.content)
      case "log" | "trace" => compressLogContent(message.content)
      case "error"         => compressErrorContent(message.content)
      case "binary"        => externalizeBinaryContent(message)
      case _               => compressGenericText(message.content)
    }

    compressed match {
      case Right(content) => Right(message.copy(content = content))
      case Left(error)    => Left(error)
    }
  }

  private def detectContentType(content: String): String = {
    val trimmed = content.trim

    trimmed match {
      case c if c.startsWith("{") && c.endsWith("}")                                       => "json"
      case c if c.startsWith("[") && c.endsWith("]")                                       => "json"
      case c if c.contains("---") && (c.contains(":") || c.contains("-"))                  => "yaml"
      case c if c.contains("ERROR:") || c.contains("Exception") || c.contains("Traceback") => "error"
      case c if c.contains("INFO ") || c.contains("DEBUG ") || c.contains("WARN ")         => "log"
      case c if c.startsWith("data:") || c.contains("base64")                              => "binary"
      case _                                                                               => "text"
    }
  }

  private def compressStructuredData(content: String): Result[String] =
    parseJsonSafely(content) match {
      case Right(json) =>
        val compressed = compressJsonRecursively(json)
        Right(ujson.write(compressed, indent = -1))
      case Left(_) =>
        logger.debug("Content is not valid JSON, applying generic text compression")
        compressGenericText(content)
    }

  private def parseJsonSafely(content: String): Result[Value] =
    Try(ujson.read(content)).toEither.left
      .map(_ => ContextError.schemaCompressionFailed("ToolOutputCompressor", "Failed to parse JSON content"))

  private def compressJsonRecursively(value: Value): Value = value match {
    case obj: Obj =>
      val filtered = obj.value.filter {
        case (_, Null)                    => false // Drop null values
        case (_, Str(""))                 => false // Drop empty strings
        case (_, Arr(arr)) if arr.isEmpty => false // Drop empty arrays
        case _                            => true
      }

      Obj.from(filtered.view.mapValues(compressJsonRecursively).toMap)

    case arr: Arr if arr.value.length > 20 =>
      // Cap large arrays: head 10 + tail 10 + count marker
      val head    = arr.value.take(10).map(compressJsonRecursively)
      val tail    = arr.value.takeRight(10).map(compressJsonRecursively)
      val skipped = arr.value.length - 20

      val marker = Str(s"...[+$skipped items]...")
      Arr.from(head ++ Seq(marker) ++ tail)

    case arr: Arr =>
      Arr.from(arr.value.map(compressJsonRecursively))

    case Num(n) if n.isWhole && n > 1000 =>
      Num(math.round(n.toDouble).toDouble) // Round large numbers

    case other => other
  }

  private def compressLogContent(content: String): Result[String] = {
    val lines = content.split("\n")

    lines.length match {
      case n if n <= 120 => Right(content) // Keep short logs as-is
      case n =>
        val head    = lines.take(80)
        val tail    = lines.takeRight(40)
        val skipped = n - 120

        val compressed = head ++
          Seq(s"... [collapsed $skipped repeated/verbose log lines] ...") ++
          tail

        // Collapse repeated lines
        Right(collapseRepeatedLines(compressed.toIndexedSeq).mkString("\n"))
    }
  }

  private def compressErrorContent(content: String): Result[String] = {
    val lines = content.split("\n")

    // Extract error type and message (first few lines)
    val errorHeader = lines.take(3).mkString("\n")

    // Find stack trace portion
    val stackLines =
      lines.drop(3).filter(line => line.trim.startsWith("at ") || line.contains(".java:") || line.contains(".scala:"))

    stackLines.length match {
      case n if n <= 10 => Right(content) // Keep short stacks as-is
      case _ =>
        val topFrames    = stackLines.take(10)
        val skippedCount = stackLines.length - 10

        Right(s"$errorHeader\n${topFrames.mkString("\n")}\n... [+$skippedCount additional stack frames] ...")
    }
  }

  private def externalizeBinaryContent(message: ToolMessage): Result[String] =
    // Binary content should never be inline
    Right(s"[BINARY CONTENT - ${message.content.length} bytes - externalized: ${message.toolCallId}]")

  private def compressGenericText(content: String): Result[String] =
    content.length match {
      case len if len <= 1000 => Right(content)
      case _ =>
        val words = content.split("\\s+")
        val summary = words.take(50).mkString(" ") +
          s" ... [+${words.length - 50} words] ... " +
          words.takeRight(20).mkString(" ")
        Right(summary)
    }

  private def collapseRepeatedLines(lines: Seq[String]): Seq[String] = {
    val (_, collapsed) = lines.foldLeft((Option.empty[String], Seq.empty[String])) { case ((lastLine, acc), line) =>
      lastLine match {
        case Some(prev) if prev == line =>
          // Found repeat - check if we already have a repeat marker
          acc.lastOption match {
            case Some(marker) if marker.contains("×") =>
              val count   = extractRepeatCount(marker) + 1
              val updated = acc.init :+ s"$line ×$count"
              (Some(line), updated)
            case _ =>
              val updated = acc.init :+ s"$line ×2"
              (Some(line), updated)
          }
        case _ =>
          (Some(line), acc :+ line)
      }
    }

    collapsed
  }

  private def extractRepeatCount(marker: String): Int =
    "×(\\d+)".r.findFirstMatchIn(marker).map(_.group(1).toInt).getOrElse(1)

  private def generateContentSummary(content: String, contentType: String): String = {
    val size = content.length
    contentType match {
      case "json" =>
        parseJsonSafely(content) match {
          case Right(json) => s"JSON object with ${countJsonFields(json)} fields, $size bytes"
          case Left(_)     => s"JSON-like content, $size bytes"
        }
      case "log" =>
        val lineCount = content.count(_ == '\n') + 1
        s"Log output with $lineCount lines, $size bytes"
      case "error" =>
        val firstLine = content.split("\n").headOption.getOrElse("Error")
        s"Error: ${firstLine.take(100)}, $size bytes"
      case _ =>
        s"${contentType.capitalize} content, $size bytes"
    }
  }

  private def countJsonFields(value: Value): Int = value match {
    case obj: Obj => obj.value.size
    case arr: Arr => arr.value.length
    case _        => 1
  }

  private def createContentPointer(externalized: ExternalizedContent): String =
    s"[EXTERNALIZED: ${externalized.key} | ${externalized.contentType.toUpperCase} | ${externalized.summary}]"
}

/**
 * Simple in-memory artifact store for externalized content.
 * In production, this could be replaced with cloud storage, database, etc.
 */
trait ArtifactStore {
  def store(key: ArtifactKey, content: String): Result[Unit]
  def retrieve(key: ArtifactKey): Result[Option[String]]
  def exists(key: ArtifactKey): Boolean
}

object ArtifactStore {
  def inMemory(): ArtifactStore = new InMemoryArtifactStore()
}

private class InMemoryArtifactStore extends ArtifactStore {
  private val storage = scala.collection.mutable.Map.empty[ArtifactKey, String]

  def store(key: ArtifactKey, content: String): Result[Unit] = {
    storage.put(key, content)
    Right(())
  }

  def retrieve(key: ArtifactKey): Result[Option[String]] =
    Right(storage.get(key))

  def exists(key: ArtifactKey): Boolean =
    storage.contains(key)
}
