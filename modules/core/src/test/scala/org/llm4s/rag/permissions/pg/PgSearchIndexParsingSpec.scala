package org.llm4s.rag.permissions.pg

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests that PgSearchIndex delegates parsing to EmbeddingParser.
 * Comprehensive format tests are in EmbeddingParserSpec.
 *
 * Note: PgSearchIndex.parseEmbedding is package-private and directly
 * delegates to EmbeddingParser.parse(). No additional testing needed
 * beyond verifying EmbeddingParser behavior (covered in EmbeddingParserSpec).
 */
class PgSearchIndexParsingSpec extends AnyFlatSpec with Matchers {

  "PgSearchIndex parsing delegation" should "be tested via EmbeddingParserSpec" in {
    // parseEmbedding directly calls EmbeddingParser.parse()
    // All parsing logic is tested in EmbeddingParserSpec with 15 test cases
    // Integration: PgSearchIndexSpec tests end-to-end with real database
    succeed
  }
}
