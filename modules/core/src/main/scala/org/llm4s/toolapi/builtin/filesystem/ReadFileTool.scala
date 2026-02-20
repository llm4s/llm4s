package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.nio.file.{ Files, LinkOption, Paths }
import scala.util.Try

/**
 * Result from reading a file.
 */
case class ReadFileResult(
  path: String,
  content: String,
  size: Long,
  lines: Int,
  truncated: Boolean
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
 * val tools = new ToolRegistry(Seq(readTool))
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
        "encoding",
        Schema
          .string("Character encoding (default: UTF-8)")
          .withEnum(Seq("UTF-8", "ISO-8859-1", "US-ASCII"))
      )
    )

  /**
   * Create a read file tool with the given configuration, returning a Result for safe error handling.
   */
  def createSafe(config: FileConfig = FileConfig()): Result[ToolFunction[Map[String, Any], ReadFileResult]] =
    ToolBuilder[Map[String, Any], ReadFileResult](
      name = "read_file",
      description = s"Read the contents of a file. Maximum file size: ${config.maxFileSize / 1024}KB. " +
        s"Blocked paths: ${config.blockedPaths.mkString(", ")}. " +
        config.allowedPaths.map(p => s"Allowed paths: ${p.mkString(", ")}").getOrElse(""),
      schema = createSchema
    ).withHandler { extractor =>
      for {
        pathStr <- extractor.getString("path")
        maxLines = extractor.getInt("max_lines").toOption
        encoding = extractor.getString("encoding").fold(_ => "UTF-8", identity)
        result <- readFile(pathStr, maxLines, encoding, config)
      } yield result
    }.buildSafe()

  /**
   * Default read file tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[Map[String, Any], ReadFileResult]] = createSafe()

  /**
   * Create a read file tool with the given configuration.
   *
   * @throws IllegalStateException if tool creation fails
   */
  @deprecated("Use createSafe() which returns Result[ToolFunction] for safe error handling", "0.2.9")
  def create(config: FileConfig = FileConfig()): ToolFunction[Map[String, Any], ReadFileResult] =
    createSafe(config) match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"ReadFileTool.create failed: ${e.formatted}")
    }

  /**
   * Default read file tool with standard configuration.
   *
   * @throws IllegalStateException if tool initialization fails
   */
  @deprecated("Use toolSafe which returns Result[ToolFunction] for safe error handling", "0.2.9")
  lazy val tool: ToolFunction[Map[String, Any], ReadFileResult] =
    toolSafe match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"ReadFileTool.tool lazy initialization failed: ${e.formatted}")
    }

  private def readFile(
    pathStr: String,
    maxLines: Option[Int],
    encodingStr: String,
    config: FileConfig
  ): Either[String, ReadFileResult] = {
    val pathResult =
      Try(Paths.get(pathStr).toAbsolutePath.normalize()).toEither.left.map(e => s"Invalid path: ${e.getMessage}")

    pathResult.flatMap { path =>
      // Security checks
      if (!config.isPathAllowed(path)) {
        Left(s"Access denied: path '$pathStr' is not allowed")
      } else {
        val linkOptions = if (config.followSymlinks) Array.empty[LinkOption] else Array(LinkOption.NOFOLLOW_LINKS)

        if (!Files.exists(path, linkOptions: _*)) {
          Left(s"File not found: $pathStr")
        } else if (!Files.isRegularFile(path, linkOptions: _*)) {
          Left(s"Not a regular file: $pathStr")
        } else {
          val fileSize = Files.size(path)
          if (fileSize > config.maxFileSize) {
            Left(s"File too large: ${fileSize} bytes (max: ${config.maxFileSize} bytes)")
          } else {
            Try {
              val charset    = java.nio.charset.Charset.forName(encodingStr)
              val allLines   = Files.readAllLines(path, charset)
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
