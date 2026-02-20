package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.nio.file.{ Files, LinkOption, Paths }
import java.nio.file.attribute.BasicFileAttributes
import scala.annotation.tailrec
import scala.util.Try

/**
 * Detailed information about a file.
 */
case class FileInfoResult(
  path: String,
  name: String,
  exists: Boolean,
  isFile: Boolean,
  isDirectory: Boolean,
  isSymlink: Boolean,
  size: Long,
  sizeHuman: String,
  createdAt: Long,
  lastModified: Long,
  lastAccessed: Long,
  isReadable: Boolean,
  isWritable: Boolean,
  isExecutable: Boolean,
  extension: Option[String]
)

object FileInfoResult {
  implicit val fileInfoResultRW: ReadWriter[FileInfoResult] = macroRW[FileInfoResult]
}

/**
 * Tool for getting detailed file information.
 *
 * Features:
 * - File metadata (size, timestamps)
 * - File type detection
 * - Permission checks
 * - Human-readable size formatting
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.filesystem._
 *
 * val infoTool = FileInfoTool.create(FileConfig(
 *   allowedPaths = Some(Seq("/tmp"))
 * ))
 *
 * val tools = new ToolRegistry(Seq(infoTool))
 * agent.run("Get info about /tmp/data.txt", tools)
 * }}}
 */
object FileInfoTool {

  private def createSchema = Schema
    .`object`[Map[String, Any]]("File info parameters")
    .withProperty(
      Schema.property(
        "path",
        Schema.string("Absolute path to the file or directory")
      )
    )

  /**
   * Create a file info tool with the given configuration, returning a Result for safe error handling.
   */
  def createSafe(config: FileConfig = FileConfig()): Result[ToolFunction[Map[String, Any], FileInfoResult]] =
    ToolBuilder[Map[String, Any], FileInfoResult](
      name = "file_info",
      description = "Get detailed information about a file or directory including size, " +
        "timestamps, type, and permissions. " +
        s"Blocked paths: ${config.blockedPaths.mkString(", ")}. " +
        config.allowedPaths.map(p => s"Allowed paths: ${p.mkString(", ")}").getOrElse(""),
      schema = createSchema
    ).withHandler { extractor =>
      for {
        pathStr <- extractor.getString("path")
        result  <- getFileInfo(pathStr, config)
      } yield result
    }.buildSafe()

  /**
   * Default file info tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[Map[String, Any], FileInfoResult]] = createSafe()

  /**
   * Create a file info tool with the given configuration.
   *
   * @throws IllegalStateException if tool creation fails
   */
  @deprecated("Use createSafe() which returns Result[ToolFunction] for safe error handling", "0.2.9")
  def create(config: FileConfig = FileConfig()): ToolFunction[Map[String, Any], FileInfoResult] =
    createSafe(config) match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"FileInfoTool.create failed: ${e.formatted}")
    }

  /**
   * Default file info tool with standard configuration.
   *
   * @throws IllegalStateException if tool initialization fails
   */
  @deprecated("Use toolSafe which returns Result[ToolFunction] for safe error handling", "0.2.9")
  lazy val tool: ToolFunction[Map[String, Any], FileInfoResult] =
    toolSafe match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"FileInfoTool.tool lazy initialization failed: ${e.formatted}")
    }

  private def getFileInfo(
    pathStr: String,
    config: FileConfig
  ): Either[String, FileInfoResult] = {
    val path =
      Try(Paths.get(pathStr).toAbsolutePath.normalize()).toEither.left.map(e => s"Invalid path: ${e.getMessage}")

    path.flatMap { p =>
      // Security check
      if (!config.isPathAllowed(p)) {
        Left(s"Access denied: path '$pathStr' is not allowed")
      } else {
        val name   = p.getFileName.toString
        val exists = Files.exists(p)

        if (!exists) {
          Right(
            FileInfoResult(
              path = p.toString,
              name = name,
              exists = false,
              isFile = false,
              isDirectory = false,
              isSymlink = false,
              size = 0,
              sizeHuman = "0 B",
              createdAt = 0,
              lastModified = 0,
              lastAccessed = 0,
              isReadable = false,
              isWritable = false,
              isExecutable = false,
              extension = extractExtension(name)
            )
          )
        } else {
          Try {
            val linkOptions = if (config.followSymlinks) Array.empty[LinkOption] else Array(LinkOption.NOFOLLOW_LINKS)
            val attrs       = Files.readAttributes(p, classOf[BasicFileAttributes], linkOptions: _*)
            val size        = if (attrs.isRegularFile) attrs.size() else 0L

            FileInfoResult(
              path = p.toString,
              name = name,
              exists = true,
              isFile = attrs.isRegularFile,
              isDirectory = attrs.isDirectory,
              isSymlink = attrs.isSymbolicLink,
              size = size,
              sizeHuman = humanReadableSize(size),
              createdAt = attrs.creationTime().toMillis,
              lastModified = attrs.lastModifiedTime().toMillis,
              lastAccessed = attrs.lastAccessTime().toMillis,
              isReadable = Files.isReadable(p),
              isWritable = Files.isWritable(p),
              isExecutable = Files.isExecutable(p),
              extension = extractExtension(name)
            )
          }.toEither.left.map(e => s"Failed to get file info: ${e.getMessage}")
        }
      }
    }
  }

  private def extractExtension(name: String): Option[String] = {
    val dotIndex = name.lastIndexOf('.')
    if (dotIndex > 0 && dotIndex < name.length - 1) {
      Some(name.substring(dotIndex + 1))
    } else {
      None
    }
  }

  private val FileSizeUnits = Seq("KB", "MB", "GB", "TB", "PB")

  private def humanReadableSize(bytes: Long): String = {
    @tailrec
    def calculate(currentSize: Double, remainingUnits: Seq[String]): String =
      if (currentSize < 1024 || remainingUnits.tail.isEmpty) {
        f"$currentSize%.1f ${remainingUnits.head}"
      } else {
        calculate(currentSize / 1024, remainingUnits.tail)
      }

    if (bytes < 1024) {
      s"$bytes B"
    } else {
      calculate(bytes.toDouble / 1024, FileSizeUnits)
    }
  }
}
