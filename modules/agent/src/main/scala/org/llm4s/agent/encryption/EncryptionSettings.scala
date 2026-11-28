package org.llm4s.agent.encryption

import org.llm4s.annotation.Experimental

/**
 * How [[EncryptingCheckpointer]], [[EncryptingRunQueue]] and [[EncryptingLongTermStore]] behave beyond their keys
 * (#2449).
 *
 * @param plaintextReads whether a payload stored in plain is read as it is. Off by default: an unencrypted payload
 *   fails the read ([[EncryptionError.NotEncrypted]]), so nobody with write access to the database can swap a
 *   ciphertext for a plaintext of their own. Turn it on to switch encryption on over a store that already holds
 *   threads - their rows are read in plain, and everything written from then on is encrypted. Retention does not
 *   prune a thread's latest checkpoint, its pending writes or its effect records (#3242), so a thread from before the
 *   switch keeps plaintext rows until it is written again or deleted: turn this off only once every such thread has
 *   been deleted, and every long-term item written again.
 *   Writes are always encrypted, whatever this is.
 */
@Experimental
final case class EncryptionSettings private (plaintextReads: Boolean):
  def withPlaintextReads(allowed: Boolean): EncryptionSettings = copy(plaintextReads = allowed)

@Experimental
object EncryptionSettings:
  def apply(plaintextReads: Boolean = false): EncryptionSettings = new EncryptionSettings(plaintextReads)
