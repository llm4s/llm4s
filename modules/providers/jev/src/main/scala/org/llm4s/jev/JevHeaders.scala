package org.llm4s.jev

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.util.Locale

/**
 * Checks the extra HTTP headers a caller attaches to a request.
 *
 * A header name or value that carries a line break would let its author inject headers, and a header the client
 * sets itself (credentials, content type and length, framing) must not be overridden by a caller. Both are
 * refused before anything is sent. HTTP header names are case-insensitive, so two names that differ only in case
 * are one header: a map holding both is refused, and [[merge]] lets an override replace a header whatever its case.
 */
private[jev] object JevHeaders {

  /** Headers the client controls: a caller cannot set or replace them. */
  val Reserved: Set[String] =
    Set("authorization", "content-type", "content-length", "host", "transfer-encoding", "connection", "accept")

  // RFC 9110 token characters.
  private val TokenChars = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

  private def isToken(name: String): Boolean = name.nonEmpty && name.forall(c => TokenChars.indexOf(c.toInt) >= 0)

  private def isFieldValue(value: String): Boolean =
    value.forall(c => c == '\t' || (c >= ' ' && c != '\u007f' && c <= '~'))

  private def key(name: String): String = name.toLowerCase(Locale.ROOT)

  /** Checks `headers`; `field` names where they came from in the error. */
  def validate(field: String, headers: Map[String, String]): Result[Unit] =
    headers.keys.groupBy(key).collectFirst { case (_, names) if names.size > 1 => names.toSeq.sorted } match {
      case Some(names) =>
        Left(ValidationError(field, s"${names.mkString(" and ")} are the same header; give it once"))
      case None => validateEach(field, headers)
    }

  private def validateEach(field: String, headers: Map[String, String]): Result[Unit] =
    headers
      .collectFirst {
        case (name, _) if !isToken(name) =>
          ValidationError(field, "a header name must be a non-empty token (letters, digits and !#$%&'*+-.^_`|~)")
        case (name, _) if Reserved.contains(key(name)) =>
          ValidationError(field, s"the client sets the $name header itself; it cannot be overridden")
        case (name, value) if !isFieldValue(value) =>
          ValidationError(field, s"the value of header $name must be printable ASCII without line breaks")
      }
      .fold[Result[Unit]](Right(()))(Left(_))

  /** `base` with `overrides` on top: an override replaces a base header of the same name in any case. */
  def merge(base: Map[String, String], overrides: Map[String, String]): Map[String, String] = {
    val replaced = overrides.keySet.map(key)
    base.filterNot { case (name, _) => replaced.contains(key(name)) } ++ overrides
  }
}
