package org.llm4s.context.tokens

import com.knuddels.jtokkit.Encodings
import org.llm4s.identity.TokenizerId

/**
 * Represents a single BPE token as an integer vocabulary index.
 *
 * The integer corresponds to the token identifier used by the underlying
 * jtokkit / TikToken encoding (e.g. `cl100k_base` for GPT-4).
 */
case class Token(tokenId: Int) {
  override def toString: String = s"$tokenId"
}

/**
 * Converts a plain string into a sequence of [[Token]]s using a specific BPE vocabulary.
 *
 * Implementations are backed by jtokkit encodings and obtained via
 * [[Tokenizer.lookupStringTokenizer]]; the interface is kept minimal to allow
 * test doubles without a real encoding registry.
 */
trait StringTokenizer {

  /** Encodes `text` into a list of BPE [[Token]]s. */
  def encode(text: String): List[Token]
}

/**
 * Factory for obtaining [[StringTokenizer]] instances backed by jtokkit BPE encodings.
 *
 * Encodings are looked up from the jtokkit default registry which bundles the
 * standard TikToken vocabularies (`cl100k_base`, `o200k_base`, etc.).
 * An unknown `tokenizerId` — one whose name does not match any bundled vocabulary —
 * returns `None`; callers must handle the absent case explicitly.
 *
 * @see [[org.llm4s.identity.TokenizerId]] for vocabulary name constants
 * @see [[org.llm4s.context.tokens.TokenizerMapping]] for the model → tokenizer mapping
 */
object Tokenizer {
  private val registry = Encodings.newDefaultEncodingRegistry

  /**
   * Returns a [[StringTokenizer]] for the given vocabulary, or `None` if the
   * vocabulary name is not recognised by the bundled jtokkit registry.
   *
   * @param tokenizerId identifies the BPE vocabulary (e.g. `TokenizerId("cl100k_base")`)
   * @return `Some` tokenizer when the vocabulary is available; `None` otherwise
   */
  def lookupStringTokenizer(tokenizerId: TokenizerId): Option[StringTokenizer] = {
    val encoderOptional = registry.getEncoding(tokenizerId.name)
    if (encoderOptional.isPresent) {
      val encoder = encoderOptional.get()
      // noinspection ConvertExpressionToSAM
      Some(new StringTokenizer {
        override def encode(text: String): List[Token] =
          encoder.encode(text).toArray.map(tokenId => Token(tokenId)).toList
      })
    } else None
  }
}
