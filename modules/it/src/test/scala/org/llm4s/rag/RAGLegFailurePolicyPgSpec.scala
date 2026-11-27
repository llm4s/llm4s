package org.llm4s.rag

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.rag.permissions.pg.PgSuiteCleanup
import org.llm4s.security.Secret
import org.llm4s.vectorstore.{ FusionStrategy, LegFailurePolicy }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `RAGConfig.withLegFailurePolicy` reaches the searcher on both Postgres backends (#2995): pgvector with a SQLite
 * keyword index, and full Postgres hybrid. The local backends are `RAGLegFailurePolicySpec` in `llm4s-rag`.
 *
 * Needs PostgreSQL with pgvector; set `PGVECTOR_TEST_URL` (CI's Docker tier provides it).
 */
@Docker
class RAGLegFailurePolicyPgSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = Secret(sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres"))

  private val suffix   = System.currentTimeMillis().toString
  private val vectors  = s"leg_policy_vectors_$suffix"
  private val hybridV  = s"leg_policy_hv_$suffix"
  private val keywords = s"leg_policy_kw_$suffix"

  override def afterAll(): Unit = {
    pgUrl.foreach(url =>
      PgSuiteCleanup.dropOwnTables(url, pgUser, pgPassword, Seq(vectors, hybridV, s"${keywords}_keyword"))
    )
    super.afterAll()
  }

  private def url: String = {
    Tier.require(pgUrl.isDefined, "PGVECTOR_TEST_URL not set")
    pgUrl.get
  }

  "RAG's searcher" should "fail under Fail, and answer under Degrade, when the SQLite keyword leg of pgvector fails" in {
    Seq(LegFailurePolicy.Degrade -> true, LegFailurePolicy.Fail -> false).foreach { case (policy, answers) =>
      withClue(policy) {
        val config   = RAGConfig.default.withPgVector(url, pgUser, pgPassword, vectors).withLegFailurePolicy(policy)
        val searcher = RAG.createHybridSearcher(config).toOption.get
        searcher.legFailurePolicy shouldBe policy
        searcher.keywordIndex.close() // every keyword search now fails
        searcher.search(Array(0.1f, 0.2f, 0.3f), "anything", 3, FusionStrategy.RRF()).isRight shouldBe answers
        searcher.vectorStore.close()
      }
    }
  }

  it should "fail under Fail, and answer under Degrade, when the Postgres keyword leg fails" in {
    Seq(LegFailurePolicy.Degrade -> true, LegFailurePolicy.Fail -> false).foreach { case (policy, answers) =>
      withClue(policy) {
        val config =
          RAGConfig.default.withPgHybrid(url, pgUser, pgPassword, hybridV, keywords).withLegFailurePolicy(policy)
        val searcher = RAG.createHybridSearcher(config).toOption.get
        searcher.legFailurePolicy shouldBe policy
        PgSuiteCleanup.dropOwnTables(url, pgUser, pgPassword, Seq(s"${keywords}_keyword")) // the keyword leg fails
        searcher.search(Array(0.1f, 0.2f, 0.3f), "anything", 3, FusionStrategy.RRF()).isRight shouldBe answers
        searcher.close()
      }
    }
  }
}
