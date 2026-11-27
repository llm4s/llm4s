package org.llm4s.rag.permissions.pg

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.rag.permissions._
import org.llm4s.security.Secret
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{ Connection, DriverManager }
import scala.collection.mutable
import scala.util.{ Try, Using }

/**
 * The documented reset, `dropSchema()` then `initializeSchema()`, and the migration after it (#2992): the reset
 * deletes every document that was in a collection, and the migration never makes a row ingested with permissions
 * public, including one an earlier `dropSchema` kept (a table in the old shape). The same over a scripted connection
 * is `PgSchemaManagerSpec`.
 *
 * `dropSchema` drops the permission tables every suite shares, so each test runs in a database of its own, created
 * here and dropped afterwards; nothing touches the shared database's `llm4s_` tables.
 *
 * Needs PostgreSQL with pgvector; set `PGVECTOR_TEST_URL` (CI's Docker tier provides it).
 */
@Docker
class PgSchemaResetSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = Secret(sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres"))

  private val JdbcPattern = """(jdbc:postgresql://[^/]+/)([^?]+)(.*)""".r
  private val suffix      = System.currentTimeMillis().toString
  private val databases   = mutable.ListBuffer.empty[String]
  private val indexes     = mutable.ListBuffer.empty[PgSearchIndex]

  private val reader  = PrincipalId.user(7)
  private val vector  = Array(0.1f, 0.2f, 0.3f)
  private val legacy  = CollectionPath.unsafe("legacy")
  private val secrets = CollectionPath.unsafe("secrets")

  private def admin(sql: String): Unit =
    Using.resource(DriverManager.getConnection(pgUrl.get, pgUser, pgPassword.reveal)) { conn =>
      Using.resource(conn.createStatement())(_.execute(sql)): Unit
    }

  /** A fresh database for one test, and its JDBC URL. */
  private def freshDatabase(label: String): String = {
    Tier.require(pgUrl.isDefined, "PGVECTOR_TEST_URL not set")
    val name = s"llm4s_reset_${label}_$suffix"
    admin(s"CREATE DATABASE $name")
    databases += name
    pgUrl.get match {
      case JdbcPattern(prefix, _, params) => s"$prefix$name$params"
      case other                          => fail(s"Unexpected PGVECTOR_TEST_URL: $other")
    }
  }

  private def openIndex(url: String, table: String): PgSearchIndex = {
    val index = PgSearchIndex.fromJdbcUrl(url, pgUser, pgPassword, table).toOption.get
    indexes += index
    index.initializeSchema() shouldBe Right(())
    index
  }

  private def withConnection[A](url: String)(f: Connection => A): A =
    Using.resource(DriverManager.getConnection(url, pgUser, pgPassword.reveal))(f)

  private def count(url: String, sql: String): Long =
    withConnection(url)(conn =>
      Using.resource(conn.createStatement())(s =>
        Using.resource(s.executeQuery(sql)) { rs =>
          rs.next(); rs.getLong(1)
        }
      )
    )

  private def insertPlainRow(url: String, table: String, id: String): Unit =
    withConnection(url) { conn =>
      Using.resource(
        conn.prepareStatement(
          s"INSERT INTO $table (id, embedding, embedding_dim, content, metadata) VALUES (?, '[0.1,0.2,0.3]', 3, ?, '{}')"
        )
      ) { stmt =>
        stmt.setString(1, id)
        stmt.setString(2, s"plain $id")
        stmt.executeUpdate(): Unit
      }
    }

  private def anonymous(index: PgSearchIndex): Seq[String] =
    index.query(UserAuthorization.Anonymous, CollectionPattern.All, vector, topK = 50).toOption.get.map(_.record.id)

  override def afterAll(): Unit = {
    indexes.foreach(i => Try(i.close()))
    pgUrl.foreach(_ => databases.foreach(db => Try(admin(s"DROP DATABASE IF EXISTS $db WITH (FORCE)"))))
    super.afterAll()
  }

  /** A restricted document ingested into `secrets`, readable by `reader` only. */
  private def ingestSecret(index: PgSearchIndex): Unit = {
    index.collections.create(CollectionConfig.restrictedLeaf(secrets, Set(reader))).isRight shouldBe true
    index
      .ingest(
        secrets,
        "salaries",
        Seq(ChunkWithEmbedding("salaries", vector, 0), ChunkWithEmbedding("more", vector, 1))
      )
      .isRight shouldBe true
  }

  "dropSchema" should "delete the documents in collections, keeping plain rows, and a migration then exposes nothing" in {
    val url   = freshDatabase("reset")
    val table = "reset_vectors"
    val index = openIndex(url, table)
    ingestSecret(index)
    insertPlainRow(url, table, "plain-1")

    index.dropSchema() shouldBe Right(())

    count(url, s"SELECT COUNT(*) FROM $table WHERE metadata->>'docId' = 'salaries'") shouldBe 0
    count(url, s"SELECT COUNT(*) FROM $table") shouldBe 1

    index.initializeSchema() shouldBe Right(())
    index.collections.create(CollectionConfig.publicLeaf(legacy)).isRight shouldBe true
    val stats = withConnection(url)(PgSchemaManager.runFullMigration(_, table, legacy)).toOption.get
    stats.vectorsMigrated shouldBe 1
    stats.vectorsSkipped shouldBe 0
    anonymous(index) shouldBe Seq("plain-1")
  }

  it should "leave an index that needs no migration empty after the reset" in {
    val url   = freshDatabase("empty")
    val index = openIndex(url, "empty_vectors")
    ingestSecret(index)

    index.dropSchema() shouldBe Right(())
    index.initializeSchema() shouldBe Right(())

    count(url, "SELECT COUNT(*) FROM empty_vectors") shouldBe 0
    index.collections.create(CollectionConfig.publicLeaf(legacy)).isRight shouldBe true
    withConnection(url)(PgSchemaManager.runFullMigration(_, "empty_vectors", legacy)).map(_.vectorsMigrated) shouldBe
      Right(0L)
    anonymous(index) shouldBe empty
  }

  it should "refuse, changing nothing, while another vectors table holds documents in the shared collections" in {
    val url   = freshDatabase("shared")
    val first = openIndex(url, "first_vectors")
    val other = openIndex(url, "other_vectors")
    ingestSecret(other)

    first.dropSchema().left.toOption.get.message should include("other_vectors")
    other.collections.get(secrets).map(_.isDefined) shouldBe Right(true)
    count(url, "SELECT COUNT(*) FROM other_vectors WHERE collection_id IS NOT NULL") shouldBe 2

    other.clearCollection(secrets) shouldBe Right(2L)
    first.dropSchema() shouldBe Right(())
  }

  "runFullMigration" should "never make public a row ingested with permissions that an earlier dropSchema kept" in {
    val url   = freshDatabase("oldshape")
    val table = "old_vectors"
    // The table as dropSchema left it before #2992: the ingested rows kept, their permission columns dropped.
    withConnection(url) { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        org.llm4s.vectorstore.PgVectorTableSchema.statements(table).foreach(stmt.execute)
        stmt.execute(
          s"""INSERT INTO $table (id, embedding, embedding_dim, content, metadata) VALUES
             |('coll-3-salaries-chunk-0', '[0.1,0.2,0.3]', 3, 'salaries', '{"docId":"salaries","chunkIndex":"0"}'),
             |('notes-chunk-0', '[0.1,0.2,0.3]', 3, 'notes', '{"docId":"notes","chunkIndex":"0"}')""".stripMargin
        )
      }
    }
    val index = openIndex(url, table)
    index.collections.create(CollectionConfig.publicLeaf(legacy)).isRight shouldBe true

    val first = withConnection(url)(PgSchemaManager.runFullMigration(_, table, legacy)).toOption.get
    first.vectorsMigrated shouldBe 1
    first.vectorsSkipped shouldBe 1
    anonymous(index) shouldBe Seq("notes-chunk-0")

    // idempotent: a second run migrates nothing more and still skips the orphan
    val second = withConnection(url)(PgSchemaManager.runFullMigration(_, table, legacy)).toOption.get
    second.vectorsMigrated shouldBe 0
    second.vectorsSkipped shouldBe 1
    anonymous(index) shouldBe Seq("notes-chunk-0")
  }

  it should "refuse a collection the caller has not created" in {
    val url = freshDatabase("missing")
    openIndex(url, "missing_vectors")
    insertPlainRow(url, "missing_vectors", "plain-1")

    withConnection(url)(PgSchemaManager.runFullMigration(_, "missing_vectors", legacy)).isLeft shouldBe true
    count(url, "SELECT COUNT(*) FROM missing_vectors WHERE collection_id IS NOT NULL") shouldBe 0
  }
}
