package org.llm4s.rag.evaluation.metrics

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.rag.evaluation._
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * Faithfulness metric: measures factual accuracy of the answer
 * relative to the retrieved contexts.
 *
 * Algorithm:
 * 1. Extract factual claims from the generated answer using LLM
 * 2. For each claim, verify if it can be inferred from the contexts
 * 3. Score = Number of supported claims / Total number of claims
 *
 * A score of 1.0 means all claims in the answer can be verified
 * from the retrieved context. Lower scores indicate hallucination.
 *
 * @param llmClient The LLM client for claim extraction and verification
 * @param batchSize Number of claims to verify per LLM call (default: 5)
 *
 * @example
 * {{{
 * val faithfulness = Faithfulness(llmClient)
 * val sample = EvalSample(
 *   question = "What is the capital of France?",
 *   answer = "Paris is the capital of France and has a population of 2.1 million.",
 *   contexts = Seq("Paris is the capital and largest city of France.")
 * )
 * val result = faithfulness.evaluate(sample)
 * // Result: score ~0.5 (capital claim supported, population claim not supported)
 * }}}
 */
class Faithfulness(
  llmClient: LLMClient,
  batchSize: Int = Faithfulness.DEFAULT_BATCH_SIZE
) extends RAGASMetric {

  require(batchSize > 0, "batchSize must be positive")

  override val name: String        = "faithfulness"
  override val description: String = "Measures if answer claims are supported by retrieved context"

  override val requiredInputs: Set[RequiredInput] =
    Set(RequiredInput.Question, RequiredInput.Answer, RequiredInput.Contexts)

  override def evaluate(sample: EvalSample): Result[MetricResult] = {
    if (sample.answer.trim.isEmpty) {
      return Right(
        MetricResult(
          metricName = name,
          score = 1.0,
          details = Map("reason" -> "Empty answer has no claims to verify")
        )
      )
    }

    if (sample.contexts.isEmpty || sample.contexts.forall(_.trim.isEmpty)) {
      return Right(
        MetricResult(
          metricName = name,
          score = 0.0,
          details = Map("reason" -> "No context provided to verify claims against")
        )
      )
    }

    for {
      claims   <- extractClaims(sample.answer)
      verified <- verifyClaims(claims, sample.contexts)
      score = if (claims.isEmpty) 1.0 else verified.count(_.supported).toDouble / claims.size
    } yield MetricResult(
      metricName = name,
      score = score,
      details = Map(
        "totalClaims"        -> claims.size,
        "supportedClaims"    -> verified.count(_.supported),
        "unsupportedClaims"  -> verified.filterNot(_.supported).map(_.claim),
        "claimVerifications" -> verified
      )
    )
  }

  /**
   * Extract factual claims from the answer using LLM.
   */
  private def extractClaims(answer: String): Result[Seq[String]] = {
    val systemPrompt =
      """You are an expert at breaking down text into factual claims.
        |Extract all factual claims from the given text.
        |
        |Rules:
        |- Each claim should be a single, atomic fact
        |- Claims should be self-contained and understandable without context
        |- Exclude opinions, questions, and subjective statements
        |- Exclude hedged statements (e.g., "might be", "could be")
        |
        |Respond with ONLY a JSON array of claim strings.
        |Example: ["Paris is the capital of France", "France is in Europe"]""".stripMargin

    val userPrompt = s"""Extract all factual claims from this text:

\"\"\"
$answer
\"\"\"

Respond with ONLY a JSON array of strings:"""

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(temperature = 0.0, maxTokens = Some(1000))

    for {
      completion <- llmClient.complete(conversation, options)
      claims     <- parseClaims(completion.content)
    } yield claims
  }

  /**
   * Verify claims against the provided contexts.
   */
  private def verifyClaims(claims: Seq[String], contexts: Seq[String]): Result[Seq[ClaimVerification]] =
    if (claims.isEmpty) {
      Right(Seq.empty)
    } else {
      // Verify in batches to manage token limits
      val batches = claims.grouped(batchSize).toSeq

      val results = batches.zipWithIndex.map { case (batch, _) =>
        verifyClaimBatch(batch, contexts)
      }

      // Combine results
      val allVerifications = results.collect { case Right(v) => v }.flatten
      val errors           = results.collect { case Left(e) => e }

      if (errors.nonEmpty && allVerifications.isEmpty) {
        Left(errors.head)
      } else {
        Right(allVerifications)
      }
    }

  /**
   * Verify a batch of claims.
   */
  private def verifyClaimBatch(claims: Seq[String], contexts: Seq[String]): Result[Seq[ClaimVerification]] = {
    val combinedContext = contexts.zipWithIndex
      .map { case (ctx, i) => s"[Context ${i + 1}]: $ctx" }
      .mkString("\n\n")

    val claimsFormatted = claims.zipWithIndex
      .map { case (claim, i) => s"${i + 1}. $claim" }
      .mkString("\n")

    val systemPrompt =
      """You are an expert at verifying factual claims against reference text.
        |For each claim, determine if it can be logically inferred from the provided context.
        |
        |Rules:
        |- A claim is SUPPORTED if the context explicitly states it OR it can be directly inferred
        |- A claim is NOT SUPPORTED if the context contradicts it OR provides no evidence for it
        |- Be strict: partial support is NOT supported
        |
        |Respond with ONLY a JSON array of objects with "claim", "supported" (boolean), and "evidence" (string or null).
        |Example: [{"claim": "Paris is in France", "supported": true, "evidence": "The context states Paris is the capital of France"}]""".stripMargin

    val userPrompt = s"""Context:
$combinedContext

Claims to verify:
$claimsFormatted

For each claim, determine if it is supported by the context. Respond with ONLY JSON:"""

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(temperature = 0.0, maxTokens = Some(2000))

    for {
      completion    <- llmClient.complete(conversation, options)
      verifications <- parseVerifications(completion.content, claims)
    } yield verifications
  }

  /**
   * Parse extracted claims from LLM response.
   */
  private def parseClaims(response: String): Result[Seq[String]] =
    Try {
      val jsonStr = extractJsonArray(response)
      val arr     = ujson.read(jsonStr).arr
      arr.map(_.str).toSeq
    }.toResult.left.map(e => EvaluationError.parseError(s"Failed to parse claims: ${e.message}"))

  /**
   * Parse verification results from LLM response.
   */
  private def parseVerifications(response: String, originalClaims: Seq[String]): Result[Seq[ClaimVerification]] =
    Try {
      val jsonStr = extractJsonArray(response)
      val arr     = ujson.read(jsonStr).arr

      arr.zipWithIndex.map { case (v, idx) =>
        val obj       = v.obj
        val claim     = obj.get("claim").map(_.str).getOrElse(originalClaims.lift(idx).getOrElse(""))
        val supported = obj.get("supported").exists(_.bool)
        val evidence  = obj.get("evidence").flatMap(e => if (e.isNull) None else Some(e.str))
        ClaimVerification(claim, supported, evidence)
      }.toSeq
    }.toResult.left.map(e => EvaluationError.parseError(s"Failed to parse verifications: ${e.message}"))

  /**
   * Extract JSON array from potentially markdown-wrapped response.
   */
  private def extractJsonArray(response: String): String = {
    val trimmed = response.trim

    // Handle markdown code blocks
    val withoutCodeBlock = if (trimmed.startsWith("```")) {
      val lines = trimmed.split("\n")
      val start = 1 // Skip first line (```json or ```)
      val end   = lines.lastIndexWhere(_.trim == "```")
      if (end > start) {
        lines.slice(start, end).mkString("\n")
      } else {
        trimmed.stripPrefix("```json").stripPrefix("```").stripSuffix("```")
      }
    } else {
      trimmed
    }

    // Find JSON array bounds
    val startIdx = withoutCodeBlock.indexOf('[')
    val endIdx   = withoutCodeBlock.lastIndexOf(']')

    if (startIdx >= 0 && endIdx > startIdx) {
      withoutCodeBlock.substring(startIdx, endIdx + 1)
    } else {
      withoutCodeBlock
    }
  }
}

object Faithfulness {

  /**
   * Default batch size for claim verification.
   */
  val DEFAULT_BATCH_SIZE: Int = 5

  /**
   * Create a new Faithfulness metric.
   */
  def apply(
    llmClient: LLMClient,
    batchSize: Int = DEFAULT_BATCH_SIZE
  ): Faithfulness = new Faithfulness(llmClient, batchSize)
}
