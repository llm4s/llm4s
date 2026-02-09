package org.llm4s.error

/**
 * Error indicating an optimistic locking conflict during concurrent update.
 *
 * This error occurs when attempting to update a memory that has been modified
 * by another concurrent operation. The memory was read with version V, but by
 * the time the update was attempted, the current version in the database had
 * already changed to V+1 or higher.
 *
 * This is a recoverable error - the operation can be retried by:
 * 1. Re-reading the memory (with its new version)
 * 2. Re-applying the update function
 * 3. Re-attempting the conditional write
 *
 * Example usage:
 * {{{
 * store.update(memoryId, mem => mem.withMetadata("key", "value")) match {
 *   case Left(OptimisticLockFailure(id, attemptedVersion)) =>
 *     // Retry the operation
 *     store.retryingUpdate(memoryId, updateFn, maxRetries = 3)
 *   case Right(updatedStore) =>
 *     // Success
 * }
 * }}}
 *
 * @param memoryId The ID of the memory that failed to update
 * @param attemptedVersion The version that was expected but no longer current
 */
final case class OptimisticLockFailure private (
  override val message: String,
  memoryId: String,
  attemptedVersion: Long
) extends LLMError
    with RecoverableError {

  override val context: Map[String, String] = Map(
    "memory_id"         -> memoryId,
    "attempted_version" -> attemptedVersion.toString
  )

  override val code: Option[String] = Some("OPTIMISTIC_LOCK_CONFLICT")

  /** Suggest immediate retry with exponential backoff */
  override def retryDelay: Option[Long] = Some(10L) // 10ms initial delay

  /** Allow up to 5 retries for lock conflicts */
  override def maxRetries: Int = 5
}

object OptimisticLockFailure {

  /**
   * Create an optimistic lock failure error.
   *
   * @param memoryId The memory ID that couldn't be updated
   * @param attemptedVersion The version that was expected
   * @return Optimistic lock failure error
   */
  def apply(memoryId: String, attemptedVersion: Long): OptimisticLockFailure =
    new OptimisticLockFailure(
      s"Optimistic lock conflict: Memory '$memoryId' was modified concurrently (expected version $attemptedVersion)",
      memoryId,
      attemptedVersion
    )

  /** Unapply extractor for pattern matching */
  def unapply(error: OptimisticLockFailure): Option[(String, Long)] =
    Some((error.memoryId, error.attemptedVersion))
}
