package org.llm4s.rag

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk, TokenUsage }
import org.llm4s.trace.{ TraceEvent, Tracing, TracingLLMClient }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import scala.concurrent.duration.*
import scala.util.Try

/**
 * What one RAG query cost, stage by stage (#2734): on every [[RAGAnswerResult]] as `metrics`, and in the
 * `TraceEvent.RAGOperationCompleted` a traced `RAG` emits, so an application gets them with or without a tracer.
 *
 * @param totalDuration       the whole call, from the query to the answer
 * @param rewriteDuration     the configured query transformers (zero with none)
 * @param embeddingDuration   embedding the query
 * @param searchDuration      the vector and keyword search and their fusion, not the embedding or the reranking
 * @param rerankDuration      the reranker, when one is configured
 * @param answerDuration      generating the answer, for a query that answers
 * @param candidatesRetrieved how many results the search returned (`rerankTopK` of them with a reranker)
 * @param chunksUsed          how many results were kept: the contexts the answer was generated from
 * @param embeddingTokens     the query embedding's tokens, when the provider reports them
 * @param promptTokens        the answer's prompt tokens, when the provider reports them
 * @param completionTokens    the answer's completion tokens, when the provider reports them
 * @param costUsd             the answer's estimated cost, when known. The rewrite and LLM-rerank calls are not in
 *                            these totals: with a tracer they are traced on their own, tagged `rag.rewrite` and
 *                            `rag.rerank`.
 */
@Experimental
final case class RAGQueryMetrics private (
  totalDuration: FiniteDuration,
  rewriteDuration: FiniteDuration,
  embeddingDuration: FiniteDuration,
  searchDuration: FiniteDuration,
  rerankDuration: Option[FiniteDuration],
  answerDuration: Option[FiniteDuration],
  candidatesRetrieved: Int,
  chunksUsed: Int,
  embeddingTokens: Option[Int],
  promptTokens: Option[Int],
  completionTokens: Option[Int],
  costUsd: Option[Double]
) {
  def withTotalDuration(d: FiniteDuration): RAGQueryMetrics          = copy(totalDuration = d)
  def withRewriteDuration(d: FiniteDuration): RAGQueryMetrics        = copy(rewriteDuration = d)
  def withEmbeddingDuration(d: FiniteDuration): RAGQueryMetrics      = copy(embeddingDuration = d)
  def withSearchDuration(d: FiniteDuration): RAGQueryMetrics         = copy(searchDuration = d)
  def withRerankDuration(d: FiniteDuration): RAGQueryMetrics         = copy(rerankDuration = Some(d))
  def withRerankDuration(d: Option[FiniteDuration]): RAGQueryMetrics = copy(rerankDuration = d)
  def withAnswerDuration(d: FiniteDuration): RAGQueryMetrics         = copy(answerDuration = Some(d))
  def withAnswerDuration(d: Option[FiniteDuration]): RAGQueryMetrics = copy(answerDuration = d)
  def withCandidatesRetrieved(n: Int): RAGQueryMetrics               = copy(candidatesRetrieved = n)
  def withChunksUsed(n: Int): RAGQueryMetrics                        = copy(chunksUsed = n)
  def withEmbeddingTokens(n: Int): RAGQueryMetrics                   = copy(embeddingTokens = Some(n))
  def withEmbeddingTokens(n: Option[Int]): RAGQueryMetrics           = copy(embeddingTokens = n)
  def withPromptTokens(n: Int): RAGQueryMetrics                      = copy(promptTokens = Some(n))
  def withPromptTokens(n: Option[Int]): RAGQueryMetrics              = copy(promptTokens = n)
  def withCompletionTokens(n: Int): RAGQueryMetrics                  = copy(completionTokens = Some(n))
  def withCompletionTokens(n: Option[Int]): RAGQueryMetrics          = copy(completionTokens = n)
  def withCostUsd(cost: Double): RAGQueryMetrics                     = copy(costUsd = Some(cost))
  def withCostUsd(cost: Option[Double]): RAGQueryMetrics             = copy(costUsd = cost)

  /** The stage durations by name - `rewrite`, `embedding`, `search`, and `rerank` and `answer` where they ran. */
  def stageDurations: Map[String, FiniteDuration] =
    Map("rewrite" -> rewriteDuration, "embedding" -> embeddingDuration, "search" -> searchDuration) ++
      rerankDuration.map("rerank" -> _) ++ answerDuration.map("answer" -> _)

  /** These metrics as the `RAGOperationCompleted` event of `operation` (`query` or `answer`). */
  def toTraceEvent(operation: String): TraceEvent.RAGOperationCompleted =
    TraceEvent.RAGOperationCompleted(
      operation,
      totalDuration,
      embeddingTokens = embeddingTokens,
      llmPromptTokens = promptTokens,
      llmCompletionTokens = completionTokens,
      totalCostUsd = costUsd,
      stageDurations = stageDurations
    )
}

@Experimental
object RAGQueryMetrics {

  def apply(
    totalDuration: FiniteDuration,
    rewriteDuration: FiniteDuration = Duration.Zero,
    embeddingDuration: FiniteDuration = Duration.Zero,
    searchDuration: FiniteDuration = Duration.Zero,
    rerankDuration: Option[FiniteDuration] = None,
    answerDuration: Option[FiniteDuration] = None,
    candidatesRetrieved: Int = 0,
    chunksUsed: Int = 0,
    embeddingTokens: Option[Int] = None,
    promptTokens: Option[Int] = None,
    completionTokens: Option[Int] = None,
    costUsd: Option[Double] = None
  ): RAGQueryMetrics =
    new RAGQueryMetrics(
      totalDuration,
      rewriteDuration,
      embeddingDuration,
      searchDuration,
      rerankDuration,
      answerDuration,
      candidatesRetrieved,
      chunksUsed,
      embeddingTokens,
      promptTokens,
      completionTokens,
      costUsd
    )
}

/**
 * Times one RAG call's stages. One per call, used on the calling thread only.
 */
final private[rag] class QueryMeter {
  private val startedAt                  = System.nanoTime()
  private var rewriteNanos               = 0L
  private var embeddingNanos             = 0L
  private var searchNanos                = 0L
  private var rerankNanos                = Option.empty[Long]
  private var answerNanos                = Option.empty[Long]
  private var embeddingTokens            = Option.empty[Int]
  private var candidates                 = 0
  private var answer: Option[Completion] = None

  private def timed[A](record: Long => Unit)(f: => A): A = {
    val t0 = System.nanoTime()
    val a  = f
    record(System.nanoTime() - t0)
    a
  }

  def rewrite[A](f: => A): A = timed(n => rewriteNanos += n)(f)

  def embedding[A](f: => A): A = timed(n => embeddingNanos += n)(f)

  /** The search, less any embedding done inside it (the permission search embeds as it goes). */
  def search[A](f: => A): A = {
    val embeddedBefore = embeddingNanos
    timed(n => searchNanos += math.max(0L, n - (embeddingNanos - embeddedBefore)))(f)
  }

  def rerank[A](f: => A): A = timed(n => rerankNanos = Some(rerankNanos.getOrElse(0L) + n))(f)

  def answering(f: => Result[Completion]): Result[Completion] = {
    val result = timed(n => answerNanos = Some(answerNanos.getOrElse(0L) + n))(f)
    result.foreach(c => answer = Some(c))
    result
  }

  def addEmbeddingTokens(tokens: Int): Unit = embeddingTokens = Some(embeddingTokens.getOrElse(0) + tokens)

  def retrieved(n: Int): Unit = candidates = n

  def metrics(chunksUsed: Int): RAGQueryMetrics = {
    val usage: Option[TokenUsage] = answer.flatMap(_.usage)
    RAGQueryMetrics(
      totalDuration = (System.nanoTime() - startedAt).nanos,
      rewriteDuration = rewriteNanos.nanos,
      embeddingDuration = embeddingNanos.nanos,
      searchDuration = searchNanos.nanos,
      rerankDuration = rerankNanos.map(_.nanos),
      answerDuration = answerNanos.map(_.nanos),
      candidatesRetrieved = candidates,
      chunksUsed = chunksUsed,
      embeddingTokens = embeddingTokens,
      promptTokens = usage.map(_.promptTokens),
      completionTokens = usage.map(_.completionTokens),
      costUsd = answer.flatMap(_.estimatedCost)
    )
  }
}

/** Tracing for the LLM calls a `RAG` makes. */
private[rag] object RAGTracing {

  val Answer  = "rag.answer"
  val Rerank  = "rag.rerank"
  val Rewrite = "rag.rewrite"

  /**
   * `client`, traced: each call as `TracingLLMClient` traces it (a `CompletionReceived` or an `ErrorOccurred`), and
   * its usage as a `TokenUsageRecorded` tagged with `operation`, so a backend can tell the answer from the rerank
   * and rewrite calls.
   */
  def llm(client: LLMClient, tracing: Tracing, operation: String): LLMClient =
    new OperationTracedClient(client, tracing, operation)

  /** Emits `event`; a tracing failure never fails the query. */
  def emit(tracing: Option[Tracing], event: => TraceEvent): Unit =
    tracing.foreach(t => Try(t.traceEvent(event)): Unit)

  final private class OperationTracedClient(underlying: LLMClient, tracing: Tracing, operation: String)
      extends LLMClient {
    private val traced = TracingLLMClient(underlying, tracing)

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      tagged(traced.complete(conversation, options))

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      tagged(traced.streamComplete(conversation, options, onChunk))

    private def tagged(result: Result[Completion]): Result[Completion] = {
      result.foreach(c => c.usage.foreach(u => Try(tracing.traceTokenUsage(u, c.model, operation)): Unit))
      result
    }

    def getContextWindow(): Int                 = underlying.getContextWindow()
    def getReserveCompletion(): Int             = underlying.getReserveCompletion()
    override def validate(): Result[Unit]       = underlying.validate()
    override def close(): Unit                  = underlying.close()
    override def providerId: Option[ProviderId] = underlying.providerId
  }
}
