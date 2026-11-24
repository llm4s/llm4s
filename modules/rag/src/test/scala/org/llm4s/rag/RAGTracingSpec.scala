package org.llm4s.rag

import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.permissions._
import org.llm4s.rag.transform.{ LLMQueryRewriter, QueryTransformer }
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord, VectorRecord }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.duration.*

/** `withTracing` traces every LLM call RAG makes, and each query's stages (#2734). */
class RAGTracingSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private object Embeddings extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(
        EmbeddingResponse(
          embeddings = request.input.map(t => Seq(0.1, 0.2, (t.length % 10) / 10.0)),
          usage = Some(EmbeddingUsage(promptTokens = 7, totalTokens = 7))
        )
      )
  }

  /** Answers the reranker with a score per document, the rewriter with a query, and anything else with "42". */
  private class RoutingClient(failAnswer: Boolean = false) extends LLMClient {
    val calls: mutable.Buffer[String] = mutable.Buffer.empty
    private def completion(text: String, usage: TokenUsage) =
      Completion(
        id = "c",
        created = Instant.EPOCH,
        model = "mock-model",
        message = AssistantMessage(text),
        usage = Some(usage),
        estimatedCost = Some(0.25)
      )
    private def route(conversation: Conversation): Result[Completion] = {
      val system = conversation.messages.collectFirst { case m: SystemMessage => m.content }.getOrElse("")
      if (system.contains("relevance score")) {
        calls += "rerank"
        val docs =
          "Document \\d+:".r.findAllIn(conversation.messages.collect { case m: UserMessage => m.content }.mkString).size
        Right(completion(Seq.fill(math.max(docs, 1))("0.9").mkString("[", ", ", "]"), TokenUsage(5, 1, 6)))
      } else if (system.contains("rewrit")) {
        calls += "rewrite"
        Right(completion("the answer", TokenUsage(3, 2, 5)))
      } else {
        calls += "answer"
        if (failAnswer) Left(NetworkError("down", None, "test"))
        else Right(completion("42", TokenUsage(10, 2, 12)))
      }
    }
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      route(conversation)
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      onChunk(StreamedChunk(id = "c", content = Some("42")))
      route(conversation)
    }
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private class Recording extends Tracing {
    val events: mutable.Buffer[TraceEvent]          = mutable.Buffer.empty
    def traceEvent(event: TraceEvent): Result[Unit] = { events += event; Right(()) }
    def usages: Seq[(String, String, Int)] =
      events.collect { case e: TraceEvent.TokenUsageRecorded => (e.operation, e.model, e.usage.totalTokens) }.toSeq
    def ragEvents: Seq[TraceEvent.RAGOperationCompleted] =
      events.collect { case e: TraceEvent.RAGOperationCompleted => e }.toSeq
    def completions: Int = events.count(_.isInstanceOf[TraceEvent.CompletionReceived])
  }

  private def rag(config: RAGConfig): RAG = {
    val built = RAG.buildWithClient(config, EmbeddingClient(Embeddings)).fold(e => fail(e.message), identity)
    if (!built.hasPermissions)
      built.ingestText("The answer to everything is 42.", "doc1").fold(e => fail(e.message), identity)
    built
  }

  private def fullConfig(client: LLMClient, tracer: Option[Tracing]): RAGConfig = {
    val base = RAGConfig.default
      .withLLM(client)
      .withReranking(RerankingStrategy.LLM)
      .withQueryTransformer(LLMQueryRewriter(client))
    tracer.fold(base)(base.withTracing)
  }

  "RAG.queryWithAnswer with a tracer" should "trace the answer, rerank and rewrite calls, each tagged" in {
    val client = new RoutingClient
    val tracer = new Recording
    val r      = rag(fullConfig(client, Some(tracer)))
    try {
      r.queryWithAnswer("What is the answer?").map(_.answer) shouldBe Right("42")
      client.calls.toList shouldBe List("rewrite", "rerank", "answer")
      tracer.completions shouldBe 3
      tracer.usages.map(_._1).toList shouldBe List("rag.rewrite", "rag.rerank", "rag.answer")
      tracer.usages.map(_._3).toList shouldBe List(5, 6, 12)
    } finally r.close()
  }

  it should "emit one RAGOperationCompleted with the stages, tokens and cost, matching the result's metrics" in {
    val tracer = new Recording
    val r      = rag(fullConfig(new RoutingClient, Some(tracer)))
    try {
      val result  = r.queryWithAnswer("What is the answer?").fold(e => fail(e.message), identity)
      val metrics = result.metrics.getOrElse(fail("no metrics"))
      metrics.chunksUsed shouldBe 1
      metrics.candidatesRetrieved shouldBe 1
      metrics.embeddingTokens shouldBe Some(7)
      metrics.promptTokens shouldBe Some(10)
      metrics.completionTokens shouldBe Some(2)
      metrics.costUsd shouldBe Some(0.25)
      metrics.rerankDuration shouldBe defined
      metrics.answerDuration shouldBe defined
      metrics.stageDurations.keySet shouldBe Set("rewrite", "embedding", "search", "rerank", "answer")
      metrics.totalDuration should be >= metrics.stageDurations.values.foldLeft(Duration.Zero)(_ + _)

      val Seq(event) = tracer.ragEvents
      event.operation shouldBe "answer"
      event shouldBe metrics.toTraceEvent("answer").withTimestamp(event.timestamp)
      event.toJson.obj("stage_durations_ms").obj.keySet shouldBe metrics.stageDurations.keySet
    } finally r.close()
  }

  "RAG.query with a tracer" should "emit a query RAGOperationCompleted, with no answer stage" in {
    val tracer = new Recording
    val r      = rag(RAGConfig.default.withTracing(tracer))
    try {
      r.query("What is the answer?").map(_.size) shouldBe Right(1)
      val Seq(event) = tracer.ragEvents
      event.operation shouldBe "query"
      event.stageDurations.keySet shouldBe Set("rewrite", "embedding", "search")
      event.embeddingTokens shouldBe Some(7)
      event.llmPromptTokens shouldBe None
    } finally r.close()
  }

  "RAG without a tracer" should "still return the metrics, and trace nothing" in {
    val client = new RoutingClient
    val r      = rag(fullConfig(client, None))
    try {
      val metrics = r.queryWithAnswer("Q?").fold(e => fail(e.message), identity).metrics
      metrics.map(_.chunksUsed) shouldBe Some(1)
      metrics.flatMap(_.rerankDuration) shouldBe defined
      client.calls.toList shouldBe List("rewrite", "rerank", "answer")
    } finally r.close()
  }

  "A failed answer" should "be traced as an error, and emit no RAGOperationCompleted" in {
    val tracer = new Recording
    val r      = rag(RAGConfig.default.withLLM(new RoutingClient(failAnswer = true)).withTracing(tracer))
    try {
      r.queryWithAnswer("Q?").isLeft shouldBe true
      tracer.events.collect { case e: TraceEvent.ErrorOccurred => e }.size shouldBe 1
      tracer.ragEvents shouldBe empty
    } finally r.close()
  }

  "RAG.queryWithAnswerStreaming with a tracer" should "trace the streamed answer and return its metrics" in {
    val tracer = new Recording
    val r      = rag(RAGConfig.default.withLLM(new RoutingClient).withTracing(tracer))
    try {
      val result = r.queryWithAnswerStreaming("Q?")(_ => (), _ => ()).fold(e => fail(e.message), identity)
      result.metrics.flatMap(_.answerDuration) shouldBe defined
      result.metrics.flatMap(_.rerankDuration) shouldBe None
      tracer.events.collect { case e: TraceEvent.CompletionReceived => e.streamed } shouldBe Seq(true)
      tracer.usages.map(_._1).toList shouldBe List("rag.answer")
      tracer.ragEvents.map(_.operation) shouldBe Seq("answer")
    } finally r.close()
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

  private def auth = UserAuthorization.Admin

  "The permission queries with a tracer" should "emit their RAGOperationCompleted events and metrics" in {
    val tracer = new Recording
    val config = RAGConfig.default
      .withSearchIndex(OneChunkIndex)
      .vectorOnly
      .withLLM(new RoutingClient)
      .withReranking(RerankingStrategy.LLM)
      .withTracing(tracer)
    val r = rag(config)
    try {
      r.queryWithPermissions(auth, CollectionPattern.All, "Q?").map(_.size) shouldBe Right(1)
      val answer =
        r.queryWithPermissionsAndAnswer(auth, CollectionPattern.All, "Q?").fold(e => fail(e.message), identity)
      answer.metrics.map(_.embeddingTokens) shouldBe Some(Some(7))
      answer.metrics.flatMap(_.rerankDuration) shouldBe defined
      r.queryWithPermissionsAndAnswerStreaming(auth, CollectionPattern.All, "Q?")(_ => (), _ => ())
        .map(_.metrics.isDefined) shouldBe Right(true)
      tracer.ragEvents.map(_.operation) shouldBe Seq("query", "answer", "answer")
      tracer.usages.map(_._1).distinct.toSet shouldBe Set("rag.rerank", "rag.answer")
    } finally r.close()
  }

  "QueryTransformer.withTracing" should "leave a transformer without LLM calls unchanged" in {
    val upper = new QueryTransformer {
      def transform(query: String): Result[String] = Right(query.toUpperCase)
      def name: String                             = "upper"
    }
    (upper.withTracing(new Recording) should be).theSameInstanceAs(upper)
  }

  "RAGQueryMetrics" should "set each field with its setter" in {
    val m = RAGQueryMetrics(1.second)
      .withTotalDuration(2.seconds)
      .withRewriteDuration(1.milli)
      .withEmbeddingDuration(2.millis)
      .withSearchDuration(3.millis)
      .withRerankDuration(4.millis)
      .withAnswerDuration(5.millis)
      .withCandidatesRetrieved(50)
      .withChunksUsed(5)
      .withEmbeddingTokens(7)
      .withPromptTokens(10)
      .withCompletionTokens(2)
      .withCostUsd(0.5)
    m.stageDurations shouldBe Map(
      "rewrite"   -> 1.milli,
      "embedding" -> 2.millis,
      "search"    -> 3.millis,
      "rerank"    -> 4.millis,
      "answer"    -> 5.millis
    )
    (m.totalDuration, m.candidatesRetrieved, m.chunksUsed) shouldBe ((2.seconds, 50, 5))
    (m.embeddingTokens, m.promptTokens, m.completionTokens, m.costUsd) shouldBe ((
      Some(7),
      Some(10),
      Some(2),
      Some(0.5)
    ))
    val cleared = m
      .withRerankDuration(None)
      .withAnswerDuration(None)
      .withEmbeddingTokens(None)
      .withPromptTokens(None)
      .withCompletionTokens(None)
      .withCostUsd(None)
    cleared.stageDurations.keySet shouldBe Set("rewrite", "embedding", "search")
    (cleared.embeddingTokens, cleared.promptTokens, cleared.completionTokens, cleared.costUsd) shouldBe
      ((None, None, None, None))
  }
}
