package org.llm4s.context.tokens

import org.llm4s.identity.TokenizerId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for [[Tokenizer.lookupStringTokenizer]] and [[Token]]: which vocabularies are known,
 * and the basic contract of the tokenizers it returns. jtokkit bundles its vocabularies, so no
 * network access is needed.
 */
class TokenizerSpec extends AnyFlatSpec with Matchers {

  private val allIds: Seq[TokenizerId] = Seq(
    TokenizerId.R50K_BASE,
    TokenizerId.P50K_BASE,
    TokenizerId.P50K_EDIT,
    TokenizerId.CL100K_BASE,
    TokenizerId.O200K_BASE
  )

  private def tokenizer(id: TokenizerId): StringTokenizer =
    Tokenizer.lookupStringTokenizer(id).getOrElse(fail(s"no tokenizer for ${id.name}"))

  "Tokenizer.lookupStringTokenizer" should "return a tokenizer for every TokenizerId constant" in {
    allIds.foreach { id =>
      withClue(s"tokenizer id '${id.name}': ")(Tokenizer.lookupStringTokenizer(id) should not be empty)
    }
  }

  it should "return None for an unknown vocabulary" in {
    Tokenizer.lookupStringTokenizer(TokenizerId("no-such-vocabulary")) shouldBe None
  }

  "A StringTokenizer" should "encode the empty string to an empty list" in {
    allIds.foreach(id => tokenizer(id).encode("") shouldBe Nil)
  }

  it should "encode non-empty text to non-empty tokens with non-negative ids" in {
    val tokens = tokenizer(TokenizerId.CL100K_BASE).encode("Hello, world!")
    tokens should not be empty
    all(tokens.map(_.tokenId)) should be >= 0
  }

  it should "be deterministic" in {
    val t    = tokenizer(TokenizerId.CL100K_BASE)
    val text = "The quick brown fox jumps over the lazy dog."
    t.encode(text) shouldBe t.encode(text)
  }

  it should "give at least as many tokens for a longer text as for a prefix of it" in {
    val t      = tokenizer(TokenizerId.CL100K_BASE)
    val prefix = "The quick brown fox"
    val longer = "The quick brown fox jumps over the lazy dog."
    t.encode(longer).size should be >= t.encode(prefix).size
  }

  it should "differ between vocabularies" in {
    val text = "hello world"
    val cl   = tokenizer(TokenizerId.CL100K_BASE).encode(text)
    val o2   = tokenizer(TokenizerId.O200K_BASE).encode(text)
    cl should not be empty
    o2 should not be empty
    cl.map(_.tokenId) should not be o2.map(_.tokenId)
  }

  "Token" should "print its id from toString" in {
    Token(42).toString shouldBe "42"
  }
}
