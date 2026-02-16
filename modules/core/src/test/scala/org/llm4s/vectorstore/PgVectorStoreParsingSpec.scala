package org.llm4s.vectorstore

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

/**
 * Pure unit tests for embedding parsing logic.
 * Tests corrupt/unparseable embedding strings without DB dependency.
 */
class PgVectorStoreParsingSpec extends AnyWordSpec with Matchers {

  "EmbeddingParser.parse" should {
    "parse valid embedding strings" in {
      val result = EmbeddingParser.parse("[0.1,0.2,0.3]")
      result shouldBe defined
      (result.get should have).length(3)
      result.get(0) shouldBe 0.1f +- 0.001f
      result.get(1) shouldBe 0.2f +- 0.001f
      result.get(2) shouldBe 0.3f +- 0.001f
    }

    "parse embedding with negative values" in {
      val result = EmbeddingParser.parse("[-0.5,0.7,-1.2]")
      result shouldBe defined
      (result.get should have).length(3)
      result.get(0) shouldBe -0.5f +- 0.001f
    }

    "parse embedding with spaces" in {
      val result = EmbeddingParser.parse("[ 0.1 , 0.2 , 0.3 ]")
      result shouldBe defined
      (result.get should have).length(3)
    }

    "return None for null input" in {
      EmbeddingParser.parse(null) shouldBe None
    }

    "return None for empty string" in {
      EmbeddingParser.parse("") shouldBe None
    }

    "return None for empty brackets" in {
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

    "return None for oversized embeddings beyond max dimension" in {
      // Generate embedding string with MAX_EMBEDDING_DIM + 1 elements
      val oversized = "[" + (1 to (EmbeddingParser.MAX_EMBEDDING_DIM + 1)).map(_ => "0.1").mkString(",") + "]"
      EmbeddingParser.parse(oversized) shouldBe None
    }

    "accept embeddings at max dimension limit" in {
      // Generate embedding string with exactly MAX_EMBEDDING_DIM elements
      val maxSize = "[" + (1 to EmbeddingParser.MAX_EMBEDDING_DIM).map(_ => "0.1").mkString(",") + "]"
      val result  = EmbeddingParser.parse(maxSize)
      result shouldBe defined
      (result.get should have).length(EmbeddingParser.MAX_EMBEDDING_DIM)
    }
  }
}
