package org.llm4s.javaapi.rag

import org.llm4s.testutil.TestTimeLimit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Runs the RAG and embedding blocks of `docs/guide/java-migration.md` (`MigrationRagSnippets.java`) over `RagFakes`'
 * word-count embeddings and echoing model, so they need no network. `JavaMigrationGuideSpec` in `llm4s-java-api`
 * checks that the guide's blocks are word for word in that file.
 */
class JavaMigrationRagSpec extends AnyWordSpec with TestTimeLimit with Matchers {

  import RagFakes.*

  "The embeddings block" should {
    "find two texts about a cat on a mat alike" in {
      MigrationRagSnippets.embeddings(embeddings()) shouldBe 1.0 +- 1e-6
    }
  }

  "The RAG block" should {
    "ingest a text and answer from it, naming the source" in {
      MigrationRagSnippets.rag(embeddings(), llm()) shouldBe "Refunds are paid within 14 days. [refund-policy]"
    }
  }
}
