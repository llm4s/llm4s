package org.llm4s.rag.permissions.pg

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.rag.permissions._
import org.llm4s.security.Secret
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Using

/**
 * Re-ingesting a document into a `PgSearchIndex` (#2988): the new chunks replace the old ones, so a chunk the new
 * version no longer has, or the old version's permissions, never stays searchable; and `moveDocument` removes the
 * copy in the collection a document leaves. The same over a scripted connection is `PgSearchIndexIngestSpec`.
 *
 * Needs PostgreSQL with pgvector; set `PGVECTOR_TEST_URL` (CI's Docker tier provides it).
 */
@Docker
class PgSearchIndexReingestSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = Secret(sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres"))

  private val table                              = s"reingest_vectors_${System.currentTimeMillis()}"
  private val root                               = PgSuiteCleanup.runRoot("reingest")
  private def under(p: String): CollectionPath   = CollectionPath.unsafe(s"${root.value}/$p")
  private var searchIndex: Option[PgSearchIndex] = None

  private val hr       = PrincipalId.group(41)
  private val hrUser   = UserAuthorization.forUser(PrincipalId.user(41), Set(hr))
  private val outsider = UserAuthorization.forUser(PrincipalId.user(42), Set.empty)

  override def beforeAll(): Unit = {
    super.beforeAll()
    pgUrl.foreach { url =>
      PgSearchIndex.fromJdbcUrl(url, pgUser, pgPassword, table).foreach { index =>
        index.initializeSchema().flatMap(_ => index.collections.create(CollectionConfig.publicParent(root))) match {
          case Right(_) => searchIndex = Some(index)
          case Left(e)  => println(s"Failed to initialize schema: ${e.message}"); index.close()
        }
      }
    }
  }

  override def afterAll(): Unit = {
    for {
      index <- searchIndex
      url   <- pgUrl
    } PgSuiteCleanup.removeOwn(index, root, url, pgUser, pgPassword, Seq(table))
    searchIndex.foreach(_.close())
    super.afterAll()
  }

  private def index: PgSearchIndex = {
    Tier.require(searchIndex.isDefined, "PostgreSQL with pgvector not available")
    searchIndex.get
  }

  private val vector = Array(0.1f, 0.2f, 0.3f)

  private def chunks(n: Int, text: Int => String): Seq[ChunkWithEmbedding] =
    (0 until n).map(i => ChunkWithEmbedding(text(i), vector, i))

  private def search(auth: UserAuthorization, pattern: CollectionPattern): Seq[String] =
    index.query(auth, pattern, vector, topK = 50).toOption.get.flatMap(_.record.content)

  private def rows(docId: String): Long =
    Using.resource(DriverManager.getConnection(pgUrl.get, pgUser, pgPassword.reveal)) { conn =>
      Using.resource(conn.prepareStatement(s"SELECT COUNT(*) FROM $table WHERE metadata->>'docId' = ?")) { stmt =>
        stmt.setString(1, docId)
        Using.resource(stmt.executeQuery()) { rs =>
          rs.next(); rs.getLong(1)
        }
      }
    }

  "A re-ingest" should "replace the document: fewer chunks, and the tightened readableBy on every one" in {
    val path = under("handbook")
    index.collections.create(CollectionConfig.publicLeaf(path)).isRight shouldBe true

    index.ingest(path, "handbook", chunks(10, i => s"open section $i")).map(_.size) shouldBe Right(10)
    index.ingest(path, "handbook", chunks(6, i => s"hr section $i"), readableBy = Set(hr)).map(_.size) shouldBe
      Right(6)

    rows("handbook") shouldBe 6
    search(UserAuthorization.Anonymous, CollectionPattern.Exact(path)) shouldBe empty
    search(outsider, CollectionPattern.Exact(path)) shouldBe empty
    search(hrUser, CollectionPattern.Exact(path)).sorted shouldBe (0 until 6).map(i => s"hr section $i").sorted
    index
      .keywordQuery(UserAuthorization.Anonymous, CollectionPattern.Exact(path), "open section", 50, None)
      .map(_.size) shouldBe Right(0)
  }

  it should "keep the copy in another collection, which moveDocument removes" in {
    val public     = under("public")
    val restricted = under("restricted")
    index.collections.create(CollectionConfig.publicLeaf(public)).isRight shouldBe true
    index.collections.create(CollectionConfig.restrictedLeaf(restricted, Set(hr))).isRight shouldBe true

    index.ingest(public, "memo", chunks(3, i => s"memo $i")).isRight shouldBe true

    // ingest into another collection is a second copy
    index.ingest(restricted, "memo", chunks(2, i => s"memo $i")).isRight shouldBe true
    search(outsider, CollectionPattern.Exact(public)) should have size 3

    // moveDocument is a move: the public copy goes in the same transaction
    index.moveDocument(restricted, "memo", chunks(2, i => s"memo $i")).isRight shouldBe true
    rows("memo") shouldBe 2
    search(outsider, CollectionPattern.AllDescendants(root)) shouldBe empty
    search(hrUser, CollectionPattern.Exact(restricted)) should have size 2
  }

  it should "leave the old version in place when the new one fails to insert" in {
    val path = under("refused")
    index.collections.create(CollectionConfig.publicLeaf(path)).isRight shouldBe true
    index.ingest(path, "kept", chunks(2, i => s"kept $i")).isRight shouldBe true

    // Postgres refuses a NUL in text, after the delete has run: it is rolled back with the insert
    index.ingest(path, "kept", chunks(1, _ => "bad \u0000 text")).isLeft shouldBe true

    rows("kept") shouldBe 2
    search(outsider, CollectionPattern.Exact(path)).sorted shouldBe Seq("kept 0", "kept 1")
  }
}
