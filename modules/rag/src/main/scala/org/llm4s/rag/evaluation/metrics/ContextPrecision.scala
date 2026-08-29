package org.llm4s.rag.evaluation.metrics

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.rag.evaluation._
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * Context Precision metric: measures if relevant contexts are ranked at the top.
 *
 * Algorithm:
 * 1. For each retrieved context, determine if it's relevant to the question/ground_truth
 * 2. Calculate precision@k for each position where a relevant doc appears
 * 3. Score = Average Precision (AP) = sum of (precision@k * relevance@k) / total_relevant
 *
 * The intuition: if your retrieval system ranks relevant documents at the top,
 * you get a higher score. Documents ranked lower contribute less to the score.
 *
 * @param llmClient The LLM client for relevance assessment
 *
 * @example
 * {{{{
 * val metric = ContextPrecision(llmClient)
 * val sample = EvalSample(
 *   question = "What is the capital of France?",
 *   answer = "Paris is the capital of France.",
 *   contexts = Seq(
 *     "Paris is the capital and largest city of France.",  // relevant
 *     "France has beautiful countryside.",                  // less relevant
 *     "Paris has the Eiffel Tower."                         // relevant
 *   ),
 *   groundTruth = Some("The capital of France is Paris.")
 * )
 * val result = metric.evaluate(sample)
 * // High score if relevant contexts are at positions 1 and 2 vs scattered
 * }}}}
 */
class ContextPrecision(llmClient: LLMClient) extends RAGASMetric {

  override val name: String        = "context_precision"
  override val description: String = "Measures if relevant contexts are ranked at the top"

  override val requiredInputs: Set[RequiredInput] =
    Set(RequiredInput.Question, RequiredInput.Contexts, RequiredInput.GroundTruth)

  override def evaluate(sample: EvalSample): Result[MetricResult] =
    sample.groundTruth match {
      case None =>
        Left(EvaluationError.missingInput("ground_truth"))

      case Some(gt) if gt.trim.isEmpty =>
        Right(
          MetricResult(
            metricName = name,
            score = 0.0,
            details = Map("reason" -> "Empty ground truth cannot be used for relevance assessment")
          )
        )

      case Some(gt) =>
        if (sample.contexts.isEmpty || sample.contexts.forall(_.trim.isEmpty)) {
          return Right(
            MetricResult(
              metricName = name,
              score = 0.0,
              details = Map("reason" -> "No contexts to evaluate")
            )
          )
        }

        for {
          relevanceScores <- assessRelevance(sample.question, sample.contexts, gt)
          score = calculateAveragePrecision(relevanceScores)
        } yield MetricResult(
          metricName = name,
          score = math.max(0.0, math.min(1.0, score)),
          details = Map(
            "relevancePerPosition" -> relevanceScores,
            "relevantCount"        -> relevanceScores.count(_ == 1.0),
            "totalContexts"        -> relevanceScores.size
          )
        )
    }

  /**
   * Assess relevance of each context to the question/ground truth.
   * Returns a sequence of relevance scores (0.0 or 1.0) in the same order as contexts.
   */
  private def assessRelevance(
    question: String,
    contexts: Seq[String],
    groundTruth: String
  ): Result[Seq[Double]] = {
    val systemPrompt =
      """You are an expert at assessing document relevance.
        |Given a question, its ground truth answer, and a list of contexts,
        |determine which contexts are relevant for answering the question.
        |
        |A context is RELEVANT if it:
        |- Contains information that helps answer the question correctly
        |- Provides facts or details that align with the ground truth
        |- Would be useful for generating the correct answer
        |
        |A context is NOT RELEVANT if it:
        |- Contains unrelated information
        |- Would not help in answering the question
        |- Contains contradictory information
        |
        |Respond with ONLY a JSON array of objects with "index" (0-based) and "relevant" (boolean).
        |Example: [{"index": 0, "relevant": true}, {"index": 1, "relevant": false}]""".stripMargin

    val contextsFormatted = contexts.zipWithIndex
      .map { case (ctx, i) => s"[Context $i]: $ctx" }
      .mkString("\n\n")

    val userPrompt = s"""Question: $question

Ground Truth Answer: $groundTruth

Contexts to evaluate:
$contextsFormatted

For each context (0 to ${contexts.size - 1}), determine if it is relevant for answering the question.
Respond with ONLY a JSON array:"""

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(temperature = 0.0, maxTokens = Some(1000))

    for {
      completion <- llmClient.complete(conversation, options)
      relevances <- parseRelevances(completion.content, contexts.size)
    } yield relevances
  }

  /**
   * Calculate Average Precision from relevance scores.
   *
   * AP = (1/R) * sum_{k=1}^{n} (Precision@k * rel_k)
   *
   * where R = total relevant docs, rel_k = 1 if doc at position k is relevant
   */
  private def calculateAveragePrecision(relevances: Seq[Double]): Double = {
    val totalRelevant = relevances.count(_ > 0.5)
    if (totalRelevant == 0) return 0.0

    var relevantSoFar = 0
    var apSum         = 0.0

    relevances.zipWithIndex.foreach { case (rel, idx) =>
      if (rel > 0.5) {
        relevantSoFar += 1
        val precisionAtK = relevantSoFar.toDouble / (idx + 1)
        apSum += precisionAtK
      }
    }

    apSum / totalRelevant
  }

  /**
   * Parse relevance assessments from LLM response.
   */
  private def parseRelevances(response: String, expectedCount: Int): Result[Seq[Double]] =
    Try {
      val jsonStr = extractJsonArray(response)
      val arr     = ujson.read(jsonStr).arr

      // Build a map of index -> relevance
      val relevanceMap = arr.map { v =>
        val obj      = v.obj
        val index    = obj("index").num.toInt
        val relevant = obj("relevant").bool
        index -> (if (relevant) 1.0 else 0.0)
      }.toMap

      // Return relevances in order, defaulting to 0.0 for missing indices
      (0 until expectedCount).map(i => relevanceMap.getOrElse(i, 0.0))
    }.toResult.left.map(e => EvaluationError.parseError(s"Failed to parse relevance assessments: ${e.message}"))

  /**
   * Extract JSON array from potentially markdown-wrapped response.
   */
  private def extractJsonArray(response: String): String = {
    val trimmed = response.trim

    val withoutCodeBlock = if (trimmed.startsWith("```")) {
      val lines = trimmed.split("\n")
      val start = 1
      val end   = lines.lastIndexWhere(_.trim == "```")
      if (end > start) {
        lines.slice(start, end).mkString("\n")
      } else {
        trimmed.stripPrefix("```json").stripPrefix("```").stripSuffix("```")
      }
    } else {
      trimmed
    }

    val startIdx = withoutCodeBlock.indexOf('[')
    val endIdx   = withoutCodeBlock.lastIndexOf(']')

    if (startIdx >= 0 && endIdx > startIdx) {
      withoutCodeBlock.substring(startIdx, endIdx + 1)
    } else {
      withoutCodeBlock
    }
  }
}

object ContextPrecision {

  /**
   * Create a new ContextPrecision metric.
   */
  def apply(llmClient: LLMClient): ContextPrecision = new ContextPrecision(llmClient)
}
