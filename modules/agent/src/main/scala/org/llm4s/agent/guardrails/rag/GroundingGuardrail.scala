package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.util.Try

/**
 * Result of a grounding evaluation.
 *
 * @param score Overall grounding score (0.0 to 1.0)
 * @param isGrounded Whether the response passes the threshold
 * @param ungroundedClaims Claims that weren't supported by context (if any)
 * @param explanation Brief explanation of the evaluation
 */
final case class GroundingResult(
  score: Double,
  isGrounded: Boolean,
  ungroundedClaims: Seq[String],
  explanation: String
)

/**
 * LLM-based grounding guardrail for RAG validation.
 *
 * GroundingGuardrail uses an LLM to evaluate whether a response is factually
 * grounded in the retrieved context. This is critical for RAG applications
 * to prevent hallucination and ensure answer quality.
 *
 * **Evaluation process:**
 * 1. Each claim in the response is checked against the retrieved chunks
 * 2. Claims are classified as: supported, not supported, or contradicted
 * 3. An overall grounding score is computed
 * 4. Response passes if score >= threshold
 *
 * **Scoring:**
 * - 1.0: All claims are fully supported by the context
 * - 0.5-0.9: Most claims supported, some unverifiable
 * - 0.0-0.4: Many claims not supported or contradicted
 *
 * Example usage:
 * {{{
 * val guardrail = GroundingGuardrail(llmClient, threshold = 0.8)
 *
 * // Use in RAG pipeline
 * val context = RAGContext(
 *   query = "What causes climate change?",
 *   retrievedChunks = Seq(
 *     "Greenhouse gases trap heat in the atmosphere...",
 *     "Human activities release CO2 and methane..."
 *   )
 * )
 *
 * guardrail.validateWithContext(response, context)
 * }}}
 *
 * @param llmClient The LLM client for evaluation
 * @param threshold Minimum grounding score to pass (default: 0.7)
 * @param onFail Action to take when grounding fails (default: Block)
 * @param strictMode If true, ANY ungrounded claim fails. If false, uses score threshold.
 */
class GroundingGuardrail(
  val llmClient: LLMClient,
  val threshold: Double = 0.7,
  val onFail: GuardrailAction = GuardrailAction.Block,
  val strictMode: Boolean = false
) extends RAGGuardrail {

  val name: String = "GroundingGuardrail"

  override val description: Option[String] = Some(
    s"LLM-based grounding validation (threshold: $threshold, strict: $strictMode)"
  )

  /**
   * Validate output against RAG context for factual grounding.
   */
  override def validateWithContext(output: String, context: RAGContext): Result[String] =
    if (context.retrievedChunks.isEmpty) {
      // No context to ground against - pass through with warning
      onFail match {
        case GuardrailAction.Warn => Right(output)
        case GuardrailAction.Fix  => Right(output) // Can't fix without context
        case GuardrailAction.Block =>
          Left(
            ValidationError.invalid(
              "grounding",
              "Cannot validate grounding: no retrieved chunks provided"
            )
          )
      }
    } else {
      evaluateGrounding(output, context).flatMap { result =>
        if (result.isGrounded) {
          Right(output)
        } else {
          handleFailure(output, result)
        }
      }
    }

  /**
   * Standard validate without context - falls back to pass-through.
   *
   * For full grounding validation, use `validateWithContext`.
   */
  override def validate(value: String): Result[String] =
    Right(value) // Cannot ground without context

  /**
   * Handle grounding failure based on configured action.
   */
  private def handleFailure(output: String, result: GroundingResult): Result[String] =
    onFail match {
      case GuardrailAction.Block =>
        val claims = if (result.ungroundedClaims.nonEmpty) {
          s" Ungrounded claims: ${result.ungroundedClaims.mkString("; ")}"
        } else ""
        Left(
          ValidationError.invalid(
            "grounding",
            s"Response not sufficiently grounded in context. " +
              s"Score: ${"%.2f".format(result.score)} (threshold: ${"%.2f".format(threshold)}).${claims}"
          )
        )

      case GuardrailAction.Warn =>
        // Log would happen here in production
        Right(output)

      case GuardrailAction.Fix =>
        // For grounding, we can't automatically fix - fall back to block
        Left(
          ValidationError.invalid(
            "grounding",
            s"Response not sufficiently grounded. Cannot auto-fix grounding issues. " +
              s"Score: ${"%.2f".format(result.score)}"
          )
        )
    }

  /**
   * Evaluate the grounding of a response against context.
   */
  private def evaluateGrounding(output: String, context: RAGContext): Result[GroundingResult] = {
    val systemPrompt = buildSystemPrompt()
    val userPrompt   = buildUserPrompt(output, context)

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(
      temperature = 0.0,    // Deterministic evaluation
      maxTokens = Some(500) // Room for explanation and ungrounded claims
    )

    for {
      completion <- llmClient.complete(conversation, options)
      result     <- parseGroundingResult(completion.message.content)
    } yield result
  }

  private def buildSystemPrompt(): String =
    """You are a grounding evaluation assistant. Your task is to determine whether
      |a response is factually grounded in the provided context.
      |
      |You MUST respond in this exact format:
      |SCORE: [number between 0.0 and 1.0]
      |GROUNDED: [YES or NO]
      |UNGROUNDED_CLAIMS: [comma-separated list of claims not supported by context, or NONE]
      |EXPLANATION: [brief explanation]
      |
      |Scoring guidelines:
      |- 1.0: Every factual claim is directly supported by the context
      |- 0.7-0.9: Most claims supported, minor details unverifiable
      |- 0.4-0.6: Some claims supported, others not in context
      |- 0.0-0.3: Many claims contradict or are not in context
      |
      |Only evaluate factual claims. Ignore:
      |- General transitional phrases
      |- Stylistic differences
      |- Paraphrasing that preserves meaning""".stripMargin

  private def buildUserPrompt(output: String, context: RAGContext): String = {
    val chunksFormatted = context.retrievedChunks.zipWithIndex
      .map { case (chunk, i) =>
        s"[Chunk ${i + 1}]\n$chunk"
      }
      .mkString("\n\n")

    s"""Original Query: ${context.query}
       |
       |Retrieved Context:
       |$chunksFormatted
       |
       |Response to evaluate:
       |\"\"\"
       |$output
       |\"\"\"
       |
       |Evaluate if this response is grounded in the provided context.""".stripMargin
  }

  /**
   * Parse the structured grounding result from LLM response.
   */
  private def parseGroundingResult(response: String): Result[GroundingResult] = {
    val lines = response.split("\n").map(_.trim).filter(_.nonEmpty)

    val scoreOpt = lines.find(_.startsWith("SCORE:")).flatMap { line =>
      val value = line.stripPrefix("SCORE:").trim.replaceAll("[^0-9.]", "")
      Try(value.toDouble).toOption
    }

    val groundedOpt = lines.find(_.startsWith("GROUNDED:")).map { line =>
      line.stripPrefix("GROUNDED:").trim.toUpperCase.startsWith("YES")
    }

    val ungroundedClaims = lines
      .find(_.startsWith("UNGROUNDED_CLAIMS:"))
      .map { line =>
        val claims = line.stripPrefix("UNGROUNDED_CLAIMS:").trim
        if (claims.toUpperCase == "NONE" || claims.isEmpty) Seq.empty
        else claims.split(",").map(_.trim).filter(_.nonEmpty).toSeq
      }
      .getOrElse(Seq.empty)

    val explanation = lines
      .find(_.startsWith("EXPLANATION:"))
      .map(line => line.stripPrefix("EXPLANATION:").trim)
      .getOrElse("No explanation provided")

    (scoreOpt, groundedOpt) match {
      case (Some(score), Some(grounded)) =>
        // In strict mode, ANY ungrounded claim means failure
        val finalGrounded = if (strictMode) {
          grounded && ungroundedClaims.isEmpty
        } else {
          score >= threshold
        }

        Right(
          GroundingResult(
            score = Math.max(0.0, Math.min(1.0, score)),
            isGrounded = finalGrounded,
            ungroundedClaims = ungroundedClaims,
            explanation = explanation
          )
        )

      case _ =>
        // Fallback: try to extract just a score
        val fallbackScore = response.trim.replaceAll("[^0-9.]", "")
        Try(fallbackScore.toDouble).toOption match {
          case Some(score) if score >= 0.0 && score <= 1.0 =>
            Right(
              GroundingResult(
                score = score,
                isGrounded = score >= threshold,
                ungroundedClaims = Seq.empty,
                explanation = "Score-only response"
              )
            )
          case _ =>
            Left(
              ValidationError.invalid(
                "grounding_parse",
                s"Could not parse grounding evaluation from LLM response: ${response.take(200)}"
              )
            )
        }
    }
  }
}

object GroundingGuardrail {

  /**
   * Create a grounding guardrail with default settings.
   */
  def apply(llmClient: LLMClient): GroundingGuardrail =
    new GroundingGuardrail(llmClient)

  /**
   * Create a grounding guardrail with custom threshold.
   *
   * @param llmClient The LLM client for evaluation
   * @param threshold Minimum score to pass (0.0 to 1.0)
   */
  def apply(llmClient: LLMClient, threshold: Double): GroundingGuardrail =
    new GroundingGuardrail(llmClient, threshold = threshold)

  /**
   * Create a grounding guardrail with custom settings.
   */
  def apply(
    llmClient: LLMClient,
    threshold: Double,
    onFail: GuardrailAction
  ): GroundingGuardrail =
    new GroundingGuardrail(llmClient, threshold = threshold, onFail = onFail)

  /**
   * Preset: Strict mode - any ungrounded claim fails.
   *
   * Use for high-stakes applications where accuracy is critical.
   */
  def strict(llmClient: LLMClient): GroundingGuardrail =
    new GroundingGuardrail(
      llmClient,
      threshold = 0.9,
      strictMode = true,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Balanced mode - good default for most applications.
   *
   * Allows some unverifiable claims if overall grounding is strong.
   */
  def balanced(llmClient: LLMClient): GroundingGuardrail =
    new GroundingGuardrail(
      llmClient,
      threshold = 0.7,
      strictMode = false,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Lenient mode - only fails on clearly ungrounded responses.
   *
   * Use when some creative interpretation is acceptable.
   */
  def lenient(llmClient: LLMClient): GroundingGuardrail =
    new GroundingGuardrail(
      llmClient,
      threshold = 0.5,
      strictMode = false,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Monitoring mode - warns but doesn't block.
   *
   * Use for measuring grounding quality without affecting user experience.
   */
  def monitoring(llmClient: LLMClient): GroundingGuardrail =
    new GroundingGuardrail(
      llmClient,
      threshold = 0.7,
      strictMode = false,
      onFail = GuardrailAction.Warn
    )
}
