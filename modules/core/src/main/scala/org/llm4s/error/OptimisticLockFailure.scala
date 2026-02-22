package org.llm4s.error

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
final case class OptimisticLockFailure(
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
