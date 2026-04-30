package org.llm4s.samples.chat.tui

import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.llm4s.types.Result
import upickle.default.{ macroRW, ReadWriter => RW }

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.util.Try

/**
 * `read_file` — the single tool exposed by the demo. Reads up to 64 KB
 * from a workspace-relative path, refuses traversal outside the
 * workspace root, and returns a small structured payload the model can
 * reason about.
 */
object ChatTuiTool:

  final case class ReadFileResult(
    path: String,
    content: String,
    sizeBytes: Long,
    truncated: Boolean
  )

  object ReadFileResult:
    given rw: RW[ReadFileResult] = macroRW

  val Name: String = "read_file"
  private val Description: String =
    "Read up to 64 KB of UTF-8 text from a workspace-relative file path. " +
      "Refuses paths that resolve outside the workspace. " +
      "Returns the file's content plus a truncation flag and byte size."

  private val MaxBytes: Int = 64 * 1024
  val WarnBytes: Long       = 16L * 1024L

  private val schema = Schema
    .`object`[Map[String, Any]]("read_file parameters")
    .withProperty(Schema.property("path", Schema.string("Workspace-relative file path")))

  /** Resolve `path` against `root` and reject any escape outside it. */
  def safeResolve(root: Path, path: String): Either[String, Path] =
    if path == null || path.trim.isEmpty then Left("path must be non-empty")
    else
      Try {
        val absRoot   = root.toAbsolutePath.normalize
        val candidate = absRoot.resolve(path).normalize
        (absRoot, candidate)
      }.toEither.left
        .map(t => s"could not resolve path: ${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)}")
        .flatMap { case (absRoot, candidate) =>
          if !candidate.startsWith(absRoot) then Left(s"path '$path' resolves outside the workspace")
          else Right(candidate)
        }

  /** Probe the size of a candidate path (used by the approval dialog). */
  def probeSize(root: Path, path: String): Option[Long] =
    safeResolve(root, path).toOption.flatMap(p => Try(Files.size(p)).toOption)

  /**
   * Build a registered tool function. Returns `Left(ValidationError)`
   * only if the underlying [[ToolBuilder]] fails — practically
   * impossible here because the handler is always supplied.
   */
  def tool(workspaceRoot: Path): Result[ToolFunction[Map[String, Any], ReadFileResult]] =
    ToolBuilder[Map[String, Any], ReadFileResult](Name, Description, schema)
      .withHandler { params =>
        for {
          rawPath <- params.getString("path")
          target  <- safeResolve(workspaceRoot, rawPath)
          result  <- readBounded(target, rawPath)
        } yield result
      }
      .buildSafe()

  private def readBounded(target: Path, rawPath: String): Either[String, ReadFileResult] =
    if !Files.exists(target) then Left(s"file not found: $rawPath")
    else if Files.isDirectory(target) then Left(s"path is a directory: $rawPath")
    else
      Try {
        val full      = Files.readAllBytes(target)
        val size      = full.length.toLong
        val truncated = full.length > MaxBytes
        val slice     = if truncated then full.take(MaxBytes) else full
        val text      = new String(slice, StandardCharsets.UTF_8)
        ReadFileResult(rawPath, text, size, truncated)
      }.toEither.left.map { t =>
        s"failed to read $rawPath: ${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)}"
      }
