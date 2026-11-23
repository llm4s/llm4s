package org.llm4s.agent.encryption

import org.llm4s.annotation.Experimental

/**
 * How [[EncryptingCheckpointer]] and [[EncryptingRunQueue]] behave beyond their keys (#2449).
 *
 * @param plaintextReads whether a payload stored in plain is read as it is. Off by default: an unencrypted payload
 *   fails the read ([[EncryptionError.NotEncrypted]]), so nobody with write access to the database can swap a
 *   ciphertext for a plaintext of their own. Turn it on to switch encryption on over a store that already holds
 *   threads - their rows are read in plain, and everything written from then on is encrypted - and off again once
 *   retention has pruned the old rows. Writes are always encrypted, whatever this is.
 */
@Experimental
final case class EncryptionSettings private (plaintextReads: Boolean):
  def withPlaintextReads(allowed: Boolean): EncryptionSettings = copy(plaintextReads = allowed)

@Experimental
object EncryptionSettings:
  def apply(plaintextReads: Boolean = false): EncryptionSettings = new EncryptionSettings(plaintextReads)
