package org.llm4s.llmconnect.caching

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.InputPurpose

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * Utility object for generating cache keys using secure hashing.
 * Keys are deterministic (the same inputs always produce the same key) and unambiguous: every part is
 * prefixed with its length before hashing, so two different sequences of parts never hash the same input,
 * whatever characters the parts contain.
 */
@Stable
object CacheKeyGenerator {

  /**
   * The default embedding cache key: the text, the model name and the purpose of the request, each
   * encoded as a part of its own, so a query and a document with the same text never share a key, and no
   * model name or text can stand in for another.
   *
   * @param text    The input text to embed
   * @param model   The model name
   * @param purpose Whether the text is embedded as a document or as a query
   * @return A 64-character hex string representing the hash
   */
  def embeddingKey(text: String, model: String, purpose: InputPurpose): String =
    sha256(text, model, purposeTag(purpose))

  /**
   * The SHA-256 of the given parts, each prefixed with its length and a colon (`5:hello`), so the
   * encoding is unambiguous: `sha256("a:b", "c")` and `sha256("a", "b:c")` are different keys. Use it to
   * build a key function of your own that adds parts, such as a tenant.
   *
   * @param parts The values that together identify the cached item
   * @return A 64-character hex string representing the hash
   */
  def sha256(parts: String*): String = {
    val input  = parts.map(part => s"${part.length}:$part").mkString
    val digest = MessageDigest.getInstance("SHA-256")
    val hash   = digest.digest(input.getBytes(StandardCharsets.UTF_8))

    hash.map(byte => "%02x".format(byte & 0xff)).mkString
  }

  private def purposeTag(purpose: InputPurpose): String =
    purpose match {
      case InputPurpose.Document => "document"
      case InputPurpose.Query    => "query"
    }
}
