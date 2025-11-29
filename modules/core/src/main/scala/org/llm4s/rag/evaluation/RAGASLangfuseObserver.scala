package org.llm4s.rag.evaluation

import org.llm4s.llmconnect.config.{ LangfuseConfig, TracingSettings }
import org.llm4s.trace.{ LangfuseBatchSender, LangfuseHttpApiCaller }
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Observer that logs RAGAS evaluation results to Langfuse.
 *
 * Integrates with existing Langfuse tracing infrastructure to log:
 * - Individual metric scores
 * - Composite RAGAS scores
 * - Evaluation details and metadata
 *
 * @param langfuseUrl The Langfuse API URL
 * @param publicKey Langfuse public key
 * @param secretKey Langfuse secret key
 * @param environment Environment name (e.g., "production", "development")
 * @param release Release version
 * @param version API version
 *
 * @example
 * {{{{
 * val observer = RAGASLangfuseObserver.fromTracingSettings(tracingSettings)
 *
 * val result = evaluator.evaluate(sample)
 * result.foreach { evalResult =>
 *   observer.logEvaluation(evalResult)
 * }
 * }}}}
 */
class RAGASLangfuseObserver(
  langfuseUrl: String,
  publicKey: String,
  secretKey: String,
  environment: String = "development",
  release: String = "1.0.0",
  version: String = "1.0",
  batchSender: LangfuseBatchSender = new org.llm4s.trace.DefaultLangfuseBatchSender()
) {

  private val logger         = LoggerFactory.getLogger(getClass)
  private def nowIso: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
  private def uuid: String   = UUID.randomUUID().toString
  private val config         = LangfuseHttpApiCaller(langfuseUrl, publicKey, secretKey)

  protected def sendBatch(events: Seq[ujson.Obj]): Unit =
    batchSender.sendBatch(events, config)

  /**
   * Log a single evaluation result to Langfuse.
   *
   * Creates a trace with the evaluation details and scores for each metric.
   *
   * @param result The evaluation result to log
   * @param traceId Optional trace ID to link to an existing trace
   */
  def logEvaluation(result: EvalResult, traceId: Option[String] = None): Unit = {
    val effectiveTraceId = traceId.getOrElse(uuid)
    val now              = nowIso

    logger.info(s"[RAGASLangfuseObserver] Logging RAGAS evaluation to Langfuse: traceId=$effectiveTraceId")

    val events = scala.collection.mutable.ListBuffer[ujson.Obj]()

    // Create trace event if no existing trace ID provided
    if (traceId.isEmpty) {
      events += createTraceEvent(result, effectiveTraceId, now)
    }

    // Create score events for each metric
    result.metrics.foreach(metric => events += createScoreEvent(metric, effectiveTraceId, now))

    // Create composite RAGAS score event
    events += createCompositeScoreEvent(result.ragasScore, effectiveTraceId, now)

    // Create span with evaluation details
    events += createEvaluationSpan(result, effectiveTraceId, now)

    sendBatch(events.toSeq)
  }

  /**
   * Log a batch evaluation summary to Langfuse.
   *
   * @param summary The evaluation summary to log
   */
  def logEvaluationSummary(summary: EvalSummary): Unit = {
    val traceId = uuid
    val now     = nowIso

    logger.info(s"[RAGASLangfuseObserver] Logging RAGAS summary to Langfuse: ${summary.sampleCount} samples")

    val events = scala.collection.mutable.ListBuffer[ujson.Obj]()

    // Create summary trace
    events += createSummaryTraceEvent(summary, traceId, now)

    // Create average score events
    summary.averages.foreach { case (metricName, avgScore) =>
      events += ujson.Obj(
        "id"        -> uuid,
        "timestamp" -> now,
        "type"      -> "score-create",
        "body" -> ujson.Obj(
          "id"      -> uuid,
          "traceId" -> traceId,
          "name"    -> s"avg_$metricName",
          "value"   -> avgScore,
          "comment" -> s"Average $metricName across ${summary.sampleCount} samples"
        )
      )
    }

    // Create overall RAGAS score
    events += ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> now,
      "type"      -> "score-create",
      "body" -> ujson.Obj(
        "id"      -> uuid,
        "traceId" -> traceId,
        "name"    -> "avg_ragas_score",
        "value"   -> summary.overallRagasScore,
        "comment" -> s"Overall RAGAS score across ${summary.sampleCount} samples"
      )
    )

    sendBatch(events.toSeq)
  }

  private def createTraceEvent(result: EvalResult, traceId: String, now: String): ujson.Obj =
    ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> now,
      "type"      -> "trace-create",
      "body" -> ujson.Obj(
        "id"        -> traceId,
        "timestamp" -> now,
        "name"      -> "RAGAS Evaluation",
        "input" -> ujson.Obj(
          "question" -> result.sample.question,
          "answer"   -> result.sample.answer,
          "contexts" -> ujson.Arr(result.sample.contexts.map(ujson.Str(_)): _*)
        ),
        "output" -> ujson.Obj(
          "ragasScore"  -> result.ragasScore,
          "metricCount" -> result.metrics.size
        ),
        "metadata" -> ujson.Obj(
          "environment"    -> environment,
          "release"        -> release,
          "version"        -> version,
          "hasGroundTruth" -> result.sample.groundTruth.isDefined,
          "evaluatedAt"    -> result.evaluatedAt
        )
      )
    )

  private def createScoreEvent(metric: MetricResult, traceId: String, now: String): ujson.Obj = {
    val commentDetails = metric.details
      .take(3)
      .map { case (k, v) =>
        s"$k: ${v.toString.take(50)}"
      }
      .mkString(", ")

    ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> now,
      "type"      -> "score-create",
      "body" -> ujson.Obj(
        "id"      -> uuid,
        "traceId" -> traceId,
        "name"    -> metric.metricName,
        "value"   -> metric.score,
        "comment" -> (if (commentDetails.nonEmpty) commentDetails else "No additional details")
      )
    )
  }

  private def createCompositeScoreEvent(ragasScore: Double, traceId: String, now: String): ujson.Obj =
    ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> now,
      "type"      -> "score-create",
      "body" -> ujson.Obj(
        "id"      -> uuid,
        "traceId" -> traceId,
        "name"    -> "ragas_score",
        "value"   -> ragasScore,
        "comment" -> "Composite RAGAS score (mean of all metrics)"
      )
    )

  private def createEvaluationSpan(result: EvalResult, traceId: String, now: String): ujson.Obj = {
    val metricsObj = ujson.Obj()
    result.metrics.foreach(m => metricsObj(m.metricName) = m.score)

    ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> now,
      "type"      -> "span-create",
      "body" -> ujson.Obj(
        "id"        -> uuid,
        "traceId"   -> traceId,
        "timestamp" -> now,
        "name"      -> "Evaluation Details",
        "startTime" -> now,
        "endTime"   -> now,
        "input" -> ujson.Obj(
          "question"       -> result.sample.question,
          "answer"         -> result.sample.answer.take(500),
          "contextCount"   -> result.sample.contexts.size,
          "hasGroundTruth" -> result.sample.groundTruth.isDefined
        ),
        "output" -> ujson.Obj(
          "metrics"    -> metricsObj,
          "ragasScore" -> result.ragasScore
        ),
        "metadata" -> ujson.Obj(
          "sampleMetadata" -> ujson.Obj.from(result.sample.metadata.map { case (k, v) => k -> ujson.Str(v) })
        )
      )
    )
  }

  private def createSummaryTraceEvent(summary: EvalSummary, traceId: String, now: String): ujson.Obj = {
    val avgObj = ujson.Obj()
    summary.averages.foreach { case (name, score) =>
      avgObj(name) = score
    }

    ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> now,
      "type"      -> "trace-create",
      "body" -> ujson.Obj(
        "id"        -> traceId,
        "timestamp" -> now,
        "name"      -> "RAGAS Batch Evaluation",
        "input" -> ujson.Obj(
          "sampleCount" -> summary.sampleCount
        ),
        "output" -> ujson.Obj(
          "overallRagasScore" -> summary.overallRagasScore,
          "averages"          -> avgObj
        ),
        "metadata" -> ujson.Obj(
          "environment"    -> environment,
          "release"        -> release,
          "version"        -> version,
          "evaluationType" -> "batch"
        )
      )
    )
  }
}

object RAGASLangfuseObserver {

  /**
   * Create an observer from Langfuse configuration.
   *
   * @param config The Langfuse configuration
   * @return A configured observer
   */
  def from(config: LangfuseConfig): RAGASLangfuseObserver =
    new RAGASLangfuseObserver(
      langfuseUrl = config.url,
      publicKey = config.publicKey.getOrElse(""),
      secretKey = config.secretKey.getOrElse(""),
      environment = config.env,
      release = config.release,
      version = config.version
    )

  /**
   * Create an observer from tracing settings.
   */
  def fromTracingSettings(tracing: TracingSettings): RAGASLangfuseObserver =
    from(tracing.langfuse)

  /**
   * Create an observer with explicit credentials.
   *
   * @param publicKey Langfuse public key
   * @param secretKey Langfuse secret key
   * @param url Optional Langfuse URL (defaults to cloud)
   * @return A configured observer
   */
  def apply(
    publicKey: String,
    secretKey: String,
    url: String = "https://cloud.langfuse.com"
  ): RAGASLangfuseObserver =
    new RAGASLangfuseObserver(
      langfuseUrl = url,
      publicKey = publicKey,
      secretKey = secretKey
    )
}
