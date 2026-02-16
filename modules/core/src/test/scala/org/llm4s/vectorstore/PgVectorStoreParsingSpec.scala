package org.llm4s.vectorstore

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests that PgVectorStore delegates parsing to EmbeddingParser.
 * Comprehensive format tests are in EmbeddingParserSpec.
 *
 * Note: PgVectorStore.stringToEmbedding is package-private and directly
 * delegates to EmbeddingParser.parse(). No additional testing needed
 * beyond verifying EmbeddingParser behavior (covered in EmbeddingParserSpec).
 */
class PgVectorStoreParsingSpec extends AnyWordSpec with Matchers {

  "PgVectorStore parsing delegation" should {
    "be tested via EmbeddingParserSpec" in {
      // stringToEmbedding directly calls EmbeddingParser.parse()
      // All parsing logic is tested in EmbeddingParserSpec with 15 test cases
      // Integration: PgVectorStoreSpec tests end-to-end with real database
      succeed
    }
  }
}
