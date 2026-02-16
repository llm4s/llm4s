package org.llm4s.rag.permissions.pg

import org.llm4s.rag.permissions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Pure unit tests for PgSearchIndex embedding parsing logic.
 * Tests corrupt/unparseable embedding strings without DB dependency.
 */
class PgSearchIndexParsingSpec extends AnyFlatSpec with Matchers {

  // Access package-private parser without creating a full index
  private def parseEmbedding(s: String): Option[Array[Float]] = {
    // Create a minimal test instance to access the method
    val testIndex = PgSearchIndex.create(
      jdbcUrl = "jdbc:postgresql://localhost:5432/test",
      username = "test",
      password = "test",
      tableName = "test_search"
    ).toOption.get
    
    try {
      testIndex.parseEmbedding(s)
    } finally {
      testIndex.close()
    }
  }

  "PgSearchIndex.parseEmbedding" should "parse valid embedding strings" in {
    val result = parseEmbedding("[0.1,0.2,0.3]")
    result shouldBe defined
    result.get should have length 3
    result.get(0) shouldBe 0.1f +- 0.001f
    result.get(1) shouldBe 0.2f +- 0.001f
    result.get(2) shouldBe 0.3f +- 0.001f
  }

  it should "parse embedding with negative values" in {
    val result = parseEmbedding("[-0.5,0.7,-1.2]")
    result shouldBe defined
    result.get should have length 3
    result.get(0) shouldBe -0.5f +- 0.001f
  }

  it should "parse embedding with spaces" in {
    val result = parseEmbedding("[ 0.1 , 0.2 , 0.3 ]")
    result shouldBe defined
    result.get should have length 3
  }

  it should "return None for null input" in {
    parseEmbedding(null) shouldBe None
  }

  it should "return None for empty string" in {
    parseEmbedding("") shouldBe None
  }

  it should "return None for empty brackets" in {
    parseEmbedding("[]") shouldBe None
  }

  it should "return None for non-numeric tokens" in {
    parseEmbedding("[abc,def,ghi]") shouldBe None
  }

  it should "return None for malformed brackets" in {
    parseEmbedding("[0.1,0.2") shouldBe None
    parseEmbedding("0.1,0.2]") shouldBe None
  }

  it should "return None for missing commas" in {
    parseEmbedding("[0.1 0.2 0.3]") shouldBe None
  }

  it should "return None for double commas" in {
    parseEmbedding("[0.1,,0.3]") shouldBe None
  }

  it should "return None for text without brackets" in {
    parseEmbedding("not-an-array") shouldBe None
  }

  it should "return None for incomplete numbers" in {
    parseEmbedding("[0.1,.,0.3]") shouldBe None
  }

  it should "return None for special characters" in {
    parseEmbedding("[0.1,@#$,0.3]") shouldBe None
  }
}
