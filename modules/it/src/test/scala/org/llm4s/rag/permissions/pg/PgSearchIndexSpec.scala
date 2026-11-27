package org.llm4s.rag.permissions.pg

import org.llm4s.security.Secret
import org.llm4s.rag.permissions._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker

/**
 * Integration tests for PgSearchIndex.
 *
 * These tests require a running PostgreSQL database with pgvector extension.
 * Set PGVECTOR_TEST_URL environment variable to enable tests.
 *
 * To run:
 *   export PGVECTOR_TEST_URL=jdbc:postgresql://localhost:5432/postgres
 *   export PGVECTOR_USER=postgres
 *   export PGVECTOR_PASSWORD=postgres
 *   sbt "it/testOnly org.llm4s.rag.permissions.pg.PgSearchIndexSpec"
 */
@Docker
class PgSearchIndexSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = Secret(sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres"))

  private val testTableName                      = "test_permission_vectors"
  private var searchIndex: Option[PgSearchIndex] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    pgUrl.foreach { url =>
      PgSearchIndex.fromJdbcUrl(url, pgUser, pgPassword, testTableName) match {
        case Right(index) =>
          index.dropSchema()
          index.initializeSchema() match {
            case Right(_) => searchIndex = Some(index)
            case Left(e)  => println(s"Failed to initialize schema: ${e.message}")
          }
        case Left(e) =>
          println(s"Failed to create PgSearchIndex: ${e.message}")
      }
    }
  }

  override def afterAll(): Unit = {
    searchIndex.foreach(_.close())
    super.afterAll()
  }

  private def requirePg(): Unit =
    Tier.require(searchIndex.isDefined, "PostgreSQL with pgvector not available")

  "PgPrincipalStore" should "create and lookup users" in {
    requirePg()
    val store = searchIndex.get.principals

    val result = for {
      id     <- store.getOrCreate(ExternalPrincipal.User("test-user@example.com"))
      lookup <- store.lookup(ExternalPrincipal.User("test-user@example.com"))
    } yield (id, lookup)

    result.isRight shouldBe true
    val (id, lookup) = result.toOption.get
    id.isUser shouldBe true
    id.value should be > 0
    lookup shouldBe Some(id)
  }

  "PgCollectionStore" should "not let _ in an AllDescendants prefix match sibling collections (#2989)" in {
    requirePg()
    val store = searchIndex.get.collections
    val paths = Seq("tenant_1", "tenant11", "tenant-1", "tenantA1")

    val created = paths.foldLeft[org.llm4s.types.Result[Unit]](Right(())) { (acc, p) =>
      for {
        _ <- acc
        _ <- store.create(CollectionConfig.publicParent(CollectionPath.unsafe(p)))
        _ <- store.create(CollectionConfig.publicLeaf(CollectionPath.unsafe(s"$p/docs")))
      } yield ()
    }
    created shouldBe Right(())

    val pattern  = CollectionPattern.AllDescendants(CollectionPath.unsafe("tenant_1"))
    val expected = Seq("tenant_1", "tenant_1/docs")

    store.list(pattern).map(_.map(_.path.value)) shouldBe Right(expected)
    store.findAccessible(UserAuthorization.Admin, pattern).map(_.map(_.path.value)) shouldBe Right(expected)
    store.findAccessible(UserAuthorization.Anonymous, pattern).map(_.map(_.path.value)) shouldBe Right(expected)
  }

  "PgSearchIndex" should "store and read back chunk and collection metadata a hand-written JSON writer broke on (#2993)" in {
    requirePg()
    val index = searchIndex.get
    val metadata = Map(
      "quote"     -> "say \"hi\"",
      "backslash" -> """C:\docs\""",
      "escape"    -> """a\nb""",
      "newline"   -> "line one\nline two",
      "tab"       -> "a\tb",
      "unicode"   -> "héllo 世界 🚀",
      "title"     -> """x","tenant":"other""",
      "tenant"    -> "acme"
    )
    val path = CollectionPath.unsafe("meta2993")

    index.collections.create(CollectionConfig(path, metadata = metadata)).map(_.metadata) shouldBe Right(metadata)
    index.collections.get(path).map(_.map(_.metadata)) shouldBe Right(Some(metadata))
    index.collections.updateMetadata(path, metadata + ("extra" -> "\u0001")).map(_.metadata) shouldBe
      Right(metadata + ("extra" -> "\u0001"))

    val chunk = ChunkWithEmbedding("metadata escaping", Array(0.1f, 0.2f, 0.3f), 0)
    index.ingest(path, "doc-2993", Seq(chunk), metadata, Set.empty).map(_.size) shouldBe Right(1)

    val hits = index.query(UserAuthorization.Admin, CollectionPattern.Exact(path), Array(0.1f, 0.2f, 0.3f), 5)
    hits.map(_.map(_.record.metadata -- Set("docId", "chunkIndex"))) shouldBe Right(Seq(metadata))
  }
}
