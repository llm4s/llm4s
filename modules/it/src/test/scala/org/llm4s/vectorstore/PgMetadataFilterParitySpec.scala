package org.llm4s.vectorstore

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.rag.permissions.{ ChunkWithEmbedding, CollectionConfig, CollectionPattern }
import org.llm4s.rag.permissions.UserAuthorization
import org.llm4s.rag.permissions.pg.{ PgSearchIndex, PgSuiteCleanup }
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.vectorstore.testkit.MetadataFilterMatrix
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `PgSearchIndex` selects the records [[MetadataFilterMatrix]] states for every filter (#2996), in `query` for an admin
 * and for an anonymous reader, and in `keywordQuery`: `HasKey` runs, `Not` keeps a record that lacks the key,
 * `Contains` is a literal substring test, and an empty `In` or `Not(All)` matches nothing. `PgVectorStore` and
 * `PgKeywordIndex` run the same matrix in their contract suites, [[PgVectorStoreContractSpec]] and
 * [[PgKeywordIndexContractSpec]].
 *
 * To run:
 *   export PGVECTOR_TEST_URL=jdbc:postgresql://localhost:5432/postgres
 *   export PGVECTOR_TEST_PASSWORD=postgres
 *   sbt "it/testOnly org.llm4s.vectorstore.PgMetadataFilterParitySpec"
 */
@Docker
class PgMetadataFilterParitySpec extends AnyWordSpec with Matchers {

  private val testUrl  = sys.env.get("PGVECTOR_TEST_URL")
  private val user     = sys.env.getOrElse("PGVECTOR_TEST_USER", "postgres")
  private val password = Secret(sys.env.getOrElse("PGVECTOR_TEST_PASSWORD", ""))
  private val suffix   = System.currentTimeMillis()

  private def requirePg(): Unit = Tier.require(testUrl.isDefined, "PGVECTOR_TEST_URL not set")

  private def checkAll(select: MetadataFilter => Result[Set[String]]): Unit =
    MetadataFilterMatrix.cases.foreach(c => withClue(c.filter)(select(c.filter) shouldBe Right(c.expected)))

  "PgSearchIndex" should {
    "select the records the filter matrix states in query and keywordQuery" in {
      requirePg()
      val table = s"filter_parity_s_$suffix"
      val index = PgSearchIndex.fromJdbcUrl(testUrl.get, user, password, table).toOption.get
      // A root of its own, never dropSchema(): the collection and principal tables are shared with other suites.
      val root = PgSuiteCleanup.runRoot("filter-parity")
      try {
        index.initializeSchema() shouldBe Right(())
        val path = root.child("leaf").toOption.get
        index.collections.create(CollectionConfig.publicParent(root)).isRight shouldBe true
        index.collections.create(CollectionConfig.publicLeaf(path)).isRight shouldBe true
        MetadataFilterMatrix.records.foreach { case (id, m) =>
          index.ingest(path, id, Seq(ChunkWithEmbedding("common words", Array(1.0f, 0.0f), 0)), m, Set.empty) shouldBe
            a[Right[?, ?]]
        }
        val pattern                              = CollectionPattern.Exact(path)
        def docIds(ms: Seq[Map[String, String]]) = ms.flatMap(_.get("docId")).toSet
        checkAll { f =>
          index
            .query(UserAuthorization.Admin, pattern, Array(1.0f, 0.0f), 10, Some(f))
            .map(r => docIds(r.map(_.record.metadata)))
        }
        checkAll { f =>
          index
            .query(UserAuthorization.Anonymous, pattern, Array(1.0f, 0.0f), 10, Some(f))
            .map(r => docIds(r.map(_.record.metadata)))
        }
        checkAll(f =>
          index
            .keywordQuery(UserAuthorization.Admin, pattern, "common", 10, Some(f))
            .map(r => docIds(r.map(_.metadata)))
        )
      } finally {
        PgSuiteCleanup.removeOwn(index, root, testUrl.get, user, password, Seq(table))
        index.close()
      }
    }
  }
}
