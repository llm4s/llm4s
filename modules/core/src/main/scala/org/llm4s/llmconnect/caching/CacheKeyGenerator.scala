package org.llm4s.llmconnect.caching

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * Utility object for generating cache keys using secure hashing.
 * Keys should be deterministic (same inputs always produce same key)
 * and collision-resistant (different inputs shouldn't produce same key).
 */
object CacheKeyGenerator {

  /**
   * Generate a secure cache key using SHA-256 hashing.
   *
   * @param text The input text to embed
   * @param model The model identifier
   * @return A 64-character hex string representing the hash
   */
  def sha256(text: String, model: String): String = {
    val input  = s"$text:$model"
    val digest = MessageDigest.getInstance("SHA-256")
    val hash   = digest.digest(input.getBytes(StandardCharsets.UTF_8))

    hash.map(byte => "%02x".format(byte & 0xff)).mkString
  }
}
