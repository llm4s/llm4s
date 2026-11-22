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

import java.time.Instant
import scala.collection.mutable

/** The streamed answer (#2717): the contexts first, then the chunks, then the whole answer. */
class RAGAnswerStreamingSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private object Embeddings extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map(t => Seq(0.1, 0.2, (t.length % 10) / 10.0))))
  }

  /** Streams "4" then "2", recording every event and the options of every call in `events`. */
  private class StreamingClient(events: mutable.Buffer[String]) extends LLMClient {
    val options: mutable.Buffer[CompletionOptions] = mutable.Buffer.empty
    private def completion(text: String) =
      Completion(
        id = "answer",
        created = Instant.EPOCH,
        content = text,
        model = "mock",
        message = AssistantMessage(text),
        usage = Some(TokenUsage(10, 2, 12))
      )
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      events += "complete"
      Right(completion("42"))
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      this.options += options
      events += "model"
      Seq("4", "2").foreach(t => onChunk(StreamedChunk(id = "answer", content = Some(t))))
      Right(completion("42"))
    }
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

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
      Right(Seq(ScoredRecord(VectorRecord("c1", queryVector, Some("Allowed: 42."), Map.empty), 1.0)))
    def ingest(
      collectionPath: CollectionPath,
      documentId: String,
      chunks: Seq[ChunkWithEmbedding],
      metadata: Map[String, String],
      readableBy: Set[PrincipalId]
    ): Result[Int] = Right(0)
    def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long] = Right(0L)
    def clearCollection(collectionPath: CollectionPath): Result[Long]                    = Right(0L)
    def initializeSchema(): Result[Unit]                                                 = Right(())
    def dropSchema(): Result[Unit]                                                       = Right(())
    def close(): Unit                                                                    = ()
  }

  private def rag(client: Option[LLMClient], config: RAGConfig = RAGConfig.default): RAG = {
    val withClient = client.fold(config)(config.withLLM)
    val built = RAG.buildWithClient(withClient, new EmbeddingClient(Embeddings)).fold(e => fail(e.message), identity)
    built.ingestText("The answer to everything is 42.", "doc1").fold(e => fail(e.message), identity)
    built
  }

  private def recorder(events: mutable.Buffer[String]) =
    (
      (contexts: Seq[RAGSearchResult]) => events += s"contexts:${contexts.map(_.content).mkString("|")}": Unit,
      (chunk: StreamedChunk) => events += s"chunk:${chunk.content.getOrElse("")}": Unit
    )

  "RAG.queryWithAnswerStreaming" should "give the contexts first, then each chunk, then return the whole answer" in {
    val events = mutable.Buffer.empty[String]
    val client = new StreamingClient(events)
    val r      = rag(Some(client))
    try {
      val (onContexts, onChunk) = recorder(events)
      val answer                = r.queryWithAnswerStreaming("What is the answer?")(onContexts, onChunk)
      answer.map(a => (a.answer, a.question, a.usage.map(_.totalTokens))) shouldBe
        Right(("42", "What is the answer?", Some(12)))
      answer.map(_.contexts.map(_.content)) shouldBe Right(Seq("The answer to everything is 42."))
      events.toList shouldBe List("contexts:The answer to everything is 42.", "model", "chunk:4", "chunk:2")
      client.options.toList shouldBe List(RAGConfig.DefaultCompletionOptions)
    } finally r.close()
  }

  it should "stream with the config's completion options, or a call's own" in {
    val events = mutable.Buffer.empty[String]
    val client = new StreamingClient(events)
    val warm   = CompletionOptions(temperature = 0.7)
    val r      = rag(Some(client), RAGConfig.default.withCompletionOptions(warm))
    try {
      val cold = CompletionOptions(temperature = 0.1, maxTokens = Some(64))
      r.queryWithAnswerStreaming("Q?")(_ => (), _ => ()).isRight shouldBe true
      r.queryWithAnswerStreaming("Q?", Some(1), cold)(_ => (), _ => ()).isRight shouldBe true
      client.options.toList shouldBe List(warm, cold)
    } finally r.close()
  }

  it should "call neither callback without an LLM client" in {
    val events = mutable.Buffer.empty[String]
    val r      = rag(None)
    try {
      val (onContexts, onChunk) = recorder(events)
      r.queryWithAnswerStreaming("Q?")(onContexts, onChunk).left.toOption.get shouldBe a[ConfigurationError]
      events shouldBe empty
    } finally r.close()
  }

  it should "return a Left, and not call the model, when onContexts throws" in {
    val events = mutable.Buffer.empty[String]
    val r      = rag(Some(new StreamingClient(events)))
    try {
      r.queryWithAnswerStreaming("Q?")(_ => throw new IllegalStateException("socket closed"), _ => ()).isLeft shouldBe
        true
      events shouldBe empty
    } finally r.close()
  }

  "RAG.queryWithPermissionsAndAnswerStreaming" should "give the permitted contexts first, then the chunks" in {
    val events = mutable.Buffer.empty[String]
    val client = new StreamingClient(events)
    val r      = rag(Some(client), RAGConfig.default.withSearchIndex(OneChunkIndex))
    try {
      val (onContexts, onChunk) = recorder(events)
      val cold                  = CompletionOptions(temperature = 0.2)
      r.queryWithPermissionsAndAnswerStreaming(UserAuthorization.Admin, CollectionPattern.All, "Q?", options = cold)(
        onContexts,
        onChunk
      ).map(_.answer) shouldBe Right("42")
      events.toList shouldBe List("contexts:Allowed: 42.", "model", "chunk:4", "chunk:2")
      client.options.toList shouldBe List(cold)
    } finally r.close()
  }

  it should "call neither callback without an LLM client" in {
    val events = mutable.Buffer.empty[String]
    val r      = rag(None, RAGConfig.default.withSearchIndex(OneChunkIndex))
    try {
      val (onContexts, onChunk) = recorder(events)
      r.queryWithPermissionsAndAnswerStreaming(UserAuthorization.Admin, CollectionPattern.All, "Q?")(
        onContexts,
        onChunk
      ).isLeft shouldBe true
      events shouldBe empty
    } finally r.close()
  }
}
