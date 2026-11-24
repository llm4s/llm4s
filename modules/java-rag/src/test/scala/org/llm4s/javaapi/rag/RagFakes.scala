package org.llm4s.javaapi.rag

import org.llm4s.error.LLMError
import org.llm4s.javaapi.{ JEmbeddingClient, JLlmClient, Llm4s }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.rag.permissions.*
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord }

import scala.collection.mutable.ArrayBuffer
import java.time.Instant

/** A deterministic embedding provider and chat client, so the pipeline runs end to end with no network. */
object RagFakes {

  /** The words a vector counts, one dimension each. */
  val Vocabulary: Vector[String] = Vector("cat", "mat", "dog", "bone", "scala", "jvm", "garden", "refunds", "orders")

  /** How often each word of the vocabulary occurs in `text`: texts that share words point the same way. */
  def vector(text: String): Seq[Double] = {
    val words = text.toLowerCase.split("[^a-z]+").toSeq
    Vocabulary.map(w => words.count(_ == w).toDouble)
  }

  /** Embeds by word counts, recording every request; `reply` overrides the answer. */
  final class WordEmbeddings(reply: Option[EmbeddingRequest => Result[EmbeddingResponse]] = None)
      extends EmbeddingProvider {
    val requests: ArrayBuffer[EmbeddingRequest] = ArrayBuffer.empty
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      requests += request
      reply.fold(Right(EmbeddingResponse(request.input.map(vector))))(_(request))
    }
  }

  val Model: EmbeddingModelConfig = EmbeddingModelConfig("word-count", Vocabulary.size)

  def embeddings(provider: EmbeddingProvider): JEmbeddingClient =
    Llm4s.wrapEmbeddingClient(EmbeddingClient(provider)(using ModelRegistryService.fromModels(Nil)), Model)

  def embeddings(): JEmbeddingClient = embeddings(new WordEmbeddings())

  def failingEmbeddings(error: LLMError): JEmbeddingClient =
    embeddings(new WordEmbeddings(Some(_ => Left(error))))

  def throwingEmbeddings(thrown: => Throwable): JEmbeddingClient =
    embeddings(new WordEmbeddings(Some(_ => throw thrown)))

  /** What the model answers when its prompt holds no context. */
  val NoContext = "The context does not say."

  /**
   * A model that answers with the first chunk of context it was given - the line `[1] ...` of the pipeline's prompt -
   * and reports 12 prompt and 3 completion tokens, recording every conversation.
   */
  final class EchoingLlm(failure: Option[LLMError] = None) extends LLMClient {
    val conversations: ArrayBuffer[Conversation] = ArrayBuffer.empty
    val options: ArrayBuffer[CompletionOptions]  = ArrayBuffer.empty

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      conversations += conversation
      this.options += options
      failure.toLeft {
        val prompt = conversation.messages.collect { case m: UserMessage => m.content }.mkString("\n")
        val answer = prompt.linesIterator
          .collectFirst { case l if l.startsWith("[1] ") => l.stripPrefix("[1] ") }
          .getOrElse(NoContext)
        Completion("id", Instant.EPOCH, "echo", AssistantMessage(answer)).withUsage(TokenUsage(12, 3, 15))
      }
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  def llm(client: LLMClient): JLlmClient = Llm4s.wrapClient(client)

  def llm(): JLlmClient = llm(new EchoingLlm())

  /**
   * A pipeline answering through `model`, built with a `SearchIndex`, so it refuses every plain query (#2716): what
   * `JRag.builder()` cannot build, for the specs of that refusal.
   */
  def withSearchIndex(model: LLMClient): JRag =
    RAG
      .buildWithEmbeddings(
        RAGConfig.default.withSearchIndex(NoIndex).withLLM(model),
        EmbeddingClient(new WordEmbeddings())(using ModelRegistryService.fromModels(Nil)),
        Model
      )
      .fold(e => throw new AssertionError(e.message), new JRag(RagFacadeToken.INSTANCE, _))

  /** A search index the refusal never reaches. */
  object NoIndex extends SearchIndex {
    def principals: PrincipalStore   = throw new UnsupportedOperationException("not used")
    def collections: CollectionStore = throw new UnsupportedOperationException("not used")
    def query(
      auth: UserAuthorization,
      collectionPattern: CollectionPattern,
      queryVector: Array[Float],
      topK: Int,
      additionalFilter: Option[MetadataFilter]
    ): Result[Seq[ScoredRecord]] = Right(Nil)
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
}
