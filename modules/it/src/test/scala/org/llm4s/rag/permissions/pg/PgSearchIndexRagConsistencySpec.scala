package org.llm4s.rag.permissions.pg

import org.llm4s.chunking.{ ChunkMetadata, DocumentChunk }
import org.llm4s.error.ConfigurationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.rag.permissions._
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ PgVectorStore, VectorRecord }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{ Connection, DriverManager }
import scala.collection.mutable
import scala.util.{ Try, Using }

/**
 * A `RAG` built with `withSearchIndex(PgSearchIndex)` mixed with the plain API (#2716): plain ingest is refused
 * instead of writing rows no permission query can find, `deleteDocument` removes a document's chunks under both id
 * schemes, and permission queries run keyword search, fusion and reranking inside the permission filter.
 *
 * Each test uses its own vectors and keyword tables and collections named with a per-run suffix, so the suite does
 * not disturb the shared permission tables of other suites.
 *
 * Needs PostgreSQL with pgvector; set `PGVECTOR_TEST_URL` (CI's Docker tier provides it).
 */
@Docker
class PgSearchIndexRagConsistencySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private given ModelRegistryService = ModelRegistryService.default().toOption.get

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = Secret(sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres"))

  private val suffix        = System.currentTimeMillis().toString
  private val createdTables = mutable.ListBuffer.empty[String]
  private val openIndexes   = mutable.ListBuffer.empty[PgSearchIndex]
  private val createdPaths  = mutable.ListBuffer.empty[CollectionPath]
  private val JdbcPattern   = """jdbc:postgresql://([^:/]+):?(\d+)?/(.+)""".r
  private val embeddings    = new EmbeddingClient(new FixedEmbeddingProvider())
  private val user1         = PrincipalId.user(1)
  private val user1Auth     = UserAuthorization.forUser(user1, Set.empty)
  private val outsiderAuth  = UserAuthorization.forUser(PrincipalId.user(2), Set.empty)
  private def tableName(label: String): String = {
    val name = s"rag_consistency_${label}_$suffix"
    createdTables += name
    name
  }

  private def requirePg(): String = {
    Tier.require(pgUrl.isDefined, "PGVECTOR_TEST_URL not set")
    pgUrl.get
  }

  private def connect(url: String): Connection = DriverManager.getConnection(url, pgUser, pgPassword.reveal)

  private class FixedEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map(_ => Seq(0.1, 0.2, 0.3))))
  }

  /** A fresh index over tables of its own, its schema initialised. */
  private def newIndex(url: String, label: String): (PgSearchIndex, String) = {
    val vectorTable = tableName(label)
    val config = url match {
      case JdbcPattern(host, port, database) =>
        SearchIndex.PgConfig(
          host = host,
          port = Option(port).map(_.toInt).getOrElse(5432),
          database = database,
          user = pgUser,
          password = pgPassword,
          vectorTableName = vectorTable,
          keywordTableName = tableName(s"${label}_kw")
        )
      case other => fail(s"Unrecognised PGVECTOR_TEST_URL: $other")
    }
    val index = PgSearchIndex(config).fold(e => fail(s"Failed to create PgSearchIndex: ${e.formatted}"), identity)
    openIndexes += index
    index.initializeSchema() shouldBe Right(())
    (index, vectorTable)
  }

  private def collection(index: PgSearchIndex, name: String, readers: Set[PrincipalId] = Set.empty): CollectionPath = {
    val path = CollectionPath.unsafe(s"consistency-$name-$suffix")
    createdPaths += path
    val config =
      if (readers.isEmpty) CollectionConfig.publicLeaf(path) else CollectionConfig.restrictedLeaf(path, readers)
    index.collections.ensureExists(config).fold(e => fail(e.formatted), _ => path)
  }

  private def rag(index: PgSearchIndex, configure: RAGConfig => RAGConfig = identity): RAG =
    RAG
      .buildWithClient(configure(RAGConfig.default.withSearchIndex(index)), embeddings)
      .fold(e => fail(s"RAG.build failed: ${e.formatted}"), identity)

  private def count(url: String, table: String, where: String): Long =
    Using.resource(connect(url)) { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        Using.resource(stmt.executeQuery(s"SELECT count(*) FROM $table WHERE $where")) { rs =>
          rs.next(); rs.getLong(1)
        }
      }
    }

  override def afterAll(): Unit = {
    openIndexes.headOption.foreach(index => createdPaths.foreach(path => Try(index.collections.delete(path))))
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

  "Plain ingest on a RAG with a PgSearchIndex" should "be refused, writing no row without a collection" in {
    val url              = requirePg()
    val (index, vectors) = newIndex(url, "ingest")
    val pipeline         = rag(index)
    try {
      pipeline.ingestText("Plain text.", "doc-1").left.toOption.get shouldBe a[ConfigurationError]
      pipeline.ingestChunks("doc-1", DocumentChunk.fromTexts(Seq("Plain text."))).isLeft shouldBe true
      count(url, vectors, "collection_id IS NULL") shouldBe 0L
    } finally pipeline.close()
  }

  "RAG.deleteDocument on a RAG with a PgSearchIndex" should "remove the document under both chunk id schemes" in {
    val url              = requirePg()
    val (index, vectors) = newIndex(url, "delete")
    val a                = collection(index, "delete-a")
    val b                = collection(index, "delete-b")
    val pipeline         = rag(index)
    try {
      pipeline.ingestWithPermissions(a, "doc-1", "Quarterly results.").isRight shouldBe true
      pipeline.ingestWithPermissions(b, "doc-1", "Quarterly results.").isRight shouldBe true
      pipeline.ingestWithPermissions(b, "doc-2", "Other results.").isRight shouldBe true

      // A row the plain API wrote before plain ingest was refused: `<docId>-chunk-<n>`, no collection
      val store = PgVectorStore(url, pgUser, pgPassword, vectors).fold(e => fail(e.formatted), identity)
      try
        store.upsert(
          VectorRecord("doc-1-chunk-0", Array(0.1f, 0.2f, 0.3f), Some("Quarterly results."), Map("docId" -> "doc-1"))
        ) shouldBe Right(())
      finally store.close()
      count(url, vectors, "metadata->>'docId' = 'doc-1'") shouldBe 3L

      pipeline.deleteDocument("doc-1") shouldBe Right(())

      count(url, vectors, "metadata->>'docId' = 'doc-1'") shouldBe 0L
      count(url, vectors, "metadata->>'docId' = 'doc-2'") shouldBe 1L
      pipeline
        .queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "Quarterly results")
        .map(_.flatMap(_.metadata.get("docId")).distinct) shouldBe Right(Seq("doc-2"))
    } finally pipeline.close()
  }

  "PgSearchIndex.keywordQuery" should "match full text inside the permission filter" in {
    val url        = requirePg()
    val (index, _) = newIndex(url, "keyword")
    val open       = collection(index, "kw-open")
    val restricted = collection(index, "kw-restricted", Set(user1))
    val pipeline   = rag(index)
    try {
      pipeline.ingestWithPermissions(open, "open", "The cafeteria menu for Monday.").isRight shouldBe true
      pipeline.ingestWithPermissions(restricted, "secret", "Merger plans for Monday.").isRight shouldBe true

      def ids(auth: UserAuthorization, text: String): Seq[String] =
        index
          .keywordQuery(auth, CollectionPattern.All, text, 10, None)
          .fold(e => fail(e.formatted), _.flatMap(_.metadata.get("docId")))

      ids(user1Auth, "merger").toSet shouldBe Set("secret")
      ids(outsiderAuth, "merger") shouldBe empty
      ids(outsiderAuth, "monday").toSet shouldBe Set("open")
      ids(UserAuthorization.Admin, "monday").toSet shouldBe Set("open", "secret")
      ids(UserAuthorization.Admin, "   ") shouldBe empty
    } finally pipeline.close()
  }

  it should "be served by the full-text index initializeSchema creates" in {
    val url          = requirePg()
    val (_, vectors) = newIndex(url, "ftsidx")
    val indexDefinitions =
      Using.resource(connect(url)) { conn =>
        Using.resource(conn.prepareStatement("SELECT indexdef FROM pg_indexes WHERE tablename = ?")) { stmt =>
          stmt.setString(1, vectors)
          Using.resource(stmt.executeQuery())(rs =>
            Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
          )
        }
      }
    indexDefinitions.exists(d => d.contains("gin") && d.contains("to_tsvector")) shouldBe true
  }

  "RAG.queryWithPermissions on a PgSearchIndex" should "fuse keyword and vector results under RRF" in {
    val url        = requirePg()
    val (index, _) = newIndex(url, "fusion")
    val open       = collection(index, "fusion-open")
    val pipeline   = rag(index)
    try {
      pipeline.ingestWithPermissions(open, "apple", "An apple a day.").isRight shouldBe true
      pipeline.ingestWithPermissions(open, "banana", "A ripe banana.").isRight shouldBe true

      // Every chunk embeds the same, so only the keyword channel tells them apart
      val results = pipeline
        .queryWithPermissions(outsiderAuth, CollectionPattern.All, "banana")
        .fold(e => fail(e.formatted), identity)
      results.head.metadata.get("docId") shouldBe Some("banana")
      results.head.keywordScore shouldBe defined
      results.head.vectorScore shouldBe defined
    } finally pipeline.close()
  }

  it should "search keywords only under keywordOnly, still filtered by permission" in {
    val url        = requirePg()
    val (index, _) = newIndex(url, "kwonly")
    val restricted = collection(index, "kwonly-restricted", Set(user1))
    val pipeline   = rag(index, _.keywordOnly)
    try {
      pipeline.ingestWithPermissions(restricted, "secret", "Merger plans.").isRight shouldBe true
      pipeline.queryWithPermissions(outsiderAuth, CollectionPattern.All, "merger") shouldBe Right(Seq.empty)
      pipeline
        .queryWithPermissions(user1Auth, CollectionPattern.All, "merger")
        .map(_.flatMap(_.metadata.get("docId"))) shouldBe Right(Seq("secret"))
    } finally pipeline.close()
  }

  "Plain query and clear on a RAG with a PgSearchIndex" should "be refused, returning and deleting nothing" in {
    val url              = requirePg()
    val (index, vectors) = newIndex(url, "plainquery")
    val restricted       = collection(index, "plainquery-restricted", Set(user1))
    val pipeline         = rag(index)
    try {
      pipeline.ingestWithPermissions(restricted, "secret", "Merger plans.").isRight shouldBe true

      // The plain search would read this table with no permission filter
      pipeline.query("merger").left.toOption.get shouldBe a[ConfigurationError]
      pipeline.clear().left.toOption.get shouldBe a[ConfigurationError]
      count(url, vectors, "metadata->>'docId' = 'secret'") shouldBe 1L

      pipeline.queryWithPermissions(outsiderAuth, CollectionPattern.All, "merger") shouldBe Right(Seq.empty)
    } finally pipeline.close()
  }

  "ingestChunksWithPermissions on a PgSearchIndex" should "return the ids a query reports and keep each chunk's metadata" in {
    val url        = requirePg()
    val (index, _) = newIndex(url, "prechunked")
    val open       = collection(index, "prechunked-open")
    val pipeline   = rag(index)
    try {
      val chunks = Seq(
        DocumentChunk("Widgets are assembled first.", 0, ChunkMetadata(headings = Seq("Guide", "Widgets"))),
        DocumentChunk("val w = Widget()", 1, ChunkMetadata.empty.asCodeBlock(Some("scala")))
      )
      val indexed =
        pipeline.ingestChunksWithPermissions(open, "doc-1", chunks).fold(e => fail(e.formatted), identity)
      indexed.map(_.index) shouldBe Seq(0, 1)
      indexed.map(_.id).zipWithIndex.foreach { case (id, i) => id should endWith(s"-doc-1-chunk-$i") }

      val results = pipeline
        .queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "widgets")
        .fold(e => fail(e.formatted), identity)
      results.map(_.id).toSet shouldBe indexed.map(_.id).toSet
      indexed.zip(chunks).foreach { case (stored, chunk) =>
        ChunkMetadata.fromRecordMetadata(results.find(_.id == stored.id).get.metadata) shouldBe chunk.metadata
      }
    } finally pipeline.close()
  }
}
