package org.llm4s.rag

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.permissions._
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord, VectorRecord }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
          created = 0L,
          content = "Mock answer.",
          model = "mock",
          message = AssistantMessage("Mock answer."),
          usage = Some(TokenUsage(10, 5, 15))
        )
      )
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /**
   * Simple in-memory SearchIndex for testing permission-aware RAG operations.
   */
  class MockSearchIndex extends SearchIndex {
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

    def query(
      auth: UserAuthorization,
      collectionPattern: CollectionPattern,
      queryVector: Array[Float],
      topK: Int,
      additionalFilter: Option[MetadataFilter]
    ): Result[Seq[ScoredRecord]] = {
      val accessibleCollIds = collectionMap.values
        .filter(c => collectionPattern.matches(c.path) && c.canQuery(auth))
        .map(_.id)
        .toSet

      val results = chunks
        .filter { case (collId, _, _, _, readableBy) =>
          accessibleCollIds.contains(collId) &&
          (readableBy.isEmpty || auth.isAdmin || auth.principalIds.exists(readableBy.contains))
        }
        .take(topK)
        .zipWithIndex
        .map { case ((_, _, chunk, metadata, _), idx) =>
          ScoredRecord(
            VectorRecord(
              id = s"chunk-$idx",
              embedding = chunk.embedding,
              content = Some(chunk.content),
              metadata = metadata
            ),
            1.0 - (idx * 0.1)
          )
        }
        .toSeq

      Right(results)
    }

    def ingest(
      collectionPath: CollectionPath,
      documentId: String,
      chunkSeq: Seq[ChunkWithEmbedding],
      metadata: Map[String, String],
      readableBy: Set[PrincipalId]
    ): Result[Int] =
      collectionMap.get(collectionPath) match {
        case None => Left(ConfigurationError(s"Collection not found: ${collectionPath.value}"))
        case Some(coll) if !coll.isLeaf =>
          Left(ConfigurationError(s"Collection ${collectionPath.value} is not a leaf"))
        case Some(coll) =>
          chunkSeq.foreach(chunk => chunks += ((coll.id, documentId, chunk, metadata, readableBy)))
          Right(chunkSeq.size)
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
    val embeddingClient = new EmbeddingClient(new MockEmbeddingProvider())
    val config          = RAGConfig.default.withSearchIndex(searchIndex)
    val rag             = RAG.buildWithClient(config, embeddingClient).toOption.get
    (rag, searchIndex)
  }

  // ==========================================================================
  // Permission-Aware Query Tests
  // ==========================================================================

  "RAG.queryWithPermissions" should "return error without SearchIndex configured" in {
    val embeddingClient = new EmbeddingClient(new MockEmbeddingProvider())
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
    val embeddingClient = new EmbeddingClient(new MockEmbeddingProvider())
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

  // ==========================================================================
  // Permission-Aware Deletion Tests
  // ==========================================================================

  "RAG.deleteFromCollection" should "return error without SearchIndex" in {
    val embeddingClient = new EmbeddingClient(new MockEmbeddingProvider())
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
    val embeddingClient = new EmbeddingClient(new MockEmbeddingProvider())
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
  // hasPermissions and searchIndex
  // ==========================================================================

  "RAG.hasPermissions" should "return false without SearchIndex" in {
    val embeddingClient = new EmbeddingClient(new MockEmbeddingProvider())
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
