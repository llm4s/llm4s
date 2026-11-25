package org.llm4s.rag

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.chunking.{ ChunkMetadata, DocumentChunk }
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.permissions._
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ KeywordSearchResult, MetadataFilter, ScoredRecord, VectorRecord }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.rag.loader.{ DocumentLoader, LoadResult, TextLoader }
import org.llm4s.rag.transform.QueryTransformer

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Tests for RAG permission-aware operations using a mock SearchIndex.
 */
class RAGPermissionsSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  // ==========================================================================
  // Mock Implementations
  // ==========================================================================

  class MockEmbeddingProvider(dimensions: Int = 3) extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val embeddings = request.input.map { text =>
        val hash = text.hashCode.abs
        (0 until dimensions).map(i => ((hash + i) % 100) / 100.0).toSeq
      }
      Right(EmbeddingResponse(embeddings = embeddings))
    }
  }

  class MockLLMClient extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(
        Completion(
          id = "mock",
          created = Instant.EPOCH,
          model = ModelName("mock"),
          message = AssistantMessage("Mock answer."),
          usage = Some(TokenUsage(10, 5, 15))
        )
      )
    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def contextWindow: Int     = 4096
    override def reserveCompletion: Int = 1024
  }

  /**
   * Simple in-memory SearchIndex for testing permission-aware RAG operations.
   */
  class MockSearchIndex(keywordSupported: Boolean = true) extends SearchIndex {
    import scala.collection.mutable

    private val principalMap  = mutable.Map[ExternalPrincipal, PrincipalId]()
    private var nextUserId    = 1
    private var nextGroupId   = -1
    private val collectionMap = mutable.Map[CollectionPath, Collection]()
    private var nextCollId    = 1
    private val chunks = mutable.ListBuffer[(Int, String, ChunkWithEmbedding, Map[String, String], Set[PrincipalId])]()

    val principals: PrincipalStore = new PrincipalStore {
      def getOrCreate(external: ExternalPrincipal): Result[PrincipalId] =
        Right(
          principalMap.getOrElseUpdate(
            external,
            external match {
              case ExternalPrincipal.User(_) =>
                val id = PrincipalId.user(nextUserId); nextUserId += 1; id
              case ExternalPrincipal.Group(_) =>
                val id = PrincipalId.group(-nextGroupId); nextGroupId -= 1; id
            }
          )
        )

      def getOrCreateBatch(externals: Seq[ExternalPrincipal]): Result[Map[ExternalPrincipal, PrincipalId]] =
        Right(externals.flatMap(e => getOrCreate(e).toOption.map(e -> _)).toMap)

      def lookup(external: ExternalPrincipal): Result[Option[PrincipalId]] =
        Right(principalMap.get(external))

      def lookupBatch(externals: Seq[ExternalPrincipal]): Result[Map[ExternalPrincipal, PrincipalId]] =
        Right(externals.flatMap(e => principalMap.get(e).map(e -> _)).toMap)

      def getExternalId(id: PrincipalId): Result[Option[ExternalPrincipal]] =
        Right(principalMap.collectFirst { case (ext, pid) if pid == id => ext })

      def delete(external: ExternalPrincipal): Result[Unit] = {
        principalMap.remove(external); Right(())
      }

      def list(principalType: String, limit: Int, offset: Int): Result[Seq[ExternalPrincipal]] =
        Right(
          principalMap.keys.toSeq
            .filter {
              case ExternalPrincipal.User(_)  => principalType == "user"
              case ExternalPrincipal.Group(_) => principalType == "group"
            }
            .drop(offset)
            .take(limit)
        )

      def count(principalType: String): Result[Long] =
        Right(principalMap.keys.count {
          case ExternalPrincipal.User(_)  => principalType == "user"
          case ExternalPrincipal.Group(_) => principalType == "group"
        }.toLong)
    }

    val collections: CollectionStore = new CollectionStore {
      def create(config: CollectionConfig): Result[Collection] = {
        val id   = nextCollId; nextCollId += 1
        val coll = Collection(id, config.path, config.parentPath, config.queryableBy, config.isLeaf, config.metadata)
        collectionMap(config.path) = coll
        Right(coll)
      }

      def get(path: CollectionPath): Result[Option[Collection]] =
        Right(collectionMap.get(path))

      def getById(id: Int): Result[Option[Collection]] =
        Right(collectionMap.values.find(_.id == id))

      def list(pattern: CollectionPattern): Result[Seq[Collection]] =
        Right(collectionMap.values.filter(c => pattern.matches(c.path)).toSeq)

      def findAccessible(auth: UserAuthorization, pattern: CollectionPattern): Result[Seq[Collection]] =
        Right(collectionMap.values.filter(c => pattern.matches(c.path) && c.canQuery(auth)).toSeq)

      def updatePermissions(path: CollectionPath, queryableBy: Set[PrincipalId]): Result[Collection] =
        collectionMap.get(path) match {
          case Some(c) =>
            val updated = c.copy(queryableBy = queryableBy)
            collectionMap(path) = updated
            Right(updated)
          case None => Left(ConfigurationError(s"Collection not found: ${path.value}"))
        }

      def updateMetadata(path: CollectionPath, metadata: Map[String, String]): Result[Collection] =
        collectionMap.get(path) match {
          case Some(c) =>
            val updated = c.copy(metadata = metadata)
            collectionMap(path) = updated
            Right(updated)
          case None => Left(ConfigurationError(s"Collection not found: ${path.value}"))
        }

      def delete(path: CollectionPath): Result[Unit] = {
        collectionMap.remove(path); Right(())
      }

      def getEffectivePermissions(path: CollectionPath): Result[Set[PrincipalId]] =
        Right(collectionMap.get(path).map(_.queryableBy).getOrElse(Set.empty))

      def canQuery(path: CollectionPath, auth: UserAuthorization): Result[Boolean] =
        Right(collectionMap.get(path).exists(_.canQuery(auth)))

      def listChildren(parentPath: CollectionPath): Result[Seq[Collection]] =
        Right(collectionMap.values.filter(_.parentPath.contains(parentPath)).toSeq)

      def countDocuments(path: CollectionPath): Result[Long] =
        Right(0L)

      def countChunks(path: CollectionPath): Result[Long] =
        Right(0L)

      def stats(path: CollectionPath): Result[CollectionStats] =
        Right(CollectionStats(0, 0, 0))

      def ensureExists(config: CollectionConfig): Result[Collection] =
        get(config.path).flatMap {
          case Some(c) => Right(c)
          case None    => create(config)
        }
    }

    private def accessibleChunks(auth: UserAuthorization, collectionPattern: CollectionPattern) = {
      val accessibleCollIds = collectionMap.values
        .filter(c => collectionPattern.matches(c.path) && c.canQuery(auth))
        .map(_.id)
        .toSet

      chunks.filter { case (collId, _, _, _, readableBy) =>
        accessibleCollIds.contains(collId) &&
        (readableBy.isEmpty || auth.isAdmin || auth.principalIds.exists(readableBy.contains))
      }.toSeq
    }

    // The id scheme PgSearchIndex uses
    private def chunkId(collId: Int, docId: String, chunk: ChunkWithEmbedding): String =
      s"coll-$collId-$docId-chunk-${chunk.chunkIndex}"

    var keywordQueries: Int = 0

    def query(
      auth: UserAuthorization,
      collectionPattern: CollectionPattern,
      queryVector: Array[Float],
      topK: Int,
      additionalFilter: Option[MetadataFilter]
    ): Result[Seq[ScoredRecord]] =
      Right(
        accessibleChunks(auth, collectionPattern)
          .take(topK)
          .zipWithIndex
          .map { case ((collId, docId, chunk, metadata, _), idx) =>
            ScoredRecord(
              VectorRecord(
                id = chunkId(collId, docId, chunk),
                embedding = chunk.embedding,
                content = Some(chunk.content),
                metadata = metadata ++ chunk.metadata + ("docId" -> docId)
              ),
              1.0 - (idx * 0.1)
            )
          }
      )

    /** Scores a chunk by how many of the query's words it contains; one with none is not a match. */
    override def keywordQuery(
      auth: UserAuthorization,
      collectionPattern: CollectionPattern,
      queryText: String,
      topK: Int,
      additionalFilter: Option[MetadataFilter]
    ): Result[Seq[KeywordSearchResult]] = {
      keywordQueries += 1
      if (!keywordSupported) super.keywordQuery(auth, collectionPattern, queryText, topK, additionalFilter)
      else {
        val words = queryText.toLowerCase.split("\\W+").filter(_.nonEmpty).toSet
        Right(
          accessibleChunks(auth, collectionPattern)
            .map { case (collId, docId, chunk, metadata, _) =>
              val hits = words.count(chunk.content.toLowerCase.contains)
              KeywordSearchResult(
                chunkId(collId, docId, chunk),
                chunk.content,
                hits.toDouble,
                metadata ++ chunk.metadata + ("docId" -> docId)
              )
            }
            .filter(_.score > 0)
            .sortBy(-_.score)
            .take(topK)
        )
      }
    }

    def ingest(
      collectionPath: CollectionPath,
      documentId: String,
      chunkSeq: Seq[ChunkWithEmbedding],
      metadata: Map[String, String],
      readableBy: Set[PrincipalId]
    ): Result[Seq[String]] =
      collectionMap.get(collectionPath) match {
        case None => Left(ConfigurationError(s"Collection not found: ${collectionPath.value}"))
        case Some(coll) if !coll.isLeaf =>
          Left(ConfigurationError(s"Collection ${collectionPath.value} is not a leaf"))
        case Some(coll) =>
          chunkSeq.foreach(chunk => chunks += ((coll.id, documentId, chunk, metadata, readableBy)))
          Right(chunkSeq.map(chunk => chunkId(coll.id, documentId, chunk)))
      }

    def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long] = {
      val before = chunks.size
      val toRemove = chunks.zipWithIndex.collect {
        case ((collId, docId, _, _, _), idx)
            if collectionMap.get(collectionPath).exists(_.id == collId) && docId == documentId =>
          idx
      }.reverse
      toRemove.foreach(chunks.remove)
      Right((before - chunks.size).toLong)
    }

    def clearCollection(collectionPath: CollectionPath): Result[Long] = {
      val before = chunks.size
      val collId = collectionMap.get(collectionPath).map(_.id)
      val toRemove = chunks.zipWithIndex.collect {
        case ((cId, _, _, _, _), idx) if collId.contains(cId) => idx
      }.reverse
      toRemove.foreach(chunks.remove)
      Right((before - chunks.size).toLong)
    }

    def initializeSchema(): Result[Unit] = Right(())
    def dropSchema(): Result[Unit]       = Right(())
    def close(): Unit                    = ()
  }

  private def createRAGWithPermissions(): (RAG, MockSearchIndex) = {
    val searchIndex     = new MockSearchIndex()
    val embeddingClient = EmbeddingClient(new MockEmbeddingProvider())
    val config          = RAGConfig.default.withSearchIndex(searchIndex)
    val rag             = RAG.buildWithClient(config, embeddingClient).toOption.get
    (rag, searchIndex)
  }

  // ==========================================================================
  // Permission-Aware Query Tests
  // ==========================================================================

  "RAG.queryWithPermissions" should "return error without SearchIndex configured" in {
    val embeddingClient = EmbeddingClient(new MockEmbeddingProvider())
    val rag             = RAG.buildWithClient(RAGConfig.default, embeddingClient).toOption.get
    try {
      val result = rag.queryWithPermissions(
        UserAuthorization.Admin,
        CollectionPattern.All,
        "test query"
      )
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ConfigurationError]
    } finally rag.close()
  }

  it should "query with permissions when SearchIndex is configured" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      // Setup: create collection and ingest
      val path = CollectionPath.create("docs").toOption.get
      index.collections.create(CollectionConfig.publicLeaf(path))

      val chunk = ChunkWithEmbedding("Test content about Scala.", Array(0.1f, 0.2f, 0.3f), 0, Map.empty)
      index.ingest(path, "doc-1", Seq(chunk), Map("topic" -> "scala"))

      val result = rag.queryWithPermissions(
        UserAuthorization.Admin,
        CollectionPattern.All,
        "Scala"
      )
      result.isRight shouldBe true
      result.toOption.get should not be empty
    } finally rag.close()
  }

  it should "filter results based on user authorization" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val user1 = PrincipalId.user(1)
      val user2 = PrincipalId.user(2)

      val path = CollectionPath.create("restricted").toOption.get
      index.collections.create(CollectionConfig.restrictedLeaf(path, Set(user1)))

      val chunk = ChunkWithEmbedding("Secret content.", Array(0.1f, 0.2f, 0.3f), 0, Map.empty)
      index.ingest(path, "doc-1", Seq(chunk), Map.empty)

      // user1 can see it
      val result1 = rag.queryWithPermissions(
        UserAuthorization.forUser(user1, Set.empty),
        CollectionPattern.All,
        "secret"
      )
      result1.isRight shouldBe true
      result1.toOption.get should not be empty

      // user2 cannot see it
      val result2 = rag.queryWithPermissions(
        UserAuthorization.forUser(user2, Set.empty),
        CollectionPattern.All,
        "secret"
      )
      result2.isRight shouldBe true
      result2.toOption.get shouldBe empty
    } finally rag.close()
  }

  // ==========================================================================
  // Permission-Aware Ingestion Tests
  // ==========================================================================

  "RAG.ingestWithPermissions" should "return error without SearchIndex" in {
    val embeddingClient = EmbeddingClient(new MockEmbeddingProvider())
    val rag             = RAG.buildWithClient(RAGConfig.default, embeddingClient).toOption.get
    try {
      val path   = CollectionPath.create("docs").toOption.get
      val result = rag.ingestWithPermissions(path, "doc-1", "Content.")
      result.isLeft shouldBe true
    } finally rag.close()
  }

  it should "ingest into collection with permissions" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val path = CollectionPath.create("docs").toOption.get
      index.collections.create(CollectionConfig.publicLeaf(path))

      val result = rag.ingestWithPermissions(path, "doc-1", "Some document content.")
      result.isRight shouldBe true
      result.toOption.get should be > 0
    } finally rag.close()
  }

  it should "fail for non-existent collection" in {
    val (rag, _) = createRAGWithPermissions()
    try {
      val path   = CollectionPath.create("nonexistent").toOption.get
      val result = rag.ingestWithPermissions(path, "doc-1", "Content.")
      result.isLeft shouldBe true
    } finally rag.close()
  }

  "RAG.ingestChunksWithPermissions" should "index the given chunks, return the index's ids and keep each chunk's metadata" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val path = CollectionPath.create("docs").toOption.get
      index.collections.create(CollectionConfig.publicLeaf(path))
      val chunks = Seq(
        DocumentChunk("Intro to widgets.", 0, ChunkMetadata(headings = Seq("Guide", "Intro"))),
        DocumentChunk("val w = Widget()", 1, ChunkMetadata.empty.asCodeBlock(Some("scala")).withOffsets(17, 33))
      )

      val indexed = rag.ingestChunksWithPermissions(path, "doc-1", chunks, Map("source" -> "wiki")).toOption.get

      indexed shouldBe Seq(
        IndexedChunk("coll-1-doc-1-chunk-0", 0, "Intro to widgets."),
        IndexedChunk("coll-1-doc-1-chunk-1", 1, "val w = Widget()")
      )
      val results = rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "widgets").toOption.get
      results.map(_.id).toSet shouldBe indexed.map(_.id).toSet
      val code = results.find(_.id == "coll-1-doc-1-chunk-1").get
      code.metadata("source") shouldBe "wiki"
      ChunkMetadata.fromRecordMetadata(code.metadata) shouldBe chunks(1).metadata
      ChunkMetadata.fromRecordMetadata(results.find(_.id == "coll-1-doc-1-chunk-0").get.metadata) shouldBe
        chunks.head.metadata
    } finally rag.close()
  }

  it should "reject chunks whose indices do not run 0, 1, 2, ... before writing anything" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val path = CollectionPath.create("docs").toOption.get
      index.collections.create(CollectionConfig.publicLeaf(path))
      val result = rag.ingestChunksWithPermissions(path, "doc-1", Seq(DocumentChunk("a", 0), DocumentChunk("b", 2)))
      result.left.toOption.get shouldBe a[ValidationError]
      result.left.toOption.get.message should include("position 1 has index 2")
      rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "a b").toOption.get shouldBe empty
    } finally rag.close()
  }

  it should "return error without SearchIndex" in {
    val rag = RAG.buildWithClient(RAGConfig.default, EmbeddingClient(new MockEmbeddingProvider())).toOption.get
    try {
      val path = CollectionPath.create("docs").toOption.get
      rag.ingestChunksWithPermissions(path, "doc-1", DocumentChunk.fromTexts(Seq("a"))).left.toOption.get shouldBe
        a[ConfigurationError]
    } finally rag.close()
  }

  it should "index no chunks without calling the embedding model" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val path = CollectionPath.create("docs").toOption.get
      index.collections.create(CollectionConfig.publicLeaf(path))
      rag.ingestChunksWithPermissions(path, "doc-1", Seq.empty) shouldBe Right(Seq.empty)
    } finally rag.close()
  }

  it should "fail when the SearchIndex returns a different number of ids than chunks" in {
    val index = new MockSearchIndex() {
      override def ingest(
        collectionPath: CollectionPath,
        documentId: String,
        chunkSeq: Seq[ChunkWithEmbedding],
        metadata: Map[String, String],
        readableBy: Set[PrincipalId]
      ): Result[Seq[String]] = Right(Seq("only-one"))
    }
    val rag =
      RAG
        .buildWithClient(RAGConfig.default.withSearchIndex(index), EmbeddingClient(new MockEmbeddingProvider()))
        .toOption
        .get
    try {
      val path   = CollectionPath.create("docs").toOption.get
      val result = rag.ingestChunksWithPermissions(path, "doc-1", DocumentChunk.fromTexts(Seq("a", "b")))
      result.left.toOption.get.message should include("returned 1 chunk ids for the 2 chunks of doc-1")
    } finally rag.close()
  }

  "RAG.ingestWithPermissions" should "index the chunks RAG.chunk returns" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val path = CollectionPath.create("docs").toOption.get
      index.collections.create(CollectionConfig.publicLeaf(path))
      val text  = "First sentence here. " * 200
      val count = rag.ingestWithPermissions(path, "doc-1", text).toOption.get
      count shouldBe rag.chunk(text).size
      count should be > 1
    } finally rag.close()
  }

  "RAG.ingestBytesWithPermissions" should "extract the bytes and ingest the text into the collection" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val path = CollectionPath.create("docs").toOption.get
      index.collections.create(CollectionConfig.publicLeaf(path))
      val count = rag
        .ingestBytesWithPermissions(
          path,
          "Bytes about gadgets.".getBytes("UTF-8"),
          "notes.txt",
          "doc-b",
          Map("k" -> "v")
        )
        .toOption
        .get
      count shouldBe 1
      val result =
        rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "gadgets").toOption.get.head
      result.content should include("gadgets")
      result.metadata("k") shouldBe "v"
      (result.metadata should contain).key("format")
    } finally rag.close()
  }

  it should "return error without SearchIndex" in {
    val rag = RAG.buildWithClient(RAGConfig.default, EmbeddingClient(new MockEmbeddingProvider())).toOption.get
    try {
      val path = CollectionPath.create("docs").toOption.get
      rag.ingestBytesWithPermissions(path, "x".getBytes("UTF-8"), "a.txt", "doc-1").left.toOption.get shouldBe
        a[ConfigurationError]
    } finally rag.close()
  }

  // ==========================================================================
  // Permission-Aware Deletion Tests
  // ==========================================================================

  "RAG.deleteFromCollection" should "return error without SearchIndex" in {
    val embeddingClient = EmbeddingClient(new MockEmbeddingProvider())
    val rag             = RAG.buildWithClient(RAGConfig.default, embeddingClient).toOption.get
    try {
      val path   = CollectionPath.create("docs").toOption.get
      val result = rag.deleteFromCollection(path, "doc-1")
      result.isLeft shouldBe true
    } finally rag.close()
  }

  it should "delete document from collection" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val path = CollectionPath.create("docs").toOption.get
      index.collections.create(CollectionConfig.publicLeaf(path))

      val chunk = ChunkWithEmbedding("Content.", Array(0.1f, 0.2f, 0.3f), 0, Map.empty)
      index.ingest(path, "doc-1", Seq(chunk), Map.empty)

      val result = rag.deleteFromCollection(path, "doc-1")
      result.isRight shouldBe true
    } finally rag.close()
  }

  // ==========================================================================
  // Permission-Aware Answer Generation Tests
  // ==========================================================================

  "RAG.queryWithPermissionsAndAnswer" should "return error without LLM client" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val path = CollectionPath.create("docs").toOption.get
      index.collections.create(CollectionConfig.publicLeaf(path))

      val result = rag.queryWithPermissionsAndAnswer(
        UserAuthorization.Admin,
        CollectionPattern.All,
        "test question"
      )
      result.isLeft shouldBe true
    } finally rag.close()
  }

  it should "return error without SearchIndex" in {
    val mockLLM         = new MockLLMClient()
    val embeddingClient = EmbeddingClient(new MockEmbeddingProvider())
    val config          = RAGConfig.default.copy(llmClient = Some(mockLLM))
    val rag             = RAG.buildWithClient(config, embeddingClient).toOption.get
    try {
      val result = rag.queryWithPermissionsAndAnswer(
        UserAuthorization.Admin,
        CollectionPattern.All,
        "test"
      )
      result.isLeft shouldBe true
    } finally rag.close()
  }

  // ==========================================================================
  // The plain API on a RAG with a SearchIndex (#2716)
  // ==========================================================================

  private def isPlainIngestRefusal(result: Result[?]): Boolean =
    result match {
      case Left(e: ConfigurationError) => e.message.contains("ingestWithPermissions")
      case _                           => false
    }

  /** A loader that records whether it was read. */
  private class TrackingLoader extends DocumentLoader {
    var loaded                                = false
    override def load(): Iterator[LoadResult] = { loaded = true; TextLoader("Some text.", "doc-1").load() }
    override def description: String          = "tracking"
  }

  "Plain ingest on a RAG with a SearchIndex" should "be refused, writing nothing, rather than ingest into no collection" in {
    val (rag, _) = createRAGWithPermissions()
    try {
      isPlainIngestRefusal(rag.ingestText("Some text.", "doc-1")) shouldBe true
      isPlainIngestRefusal(rag.ingestChunks("doc-1", DocumentChunk.fromTexts(Seq("Some text.")))) shouldBe true
      isPlainIngestRefusal(rag.ingestBytes("Some text.".getBytes, "a.txt", "doc-1")) shouldBe true
      isPlainIngestRefusal(
        rag.ingestBytesMultiple(Iterator(("Some text.".getBytes, "a.txt", "doc-1", Map.empty[String, String])))
      ) shouldBe true
      isPlainIngestRefusal(rag.ingest("does-not-matter.txt")) shouldBe true

      rag.chunkCount shouldBe 0
      rag.stats.map(_.vectorCount) shouldBe Right(0L)
    } finally rag.close()
  }

  it should "refuse loader ingest, sync and refresh before reading the loader" in {
    val (rag, _) = createRAGWithPermissions()
    try {
      val loaders = Seq.fill(7)(new TrackingLoader)
      isPlainIngestRefusal(rag.ingest(loaders(0))) shouldBe true
      isPlainIngestRefusal(rag.sync(loaders(1))) shouldBe true
      isPlainIngestRefusal(rag.sync(loaders(5), "source")) shouldBe true
      isPlainIngestRefusal(rag.refresh(loaders(2))) shouldBe true

      import scala.concurrent.ExecutionContext.Implicits.global
      isPlainIngestRefusal(Await.result(rag.ingestAsync(loaders(3)), 10.seconds)) shouldBe true
      isPlainIngestRefusal(Await.result(rag.syncAsync(loaders(4)), 10.seconds)) shouldBe true
      isPlainIngestRefusal(Await.result(rag.syncAsync(loaders(6), "source"), 10.seconds)) shouldBe true

      loaders.map(_.loaded) shouldBe Seq.fill(7)(false)
    } finally rag.close()
  }

  it should "fail the build when the config also names document loaders" in {
    val embeddingClient = EmbeddingClient(new MockEmbeddingProvider())
    val config = RAGConfig.default.withSearchIndex(new MockSearchIndex()).withDocuments(TextLoader("Text.", "d"))
    isPlainIngestRefusal(RAG.buildWithClient(config, embeddingClient)) shouldBe true
  }

  "RAG.deleteDocument with a SearchIndex" should "delete the document from every collection it was ingested into" in {
    val (rag, index) = createRAGWithPermissions()
    try {
      val a = CollectionPath.unsafe("a")
      val b = CollectionPath.unsafe("b")
      index.collections.create(CollectionConfig.publicLeaf(a))
      index.collections.create(CollectionConfig.publicLeaf(b))
      rag.ingestWithPermissions(a, "doc-1", "Scala is a language.").isRight shouldBe true
      rag.ingestWithPermissions(b, "doc-1", "Scala is a language.").isRight shouldBe true
      rag.ingestWithPermissions(b, "doc-2", "Scala is a language too.").isRight shouldBe true

      rag.deleteDocument("doc-1") shouldBe Right(())

      val remaining = rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "Scala")
      remaining.map(_.map(_.metadata.get("docId")).distinct) shouldBe Right(Seq(Some("doc-2")))
    } finally rag.close()
  }

  it should "return the index's error rather than report a delete that did not happen" in {
    val failing = new MockSearchIndex() {
      override def deleteDocumentFromAllCollections(documentId: String): Result[Long] =
        Left(ConfigurationError("index unavailable"))
    }
    val rag = RAG
      .buildWithClient(RAGConfig.default.withSearchIndex(failing), EmbeddingClient(new MockEmbeddingProvider()))
      .toOption
      .get
    try rag.deleteDocument("doc-1").left.map(_.message) shouldBe Left("index unavailable")
    finally rag.close()
  }

  "SearchIndex.deleteDocumentFromAllCollections" should "by default delete from each leaf collection in turn" in {
    val index = new MockSearchIndex()
    val a     = CollectionPath.unsafe("a")
    val b     = CollectionPath.unsafe("b")
    index.collections.create(CollectionConfig.publicLeaf(a))
    index.collections.create(CollectionConfig.publicLeaf(b))
    val chunk = ChunkWithEmbedding("x", Array(0.1f), 0)
    index.ingest(a, "doc-1", Seq(chunk))
    index.ingest(b, "doc-1", Seq(chunk, chunk.copy(chunkIndex = 1)))
    index.ingest(b, "doc-2", Seq(chunk))

    index.deleteDocumentFromAllCollections("doc-1") shouldBe Right(3L)
    index.query(UserAuthorization.Admin, CollectionPattern.All, Array(0.1f)).map(_.size) shouldBe Right(1)
  }

  // ==========================================================================
  // Fusion, keyword search and reranking in permission queries (#2716)
  // ==========================================================================

  private def permissionRag(index: MockSearchIndex, config: RAGConfig => RAGConfig = identity): RAG =
    RAG
      .buildWithClient(
        config(RAGConfig.default.withSearchIndex(index)),
        EmbeddingClient(new MockEmbeddingProvider())
      )
      .toOption
      .get

  /** Two public documents: "apple" is the first vector result, "banana" the only keyword match for "banana". */
  private def twoDocuments(index: MockSearchIndex): Unit = {
    val path = CollectionPath.unsafe("fruit")
    index.collections.create(CollectionConfig.publicLeaf(path))
    index.ingest(path, "apple", Seq(ChunkWithEmbedding("An apple a day.", Array(0.1f, 0.2f, 0.3f), 0)))
    index.ingest(path, "banana", Seq(ChunkWithEmbedding("A ripe banana.", Array(0.3f, 0.2f, 0.1f), 0)))
  }

  "RAG.queryWithPermissions" should "fuse vector and keyword results under the default RRF strategy" in {
    val index = new MockSearchIndex()
    twoDocuments(index)
    val rag = permissionRag(index)
    try {
      val results = rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "banana").toOption.get
      index.keywordQueries shouldBe 1
      results.map(_.metadata.get("docId")) shouldBe Seq(Some("banana"), Some("apple"))
      results.head.keywordScore shouldBe defined
      results.head.vectorScore shouldBe defined
    } finally rag.close()
  }

  it should "use weighted fusion when configured" in {
    val index = new MockSearchIndex()
    twoDocuments(index)
    val rag = permissionRag(index, _.withWeightedScore(vectorWeight = 0.1, keywordWeight = 0.9))
    try {
      val results = rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "banana").toOption.get
      results.head.metadata.get("docId") shouldBe Some("banana")
      results.head.keywordScore shouldBe defined
    } finally rag.close()
  }

  it should "search vectors only, without keyword search, under vectorOnly" in {
    val index = new MockSearchIndex()
    twoDocuments(index)
    val rag = permissionRag(index, _.vectorOnly)
    try {
      val results = rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "banana").toOption.get
      index.keywordQueries shouldBe 0
      results.map(_.metadata.get("docId")) shouldBe Seq(Some("apple"), Some("banana"))
      results.forall(_.keywordScore.isEmpty) shouldBe true
    } finally rag.close()
  }

  it should "search keywords only under keywordOnly" in {
    val index = new MockSearchIndex()
    twoDocuments(index)
    val rag = permissionRag(index, _.keywordOnly)
    try {
      val results = rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "banana").toOption.get
      results.map(_.metadata.get("docId")) shouldBe Seq(Some("banana"))
      results.head.vectorScore shouldBe None
    } finally rag.close()
  }

  it should "keep the permission filter in its keyword search" in {
    val index = new MockSearchIndex()
    val user1 = PrincipalId.user(1)
    val path  = CollectionPath.unsafe("restricted")
    index.collections.create(CollectionConfig.restrictedLeaf(path, Set(user1)))
    index.ingest(path, "secret", Seq(ChunkWithEmbedding("A secret banana.", Array(0.1f, 0.2f, 0.3f), 0)))
    val rag = permissionRag(index, _.keywordOnly)
    try {
      val outsider = UserAuthorization.forUser(PrincipalId.user(2), Set.empty)
      rag.queryWithPermissions(outsider, CollectionPattern.All, "banana") shouldBe Right(Seq.empty)
      rag
        .queryWithPermissions(UserAuthorization.forUser(user1, Set.empty), CollectionPattern.All, "banana")
        .map(_.size) shouldBe Right(1)
    } finally rag.close()
  }

  it should "fail a hybrid query on an index without keyword search, not fall back to vector search" in {
    val index = new MockSearchIndex(keywordSupported = false)
    twoDocuments(index)
    val rag = permissionRag(index)
    try {
      val result = rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "banana")
      result.left.toOption.get shouldBe a[ConfigurationError]
      result.left.toOption.get.message should include("vectorOnly")
    } finally rag.close()
  }

  it should "query such an index under vectorOnly" in {
    val index = new MockSearchIndex(keywordSupported = false)
    twoDocuments(index)
    val rag = permissionRag(index, _.vectorOnly)
    try rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "banana").map(_.size) shouldBe Right(2)
    finally rag.close()
  }

  it should "rerank its candidates with the configured reranker" in {
    val index = new MockSearchIndex()
    twoDocuments(index)
    // The LLM reranker scores the candidates in order: apple (the first vector result) low, banana high
    val reranking = new MockLLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Right(
          Completion(
            id = "rerank",
            created = Instant.EPOCH,
            model = ModelName("mock"),
            message = AssistantMessage("[0.1, 0.9]"),
            usage = None
          )
        )
    }
    val rag = permissionRag(index, _.vectorOnly.withLLM(reranking).withLLMReranking)
    try {
      val results = rag.queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "fruit").toOption.get
      results.map(_.metadata.get("docId")) shouldBe Seq(Some("banana"), Some("apple"))
      results.head.score shouldBe 0.9
    } finally rag.close()
  }

  it should "apply the configured query transformers" in {
    val index = new MockSearchIndex()
    twoDocuments(index)
    val toBanana = new QueryTransformer {
      override def name: String                             = "to-banana"
      override def transform(query: String): Result[String] = Right("banana")
    }
    val rag = permissionRag(index, _.keywordOnly.withQueryTransformer(toBanana))
    try
      rag
        .queryWithPermissions(UserAuthorization.Admin, CollectionPattern.All, "cherry")
        .map(_.map(_.metadata.get("docId"))) shouldBe Right(Seq(Some("banana")))
    finally rag.close()
  }

  // ==========================================================================
  // Plain search and clear on a RAG with a SearchIndex (#2716 follow-up)
  // ==========================================================================

  private def isPlainQueryRefusal(result: Result[?], instead: String): Boolean =
    result match {
      case Left(e: ConfigurationError) => e.message.contains("no permission filter") && e.message.contains(instead)
      case _                           => false
    }

  /** A RAG with a SearchIndex and an LLM, holding one restricted document. */
  private def restrictedRag(llm: LLMClient = new MockLLMClient()): (RAG, MockSearchIndex) = {
    val index = new MockSearchIndex()
    val path  = CollectionPath.unsafe("restricted")
    index.collections.create(CollectionConfig.restrictedLeaf(path, Set(PrincipalId.user(1))))
    index.ingest(path, "secret", Seq(ChunkWithEmbedding("Merger plans.", Array(0.1f, 0.2f, 0.3f), 0)))
    val rag = RAG
      .buildWithClient(
        RAGConfig.default.withSearchIndex(index).withLLM(llm),
        EmbeddingClient(new MockEmbeddingProvider())
      )
      .toOption
      .get
    (rag, index)
  }

  "Plain search on a RAG with a SearchIndex" should "be refused by query, pointing to queryWithPermissions" in {
    val (rag, _) = restrictedRag()
    try isPlainQueryRefusal(rag.query("merger"), "queryWithPermissions") shouldBe true
    finally rag.close()
  }

  it should "be refused by queryWithAnswer before the model is called, pointing to queryWithPermissionsAndAnswer" in {
    val calls = new java.util.concurrent.atomic.AtomicInteger()
    val llm = new MockLLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        calls.incrementAndGet()
        super.complete(conversation, options)
      }
    }
    val (rag, _) = restrictedRag(llm)
    try {
      isPlainQueryRefusal(rag.queryWithAnswer("merger"), "queryWithPermissionsAndAnswer") shouldBe true
      calls.get shouldBe 0
    } finally rag.close()
  }

  it should "be refused by queryWithAnswerStreaming, calling neither callback" in {
    val (rag, _) = restrictedRag()
    try {
      var called = false
      val result = rag.queryWithAnswerStreaming("merger")(_ => called = true, _ => called = true)
      isPlainQueryRefusal(result, "queryWithPermissionsAndAnswerStreaming") shouldBe true
      called shouldBe false
    } finally rag.close()
  }

  it should "be refused by GraphRAG's Hybrid mode, whose vector half is a plain search" in {
    val index = new MockSearchIndex()
    val rag = RAG
      .buildWithClient(
        RAGConfig.default
          .withSearchIndex(index)
          .withLLM(new MockLLMClient())
          .withGraphStore(new org.llm4s.knowledgegraph.storage.InMemoryGraphStore()),
        EmbeddingClient(new MockEmbeddingProvider())
      )
      .toOption
      .get
    try
      isPlainQueryRefusal(
        rag.queryWithGraphRAGMode("merger", org.llm4s.knowledgegraph.graphrag.GraphRAGMode.Hybrid),
        "queryWithPermissionsAndAnswer"
      ) shouldBe true
    finally rag.close()
  }

  it should "leave the permission API working: the answer comes from the permitted document only" in {
    val (rag, _) = restrictedRag()
    try {
      val owner = UserAuthorization.forUser(PrincipalId.user(1), Set.empty)
      rag
        .queryWithPermissionsAndAnswer(owner, CollectionPattern.All, "merger")
        .map(_.contexts.map(_.content)) shouldBe Right(Seq("Merger plans."))
      val outsider = UserAuthorization.forUser(PrincipalId.user(2), Set.empty)
      rag.queryWithPermissionsAndAnswer(outsider, CollectionPattern.All, "merger").map(_.contexts) shouldBe
        Right(Seq.empty)
    } finally rag.close()
  }

  "RAG.clear on a RAG with a SearchIndex" should "be refused, deleting nothing" in {
    val (rag, index) = restrictedRag()
    try {
      rag.clear() match {
        case Left(e: ConfigurationError) => e.message should include("clearCollection")
        case other                       => fail(s"expected a ConfigurationError, got $other")
      }
      index.query(UserAuthorization.Admin, CollectionPattern.All, Array(0.1f, 0.2f, 0.3f)).map(_.size) shouldBe
        Right(1)
    } finally rag.close()
  }

  "A RAG without a SearchIndex" should "still query, answer and clear" in {
    val rag = RAG
      .buildWithClient(
        RAGConfig.default.withLLM(new MockLLMClient()),
        EmbeddingClient(new MockEmbeddingProvider())
      )
      .toOption
      .get
    try {
      rag.ingestText("Plain text about Scala.", "doc-1").isRight shouldBe true
      rag.query("Scala").map(_.nonEmpty) shouldBe Right(true)
      rag.queryWithAnswer("Scala").isRight shouldBe true
      rag.clear() shouldBe Right(())
      rag.stats.map(_.vectorCount) shouldBe Right(0L)
    } finally rag.close()
  }

  // ==========================================================================
  // hasPermissions and searchIndex
  // ==========================================================================

  "RAG.hasPermissions" should "return false without SearchIndex" in {
    val embeddingClient = EmbeddingClient(new MockEmbeddingProvider())
    val rag             = RAG.buildWithClient(RAGConfig.default, embeddingClient).toOption.get
    try {
      rag.hasPermissions shouldBe false
      rag.searchIndex shouldBe None
    } finally rag.close()
  }

  it should "return true with SearchIndex" in {
    val (rag, _) = createRAGWithPermissions()
    try {
      rag.hasPermissions shouldBe true
      rag.searchIndex shouldBe defined
    } finally rag.close()
  }
}
