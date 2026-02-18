package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.nio.file.{ Files, LinkOption, Paths }
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Information about a single file or directory entry.
 */
case class FileEntry(
  name: String,
  path: String,
  isDirectory: Boolean,
  isFile: Boolean,
  isSymlink: Boolean,
  size: Long,
  lastModified: Long
)

object FileEntry {
  implicit val fileEntryRW: ReadWriter[FileEntry] = macroRW[FileEntry]
}

/**
 * Result from listing a directory.
 */
case class ListDirectoryResult(
  path: String,
  entries: Seq[FileEntry],
  totalFiles: Int,
  totalDirectories: Int,
  truncated: Boolean
)

object ListDirectoryResult {
  implicit val listDirectoryResultRW: ReadWriter[ListDirectoryResult] = macroRW[ListDirectoryResult]
}

/**
 * Tool for listing directory contents.
 *
 * Features:
 * - List files and directories
 * - File metadata (size, modified time)
 * - Configurable max entries
 * - Path restrictions
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.filesystem._
 *
 * val listTool = ListDirectoryTool.create(FileConfig(
 *   allowedPaths = Some(Seq("/tmp", "/home/user"))
 * ))
 *
 * val tools = new ToolRegistry(Seq(listTool))
 * agent.run("List files in /tmp", tools)
 * }}}
 */
object ListDirectoryTool {

  private val MaxEntries = 100

  private def createSchema = Schema
    .`object`[Map[String, Any]]("Directory listing parameters")
    .withProperty(
      Schema.property(
        "path",
        Schema.string("Absolute path to the directory to list")
      )
    )
    .withProperty(
      Schema.property(
        "max_entries",
        Schema.integer(s"Maximum number of entries to return (default: $MaxEntries)")
      )
    )
    .withProperty(
      Schema.property(
        "include_hidden",
        Schema.boolean("Include hidden files (starting with .) (default: false)")
      )
    )

  /**
   * Create a list directory tool with the given configuration, returning a Result for safe error handling.
   */
  def createSafe(config: FileConfig = FileConfig()): Result[ToolFunction[Map[String, Any], ListDirectoryResult]] =
    ToolBuilder[Map[String, Any], ListDirectoryResult](
      name = "list_directory",
      description = s"List contents of a directory. Returns file names, sizes, and types. " +
        s"Maximum entries: $MaxEntries. " +
        s"Blocked paths: ${config.blockedPaths.mkString(", ")}. " +
        config.allowedPaths.map(p => s"Allowed paths: ${p.mkString(", ")}").getOrElse(""),
      schema = createSchema
    ).withHandler { extractor =>
      for {
        pathStr <- extractor.getString("path")
        maxEntries    = extractor.getInt("max_entries").fold(_ => MaxEntries, identity).min(MaxEntries)
        includeHidden = extractor.getBoolean("include_hidden").fold(_ => false, identity)
        result <- listDirectory(pathStr, maxEntries, includeHidden, config)
      } yield result
    }.buildSafe()

  /**
   * Default list directory tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[Map[String, Any], ListDirectoryResult]] = createSafe()

  /**
   * Create a list directory tool with the given configuration.
   *
   * @throws IllegalStateException if tool creation fails
   */
  @deprecated("Use createSafe() which returns Result[ToolFunction] for safe error handling", "0.2.9")
  def create(config: FileConfig = FileConfig()): ToolFunction[Map[String, Any], ListDirectoryResult] =
    createSafe(config) match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"ListDirectoryTool.create failed: ${e.formatted}")
    }

  /**
   * Default list directory tool with standard configuration.
   *
   * @throws IllegalStateException if tool initialization fails
   */
  @deprecated("Use toolSafe which returns Result[ToolFunction] for safe error handling", "0.2.9")
  lazy val tool: ToolFunction[Map[String, Any], ListDirectoryResult] =
    toolSafe match {
      case Right(t) => t
      case Left(e) =>
        throw new IllegalStateException(s"ListDirectoryTool.tool lazy initialization failed: ${e.formatted}")
    }

  private def listDirectory(
    pathStr: String,
    maxEntries: Int,
    includeHidden: Boolean,
    config: FileConfig
  ): Either[String, ListDirectoryResult] = {
    val pathResult =
      Try(Paths.get(pathStr).toAbsolutePath.normalize()).toEither.left.map(e => s"Invalid path: ${e.getMessage}")

    pathResult.flatMap { path =>
      // Security check
      if (!config.isPathAllowed(path)) {
        Left(s"Access denied: path '$pathStr' is not allowed")
      } else if (!Files.exists(path)) {
        Left(s"Directory not found: $pathStr")
      } else if (!Files.isDirectory(path)) {
        Left(s"Not a directory: $pathStr")
      } else {
        Try {
          // List directory contents
          val linkOptions = if (config.followSymlinks) Array.empty[LinkOption] else Array(LinkOption.NOFOLLOW_LINKS)

          val allEntries = Files
            .list(path)
            .iterator()
            .asScala
            .toSeq
            .filter(p => includeHidden || !p.getFileName.toString.startsWith("."))
            .sortBy(_.getFileName.toString)

          val truncated      = allEntries.size > maxEntries
          val limitedEntries = allEntries.take(maxEntries)

          var fileCount = 0
          var dirCount  = 0

          val entries = limitedEntries.map { entryPath =>
            val attrs = Files.readAttributes(entryPath, classOf[BasicFileAttributes], linkOptions: _*)

            val isDir     = attrs.isDirectory
            val isFile    = attrs.isRegularFile
            val isSymlink = attrs.isSymbolicLink

            if (isDir) dirCount += 1
            if (isFile) fileCount += 1

            FileEntry(
              name = entryPath.getFileName.toString,
              path = entryPath.toString,
              isDirectory = isDir,
              isFile = isFile,
              isSymlink = isSymlink,
              size = if (isFile) attrs.size() else 0L,
              lastModified = attrs.lastModifiedTime().toMillis
            )
          }

          ListDirectoryResult(
            path = path.toString,
            entries = entries,
            totalFiles = fileCount,
            totalDirectories = dirCount,
            truncated = truncated
          )
        }.toEither.left.map(e => s"Failed to list directory: ${e.getMessage}")
      }
    }
  }
}
