package org.llm4s.rag.evaluation

import org.llm4s.security.Secret
import org.llm4s.llmconnect.config.LangfuseConfig
import org.llm4s.llmconnect.utils.CodePointBoundary
import org.llm4s.trace.{ LangfuseBatchSender, LangfuseHttpApiCaller, LangfuseOtlp, TraceContext }
import org.llm4s.trace.LangfuseOtlp.{ Observation, ObservationType, TraceAttributes }
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.UUID

/**
 * Observer that logs RAGAS evaluation results to Langfuse.
 *
 * Integrates with the Langfuse tracing infrastructure in `llm4s-observability`, which
 * `llm4s-rag` depends on for it, to log:
 * - Individual metric scores
 * - Composite RAGAS scores
 * - Evaluation details and metadata
 *
 * An evaluation's trace and its `Evaluation Details` span go to Langfuse's OpenTelemetry endpoint
 * (`/api/public/otel/v1/traces`) as OTLP spans, and its scores to `POST /api/public/scores`, each score naming
 * the trace's id, 32 hex digits (#2883).
 *
 * @param langfuseUrl The Langfuse host (a URL ending in one of Langfuse's API paths names the host it is on)
 * @param publicKey Langfuse public key
 * @param secretKey Langfuse secret key
 * @param environment Environment name (e.g., "production", "development")
 * @param release Release version
 * @param version API version
 *
 * @example
 * {{{{
 * val observer = LangfuseConfigLoader.default().map(RAGASLangfuseObserver.from)
 *
 * val result = evaluator.evaluate(sample)
 * for {
 *   obs        <- observer
 *   evalResult <- result
 * } obs.logEvaluation(evalResult)
 * }}}}
 */
class RAGASLangfuseObserver(
  langfuseUrl: String,
  publicKey: Secret,
  secretKey: Secret,
  environment: String = "development",
  release: String = "1.0.0",
  version: String = "1.0",
  batchSender: LangfuseBatchSender = new org.llm4s.trace.DefaultLangfuseBatchSender()
) {

  private val logger       = LoggerFactory.getLogger(getClass)
  private def uuid: String = UUID.randomUUID().toString
  private val config       = LangfuseHttpApiCaller(langfuseUrl, publicKey, secretKey)
  private val deployment   = LangfuseOtlp.Deployment(environment, release, version)

  private def send(spans: Seq[Observation], scores: Seq[ujson.Obj]): Unit = {
    if (spans.nonEmpty)
      batchSender.sendTraces(LangfuseOtlp.request(spans, deployment, RAGASLangfuseObserver.Scope), config)
    batchSender.sendScores(scores, config)
  }

  /**
   * Log a single evaluation result to Langfuse.
   *
   * Without `traceId` it creates a trace, `RAGAS Evaluation`, with the evaluation's details as a span under it. With
   * one it adds the details span to that trace. Either way it scores the trace for each metric and for the composite
   * RAGAS score.
   *
   * @param result The evaluation result to log
   * @param traceId Optional id of an existing Langfuse trace to score: for a trace llm4s sent, its OpenTelemetry trace
   *                id, 32 hex digits. The details span is sent only when the id is one; the scores are sent either way
   */
  def logEvaluation(result: EvalResult, traceId: Option[String] = None): Unit = {
    val now = Instant.now()
    // the context the details span is under: a new trace's root, or a span of the given trace
    val root = traceId match {
      case None     => Some(TraceContext.random())
      case Some(id) => TraceContext.of(id, "0000000000000001").toOption.map(_.spanFor(s"ragas:$uuid"))
    }
    val effectiveTraceId = traceId.orElse(root.map(_.traceId)).getOrElse("")

    logger.info(s"[RAGASLangfuseObserver] Logging RAGAS evaluation to Langfuse: traceId=$effectiveTraceId")
    if (root.isEmpty)
      logger.warn(
        s"[RAGASLangfuseObserver] traceId $effectiveTraceId is not an OpenTelemetry trace id (32 hex digits); " +
          "sending its scores without the Evaluation Details span"
      )

    val spans = root.toSeq.flatMap { context =>
      val trace = Option.when(traceId.isEmpty)(traceObservation(result, context, now))
      trace.toSeq :+ evaluationSpan(result, context, trace.map(_.context.spanId), now)
    }

    // a score for each metric, then the composite RAGAS score
    val scores = result.metrics.map(metric => metricScore(metric, effectiveTraceId)) :+
      score(effectiveTraceId, "ragas_score", result.ragasScore, "Composite RAGAS score (mean of all metrics)")

    send(spans, scores)
  }

  /**
   * Log a batch evaluation summary to Langfuse: a trace, `RAGAS Batch Evaluation`, scored with each metric's average
   * and the overall RAGAS score.
   *
   * @param summary The evaluation summary to log
   */
  def logEvaluationSummary(summary: EvalSummary): Unit = {
    val context = TraceContext.random()
    val now     = Instant.now()

    logger.info(s"[RAGASLangfuseObserver] Logging RAGAS summary to Langfuse: ${summary.sampleCount} samples")

    val avgObj = ujson.Obj()
    summary.averages.foreach { case (name, score) => avgObj(name) = score }
    val metadata = ujson.Obj(
      "environment"    -> environment,
      "release"        -> release,
      "version"        -> version,
      "evaluationType" -> "batch"
    )
    val trace = Observation(
      context,
      None,
      "RAGAS Batch Evaluation",
      ObservationType.Span,
      now,
      now,
      input = Some(ujson.Obj("sampleCount" -> summary.sampleCount)),
      output = Some(ujson.Obj("overallRagasScore" -> summary.overallRagasScore, "averages" -> avgObj)),
      metadata = metadata,
      trace = Some(TraceAttributes("RAGAS Batch Evaluation", None, None, Seq.empty, metadata, public = false))
    )

    // a score for each metric's average, then the overall RAGAS score
    val scores = summary.averages.toSeq.map { case (metricName, avgScore) =>
      score(context.traceId, s"avg_$metricName", avgScore, s"Average $metricName across ${summary.sampleCount} samples")
    } :+ score(
      context.traceId,
      "avg_ragas_score",
      summary.overallRagasScore,
      s"Overall RAGAS score across ${summary.sampleCount} samples"
    )

    send(Seq(trace), scores)
  }

  private def traceObservation(result: EvalResult, context: TraceContext, now: Instant): Observation = {
    val metadata = ujson.Obj(
      "environment"    -> environment,
      "release"        -> release,
      "version"        -> version,
      "hasGroundTruth" -> result.sample.groundTruth.isDefined,
      "evaluatedAt"    -> result.evaluatedAt
    )
    Observation(
      context,
      None,
      "RAGAS Evaluation",
      ObservationType.Span,
      now,
      now,
      input = Some(
        ujson.Obj(
          "question" -> result.sample.question,
          "answer"   -> result.sample.answer,
          "contexts" -> ujson.Arr(result.sample.contexts.map(ujson.Str(_)): _*)
        )
      ),
      output = Some(ujson.Obj("ragasScore" -> result.ragasScore, "metricCount" -> result.metrics.size)),
      metadata = metadata,
      trace = Some(TraceAttributes("RAGAS Evaluation", None, None, Seq.empty, metadata, public = false))
    )
  }

  private def score(traceId: String, name: String, value: Double, comment: String): ujson.Obj =
    ujson.Obj(
      "id"       -> uuid,
      "traceId"  -> traceId,
      "name"     -> name,
      "value"    -> value,
      "dataType" -> "NUMERIC",
      "comment"  -> comment
    )

  private def metricScore(metric: MetricResult, traceId: String): ujson.Obj = {
    val commentDetails = metric.details
      .take(3)
      .map { case (k, v) =>
        s"$k: ${CodePointBoundary.take(v.toString, 50)}"
      }
      .mkString(", ")
    score(
      traceId,
      metric.metricName,
      metric.score,
      if (commentDetails.nonEmpty) commentDetails else "No additional details"
    )
  }

  private def evaluationSpan(
    result: EvalResult,
    trace: TraceContext,
    parent: Option[String],
    now: Instant
  ): Observation = {
    val metricsObj = ujson.Obj()
    result.metrics.foreach(m => metricsObj(m.metricName) = m.score)

    Observation(
      trace.spanFor(s"details:$uuid"),
      parent,
      "Evaluation Details",
      ObservationType.Span,
      now,
      now,
      input = Some(
        ujson.Obj(
          "question"       -> result.sample.question,
          "answer"         -> CodePointBoundary.take(result.sample.answer, 500),
          "contextCount"   -> result.sample.contexts.size,
          "hasGroundTruth" -> result.sample.groundTruth.isDefined
        )
      ),
      output = Some(ujson.Obj("metrics" -> metricsObj, "ragasScore" -> result.ragasScore)),
      metadata = ujson.Obj(
        "sampleMetadata" -> ujson.Obj.from(result.sample.metadata.map { case (k, v) => k -> ujson.Str(v) })
      )
    )
  }
}

object RAGASLangfuseObserver {

  /** The instrumentation scope of the spans the observer sends. */
  private val Scope = "org.llm4s.rag.evaluation.RAGASLangfuseObserver"

  /**
   * Create an observer from Langfuse configuration.
   *
   * `org.llm4s.config.LangfuseConfigLoader.default()` reads it from
   * `llm4s.tracing.langfuse` (the `LANGFUSE_*` variables) whatever `TRACING_MODE` is.
   * This replaced `fromTracingSettings(TracingSettings)` when the typed
   * `TracingSettings.langfuse` field left core (#1133).
   *
   * @param config The Langfuse configuration
   * @return A configured observer
   */
  def from(config: LangfuseConfig): RAGASLangfuseObserver =
    new RAGASLangfuseObserver(
      langfuseUrl = config.url,
      publicKey = config.publicKey.getOrElse(Secret("")),
      secretKey = config.secretKey.getOrElse(Secret("")),
      environment = config.env,
      release = config.release,
      version = config.version
    )

  /**
   * Create an observer with explicit credentials.
   *
   * @param publicKey Langfuse public key
   * @param secretKey Langfuse secret key
   * @param url Optional Langfuse URL (defaults to cloud)
   * @return A configured observer
   */
  def apply(
    publicKey: Secret,
    secretKey: Secret,
    url: String = "https://cloud.langfuse.com"
  ): RAGASLangfuseObserver =
    new RAGASLangfuseObserver(
      langfuseUrl = url,
      publicKey = publicKey,
      secretKey = secretKey
    )
}
