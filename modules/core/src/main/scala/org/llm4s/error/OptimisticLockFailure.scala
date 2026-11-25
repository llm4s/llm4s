package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * Error indicating a concurrent modification was detected during an optimistic update.
 *
 * Returned when an update is attempted but the record has been modified by another
 * concurrent writer since it was last read. Callers should re-read the record and
 * retry the operation. It is not [[LLMError.isRetryable retryable]]: the same update fails again until the
 * record is re-read.
 *
 * @param message          Human-readable error description
 * @param recordId         The ID of the memory record that was concurrently modified
 * @param attemptedVersion The version number that was expected but not found
 */
@Stable
final case class OptimisticLockFailure private (
  override val message: String,
  recordId: String,
  attemptedVersion: Long
) extends LLMError {
  override val context: Map[String, String] = Map(
    "recordId"         -> recordId,
    "attemptedVersion" -> attemptedVersion.toString
  )
}

object OptimisticLockFailure {
  def apply(message: String, recordId: String, attemptedVersion: Long): OptimisticLockFailure =
    new OptimisticLockFailure(message, recordId, attemptedVersion)

  def unapply(error: OptimisticLockFailure): Option[(String, String, Long)] =
    Some((error.message, error.recordId, error.attemptedVersion))
}
