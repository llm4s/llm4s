package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.core.safety.UsingOps.using
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
 * Result from listing a directory. `totalFiles` and `totalDirectories` count only the entries returned in `entries`
 * (the requested page), not the directory as a whole.
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
 * val tools = ToolRegistry.of(Seq(listTool))
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
        Schema.integer(s"Maximum number of entries to return (default: $MaxEntries, minimum: 1)")
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
  def createSafe(config: FileConfig = FileConfig()): Result[ToolFunction[ListDirectoryResult]] =
    ToolBuilder[ListDirectoryResult](
      name = "list_directory",
      description = s"List contents of a directory. Returns file names, sizes, and types. " +
        s"Maximum entries: $MaxEntries. totalFiles and totalDirectories count only the returned entries. " +
        s"Blocked paths: ${config.describedBlockedPaths}. " +
        config.allowedPaths.map(p => s"Allowed paths: ${p.mkString(", ")}").getOrElse(""),
      schema = createSchema
    ).withHandler { extractor =>
      for {
        pathStr <- extractor.getString("path")
        // absent or null is the default; a wrong type refuses the call
        maxEntries    <- extractor.getOptionalInt("max_entries").map(_.getOrElse(MaxEntries).min(MaxEntries).max(1))
        includeHidden <- extractor.getOptionalBoolean("include_hidden").map(_.getOrElse(false))
        result        <- listDirectory(pathStr, maxEntries, includeHidden, config).left.map(ToolHandlerError.failed)
      } yield result
    }.build()

  /**
   * Default list directory tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[ListDirectoryResult]] = createSafe()

  private def listDirectory(
    pathStr: String,
    maxEntries: Int,
    includeHidden: Boolean,
    config: FileConfig
  ): Either[String, ListDirectoryResult] = {
    val pathResult =
      Try(Paths.get(pathStr).toAbsolutePath.normalize()).toEither.left.map(e => s"Invalid path: ${e.getMessage}")

    pathResult.flatMap { path =>
      // Security check: the policy judges the real location, and the directory is listed from that resolved path
      config.resolve(path) match {
        case None =>
          Left(s"Access denied: path '$pathStr' is not allowed")
        case Some(real) =>
          // Without followSymlinks, a directory that is itself a symbolic link is refused, as read_file refuses a
          // linked file. The policy above has already judged where the path really is; this is the final-component rule.
          val directoryLinkOptions =
            if (config.followSymlinks) Array.empty[LinkOption] else Array(LinkOption.NOFOLLOW_LINKS)
          if (!Files.exists(path, directoryLinkOptions: _*)) {
            Left(s"Directory not found: $pathStr")
          } else if (!Files.isDirectory(path, directoryLinkOptions: _*)) {
            Left(s"Not a directory: $pathStr")
          } else {
            Try {
              // Keep only the smallest maxEntries names in a bounded heap; the head is the greatest name accepted
              // so far, and anything smaller replaces it. The stream itself is never materialised.
              val greatestNameFirst = new java.util.Comparator[FileEntry] {
                override def compare(left: FileEntry, right: FileEntry): Int = right.name.compareTo(left.name)
              }
              val limit = maxEntries.max(1)
              val top   = new java.util.PriorityQueue[FileEntry](limit, greatestNameFirst)

              var listableEntries = 0

              using(Files.list(real)) { stream =>
                stream.iterator().asScala.foreach { entryPath =>
                  val name = entryPath.getFileName.toString
                  if (includeHidden || !name.startsWith("."))
                    config.resolve(entryPath).foreach { resolved =>
                      // An entry the policy refuses is never described. With followSymlinks, attributes are read
                      // from the location the policy judged; without it, the final link itself is described.
                      val (attributePath, linkOptions) =
                        if (config.followSymlinks) (resolved, Array.empty[LinkOption])
                        else (entryPath, Array(LinkOption.NOFOLLOW_LINKS))

                      Try(Files.readAttributes(attributePath, classOf[BasicFileAttributes], linkOptions: _*)).toOption
                        .foreach { attrs =>
                          listableEntries += 1

                          val isDir     = attrs.isDirectory
                          val isFile    = attrs.isRegularFile
                          val isSymlink = attrs.isSymbolicLink
                          val entry = FileEntry(
                            name = name,
                            path = path.resolve(entryPath.getFileName).toString,
                            isDirectory = isDir,
                            isFile = isFile,
                            isSymlink = isSymlink,
                            size = if (isFile) attrs.size() else 0L,
                            lastModified = attrs.lastModifiedTime().toMillis
                          )

                          if (top.size() < limit) top.add(entry)
                          else if (entry.name.compareTo(top.peek().name) < 0) {
                            top.poll()
                            top.add(entry)
                          }
                        }
                    }
                }
              }

              val entries   = top.iterator().asScala.toVector.sortBy(_.name)
              val fileCount = entries.count(_.isFile)
              val dirCount  = entries.count(_.isDirectory)

              ListDirectoryResult(
                path = path.toString,
                entries = entries,
                totalFiles = fileCount,
                totalDirectories = dirCount,
                truncated = listableEntries > limit
              )
            }.toEither.left.map(e => s"Failed to list directory: ${e.getMessage}")
          }
      }
    }
  }
}
