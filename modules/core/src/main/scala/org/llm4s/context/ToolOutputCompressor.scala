package org.llm4s.context

import org.llm4s.annotation.Experimental
import org.llm4s.error.ContextError
import org.llm4s.llmconnect.model.{ ContentPart, Message, ToolMessage }
import org.llm4s.types.{ ArtifactKey, ContentSize, Result }
import org.llm4s.util.BoundedJson
import org.slf4j.LoggerFactory
import ujson._

/**
 * Handles intelligent compression and externalization of tool outputs.
 *
 * Tool outputs (from function calls, API responses, file reads) can be very large
 * and quickly consume the context window. This compressor applies content-aware
 * strategies to reduce their size while preserving essential information.
 *
 * ==Content Type Detection==
 *
 * The compressor automatically detects content types:
 *
 *  - '''JSON/YAML''': Removes null values, empty strings, truncates large arrays
 *  - '''Logs''': Keeps head/tail, collapses repeated lines
 *  - '''Errors''': Preserves error message and top stack frames
 *  - '''Binary''': a `data:` URI, a run of base64, or text that is mostly control characters; externalized
 *    when it is over 2KB and there is a store, otherwise left as it is
 *  - '''Text''': Generic word-based truncation
 *
 * ==Size Thresholds==
 *
 * Content is processed based on size:
 *  - '''< 2KB''': Kept as-is (no compression)
 *  - '''2KB - 8KB''': Inline compression (type-specific)
 *  - '''> 8KB''': Externalized to [[ArtifactStore]] with content pointer; compressed inline instead when
 *    there is no store (see [[DeterministicCompressor.compressToCap]])
 *
 * ==Externalization==
 *
 * Large content is stored in an [[ArtifactStore]] and replaced with a pointer:
 * {{{
 * [EXTERNALIZED: abc123... | JSON | JSON object with 42 fields, 15234 bytes]
 * }}}
 *
 * The original content can be retrieved using the artifact key.
 *
 * @see [[ArtifactStore]] for content storage interface
 * @see [[DeterministicCompressor]] which uses this for the tool compaction phase
 */
@Experimental
object ToolOutputCompressor {
  private val logger = LoggerFactory.getLogger(getClass)

  /** Content larger than this is externalized to artifact storage (default: 8KB) */
  private val DefaultExternalizationThreshold: Long = 8192L

  /** Content larger than this but under externalization threshold gets inline compression (2KB) */
  private val MaxInlineToolOutput = 2048L

  /**
   * Compress tool messages using externalization and schema-aware strategies.
   *
   * Processes all ToolMessage instances in the sequence, leaving other message
   * types unchanged. Each tool message is analyzed for content type and size,
   * then compressed or externalized accordingly.
   *
   * @param messages The messages to process
   * @param artifactStore Storage for externalized content
   * @param threshold Size threshold for externalization (default: 8KB)
   * @return Processed messages with compressed tool outputs
   */
  def compressToolOutputs(
    messages: Seq[Message],
    artifactStore: ArtifactStore,
    threshold: Long = DefaultExternalizationThreshold
  ): Result[Seq[Message]] =
    compress(messages, Some(artifactStore), threshold)

  /**
   * As [[compressToolOutputs]], with `artifactStore` optional: without one nothing is externalized, since a pointer
   * to content nobody kept could never be resolved. Over-threshold text is compressed inline instead, and binary
   * content is left as it is.
   */
  private[context] def compress(
    messages: Seq[Message],
    artifactStore: Option[ArtifactStore],
    threshold: Long = DefaultExternalizationThreshold
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

  /**
   * The thresholds apply to the result's text in total, so text split across parts cannot slip under them.
   *
   *  - One text part is compressed as it always was: externalized over `threshold`, compressed inline over
   *    `MaxInlineToolOutput`.
   *  - Several text parts over `MaxInlineToolOutput` are compressed each in place, by its own content type, so each
   *    text stays beside the media it describes. When they are still over the bound - or over `threshold` to begin
   *    with - their text is externalized together, as one artifact (each part marked by its position), and the
   *    first text part becomes its one pointer: the result's text is then one pointer, whatever the number of parts.
   *    Media parts are kept, in place.
   */
  private def compressToolMessage(
    message: ToolMessage,
    store: Option[ArtifactStore],
    threshold: Long
  ): Result[Message] = {
    val texts                        = message.parts.collect { case ContentPart.Text(t) => t }
    def bytes(ts: Seq[String]): Long = ts.map(t => ContentSize.fromString(t).asBytes).sum
    val total                        = bytes(texts)

    def rewriteEach(f: String => Result[String]): Result[Vector[ContentPart]] =
      message.parts.foldLeft[Result[Vector[ContentPart]]](Right(Vector.empty)) { (acc, part) =>
        acc.flatMap { done =>
          part match {
            case ContentPart.Text(text) => f(text).map(t => done :+ ContentPart.Text(t))
            case media                  => Right(done :+ media)
          }
        }
      }

    // all the text as one artifact; the first text part its pointer, the other text parts gone, the media in place
    def externalizeTogether(artifactStore: ArtifactStore): Result[Vector[ContentPart]] = {
      val joined = texts.zipWithIndex.map { case (t, i) => s"[text part ${i + 1}]\n$t" }.mkString("\n\n")
      externalizeToolOutput(joined, artifactStore, detectContentType(joined)).map { pointer =>
        val first = message.parts.indexWhere(_.isInstanceOf[ContentPart.Text])
        message.parts.zipWithIndex.collect {
          case (_: ContentPart.Text, `first`)           => ContentPart.Text(pointer)
          case (media, _) if ContentPart.isMedia(media) => media
        }
      }
    }

    def externalizeEach(artifactStore: ArtifactStore): Result[Vector[ContentPart]] =
      rewriteEach(text => externalizeToolOutput(text, artifactStore, detectContentType(text)))
    def inline: Result[Vector[ContentPart]] =
      rewriteEach(text => compressInlineContent(text, detectContentType(text)))
    val binary = texts.size == 1 && detectContentType(texts.head) == "binary"

    val rewritten: Option[Result[Vector[ContentPart]]] =
      if (texts.size <= 1) (total, store) match {
        // binary content is never cut: it is stored whole, or left as it is
        case (size, Some(s)) if binary && size > MaxInlineToolOutput => Some(externalizeEach(s))
        case _ if binary                                             => None
        case (size, Some(s)) if size > threshold                     => Some(externalizeEach(s))
        case (size, _) if size > MaxInlineToolOutput                 => Some(inline)
        case _                                                       => None
      }
      else
        (total, store) match {
          case (size, Some(s)) if size > threshold => Some(externalizeTogether(s))
          case (size, _) if size > MaxInlineToolOutput =>
            Some(inline.flatMap { parts =>
              store match {
                case Some(s) if bytes(parts.collect { case ContentPart.Text(t) => t }) > MaxInlineToolOutput =>
                  externalizeTogether(s)
                case _ => Right(parts)
              }
            })
          case _ => None
        }
    rewritten.fold[Result[Message]](Right(message))(_.map(message.withParts))
  }

  private def externalizeToolOutput(
    content: String,
    artifactStore: ArtifactStore,
    contentType: String
  ): Result[String] = {
    val key     = ArtifactKey.fromContent(content)
    val summary = generateContentSummary(content, contentType)

    for {
      _ <- artifactStore.store(key, content).left.map { error =>
        ContextError.artifactStoreFailed("store", key.asString, error.message)
      }
      externalizedInfo = ExternalizedContent(key, contentType, summary)
    } yield createContentPointer(externalizedInfo)
  }

  private def compressInlineContent(content: String, contentType: String): Result[String] =
    contentType match {
      case "json" | "yaml" => compressStructuredData(content)
      case "log" | "trace" => compressLogContent(content)
      case "error"         => compressErrorContent(content)
      case "binary"        => Right(content) // never cut: it would be useless, and nothing could restore it
      case _               => compressGenericText(content)
    }

  /** The fewest characters a run of base64 needs before it counts as binary. */
  private val MinBase64Run = 256

  /**
   * Whether `content` is binary: a base64 `data:` URI, a run of at least [[MinBase64Run]] base64 characters with
   * no spaces (line breaks allowed), or text more than a tenth of which is control characters. Mentioning the word
   * "base64" is not enough (#2817).
   */
  private[context] def looksBinary(content: String): Boolean = {
    val trimmed = content.trim
    def isBase64Char(c: Char) =
      (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "+/=-_".indexOf(c.toInt) >= 0
    def dataUri = trimmed.startsWith("data:") && trimmed.indexOf(";base64,") > 0
    def base64Run = {
      val body = trimmed.filterNot(c => c == '\n' || c == '\r')
      body.length >= MinBase64Run && body.forall(isBase64Char)
    }
    def controlHeavy = {
      val control =
        trimmed.count(c => (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') || c == '\uFFFD')
      trimmed.nonEmpty && control * 10 > trimmed.length
    }
    dataUri || base64Run || controlHeavy
  }

  private def detectContentType(content: String): String = {
    val trimmed = content.trim

    trimmed match {
      case c if looksBinary(c)                                                             => "binary"
      case c if c.startsWith("{") && c.endsWith("}")                                       => "json"
      case c if c.startsWith("[") && c.endsWith("]")                                       => "json"
      case c if c.contains("---") && (c.contains(":") || c.contains("-"))                  => "yaml"
      case c if c.contains("ERROR:") || c.contains("Exception") || c.contains("Traceback") => "error"
      case c if c.contains("INFO ") || c.contains("DEBUG ") || c.contains("WARN ")         => "log"
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

  // A tool result is untrusted: one nested more than 512 levels deep is refused before it is parsed,
  // as `compressJsonRecursively` and `ujson.write` recurse once per level (#1562), and is then
  // compressed as text like any other content that is not JSON
  private def parseJsonSafely(content: String): Result[Value] =
    BoundedJson
      .read(content)
      .left
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
 * Storage interface for externalized content.
 *
 * When tool outputs exceed the externalization threshold, they are stored
 * in an ArtifactStore and replaced with content pointers. This allows the
 * conversation to reference the content without including it inline.
 *
 * ==Content-Addressed Storage==
 *
 * Content is stored using content-addressed keys (based on content hash),
 * which enables:
 *  - Deduplication of identical outputs
 *  - Efficient retrieval without scanning
 *  - Immutable content guarantees
 *
 * ==Implementations==
 *
 * Use [[ArtifactStore.inMemory]] for testing and short-lived sessions.
 * For production, implement with persistent storage (database, S3, etc.).
 */
@Experimental
trait ArtifactStore {

  /** Store content under the given key. */
  def store(key: ArtifactKey, content: String): Result[Unit]

  /** Retrieve content by key, returning None if not found. */
  def retrieve(key: ArtifactKey): Result[Option[String]]

  /** Check if content exists for the given key. */
  def exists(key: ArtifactKey): Boolean
}

/**
 * Factory methods for [[ArtifactStore]] implementations.
 */
@Experimental
object ArtifactStore {

  /** Create an in-memory artifact store (suitable for testing). */
  def inMemory(): ArtifactStore = new InMemoryArtifactStore()
}

/**
 * Thread-safe in-memory implementation of [[ArtifactStore]].
 *
 * Uses ConcurrentHashMap for safe concurrent access.
 * Suitable for testing and single-JVM use cases.
 */
private class InMemoryArtifactStore extends ArtifactStore {
  import java.util.concurrent.ConcurrentHashMap

  private val storage = new ConcurrentHashMap[ArtifactKey, String]()

  def store(key: ArtifactKey, content: String): Result[Unit] = {
    storage.put(key, content)
    Right(())
  }

  def retrieve(key: ArtifactKey): Result[Option[String]] =
    Right(Option(storage.get(key)))

  def exists(key: ArtifactKey): Boolean =
    storage.containsKey(key)
}

/** What stands in a conversation for content moved to an [[ArtifactStore]]. */
final private case class ExternalizedContent(key: ArtifactKey, contentType: String, summary: String)
