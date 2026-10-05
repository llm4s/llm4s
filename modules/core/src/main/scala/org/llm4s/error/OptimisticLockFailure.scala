package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * Error indicating a concurrent modification was detected during an optimistic update.
 *
 * Returned when an update is attempted but the record has been modified by another
 * concurrent writer since it was last read. Callers should re-read the record and
 * retry the operation.
 *
 * @param message          Human-readable error description
 * @param memoryId         The ID of the memory record that was concurrently modified
 * @param attemptedVersion The version number that was expected but not found
 */
@Stable
final case class OptimisticLockFailure private (
  override val message: String,
  memoryId: String,
  attemptedVersion: Long
) extends LLMError
    with RecoverableError {
  override val context: Map[String, String] = Map(
    "memoryId"         -> memoryId,
    "attemptedVersion" -> attemptedVersion.toString
  )
}

object OptimisticLockFailure {
  def apply(message: String, memoryId: String, attemptedVersion: Long): OptimisticLockFailure =
    new OptimisticLockFailure(message, memoryId, attemptedVersion)

  def unapply(error: OptimisticLockFailure): Option[(String, String, Long)] =
    Some((error.message, error.memoryId, error.attemptedVersion))
}
