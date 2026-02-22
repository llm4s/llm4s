package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.nio.file.{ Files, Paths, StandardOpenOption }
import scala.util.Try

/**
 * Result from writing a file.
 */
case class WriteFileResult(
  path: String,
  bytesWritten: Long,
  created: Boolean,
  appended: Boolean
)

object WriteFileResult {
  implicit val writeFileResultRW: ReadWriter[WriteFileResult] = macroRW[WriteFileResult]
}

/**
 * Tool for writing file contents.
 *
 * IMPORTANT: This tool requires explicit path allowlisting for safety.
 * It will not write to any path that is not in the allowedPaths list.
 *
 * Features:
 * - Requires explicit path allowlist
 * - Optional append mode
 * - Auto-creates parent directories
 * - Size limits
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.filesystem._
 *
 * val writeTool = WriteFileTool.create(WriteConfig(
 *   allowedPaths = Seq("/tmp", "/home/user/output")
 * ))
 *
 * val tools = new ToolRegistry(Seq(writeTool))
 * agent.run("Write 'Hello World' to /tmp/output.txt", tools)
 * }}}
 */
object WriteFileTool {

  private def createSchema = Schema
    .`object`[Map[String, Any]]("File write parameters")
    .withProperty(
      Schema.property(
        "path",
        Schema.string("Absolute path to the file to write")
      )
    )
    .withProperty(
      Schema.property(
        "content",
        Schema.string("Content to write to the file")
      )
    )
    .withProperty(
      Schema.property(
        "append",
        Schema.boolean("If true, append to existing file; if false, overwrite (default: false)")
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
   * Create a write file tool with the given configuration, returning a Result for safe error handling.
   *
   * @param config Write configuration with required allowedPaths
   */
  def createSafe(config: WriteConfig): Result[ToolFunction[Map[String, Any], WriteFileResult]] =
    ToolBuilder[Map[String, Any], WriteFileResult](
      name = "write_file",
      description = s"Write content to a file. " +
        s"Allowed paths: ${config.allowedPaths.mkString(", ")}. " +
        s"Maximum file size: ${config.maxFileSize / 1024}KB. " +
        s"Overwrite allowed: ${config.allowOverwrite}.",
      schema = createSchema
    ).withHandler { extractor =>
      for {
        pathStr <- extractor.getString("path")
        content <- extractor.getString("content")
        append   = extractor.getBoolean("append").fold(_ => false, identity)
        encoding = extractor.getString("encoding").fold(_ => "UTF-8", identity)
        result <- writeFile(pathStr, content, append, encoding, config)
      } yield result
    }.buildSafe()

  private def writeFile(
    pathStr: String,
    content: String,
    append: Boolean,
    encodingStr: String,
    config: WriteConfig
  ): Either[String, WriteFileResult] = {
    val pathResult =
      Try(Paths.get(pathStr).toAbsolutePath.normalize()).toEither.left.map(e => s"Invalid path: ${e.getMessage}")

    pathResult.flatMap { path =>
      // Security check - must be in allowed paths
      if (!config.isPathAllowed(path)) {
        Left(s"Access denied: path '$pathStr' is not in allowed paths. Allowed: ${config.allowedPaths.mkString(", ")}")
      } else {
        val contentBytes = content.getBytes(java.nio.charset.Charset.forName(encodingStr))

        if (contentBytes.length > config.maxFileSize) {
          Left(s"Content too large: ${contentBytes.length} bytes (max: ${config.maxFileSize} bytes)")
        } else {
          val fileExists = Files.exists(path)

          if (fileExists && !append && !config.allowOverwrite) {
            Left(s"File already exists and overwrite is not allowed: $pathStr")
          } else {
            Try {
              // Create parent directories if needed
              if (config.createDirectories) {
                val parent = path.getParent
                if (parent != null && !Files.exists(parent)) {
                  Files.createDirectories(parent)
                }
              }

              // Write file
              val options = if (append) {
                Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
              } else {
                Array(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
              }

              Files.write(path, contentBytes, options: _*)

              WriteFileResult(
                path = path.toString,
                bytesWritten = contentBytes.length,
                created = !fileExists,
                appended = append && fileExists
              )
            }.toEither.left.map(e => s"Failed to write file: ${e.getMessage}")
          }
        }
      }
    }
  }
}
