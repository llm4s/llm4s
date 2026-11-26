package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.nio.{ ByteBuffer, CharBuffer }
import java.nio.channels.{ FileChannel, WritableByteChannel }
import java.nio.charset.{ CharacterCodingException, Charset, CodingErrorAction }
import java.nio.file.attribute.{ AclFileAttributeView, BasicFileAttributes, PosixFilePermissions }
import java.nio.file.{
  FileAlreadyExistsException,
  FileSystemException,
  Files,
  LinkOption,
  OpenOption,
  Path,
  Paths,
  StandardCopyOption,
  StandardOpenOption
}
import java.util.UUID
import scala.util.{ Failure, Success, Try, Using }

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
 * val tools = ToolRegistry.of(Seq(writeTool))
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
  def createSafe(config: WriteConfig): Result[ToolFunction[WriteFileResult]] =
    ToolBuilder[WriteFileResult](
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
        // absent or null is the default; a wrong type refuses the call, so `"append": "true"` never overwrites
        append   <- extractor.getOptionalBoolean("append").map(_.getOrElse(false))
        encoding <- extractor.getOptionalString("encoding").map(_.getOrElse("UTF-8"))
        result   <- writeFile(pathStr, content, append, encoding, config).left.map(ToolHandlerError.failed)
      } yield result
    }.build()

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
      // Security check - the real location must be in allowed paths, and the file is written there
      config.resolve(path) match {
        case None =>
          Left(
            s"Access denied: path '$pathStr' is not in allowed paths. Allowed: ${config.allowedPaths.mkString(", ")}"
          )
        case Some(real) =>
          Try(Charset.forName(encodingStr)).toEither.left
            .map(_ => s"Unsupported encoding: $encodingStr")
            .flatMap(charset => encode(content, charset))
            .flatMap { contentBytes =>
              val fileExists = Files.exists(real, LinkOption.NOFOLLOW_LINKS)
              // the size the file will have: an append adds to what is already there
              val existing = if (append && fileExists) Try(Files.size(real)).getOrElse(0L) else 0L
              if (contentBytes.length > config.maxFileSize) {
                Left(s"Content too large: ${contentBytes.length} bytes (max: ${config.maxFileSize} bytes)")
              } else if (existing + contentBytes.length > config.maxFileSize) {
                Left(
                  s"File would be too large: $existing bytes plus ${contentBytes.length} appended " +
                    s"(max: ${config.maxFileSize} bytes)"
                )
              } else {
                if (fileExists && !append && !config.allowOverwrite) {
                  Left(s"File already exists and overwrite is not allowed: $pathStr")
                } else {
                  Try {
                    // Create parent directories if needed
                    if (config.createDirectories) {
                      val parent = real.getParent
                      if (parent != null && !Files.exists(parent)) {
                        Files.createDirectories(parent)
                      }
                    }

                    val created = writeResolved(real, contentBytes, append, config.allowOverwrite)

                    WriteFileResult(
                      path = path.toString,
                      bytesWritten = contentBytes.length,
                      created = created,
                      appended = append && !created
                    )
                  }.toEither.left.map(e => s"Failed to write file: ${e.getMessage}")
                }
              }
            }
      }
    }
  }

  /** The content in `charset`, refused when a character has no encoding in it rather than written as `?`. */
  private def encode(content: String, charset: Charset): Either[String, Array[Byte]] =
    Try {
      val encoded = charset
        .newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(content))
      val bytes = new Array[Byte](encoded.remaining())
      encoded.get(bytes)
      bytes
    }.toEither.left.map {
      case _: CharacterCodingException =>
        s"Content cannot be encoded as ${charset.name}: it contains characters ${charset.name} cannot represent"
      case e => s"Failed to encode content as ${charset.name}: ${e.getMessage}"
    }

  /**
   * Writes the checked final component without following a link inserted after resolution, and returns whether the
   * file was created.
   *
   *  - A new file is created with `CREATE_NEW`, so a file or link that appeared since the check is not written
   *    through. If the write fails, the partial file is removed.
   *  - An overwrite is staged: the content goes to a new temporary file in the same directory, which then replaces
   *    the target in one atomic rename. A failed or interrupted write leaves the original whole and no temporary
   *    file behind. The temporary file is owner-only while it is written (POSIX), and the replacement gets the
   *    original's POSIX permissions (or, on Windows, its ACL); if they cannot be copied, nothing is replaced.
   *  - An append adds to the file in place (`APPEND`), so concurrent appends are not lost; it is not atomic.
   */
  private[builtin] def writeResolved(
    real: Path,
    content: Array[Byte],
    append: Boolean,
    allowOverwrite: Boolean
  ): Boolean =
    Try(
      Files.newByteChannel(real, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
    ) match {
      case Success(channel) =>
        Try(Using.resource(channel)(writeAll(_, content))) match {
          case Success(_) => true
          case Failure(e) =>
            Try(Files.deleteIfExists(real))
            Failure(e).get
        }
      case Failure(_: FileAlreadyExistsException) if append =>
        Using.resource(
          Files.newByteChannel(real, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
        )(writeAll(_, content))
        false
      case Failure(_: FileAlreadyExistsException) if allowOverwrite =>
        replace(real, content).get
        false
      case Failure(e) => Failure(e).get
    }

  /** Replaces the regular file `real` with `content`: writes a temporary sibling and renames it over `real`. */
  private[builtin] def replace(
    real: Path,
    content: Array[Byte],
    copyAccess: (Path, Path) => Unit = copyAccessRights
  ): Try[Unit] =
    Try(Files.readAttributes(real, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)).flatMap { attributes =>
      if (!attributes.isRegularFile)
        Failure(new FileSystemException(real.toString, null, "not a regular file, so it is not replaced"))
      else {
        val temp = stagingPath(real)
        val staged = Try {
          Using.resource(openStaging(temp)) { channel =>
            writeAll(channel, content)
            channel.force(true)
          }
          // the replacement gets the original's access rights, or the write fails: never wider ones
          copyAccess(real, temp)
          Files.move(temp, real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          ()
        }
        if (staged.isFailure) Try(Files.deleteIfExists(temp))
        staged
      }
    }

  /** A staging name in `real`'s directory whose length does not depend on `real`'s (a name may be up to 255 bytes). */
  private[builtin] def stagingPath(real: Path): Path =
    real.resolveSibling(s".llm4s-write-${UUID.randomUUID()}.tmp")

  private val OwnerOnly = PosixFilePermissions.fromString("rw-------")

  /**
   * Creates the staging file, owner-only from the moment it exists where the file system has POSIX permissions, so
   * no other user can open it while the content is written (the process umask can only narrow this further).
   */
  private[builtin] def openStaging(temp: Path): FileChannel = {
    val options = new java.util.HashSet[OpenOption]()
    options.add(StandardOpenOption.WRITE)
    options.add(StandardOpenOption.CREATE_NEW)
    options.add(LinkOption.NOFOLLOW_LINKS)
    if (supportsPosix(temp)) FileChannel.open(temp, options, PosixFilePermissions.asFileAttribute(OwnerOnly))
    else FileChannel.open(temp, options)
  }

  private def supportsPosix(path: Path): Boolean =
    path.getFileSystem.supportedFileAttributeViews().contains("posix")

  /**
   * Gives `temp` the access rights of `original`: its POSIX permissions, or, on a file system with ACLs and no POSIX
   * permissions (Windows), its ACL. Throws when they cannot be read or set, so the replace is refused rather than
   * leaving a file with different rights.
   */
  private def copyAccessRights(original: Path, temp: Path): Unit =
    if (supportsPosix(original))
      Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(original, LinkOption.NOFOLLOW_LINKS))
    else
      Option(Files.getFileAttributeView(original, classOf[AclFileAttributeView], LinkOption.NOFOLLOW_LINKS))
        .zip(Option(Files.getFileAttributeView(temp, classOf[AclFileAttributeView], LinkOption.NOFOLLOW_LINKS)))
        .foreach { case (from, to) => to.setAcl(from.getAcl) }

  private def writeAll(channel: WritableByteChannel, content: Array[Byte]): Unit = {
    val bytes = ByteBuffer.wrap(content)
    while (bytes.hasRemaining) channel.write(bytes)
  }
}
