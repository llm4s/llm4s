package org.llm4s.rag.permissions.pg

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.rag.permissions._
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ PgVectorStore, PgVectorTableSchema, VectorRecord }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{ Connection, DriverManager }
import scala.collection.mutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * `PgSearchIndex` and `PgVectorStore` share one vectors table when a RAG pipeline is built with
 * `RAGConfig.withSearchIndex`. They used to carry two different `CREATE TABLE` statements for it,
 * and the permission one had no `created_at` column, so whichever created the table first decided
 * whether the other could open it: `RAG.build` after `PgSearchIndex.initializeSchema()` failed with
 * `column "created_at" does not exist`.
 *
 * These tests use their own vectors and keyword tables and leave the shared permission tables
 * (`llm4s_collections`, `llm4s_principals`) in place, so they do not disturb other suites.
 *
 * Needs PostgreSQL with pgvector; set `PGVECTOR_TEST_URL` (CI's Docker tier provides it).
 */
@Docker
class PgSharedVectorTableSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private given ModelRegistryService = ModelRegistryService.default().toOption.get

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres")

  private val suffix        = System.currentTimeMillis().toString
  private val createdTables = mutable.ListBuffer.empty[String]
  private val openIndexes   = mutable.ListBuffer.empty[PgSearchIndex]

  private val JdbcPattern = """jdbc:postgresql://([^:/]+):?(\d+)?/(.+)""".r

  private def requirePg(): String = {
    Tier.require(pgUrl.isDefined, "PGVECTOR_TEST_URL not set")
    pgUrl.get
  }

  private def tableName(label: String): String = {
    val name = s"shared_vec_${label}_$suffix"
    createdTables += name
    name
  }

  private def connect(url: String): Connection = DriverManager.getConnection(url, pgUser, pgPassword)

  private def newIndex(url: String, vectorTable: String): PgSearchIndex = {
    val keywordTable = tableName("kw")
    val config = url match {
      case JdbcPattern(host, port, database) =>
        SearchIndex.PgConfig(
          host = host,
          port = Option(port).map(_.toInt).getOrElse(5432),
          database = database,
          user = pgUser,
          password = pgPassword,
          vectorTableName = vectorTable,
          keywordTableName = keywordTable
        )
      case other => fail(s"Unrecognised PGVECTOR_TEST_URL: $other")
    }
    val index = PgSearchIndex(config).fold(e => fail(s"Failed to create PgSearchIndex: ${e.formatted}"), identity)
    openIndexes += index
    index
  }

  private def columnNames(url: String, table: String): Set[String] =
    Using.resource(connect(url)) { conn =>
      Using.resource(
        conn.prepareStatement(
          "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ?"
        )
      ) { stmt =>
        stmt.setString(1, table)
        Using.resource(stmt.executeQuery()) { rs =>
          Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toSet
        }
      }
    }

  private class FixedEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map(_ => Seq(0.1, 0.2, 0.3))))
  }

  override def afterAll(): Unit = {
    openIndexes.foreach(i => Try(i.close()))
    pgUrl.foreach { url =>
      Try {
        Using.resource(connect(url)) { conn =>
          Using.resource(conn.createStatement()) { stmt =>
            createdTables.foreach(t => stmt.execute(s"DROP TABLE IF EXISTS $t"))
          }
        }
      }
    }
    super.afterAll()
  }

  "RAG.build with withSearchIndex" should "open the vectors table PgSearchIndex.initializeSchema created" in {
    val url   = requirePg()
    val table = tableName("rag")
    val index = newIndex(url, table)
    index.initializeSchema() shouldBe Right(())

    // Before the fix: Left(... column "created_at" does not exist ...)
    val rag = RAG
      .buildWithClient(RAGConfig.default.withSearchIndex(index), new EmbeddingClient(new FixedEmbeddingProvider()))
      .fold(e => fail(s"RAG.build failed: ${e.formatted}"), identity)
    rag.close()
    columnNames(url, table) should contain("created_at")
  }

  "PgVectorStore" should "upgrade a vectors table created by the pre-fix PgSchemaManager DDL" in {
    val url   = requirePg()
    val table = tableName("legacy")

    // The table exactly as PgSchemaManager.extendVectorsTable used to create it.
    Using.resource(connect(url)) { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")
        stmt.execute(s"""
          CREATE TABLE $table (
            id TEXT PRIMARY KEY,
            content TEXT NOT NULL,
            embedding vector,
            embedding_dim INTEGER,
            metadata JSONB DEFAULT '{}',
            collection_id INTEGER,
            readable_by INTEGER[] DEFAULT '{}'
          )
        """)
        stmt.execute(
          s"INSERT INTO $table (id, content, embedding, embedding_dim) VALUES ('old-1', 'kept', '[0.1,0.2,0.3]', 3)"
        )
      }
    }

    val store = PgVectorStore(url, pgUser, pgPassword, table).fold(e => fail(e.formatted), identity)
    try {
      columnNames(url, table) should contain("created_at")
      store.get("old-1").map(_.flatMap(_.content)) shouldBe Right(Some("kept"))
      // `list` orders by created_at; rows that predate the column must still be listed.
      store.list(10, 0, None).map(_.map(_.id)) shouldBe Right(Seq("old-1"))
      // The legacy DDL made content NOT NULL; the shared definition allows a record without it.
      store.upsert(VectorRecord("new-1", Array(0.3f, 0.2f, 0.1f), None, Map.empty)) shouldBe Right(())
      store.count(None) shouldBe Right(2L)
    } finally store.close()

    // And the permission side still accepts the upgraded table.
    newIndex(url, table).initializeSchema() shouldBe Right(())
  }

  "The created_at upgrade" should "succeed for an initialiser that loses the race to add the column" in {
    val url   = requirePg()
    val table = tableName("race")
    val addCreatedAt = PgVectorTableSchema
      .statements(table)
      .find(_.contains("ADD COLUMN IF NOT EXISTS created_at"))
      .getOrElse(fail("no created_at upgrade statement"))

    Using.resource(connect(url)) { setup =>
      Using.resource(setup.createStatement()) { stmt =>
        stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")
        stmt.execute(s"CREATE TABLE $table (id TEXT PRIMARY KEY, content TEXT NOT NULL, embedding vector)")
      }
    }

    // Replica A adds the column and holds the table lock, uncommitted. Replica B's catalog check
    // cannot see A's column, so B goes on to the ALTER and waits for the lock. When A commits, B's
    // ALTER runs against a table that now has the column: an unguarded ADD COLUMN failed here.
    Using.resource(connect(url)) { a =>
      a.setAutoCommit(false)
      Using.resource(a.createStatement())(_.execute(addCreatedAt))

      val b =
        Future(Using.resource(connect(url))(conn => Using.resource(conn.createStatement())(_.execute(addCreatedAt))))

      val deadline = System.nanoTime() + 10.seconds.toNanos
      while (!waitingOnLock(url, table) && System.nanoTime() < deadline) Thread.sleep(50)
      withClue("replica B never waited on replica A's lock: ")(waitingOnLock(url, table) shouldBe true)

      a.commit()
      Try(Await.result(b, 10.seconds)).toEither.left.map(_.getMessage) shouldBe Right(false)
    }
    columnNames(url, table) should contain("created_at")
  }

  private def waitingOnLock(url: String, table: String): Boolean =
    Using.resource(connect(url)) { conn =>
      Using.resource(
        conn.prepareStatement("SELECT count(*) FROM pg_locks WHERE NOT granted AND relation = to_regclass(?)")
      ) { stmt =>
        stmt.setString(1, table)
        Using.resource(stmt.executeQuery())(rs => rs.next() && rs.getInt(1) > 0)
      }
    }

  "PgSearchIndex and PgVectorStore" should "share one table whichever creates it first" in {
    val url   = requirePg()
    val table = tableName("order")

    val store = PgVectorStore(url, pgUser, pgPassword, table).fold(e => fail(e.formatted), identity)
    try {
      val index = newIndex(url, table)
      val path  = CollectionPath.unsafe(s"shared-vec-$suffix")
      val result = for {
        _ <- index.initializeSchema()
        _ <- index.collections.ensureExists(CollectionConfig.publicLeaf(path))
        n <- index.ingest(
          collectionPath = path,
          documentId = "doc",
          chunks = Seq(ChunkWithEmbedding("permissioned chunk", Array(0.1f, 0.2f, 0.3f), 0))
        )
        listed <- store.list(10, 0, None)
      } yield (n, listed.flatMap(_.content))

      result shouldBe Right((1, Seq("permissioned chunk")))
    } finally store.close()
  }
}
