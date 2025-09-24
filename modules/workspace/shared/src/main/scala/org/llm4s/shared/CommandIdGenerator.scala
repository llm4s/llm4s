package org.llm4s.shared

import java.util.UUID

/**
 * Utility class for generating unique command IDs.
 * This centralizes the command ID generation logic across the codebase.
 */
object CommandIdGenerator {

  /**
   * Generate a new unique command ID.
   * @return A new UUID string
   */
  def generate(): String = UUID.randomUUID().toString
}
