package org.llm4s.rag

import org.llm4s.vectorstore.{ FusionStrategy, LegFailurePolicy }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/**
 * `RAGConfig.withLegFailurePolicy` reaches the searcher whichever backends the config names (#2995). The two Postgres
 * backends are checked in `RAGLegFailurePolicyPgSpec` (`modules/it`, `@Docker`).
 */
class RAGLegFailurePolicySpec extends AnyFlatSpec with Matchers {

  private def configs(): Seq[(String, RAGConfig)] = {
    val dir = Files.createTempDirectory("llm4s-leg-policy")
    Seq(
      "in memory" -> RAGConfig.default.inMemory,
      "SQLite"    -> RAGConfig.default.withSQLite(dir.resolve("rag.db").toString),
      "SQLite, two files" -> RAGConfig.default.copy(
        vectorStorePath = Some(dir.resolve("v.db").toString),
        keywordIndexPath = Some(dir.resolve("k.db").toString)
      )
    )
  }

  "RAG's searcher" should "carry the configured LegFailurePolicy on every local backend" in {
    configs().foreach { case (name, config) =>
      withClue(name) {
        Seq(LegFailurePolicy.Degrade, LegFailurePolicy.Fail).foreach { policy =>
          val searcher = RAG.createHybridSearcher(config.withLegFailurePolicy(policy)).toOption.get
          searcher.legFailurePolicy shouldBe policy
          searcher.close()
        }
      }
    }
  }

  it should "fail a fused search whose keyword leg fails under Fail, and answer from the vectors under Degrade" in {
    configs().foreach { case (name, config) =>
      withClue(name) {
        Seq(LegFailurePolicy.Degrade -> true, LegFailurePolicy.Fail -> false).foreach { case (policy, answers) =>
          val searcher = RAG.createHybridSearcher(config.withLegFailurePolicy(policy)).toOption.get
          searcher.keywordIndex.close() // every keyword search now fails
          searcher.search(Array(0.1f, 0.2f), "anything", 3, FusionStrategy.RRF()).isRight shouldBe answers
          searcher.vectorStore.close()
        }
      }
    }
  }
}
