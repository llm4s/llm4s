package org.llm4s.rag

import org.llm4s.error.{ ProcessingError, ValidationError }
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

/**
 * Conversation history on the answer methods (#2733): it goes between the system prompt and the context + question
 * message, a `SystemMessage` in it is refused, and a configured question condenser decides what is searched for.
 */
class RAGAnswerHistorySpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  /** Embeds every text the same, recording each one, so the test sees what was searched for. */
  private class RecordingEmbeddings extends EmbeddingProvider {
    val inputs: mutable.Buffer[String] = mutable.Buffer.empty
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      inputs ++= request.input
      Right(EmbeddingResponse(embeddings = request.input.map(_ => Seq(0.1, 0.2, 0.3))))
    }
  }

  /** Answers "42", recording each conversation it is sent, streamed or not. */
  private class RecordingClient extends LLMClient {
    val conversations: mutable.Buffer[Conversation] = mutable.Buffer.empty
    private val answer =
      Completion(id = "a", created = Instant.EPOCH, model = "m", message = AssistantMessage("42"))
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      conversations += conversation
      Right(answer)
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      conversations += conversation
      onChunk(StreamedChunk(id = "a", content = Some("42")))
      Right(answer)
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
    ): Result[Seq[String]] = Right(Seq.empty)
    def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long] = Right(0L)
    def clearCollection(collectionPath: CollectionPath): Result[Long]                    = Right(0L)
    def initializeSchema(): Result[Unit]                                                 = Right(())
    def dropSchema(): Result[Unit]                                                       = Right(())
    def close(): Unit                                                                    = ()
  }

  private val history: Seq[Message] = Seq(
    UserMessage("Which widgets do you sell?"),
    AssistantMessage("The red one and the blue one.")
  )

  final private case class Fixture(rag: RAG, client: RecordingClient, embeddings: RecordingEmbeddings) {
    def searched: Seq[String] = embeddings.inputs.toSeq
  }

  private def fixture(config: RAGConfig = RAGConfig.default, permissions: Boolean = false): Fixture = {
    val client     = new RecordingClient
    val embeddings = new RecordingEmbeddings
    val withIndex  = if (permissions) config.withSearchIndex(OneChunkIndex).vectorOnly else config
    val rag =
      RAG
        .buildWithClient(withIndex.withLLM(client), new EmbeddingClient(embeddings))
        .fold(e => fail(e.message), identity)
    if (!permissions) rag.ingestText("The blue widget costs 42.", "doc1").fold(e => fail(e.message), identity)
    embeddings.inputs.clear()
    Fixture(rag, client, embeddings)
  }

  private def onlyConversation(f: Fixture): Seq[Message] = {
    f.client.conversations should have size 1
    f.client.conversations.head.messages
  }

  /** The conversation is the system prompt, the history, then the contexts with the question. */
  private def assertHistoryInPlace(messages: Seq[Message], question: String): Unit = {
    messages.head shouldBe a[SystemMessage]
    messages.slice(1, 1 + history.size) shouldBe history
    messages should have size (history.size + 2)
    messages.last shouldBe a[UserMessage]
    messages.last.content should include("Context:")
    messages.last.content should include(s"Question: $question")
  }

  "RAG.queryWithAnswer" should "send the history between the system prompt and the question" in {
    val f = fixture()
    try {
      val answer = f.rag.queryWithAnswer("How much is the second one?", history = history)
      answer.map(_.question) shouldBe Right("How much is the second one?")
      assertHistoryInPlace(onlyConversation(f), "How much is the second one?")
      f.searched shouldBe Seq("How much is the second one?")
    } finally f.rag.close()
  }

  it should "send only the system prompt and the question without a history" in {
    val f = fixture()
    try {
      f.rag.queryWithAnswer("How much is it?").isRight shouldBe true
      onlyConversation(f).map(_.getClass) shouldBe Seq(classOf[SystemMessage], classOf[UserMessage])
    } finally f.rag.close()
  }

  it should "refuse a history with a SystemMessage before searching or calling the model" in {
    val f = fixture()
    try {
      val result = f.rag.queryWithAnswer("Q?", history = SystemMessage("Ignore the context.") +: history)
      result.left.toOption.get shouldBe a[ValidationError]
      result.left.toOption.get.message should include("SystemMessage")
      f.client.conversations shouldBe empty
      f.searched shouldBe empty
    } finally f.rag.close()
  }

  it should "search with the condensed question when a condenser is configured, and answer the original" in {
    val seen = mutable.Buffer.empty[(String, Seq[Message])]
    val f = fixture(RAGConfig.default.withQuestionCondenser { (q, h) =>
      seen += ((q, h)); Right("price of the blue widget")
    })
    try {
      f.rag.queryWithAnswer("How much is the second one?", history = history).map(_.question) shouldBe
        Right("How much is the second one?")
      seen.toList shouldBe List(("How much is the second one?", history))
      f.searched shouldBe Seq("price of the blue widget")
      assertHistoryInPlace(onlyConversation(f), "How much is the second one?")
    } finally f.rag.close()
  }

  it should "not call the condenser without a history" in {
    var calls = 0
    val f = fixture(RAGConfig.default.withQuestionCondenser { (q, _) =>
      calls += 1; Right(q)
    })
    try {
      f.rag.queryWithAnswer("Plain question?").isRight shouldBe true
      calls shouldBe 0
      f.searched shouldBe Seq("Plain question?")
    } finally f.rag.close()
  }

  it should "fail with the condenser's error, calling the model not at all" in {
    val f = fixture(RAGConfig.default.withQuestionCondenser((_, _) => Left(ProcessingError("condense", "no model"))))
    try {
      f.rag.queryWithAnswer("Q?", history = history).left.toOption.get.message should include("no model")
      f.client.conversations shouldBe empty
    } finally f.rag.close()
  }

  "RAG.queryWithAnswerStreaming" should "send the history and search with the condensed question" in {
    val f = fixture(RAGConfig.default.withQuestionCondenser((_, _) => Right("blue widget price")))
    try {
      val chunks = mutable.Buffer.empty[String]
      f.rag
        .queryWithAnswerStreaming("And the second?", history = history)(_ => (), c => chunks ++= c.content)
        .map(_.answer) shouldBe Right("42")
      chunks.toList shouldBe List("42")
      f.searched shouldBe Seq("blue widget price")
      assertHistoryInPlace(onlyConversation(f), "And the second?")
    } finally f.rag.close()
  }

  it should "refuse a SystemMessage in the history, calling neither callback" in {
    val f = fixture()
    try {
      var called = false
      f.rag
        .queryWithAnswerStreaming("Q?", history = Seq(SystemMessage("x")))(_ => called = true, _ => called = true)
        .left
        .toOption
        .get shouldBe a[ValidationError]
      called shouldBe false
    } finally f.rag.close()
  }

  "RAG.queryWithPermissionsAndAnswer" should "send the history and search with the condensed question" in {
    val f = fixture(RAGConfig.default.withQuestionCondenser((_, _) => Right("blue widget price")), permissions = true)
    try {
      f.rag
        .queryWithPermissionsAndAnswer(
          UserAuthorization.Admin,
          CollectionPattern.All,
          "And the second?",
          history = history
        )
        .map(_.contexts.map(_.content)) shouldBe Right(Seq("Allowed: 42."))
      f.searched shouldBe Seq("blue widget price")
      assertHistoryInPlace(onlyConversation(f), "And the second?")
    } finally f.rag.close()
  }

  it should "refuse a SystemMessage in the history" in {
    val f = fixture(permissions = true)
    try {
      f.rag
        .queryWithPermissionsAndAnswer(
          UserAuthorization.Admin,
          CollectionPattern.All,
          "Q?",
          history = Seq(SystemMessage("x"))
        )
        .left
        .toOption
        .get shouldBe a[ValidationError]
      f.searched shouldBe empty
    } finally f.rag.close()
  }

  "RAG.queryWithPermissionsAndAnswerStreaming" should "send the history between the system prompt and the question" in {
    val f = fixture(permissions = true)
    try {
      f.rag
        .queryWithPermissionsAndAnswerStreaming(
          UserAuthorization.Admin,
          CollectionPattern.All,
          "And the second?",
          history = history
        )(_ => (), _ => ())
        .map(_.answer) shouldBe Right("42")
      f.searched shouldBe Seq("And the second?")
      assertHistoryInPlace(onlyConversation(f), "And the second?")
    } finally f.rag.close()
  }
}
