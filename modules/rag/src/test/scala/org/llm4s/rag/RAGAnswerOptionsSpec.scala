package org.llm4s.rag

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.permissions._
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord, VectorRecord }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable

/** The completion options an answer is generated with (#2715): the config's, or a call's own. */
class RAGAnswerOptionsSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private object Embeddings extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map(t => Seq(0.1, 0.2, (t.length % 10) / 10.0))))
  }

  /** Records the options of every call. */
  private class RecordingClient extends LLMClient {
    val options: mutable.Buffer[CompletionOptions] = mutable.Buffer.empty
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      this.options += options
      Right(
        Completion(
          id = "answer",
          created = Instant.EPOCH,
          content = "42",
          model = "mock",
          message = AssistantMessage("42"),
          usage = Some(TokenUsage(10, 2, 12))
        )
      )
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /** A search index that answers every query with one chunk; nothing else of it is used here. */
  private object OneChunkIndex extends SearchIndex {
    def principals: PrincipalStore   = throw new UnsupportedOperationException("not used")
    def collections: CollectionStore = throw new UnsupportedOperationException("not used")
    def query(
      auth: UserAuthorization,
      collectionPattern: CollectionPattern,
      queryVector: Array[Float],
      topK: Int,
      additionalFilter: Option[MetadataFilter]
    ): Result[Seq[ScoredRecord]] =
      Right(Seq(ScoredRecord(VectorRecord("c1", queryVector, Some("The answer is 42."), Map.empty), 1.0)))
    def ingest(
      collectionPath: CollectionPath,
      documentId: String,
      chunks: Seq[ChunkWithEmbedding],
      metadata: Map[String, String],
      readableBy: Set[PrincipalId]
    ): Result[Seq[String]] = Right(Seq.empty)
    def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long] = Right(0L)
    def clearCollection(collectionPath: CollectionPath): Result[Long]                    = Right(0L)
    def initializeSchema(): Result[Unit]                                                 = Right(())
    def dropSchema(): Result[Unit]                                                       = Right(())
    def close(): Unit                                                                    = ()
  }

  private def rag(client: LLMClient, config: RAGConfig = RAGConfig.default): RAG = {
    val built =
      RAG.buildWithClient(config.withLLM(client), EmbeddingClient(Embeddings)).fold(e => fail(e.message), identity)
    // A RAG with a SearchIndex refuses plain ingest (#2716): its one chunk comes from the index
    if (!built.hasPermissions)
      built.ingestText("The answer to everything is 42.", "doc1").fold(e => fail(e.message), identity)
    built
  }

  private val warm = CompletionOptions(temperature = 0.7, maxTokens = Some(256))

  "RAGConfig" should "generate answers at temperature 0.0 by default, as before" in {
    RAGConfig.default.completionOptions shouldBe CompletionOptions(temperature = 0.0)
    RAGConfig.DefaultCompletionOptions.temperature shouldBe 0.0
    RAGConfig.default.withCompletionOptions(warm).completionOptions shouldBe warm
  }

  "RAG.queryWithAnswer" should "send the config's completion options" in {
    val client = new RecordingClient
    val r      = rag(client)
    try {
      r.queryWithAnswer("What is the answer?").map(_.answer) shouldBe Right("42")
      client.options.toList shouldBe List(RAGConfig.DefaultCompletionOptions)
    } finally r.close()
  }

  it should "send the options RAGConfig.withCompletionOptions set" in {
    val client = new RecordingClient
    val r      = rag(client, RAGConfig.default.withCompletionOptions(warm))
    try {
      r.queryWithAnswer("What is the answer?").isRight shouldBe true
      client.options.toList shouldBe List(warm)
    } finally r.close()
  }

  it should "send a call's own options in place of the config's, for that call only" in {
    val client = new RecordingClient
    val r      = rag(client)
    try {
      r.queryWithAnswer("What is the answer?", options = warm).isRight shouldBe true
      r.queryWithAnswer("What is the answer?", Some(2)).isRight shouldBe true
      client.options.toList shouldBe List(warm, RAGConfig.DefaultCompletionOptions)
    } finally r.close()
  }

  "RAG.queryWithPermissionsAndAnswer" should "send the config's options, or a call's own" in {
    val client = new RecordingClient
    val r      = rag(client, RAGConfig.default.withSearchIndex(OneChunkIndex).vectorOnly.withCompletionOptions(warm))
    try {
      val answered = r.queryWithPermissionsAndAnswer(UserAuthorization.Admin, CollectionPattern.All, "What is it?")
      answered.map(_.contexts.map(_.content)) shouldBe Right(Seq("The answer is 42."))
      val cold = CompletionOptions(temperature = 0.0, maxTokens = Some(64))
      r.queryWithPermissionsAndAnswer(UserAuthorization.Admin, CollectionPattern.All, "What is it?", options = cold)
        .isRight shouldBe true
      client.options.toList shouldBe List(warm, cold)
    } finally r.close()
  }
}
