package org.llm4s.types

import org.llm4s.annotation.Stable
import upickle.default.{ readwriter, ReadWriter => RW }

// The newtypes only the interactive assistant (`org.llm4s.assistant`) uses. They keep the package they had in
// `llm4s-core` (#2820).

/** Type-safe wrapper for the id of an assistant session. */
@Stable
final case class SessionId(value: String) extends AnyVal {
  override def toString: String = value
}

object SessionId {
  implicit val rw: RW[SessionId] =
    readwriter[String].bimap[SessionId](_.value, SessionId.apply)
}

/** Type-safe wrapper for file paths */
@Stable
final case class FilePath(value: String) extends AnyVal {
  override def toString: String = value
  def extension: Option[String] = {
    val lastDot = value.lastIndexOf('.')
    if (lastDot >= 0) Some(value.substring(lastDot + 1)) else None
  }
}

object FilePath {
  implicit val rw: RW[FilePath] =
    readwriter[String].bimap[FilePath](_.value, FilePath.apply)
}

/** Type-safe wrapper for directory paths */
@Stable
final case class DirectoryPath(value: String) extends AnyVal {
  override def toString: String = value
}

object DirectoryPath {
  implicit val rw: RW[DirectoryPath] =
    readwriter[String].bimap[DirectoryPath](_.value, DirectoryPath.apply)
}
