package org.llm4s.vectorstore

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues

/**
 * Pure unit tests for EmbeddingParser.
 *
 * Tests all corrupt/unparseable embedding formats without database dependency.
 *
 * Integration testing note:
 * Corrupt embeddings cannot be inserted into pgvector columns (validation rejects them).
 * Corruption happens at database/migration level, not via normal INSERT operations.
 *
 * Testing strategy:
 * - Unit tests (here): 15 cases verify parse() handles all corrupt formats
 * - Integration: Existing PgVectorStoreSpec/PgSearchIndexSpec test end-to-end flow
 * - Production: Rate-limited logging provides observability of corruption events
 */
class EmbeddingParserSpec extends AnyWordSpec with Matchers with OptionValues {

  "EmbeddingParser.parse" should {
    "parse valid embedding strings" in {
      val result = EmbeddingParser.parse("[0.1,0.2,0.3]")
      result shouldBe defined
      (result.value should have).length(3)
      result.value(0) shouldBe 0.1f +- 0.001f
      result.value(1) shouldBe 0.2f +- 0.001f
      result.value(2) shouldBe 0.3f +- 0.001f
    }

    "parse embedding with negative values" in {
      val result = EmbeddingParser.parse("[-0.5,0.7,-1.2]")
      result shouldBe defined
      (result.value should have).length(3)
      result.value(0) shouldBe -0.5f +- 0.001f
    }

    "parse embedding with spaces" in {
      val result = EmbeddingParser.parse("[ 0.1 , 0.2 , 0.3 ]")
      result shouldBe defined
      (result.value should have).length(3)
    }

    "return None for null input" in {
      EmbeddingParser.parse(null) shouldBe None
    }

    "return None for empty string" in {
      EmbeddingParser.parse("") shouldBe None
    }

    "return None for empty brackets (zero dimensions)" in {
      EmbeddingParser.parse("[]") shouldBe None
    }

    "return None for non-numeric tokens" in {
      EmbeddingParser.parse("[abc,def,ghi]") shouldBe None
    }

    "return None for malformed brackets" in {
      EmbeddingParser.parse("[0.1,0.2") shouldBe None
      EmbeddingParser.parse("0.1,0.2]") shouldBe None
    }

    "return None for missing commas" in {
      EmbeddingParser.parse("[0.1 0.2 0.3]") shouldBe None
    }

    "return None for double commas" in {
      EmbeddingParser.parse("[0.1,,0.3]") shouldBe None
    }

    "return None for text without brackets" in {
      EmbeddingParser.parse("not-an-array") shouldBe None
    }

    "return None for incomplete numbers" in {
      EmbeddingParser.parse("[0.1,.,0.3]") shouldBe None
    }

    "return None for special characters" in {
      EmbeddingParser.parse("[0.1,@#$,0.3]") shouldBe None
    }

    "return None for embeddings exceeding MAX_EMBEDDING_DIM" in {
      val oversized = "[" + (1 to 20000).map(_.toString + ".0").mkString(",") + "]"
      EmbeddingParser.parse(oversized) shouldBe None
    }

    "accept embeddings at MAX_EMBEDDING_DIM boundary" in {
      val atLimit = "[" + (1 to 16384).map(_.toString + ".0").mkString(",") + "]"
      val result  = EmbeddingParser.parse(atLimit)
      result shouldBe defined
      (result.value should have).length(16384)
    }
  }
}
