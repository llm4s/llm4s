package org.llm4s.context.tokens

import org.llm4s.annotation.Stable

import com.knuddels.jtokkit.Encodings

/**
 * Identifies a BPE tokenizer vocabulary by its canonical name.
 *
 * A tokenizer id selects the byte-pair-encoding vocabulary used to count tokens without calling
 * the provider API. Which vocabulary a model uses is its vendor's knowledge: the vendor's module
 * maps model names to ids (`OpenAITokenizers` in `llm4s-openai`).
 *
 * @param name Tokenizer vocabulary name as used by tiktoken and related
 *             libraries (e.g. `"cl100k_base"`).
 */
@Stable
final case class TokenizerId(name: String)

//noinspection TypeAnnotation,ScalaUnusedSymbol
object TokenizerId {
  val R50K_BASE: TokenizerId   = TokenizerId("r50k_base")
  val P50K_BASE: TokenizerId   = TokenizerId("p50k_base")
  val P50K_EDIT: TokenizerId   = TokenizerId("p50k_edit")
  val CL100K_BASE: TokenizerId = TokenizerId("cl100k_base")
  val O200K_BASE: TokenizerId  = TokenizerId("o200k_base")
}

/**
 * Represents a single BPE token as an integer vocabulary index.
 *
 * The integer corresponds to the token identifier used by the underlying
 * jtokkit / TikToken encoding (e.g. `cl100k_base`).
 */
@Stable
final case class Token(tokenId: Int) {
  override def toString: String = s"$tokenId"
}

/**
 * Converts a plain string into a sequence of [[Token]]s using a specific vocabulary.
 *
 * The bundled implementations are backed by jtokkit encodings and obtained via
 * [[Tokenizer.lookupStringTokenizer]]. Implement the trait to count with another tokenizer, and
 * pass it to `ConversationTokenCounter.of`.
 */
@Stable
trait StringTokenizer {

  /** Encodes `text` into a list of [[Token]]s. */
  def encode(text: String): List[Token]

  /**
   * The number of tokens `text` encodes to. Token counting calls this, not [[encode]]: override it
   * when the count is cheaper than building the list.
   */
  def count(text: String): Int = encode(text).length
}

/**
 * Factory for obtaining [[StringTokenizer]] instances backed by jtokkit BPE encodings.
 *
 * Encodings are looked up from the jtokkit default registry which bundles the
 * standard TikToken vocabularies (`cl100k_base`, `o200k_base`, etc.).
 * An unknown `tokenizerId` — one whose name does not match any bundled vocabulary —
 * returns `None`; callers must handle the absent case explicitly.
 *
 * @see [[TokenizerId]] for vocabulary name constants
 */
@Stable
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
        // `encodeOrdinary`: text is data, so a special-token name in it (`<|endoftext|>`) is ordinary text.
        // `encode` throws on one, which failed every count of a message quoting it.
        override def encode(text: String): List[Token] =
          encoder.encodeOrdinary(text).toArray.map(tokenId => Token(tokenId)).toList

        override def count(text: String): Int = encoder.countTokensOrdinary(text)
      })
    } else None
  }
}
