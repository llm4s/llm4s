package org.llm4s.agent.service

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * A run queue entry's id (design 4.55): positive, issued by the store in submission order and never reused, so it is
 * also the entry's place in the store-wide order of submissions. A [[Ticket]] names it; a caller keeps it to wait on or
 * follow the entry's run, from any machine.
 */
opaque type EntryId = Long

@Experimental
object EntryId:

  /** `value` as an id; a `ValidationError` unless it is positive. */
  def of(value: Long): Result[EntryId] =
    if value > 0 then Right(value) else Left(ValidationError("entryId", s"must be positive, was $value"))

  /** `text` - an id's decimal form, as [[value]] prints it - as an id. */
  def parse(text: String): Result[EntryId] =
    text.toLongOption.toRight(ValidationError("entryId", s"'$text' is not an entry id")).flatMap(of)

  /** An id a store issued, unchecked. */
  private[llm4s] def unsafe(value: Long): EntryId = value

  extension (id: EntryId) def value: Long = id

  given Ordering[EntryId] = Ordering.Long

  /** Encodes as a JSON number. */
  given upickle.default.ReadWriter[EntryId] =
    upickle.default.ReadWriter.join(upickle.default.LongReader, upickle.default.LongWriter)

/**
 * A caller's key for one submission (design 4.55): unique per tenant, so a caller that retries a submit whose answer it
 * lost - a dropped response, an ambiguous timeout - gets the ticket of the entry its first try made rather than a
 * second entry. 1 to 200 characters. The key is held for as long as its entry is (the ticket lifetime after it
 * closes); once the entry is reclaimed the key names nothing and may be used again.
 */
opaque type SubmissionKey = String

@Experimental
object SubmissionKey:

  /** The longest key accepted. */
  val MaxLength: Int = 200

  /** `value` as a key; a `ValidationError` when it is empty or longer than [[MaxLength]]. */
  def of(value: String): Result[SubmissionKey] =
    if value.nonEmpty && value.length <= MaxLength then Right(value)
    else Left(ValidationError("submissionKey", s"must be 1 to $MaxLength characters, was ${value.length}"))

  /** A key known to be valid, unchecked. */
  private[llm4s] def unsafe(value: String): SubmissionKey = value

  extension (key: SubmissionKey) def value: String = key

  /** Encodes as a plain JSON string. */
  given upickle.default.ReadWriter[SubmissionKey] =
    upickle.default.ReadWriter.join(upickle.default.StringReader, upickle.default.StringWriter)

/**
 * The fencing token of a lease on a queue entry ([[Lease]]): strictly greater than every lease token the store issued
 * for that entry before, so a worker whose lease was taken over after it expired can neither renew it, record an
 * attempt under it nor close the entry.
 */
opaque type LeaseToken = Long

@Experimental
object LeaseToken:
  def apply(value: Long): LeaseToken            = value
  extension (token: LeaseToken) def value: Long = token
  given Ordering[LeaseToken]                    = Ordering.Long
