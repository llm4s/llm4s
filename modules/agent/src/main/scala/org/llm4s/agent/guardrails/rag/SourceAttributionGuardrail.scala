package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.util.Try

/**
 * Result of source attribution evaluation.
 *
 * @param hasAttributions Whether the response contains any source citations
 * @param attributionScore Score for quality of attributions (0.0 to 1.0)
 * @param citedSources List of sources that were cited in the response
 * @param uncitedClaims Claims that should have been cited but weren't
 * @param explanation Brief explanation of the evaluation
 */
final case class SourceAttributionResult(
  hasAttributions: Boolean,
  attributionScore: Double,
  citedSources: Seq[String],
  uncitedClaims: Seq[String],
  explanation: String
)

/**
 * LLM-based guardrail to validate that responses properly cite their sources.
 *
 * SourceAttributionGuardrail ensures that RAG responses include proper citations
 * to the source documents from which information was derived. This is important
 * for transparency, verifiability, and trust.
 *
 * **Evaluation criteria:**
 * - Does the response cite sources for factual claims?
 * - Are the citations accurate (pointing to the right chunks)?
 * - Are all major claims properly attributed?
 *
 * **Use cases:**
 * - Ensure transparency in RAG responses
 * - Enable users to verify information
 * - Comply with requirements for attributing sources
 * - Detect when responses fail to cite available sources
 *
 * Example usage:
 * {{{
 * val guardrail = SourceAttributionGuardrail(llmClient)
 *
 * val context = RAGContext.withSources(
 *   query = "What causes climate change?",
 *   chunks = Seq("Human activities release greenhouse gases..."),
 *   sources = Seq("IPCC Report 2023.pdf")
 * )
 *
 * // Response should cite sources
 * val response = "According to the IPCC Report, human activities release greenhouse gases..."
 * guardrail.validateWithContext(response, context)
 * }}}
 *
 * @param llmClient The LLM client for evaluation
 * @param requireAttributions Whether citations are required (default: true)
 * @param minAttributionScore Minimum attribution quality score (default: 0.5)
 * @param onFail Action to take when attribution is insufficient (default: Block)
 */
class SourceAttributionGuardrail(
  val llmClient: LLMClient,
  val requireAttributions: Boolean = true,
  val minAttributionScore: Double = 0.5,
  val onFail: GuardrailAction = GuardrailAction.Block
) extends RAGGuardrail {

  val name: String = "SourceAttributionGuardrail"

  override val description: Option[String] = Some(
    s"LLM-based source attribution validation (required: $requireAttributions, min score: $minAttributionScore)"
  )

  /**
   * Validate that response properly attributes sources.
   */
  override def validateWithContext(output: String, context: RAGContext): Result[String] =
    if (context.retrievedChunks.isEmpty) {
      // No sources to attribute - pass through
      Right(output)
    } else if (!requireAttributions) {
      // Attributions not required - pass through
      Right(output)
    } else {
      evaluateAttribution(output, context).flatMap { result =>
        if (result.attributionScore >= minAttributionScore) {
          Right(output)
        } else {
          handleFailure(output, result)
        }
      }
    }

  /**
   * Standard validate without context.
   */
  override def validate(value: String): Result[String] =
    Right(value) // Cannot evaluate attribution without context

  /**
   * Transform response to add citations if in Fix mode.
   */
  override def transformWithContext(output: String, context: RAGContext): String = {
    val _ = context // Used for documentation - actual fix would need LLM call
    output
  }

  /**
   * Handle attribution failure based on configured action.
   */
  private def handleFailure(output: String, result: SourceAttributionResult): Result[String] =
    onFail match {
      case GuardrailAction.Block =>
        val uncitedInfo = if (result.uncitedClaims.nonEmpty) {
          s" Uncited claims: ${result.uncitedClaims.take(3).mkString("; ")}${
              if (result.uncitedClaims.size > 3) "..." else ""
            }"
        } else ""
        Left(
          ValidationError.invalid(
            "source_attribution",
            s"Response does not properly attribute sources. " +
              s"Attribution score: ${"%.2f".format(result.attributionScore)} " +
              s"(required: ${"%.2f".format(minAttributionScore)}).${uncitedInfo}"
          )
        )

      case GuardrailAction.Warn =>
        Right(output)

      case GuardrailAction.Fix =>
        // For source attribution, we could potentially add citations
        // but that would require another LLM call - fall back to warn
        Right(output)
    }

  /**
   * Evaluate the source attribution in the response.
   */
  private def evaluateAttribution(output: String, context: RAGContext): Result[SourceAttributionResult] = {
    val systemPrompt = buildSystemPrompt()
    val userPrompt   = buildUserPrompt(output, context)

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(
      temperature = 0.0,
      maxTokens = Some(400)
    )

    for {
      completion <- llmClient.complete(conversation, options)
      result     <- parseAttributionResult(completion.message.content)
    } yield result
  }

  private def buildSystemPrompt(): String =
    """You are a source attribution evaluation assistant. Your task is to evaluate
      |whether a response properly cites and attributes information to source documents.
      |
      |You MUST respond in this exact format:
      |HAS_ATTRIBUTIONS: [YES or NO]
      |ATTRIBUTION_SCORE: [number between 0.0 and 1.0]
      |CITED_SOURCES: [comma-separated list of cited source names/references, or NONE]
      |UNCITED_CLAIMS: [comma-separated list of claims that should be cited but aren't, or NONE]
      |EXPLANATION: [brief explanation]
      |
      |Scoring guidelines:
      |- 1.0: All factual claims are properly cited with clear source references
      |- 0.7-0.9: Most claims cited, minor attribution gaps
      |- 0.4-0.6: Some citations present but many claims uncited
      |- 0.1-0.3: Few or vague citations
      |- 0.0: No source attribution at all
      |
      |Consider citations valid if they:
      |- Reference specific documents, pages, or sections
      |- Use phrases like "according to...", "the source states...", "[Source: ...]"
      |- Clearly connect claims to source material""".stripMargin

  private def buildUserPrompt(output: String, context: RAGContext): String = {
    val sourcesInfo = if (context.sources.nonEmpty) {
      val sourceList = context.sources.zipWithIndex
        .map { case (src, i) =>
          s"  ${i + 1}. $src"
        }
        .mkString("\n")
      s"Available sources:\n$sourceList"
    } else {
      s"Available sources: ${context.retrievedChunks.size} unnamed chunks"
    }

    val chunksPreview = context.retrievedChunks.zipWithIndex
      .map { case (chunk, i) =>
        s"[Chunk ${i + 1}]: ${chunk.take(200)}${if (chunk.length > 200) "..." else ""}"
      }
      .mkString("\n")

    s"""$sourcesInfo
       |
       |Source content preview:
       |$chunksPreview
       |
       |Response to evaluate:
       |\"\"\"
       |$output
       |\"\"\"
       |
       |Evaluate whether this response properly attributes information to the sources.""".stripMargin
  }

  /**
   * Parse the structured attribution result from LLM response.
   */
  private def parseAttributionResult(response: String): Result[SourceAttributionResult] = {
    val lines = response.split("\n").map(_.trim).filter(_.nonEmpty)

    val hasAttributions = lines.find(_.startsWith("HAS_ATTRIBUTIONS:")).exists { line =>
      line.stripPrefix("HAS_ATTRIBUTIONS:").trim.toUpperCase.startsWith("YES")
    }

    val attributionScore = lines
      .find(_.startsWith("ATTRIBUTION_SCORE:"))
      .flatMap { line =>
        val value = line.stripPrefix("ATTRIBUTION_SCORE:").trim.replaceAll("[^0-9.]", "")
        Try(value.toDouble).toOption.map(s => Math.max(0.0, Math.min(1.0, s)))
      }
      .getOrElse(if (hasAttributions) 0.5 else 0.0)

    val citedSources = lines
      .find(_.startsWith("CITED_SOURCES:"))
      .map { line =>
        val sources = line.stripPrefix("CITED_SOURCES:").trim
        if (sources.toUpperCase == "NONE" || sources.isEmpty) Seq.empty
        else sources.split(",").map(_.trim).filter(_.nonEmpty).toSeq
      }
      .getOrElse(Seq.empty)

    val uncitedClaims = lines
      .find(_.startsWith("UNCITED_CLAIMS:"))
      .map { line =>
        val claims = line.stripPrefix("UNCITED_CLAIMS:").trim
        if (claims.toUpperCase == "NONE" || claims.isEmpty) Seq.empty
        else claims.split(",").map(_.trim).filter(_.nonEmpty).toSeq
      }
      .getOrElse(Seq.empty)

    val explanation = lines
      .find(_.startsWith("EXPLANATION:"))
      .map(_.stripPrefix("EXPLANATION:").trim)
      .getOrElse("No explanation provided")

    Right(
      SourceAttributionResult(
        hasAttributions = hasAttributions,
        attributionScore = attributionScore,
        citedSources = citedSources,
        uncitedClaims = uncitedClaims,
        explanation = explanation
      )
    )
  }
}

object SourceAttributionGuardrail {

  /**
   * Create a source attribution guardrail with default settings.
   */
  def apply(llmClient: LLMClient): SourceAttributionGuardrail =
    new SourceAttributionGuardrail(llmClient)

  /**
   * Create a source attribution guardrail with custom score threshold.
   */
  def apply(llmClient: LLMClient, minAttributionScore: Double): SourceAttributionGuardrail =
    new SourceAttributionGuardrail(llmClient, minAttributionScore = minAttributionScore)

  /**
   * Preset: Strict mode - requires high-quality attributions.
   */
  def strict(llmClient: LLMClient): SourceAttributionGuardrail =
    new SourceAttributionGuardrail(
      llmClient,
      requireAttributions = true,
      minAttributionScore = 0.8,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Balanced mode - good default requiring some attribution.
   */
  def balanced(llmClient: LLMClient): SourceAttributionGuardrail =
    new SourceAttributionGuardrail(
      llmClient,
      requireAttributions = true,
      minAttributionScore = 0.5,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Lenient mode - only requires basic attribution.
   */
  def lenient(llmClient: LLMClient): SourceAttributionGuardrail =
    new SourceAttributionGuardrail(
      llmClient,
      requireAttributions = true,
      minAttributionScore = 0.3,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Optional mode - attributions encouraged but not required.
   */
  def optional(llmClient: LLMClient): SourceAttributionGuardrail =
    new SourceAttributionGuardrail(
      llmClient,
      requireAttributions = false,
      onFail = GuardrailAction.Warn
    )

  /**
   * Preset: Monitoring mode - tracks attribution quality without blocking.
   */
  def monitoring(llmClient: LLMClient): SourceAttributionGuardrail =
    new SourceAttributionGuardrail(
      llmClient,
      requireAttributions = true,
      minAttributionScore = 0.5,
      onFail = GuardrailAction.Warn
    )
}
