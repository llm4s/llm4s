package org.llm4s.agent.harness

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.nio.charset.StandardCharsets.UTF_8

/**
 * A path in a [[FileBackend]]'s own space: absolute, `/`-separated, with non-empty segments, none of
 * them `.` or `..`, and no NUL, backslash or control character. Nothing is normalised away: `/a/../b`
 * is refused, not rewritten, so a path names what it says. One trailing `/` is dropped, so `/src/` is
 * `/src`. At most 1024 UTF-8 bytes in all, and 255 per segment.
 *
 * It encodes as a JSON string, and its reader refuses an invalid one.
 */
opaque type VPath = String

@Experimental
object VPath:

  /** The most UTF-8 bytes a path may have. */
  val MaxBytes: Int = 1024

  /** The most UTF-8 bytes one segment may have. */
  val MaxSegmentBytes: Int = 255

  /** `/`. */
  val root: VPath = "/"

  /** `s` as a path, or a `ValidationError` saying why it is not one. */
  def apply(s: String): Result[VPath] =
    val trimmed = if s.length > 1 && s.endsWith("/") then s.dropRight(1) else s
    def invalid(reason: String) = Left(ValidationError("path", s"'$s' $reason"))
    if !trimmed.startsWith("/") then invalid("is not absolute")
    else if trimmed.getBytes(UTF_8).length > MaxBytes then invalid(s"is longer than $MaxBytes bytes")
    else if trimmed == "/" then Right(root)
    else trimmed.drop(1).split("/", -1).iterator.map(segmentError).collectFirst { case Some(r) => r } match
      case Some(reason) => invalid(reason)
      case None         => Right(trimmed)

  private def segmentError(segment: String): Option[String] =
    if segment.isEmpty then Some("has an empty segment")
    else if segment == "." || segment == ".." then Some(s"has a '$segment' segment")
    else if segment.exists(c => c == '\\' || Character.isISOControl(c)) then
      Some("has a backslash or control character")
    else if segment.getBytes(UTF_8).length > MaxSegmentBytes then
      Some(s"has a segment longer than $MaxSegmentBytes bytes")
    else None

  extension (path: VPath)

    /** The path as a string. */
    def value: String = path

    /** The segments, outermost first; empty for the root. */
    def segments: Vector[String] = if path == root then Vector.empty else path.drop(1).split('/').toVector

    /** The child `name` of this path; `name` must be one valid segment. */
    def /(name: String): Result[VPath] =
      if name.contains('/') then Left(ValidationError("path", s"'$name' is not one segment"))
      else VPath(if path == root then s"/$name" else s"$path/$name")

    /** The parent; `None` for the root. */
    def parent: Option[VPath] =
      if path == root then None
      else
        val i = path.lastIndexOf('/')
        Some(if i == 0 then root else path.take(i))

    /** The last segment; `""` for the root. */
    def name: String = path.drop(path.lastIndexOf('/') + 1)

    /** Whether this path is `ancestor` or beneath it, segment by segment: `/a/bc` is not within `/a/b`. */
    def isWithin(ancestor: VPath): Boolean =
      ancestor == root || path == ancestor || path.startsWith(ancestor + "/")

    /** This path relative to `ancestor`, with no leading `/`: `""` for the ancestor itself. */
    def relativeTo(ancestor: VPath): Option[String] =
      if !isWithin(ancestor) then None
      else if ancestor == root then Some(path.drop(1))
      else Some(path.drop(ancestor.length + 1))

    /** This path moved from beneath `from` to beneath `to`; `None` when it is not within `from`. */
    def rebase(from: VPath, to: VPath): Option[VPath] =
      relativeTo(from).map(rel => if rel.isEmpty then to else if to == root then s"/$rel" else s"$to/$rel")

  given Ordering[VPath] = Ordering.String

  given upickle.default.ReadWriter[VPath] =
    upickle.default
      .readwriter[ujson.Value]
      .bimap[VPath](
        p => ujson.Str(p),
        json => VPath(json.str).fold(e => throw new IllegalArgumentException(e.message), identity)
      )
