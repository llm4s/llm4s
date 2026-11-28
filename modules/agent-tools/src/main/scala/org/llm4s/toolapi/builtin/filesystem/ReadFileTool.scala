package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.nio.file.{ Files, LinkOption, Paths }
import scala.util.Try

/**
 * Result from reading a file.
 *
 * @param truncated whether `content` is not the rest of the file: `max_lines` stopped it, or the result cap did
 * @param nextOffset where the next page starts, as the `offset` to read it with, when the result cap cut `content`
 *                   (#3273); `None` when this page reaches the end of what was asked for
 */
case class ReadFileResult(
  path: String,
  content: String,
  size: Long,
  lines: Int,
  truncated: Boolean,
  nextOffset: Option[Int] = None
)

object ReadFileResult {
  implicit val readFileResultRW: ReadWriter[ReadFileResult] = macroRW[ReadFileResult]
}

/**
 * Tool for reading file contents.
 *
 * Features:
 * - Configurable size limits
 * - Path restrictions for security
 * - Optional line limit
 * - Encoding detection
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.filesystem._
 *
 * val readTool = ReadFileTool.create(FileConfig(
 *   maxFileSize = 512 * 1024,
 *   allowedPaths = Some(Seq("/tmp", "/home/user/workspace"))
 * ))
 *
 * val tools = ToolRegistry.of(Seq(readTool))
 * agent.run("Read the contents of /tmp/data.txt", tools)
 * }}}
 */
object ReadFileTool {

  private def createSchema = Schema
    .`object`[Map[String, Any]]("File read parameters")
    .withProperty(
      Schema.property(
        "path",
        Schema.string("Absolute path to the file to read")
      )
    )
    .withProperty(
      Schema.property(
        "max_lines",
        Schema.integer("Maximum number of lines to read (optional, reads entire file if not specified)")
      )
    )
    .withProperty(
      Schema.property(
        "offset",
        Schema.integer(
          "Character offset into the text to start from (optional, default 0): a result longer than the tool's " +
            "limit is cut, and its nextOffset is the offset of the next page"
        )
      )
    )
    .withProperty(
      Schema.property(
        "encoding",
        Schema
          .string("Character encoding (default: UTF-8)")
          .withEnum(Seq("UTF-8", "ISO-8859-1", "US-ASCII"))
      )
    )

  /**
   * Create a read file tool with the given configuration, returning a Result for safe error handling.
   *
   * @param maxResultChars the most characters of content one call hands the model (#3273). The text the tool reads
   *                       (the file's lines joined with `\n`, at most `max_lines` of them) is returned from `offset`
   *                       in pages of at most this many characters, never cut inside a surrogate pair: a cut page is
   *                       `truncated` and its `nextOffset` is the `offset` of the next, so successive calls read the
   *                       whole text with no gap or overlap. The default is
   *                       [[org.llm4s.toolapi.builtin.BuiltinTools.DefaultMaxResultChars]]
   */
  def createSafe(
    config: FileConfig = FileConfig(),
    maxResultChars: Int = org.llm4s.toolapi.builtin.BuiltinTools.DefaultMaxResultChars
  ): Result[ToolFunction[ReadFileResult]] =
    ToolBuilder[ReadFileResult](
      name = "read_file",
      description = s"Read the contents of a file. Maximum file size: ${config.maxFileSize / 1024}KB. " +
        s"A result holds at most $maxResultChars characters; when it is cut, `truncated` is true and " +
        "`nextOffset` is the `offset` to read the next page with. " +
        s"Blocked paths: ${config.describedBlockedPaths}. " +
        config.describedAllowedPaths.map(p => s"Allowed paths: $p").getOrElse(""),
      schema = createSchema
    ).withHandler { extractor =>
      for {
        pathStr <- extractor.getString("path")
        // absent or null is the default; a wrong type refuses the call
        maxLines <- extractor.getOptionalInt("max_lines")
        encoding <- extractor.getOptionalString("encoding").map(_.getOrElse("UTF-8"))
        offset   <- extractor.getOptionalInt("offset").map(_.getOrElse(0))
        result   <- readFile(pathStr, maxLines, encoding, config).left.map(ToolHandlerError.failed)
        page     <- paged(result, offset, maxResultChars).left.map(ToolHandlerError.failed)
      } yield page
    }.build()
      .map(
        org.llm4s.toolapi.builtin.BuiltinToolHints.register(_, org.llm4s.toolapi.builtin.BuiltinToolHints.ReadOnlyLocal)
      )

  /**
   * The page of `result` from `offset`: at most `maxChars` characters, ending before a high surrogate whose pair
   * would be cut, with `nextOffset` set when more follows. An offset past the end is refused; one inside a surrogate
   * pair is moved back to the pair's start, so a page never starts with a lone low surrogate.
   */
  private[builtin] def paged(result: ReadFileResult, offset: Int, maxChars: Int): Either[String, ReadFileResult] = {
    val text = result.content
    if (offset < 0) Left(s"offset must not be negative, was $offset")
    else if (offset > 0 && offset >= text.length)
      Left(s"offset $offset is past the end of the text, which is ${text.length} characters")
    else {
      val start = if (offset > 0 && Character.isLowSurrogate(text.charAt(offset))) offset - 1 else offset
      val limit = math.max(2, maxChars)
      if (text.length - start <= limit) Right(result.copy(content = text.substring(start)))
      else {
        val cut = start + limit
        val end = if (Character.isHighSurrogate(text.charAt(cut - 1))) cut - 1 else cut
        Right(result.copy(content = text.substring(start, end), truncated = true, nextOffset = Some(end)))
      }
    }
  }

  /**
   * Default read file tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[ReadFileResult]] = createSafe()

  private def readFile(
    pathStr: String,
    maxLines: Option[Int],
    encodingStr: String,
    config: FileConfig
  ): Either[String, ReadFileResult] = {
    val pathResult =
      Try(Paths.get(pathStr).toAbsolutePath.normalize()).toEither.left.map(e => s"Invalid path: ${e.getMessage}")

    pathResult.flatMap { path =>
      // Security checks: the policy judges the real location, and the file is read from that resolved path
      config.resolve(path) match {
        case None =>
          Left(s"Access denied: path '$pathStr' is not allowed")
        case Some(real) =>
          val linkOptions = if (config.followSymlinks) Array.empty[LinkOption] else Array(LinkOption.NOFOLLOW_LINKS)

          if (!Files.exists(path, linkOptions: _*)) {
            Left(s"File not found: $pathStr")
          } else if (!Files.isRegularFile(path, linkOptions: _*)) {
            Left(s"Not a regular file: $pathStr")
          } else if (!config.allowHardLinks && PathPolicy.hasOtherHardLinks(real)) {
            // a hard link planted in an allowed directory is a file elsewhere under an allowed name (#3272)
            Left(s"Access denied: '$pathStr' has other hard links, which are refused")
          } else {
            val fileSize = Files.size(real)
            if (fileSize > config.maxFileSize) {
              Left(s"File too large: ${fileSize} bytes (max: ${config.maxFileSize} bytes)")
            } else {
              Try(java.nio.charset.Charset.forName(encodingStr)).toEither.left
                .map(_ => s"Unsupported encoding: $encodingStr")
                .flatMap { charset =>
                  Try {
                    val allLines   = Files.readAllLines(real, charset)
                    val totalLines = allLines.size()

                    val (content, truncated) = maxLines match {
                      case Some(limit) if limit < totalLines =>
                        val limitedLines = new java.util.ArrayList[String]()
                        for (i <- 0 until limit)
                          limitedLines.add(allLines.get(i))
                        (String.join("\n", limitedLines), true)
                      case _ =>
                        (String.join("\n", allLines), false)
                    }

                    ReadFileResult(
                      path = path.toString,
                      content = content,
                      size = fileSize,
                      lines = totalLines,
                      truncated = truncated
                    )
                  }.toEither.left.map(e => s"Failed to read file: ${e.getMessage}")
                }
            }
          }
      }
    }
  }
}
