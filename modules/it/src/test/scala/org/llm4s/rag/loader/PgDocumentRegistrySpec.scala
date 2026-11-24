package org.llm4s.rag.loader

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ HybridSearcher, KeywordIndex, PgVectorStore }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.util.{ Try, Using }

/**
 * `PgDocumentRegistry` against PostgreSQL (#2714): the registry contract, a pool shared with a `PgVectorStore`
 * (#2300), and a sync that survives a restart - the second pipeline re-embeds only what changed and deletes what the
 * source dropped.
 *
 * Each test uses its own tables. Needs PostgreSQL with pgvector; set `PGVECTOR_TEST_URL` (CI's Docker tier provides
 * it).
 */
@Docker
class PgDocumentRegistrySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private given ModelRegistryService = ModelRegistryService.default().toOption.get

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = Secret(sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres"))

  private val suffix        = System.currentTimeMillis().toString
  private val createdTables = mutable.ListBuffer.empty[String]
  private val pools         = mutable.ListBuffer.empty[HikariDataSource]

  private def requirePg(): String = {
    Tier.require(pgUrl.isDefined, "PGVECTOR_TEST_URL not set")
    pgUrl.get
  }

  private def tableName(label: String): String = {
    val name = s"doc_registry_${label}_$suffix"
    createdTables += name
    name
  }

  private def pool(url: String): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(url)
    config.setUsername(pgUser)
    config.setPassword(pgPassword.reveal)
    config.setMaximumPoolSize(4)
    val ds = new HikariDataSource(config)
    pools += ds
    ds
  }

  private def registry(url: String, label: String): PgDocumentRegistry =
    PgDocumentRegistry(url, pgUser, pgPassword, tableName(label)).fold(e => fail(e.formatted), identity)

  override def afterAll(): Unit = {
    pools.foreach(p => Try(p.close()))
    pgUrl.foreach { url =>
      Try {
        Using.resource(DriverManager.getConnection(url, pgUser, pgPassword.reveal)) { conn =>
          Using.resource(conn.createStatement()) { stmt =>
            createdTables.foreach(t => stmt.execute(s"DROP TABLE IF EXISTS $t"))
          }
        }
      }
    }
    super.afterAll()
  }

  private val v1 = DocumentVersion("hash-1")
  private val v2 = DocumentVersion("hash-2", timestamp = Some(1700000000000L), etag = Some("\"etag\""))

  "PgDocumentRegistry" should "round-trip versions and list documents by source" in {
    val url = requirePg()
    Using.resource(registry(url, "contract")) { r =>
      r.getVersion("missing") shouldBe Right(None)
      r.register("a", v1) shouldBe Right(())
      r.register("b", v2, "s3://bucket") shouldBe Right(())
      r.register("c", v1, "s3://bucket") shouldBe Right(())
      r.getVersion("a") shouldBe Right(Some(v1))
      r.getVersion("b") shouldBe Right(Some(v2))
      r.documentIds(DocumentRegistry.DefaultSourceId) shouldBe Right(Set("a"))
      r.documentIds("s3://bucket") shouldBe Right(Set("b", "c"))
      r.count() shouldBe Right(3)

      // Registering again replaces the version and moves the document to the new source
      r.register("b", v1, "other") shouldBe Right(())
      r.getVersion("b") shouldBe Right(Some(v1))
      r.documentIds("s3://bucket") shouldBe Right(Set("c"))

      r.unregister("a") shouldBe Right(())
      r.allDocumentIds() shouldBe Right(Set("b", "c"))
      r.clear() shouldBe Right(())
      r.allDocumentIds() shouldBe Right(Set.empty)
    }
  }

  it should "keep its registrations across instances over the same table" in {
    val url   = requirePg()
    val table = tableName("reopen")
    Using.resource(PgDocumentRegistry(url, pgUser, pgPassword, table).fold(e => fail(e.formatted), identity)) {
      _.register("a", v2, "docs") shouldBe Right(())
    }
    Using.resource(PgDocumentRegistry(url, pgUser, pgPassword, table).fold(e => fail(e.formatted), identity)) { r =>
      r.getVersion("a") shouldBe Right(Some(v2))
      r.documentIds("docs") shouldBe Right(Set("a"))
    }
  }

  it should "share a caller-owned DataSource and leave it open when closed" in {
    val url = requirePg()
    val ds  = pool(url)
    val r   = PgDocumentRegistry.fromDataSource(ds, tableName("shared")).fold(e => fail(e.formatted), identity)
    r.register("a", v1) shouldBe Right(())
    r.close()
    ds.isClosed shouldBe false
    PgDocumentRegistry.fromDataSource(ds, tableName("shared2")).map(_.count()) shouldBe Right(Right(0))
  }

  it should "refuse a table name that is not a SQL identifier" in {
    val url = requirePg()
    PgDocumentRegistry.fromDataSource(pool(url), "bad; DROP TABLE x").isLeft shouldBe true
  }

  "A RAG with a PgDocumentRegistry" should "sync incrementally after a restart" in {
    val url          = requirePg()
    val ds           = pool(url)
    val vectorTable  = tableName("vectors")
    val registryName = tableName("registry")
    val embedded     = new AtomicInteger()

    val provider = new EmbeddingProvider {
      override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
        embedded.addAndGet(request.input.size)
        Right(EmbeddingResponse(embeddings = request.input.map(_ => Seq(0.1, 0.2, 0.3))))
      }
    }

    // One process's pipeline: a vector store and a registry over one shared pool
    def run(loader: DocumentLoader): Result[SyncStats] = {
      val reg   = PgDocumentRegistry.fromDataSource(ds, registryName).fold(e => fail(e.formatted), identity)
      val store = PgVectorStore(ds, vectorTable).fold(e => fail(e.formatted), identity)
      val searcher =
        HybridSearcher(store, KeywordIndex.inMemory().fold(e => fail(e.formatted), identity))
      val rag = RAG
        .buildWithClient(
          RAGConfig.default.withDocumentRegistry(reg),
          EmbeddingClient(provider),
          hybridSearcher = Some(searcher)
        )
        .fold(e => fail(e.formatted), identity)
      try rag.sync(loader, "files")
      finally rag.close()
    }

    def loader(docs: (String, String)*): DocumentLoader = new DocumentLoader {
      override def load(): Iterator[LoadResult] =
        docs.iterator.map { case (id, content) => LoadResult.Success(Document(id, content)) }
      override def description: String = "test"
    }

    run(loader("a" -> "Alpha.", "b" -> "Beta.", "c" -> "Gamma.")) shouldBe Right(SyncStats(3, 0, 0, 0))
    embedded.set(0)

    run(loader("a" -> "Alpha.", "b" -> "Beta, edited.")) shouldBe Right(SyncStats(0, 1, 1, 1))
    embedded.get shouldBe 1
    ds.isClosed shouldBe false
    Using.resource(ds.getConnection) { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        Using.resource(stmt.executeQuery(s"SELECT count(*) FROM $vectorTable WHERE id LIKE 'c-chunk-%'")) { rs =>
          rs.next(); rs.getInt(1) shouldBe 0
        }
      }
    }
  }
}
