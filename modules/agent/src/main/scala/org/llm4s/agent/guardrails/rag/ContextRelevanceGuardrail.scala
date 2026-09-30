package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.util.Try

/**
 * Result of context relevance evaluation.
 *
 * @param overallScore Overall relevance score (0.0 to 1.0)
 * @param chunkScores Individual relevance scores for each chunk
 * @param relevantChunkCount Number of chunks meeting the threshold
 * @param explanation Brief explanation of the evaluation
 */
final case class ContextRelevanceResult(
  overallScore: Double,
  chunkScores: Seq[Double],
  relevantChunkCount: Int,
  explanation: String
) {

  /**
   * Get the indices of relevant chunks.
   */
  def relevantChunkIndices(threshold: Double): Seq[Int] =
    chunkScores.zipWithIndex.filter(_._1 >= threshold).map(_._2)

  /**
   * Get the indices of irrelevant chunks.
   */
  def irrelevantChunkIndices(threshold: Double): Seq[Int] =
    chunkScores.zipWithIndex.filter(_._1 < threshold).map(_._2)
}

/**
 * LLM-based guardrail to validate that retrieved chunks are relevant to the query.
 *
 * ContextRelevanceGuardrail uses an LLM to evaluate whether the chunks retrieved
 * from a vector store are actually relevant to the user's original query. This is
 * critical for RAG quality - retrieving irrelevant chunks leads to poor answers.
 *
 * **Evaluation process:**
 * 1. Each chunk is evaluated for relevance to the query
 * 2. Relevance is scored from 0.0 (completely irrelevant) to 1.0 (highly relevant)
 * 3. Overall score is computed as average chunk relevance
 * 4. Context passes if enough chunks are relevant
 *
 * **Use cases:**
 * - Detect retrieval failures before generating responses
 * - Filter out irrelevant chunks before sending to LLM
 * - Measure and monitor retrieval quality
 *
 * Example usage:
 * {{{
 * val guardrail = ContextRelevanceGuardrail(llmClient, threshold = 0.6)
 *
 * val context = RAGContext(
 *   query = "What are the symptoms of diabetes?",
 *   retrievedChunks = Seq(
 *     "Diabetes symptoms include increased thirst...",
 *     "The history of the Roman Empire..." // Irrelevant
 *   )
 * )
 *
 * // Validate that retrieved context is relevant
 * guardrail.validateWithContext(response, context)
 * }}}
 *
 * @param llmClient The LLM client for evaluation
 * @param threshold Minimum relevance score for a chunk to be considered relevant (default: 0.5)
 * @param minRelevantRatio Minimum ratio of relevant chunks required (default: 0.5)
 * @param onFail Action to take when relevance is insufficient (default: Block)
 */
class ContextRelevanceGuardrail(
  val llmClient: LLMClient,
  val threshold: Double = 0.5,
  val minRelevantRatio: Double = 0.5,
  val onFail: GuardrailAction = GuardrailAction.Block
) extends RAGGuardrail {

  val name: String = "ContextRelevanceGuardrail"

  override val description: Option[String] = Some(
    s"LLM-based context relevance validation (threshold: $threshold, min ratio: $minRelevantRatio)"
  )

  /**
   * Validate that retrieved context is relevant to the query.
   */
  override def validateWithContext(output: String, context: RAGContext): Result[String] = {
    val _ = output // Output not used - we're validating context, not response

    if (context.retrievedChunks.isEmpty) {
      onFail match {
        case GuardrailAction.Warn => Right(output)
        case GuardrailAction.Fix  => Right(output)
        case GuardrailAction.Block =>
          Left(
            ValidationError.invalid(
              "context_relevance",
              "Cannot validate context relevance: no retrieved chunks provided"
            )
          )
      }
    } else {
      evaluateRelevance(context).flatMap { result =>
        val relevantRatio = result.relevantChunkCount.toDouble / context.retrievedChunks.size
        if (relevantRatio >= minRelevantRatio) {
          Right(output)
        } else {
          handleFailure(output, result, relevantRatio)
        }
      }
    }
  }

  /**
   * Standard validate without context.
   */
  override def validate(value: String): Result[String] =
    Right(value) // Cannot evaluate relevance without context

  /**
   * Handle relevance failure based on configured action.
   */
  private def handleFailure(
    output: String,
    result: ContextRelevanceResult,
    relevantRatio: Double
  ): Result[String] =
    onFail match {
      case GuardrailAction.Block =>
        Left(
          ValidationError.invalid(
            "context_relevance",
            s"Retrieved context not sufficiently relevant to query. " +
              s"Overall score: ${"%.2f".format(result.overallScore)}, " +
              s"Relevant chunks: ${result.relevantChunkCount}/${result.chunkScores.size} " +
              s"(${"%.0f".format(relevantRatio * 100)}% < ${"%.0f".format(minRelevantRatio * 100)}% required)"
          )
        )

      case GuardrailAction.Warn =>
        Right(output)

      case GuardrailAction.Fix =>
        // For context relevance, we can't auto-fix - fall back to warn
        Right(output)
    }

  /**
   * Evaluate the relevance of each chunk to the query.
   */
  private def evaluateRelevance(context: RAGContext): Result[ContextRelevanceResult] = {
    val systemPrompt = buildSystemPrompt()
    val userPrompt   = buildUserPrompt(context)

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(
      temperature = 0.0,
      maxTokens = Some(300)
    )

    for {
      completion <- llmClient.complete(conversation, options)
      result     <- parseRelevanceResult(completion.message.content, context.retrievedChunks.size)
    } yield result
  }

  private def buildSystemPrompt(): String =
    """You are a relevance evaluation assistant. Your task is to evaluate whether
      |retrieved text chunks are relevant to a user's query.
      |
      |You MUST respond in this exact format:
      |OVERALL_SCORE: [number between 0.0 and 1.0]
      |CHUNK_SCORES: [comma-separated list of scores, one per chunk]
      |EXPLANATION: [brief explanation]
      |
      |Scoring guidelines for each chunk:
      |- 1.0: Directly and fully addresses the query
      |- 0.7-0.9: Highly relevant, contains useful information
      |- 0.4-0.6: Somewhat relevant, partially addresses query
      |- 0.1-0.3: Tangentially related but not useful
      |- 0.0: Completely irrelevant to the query""".stripMargin

  private def buildUserPrompt(context: RAGContext): String = {
    val chunksFormatted = context.retrievedChunks.zipWithIndex
      .map { case (chunk, i) =>
        s"[Chunk ${i + 1}]\n${chunk.take(500)}${if (chunk.length > 500) "..." else ""}"
      }
      .mkString("\n\n")

    s"""User Query: ${context.query}
       |
       |Retrieved Chunks:
       |$chunksFormatted
       |
       |Evaluate the relevance of each chunk to the user's query.""".stripMargin
  }

  /**
   * Parse the structured relevance result from LLM response.
   */
  private def parseRelevanceResult(
    response: String,
    chunkCount: Int
  ): Result[ContextRelevanceResult] = {
    val lines = response.split("\n").map(_.trim).filter(_.nonEmpty)

    val overallScoreOpt = lines.find(_.startsWith("OVERALL_SCORE:")).flatMap { line =>
      val value = line.stripPrefix("OVERALL_SCORE:").trim.replaceAll("[^0-9.]", "")
      Try(value.toDouble).toOption.map(s => Math.max(0.0, Math.min(1.0, s)))
    }

    val chunkScores = lines
      .find(_.startsWith("CHUNK_SCORES:"))
      .map { line =>
        val scoresStr = line.stripPrefix("CHUNK_SCORES:").trim
        scoresStr
          .split(",")
          .map(_.trim.replaceAll("[^0-9.]", ""))
          .flatMap(s => Try(s.toDouble).toOption)
          .map(s => Math.max(0.0, Math.min(1.0, s)))
          .toSeq
      }
      .getOrElse(Seq.empty)

    val explanation = lines
      .find(_.startsWith("EXPLANATION:"))
      .map(_.stripPrefix("EXPLANATION:").trim)
      .getOrElse("No explanation provided")

    // Ensure we have scores for all chunks (pad or truncate)
    val normalizedScores = if (chunkScores.size >= chunkCount) {
      chunkScores.take(chunkCount)
    } else {
      // Pad with zeros for missing chunks
      chunkScores ++ Seq.fill(chunkCount - chunkScores.size)(0.0)
    }

    val relevantCount = normalizedScores.count(_ >= threshold)

    overallScoreOpt match {
      case Some(overallScore) =>
        Right(
          ContextRelevanceResult(
            overallScore = overallScore,
            chunkScores = normalizedScores,
            relevantChunkCount = relevantCount,
            explanation = explanation
          )
        )

      case None =>
        // Fallback: compute overall from chunk scores
        if (normalizedScores.nonEmpty) {
          val computedOverall = normalizedScores.sum / normalizedScores.size
          Right(
            ContextRelevanceResult(
              overallScore = computedOverall,
              chunkScores = normalizedScores,
              relevantChunkCount = relevantCount,
              explanation = explanation
            )
          )
        } else {
          // Last fallback: try to extract a single score
          val fallbackScore = response.trim.replaceAll("[^0-9.]", "")
          Try(fallbackScore.toDouble).toOption match {
            case Some(score) if score >= 0.0 && score <= 1.0 =>
              val scores = Seq.fill(chunkCount)(score)
              Right(
                ContextRelevanceResult(
                  overallScore = score,
                  chunkScores = scores,
                  relevantChunkCount = scores.count(_ >= threshold),
                  explanation = "Score-only response"
                )
              )
            case _ =>
              Left(
                ValidationError.invalid(
                  "context_relevance_parse",
                  s"Could not parse context relevance from LLM response: ${response.take(200)}"
                )
              )
          }
        }
    }
  }
}

object ContextRelevanceGuardrail {

  /**
   * Create a context relevance guardrail with default settings.
   */
  def apply(llmClient: LLMClient): ContextRelevanceGuardrail =
    new ContextRelevanceGuardrail(llmClient)

  /**
   * Create a context relevance guardrail with custom threshold.
   */
  def apply(llmClient: LLMClient, threshold: Double): ContextRelevanceGuardrail =
    new ContextRelevanceGuardrail(llmClient, threshold = threshold)

  /**
   * Create a context relevance guardrail with custom settings.
   */
  def apply(
    llmClient: LLMClient,
    threshold: Double,
    minRelevantRatio: Double
  ): ContextRelevanceGuardrail =
    new ContextRelevanceGuardrail(llmClient, threshold = threshold, minRelevantRatio = minRelevantRatio)

  /**
   * Preset: Strict mode - requires high relevance for most chunks.
   */
  def strict(llmClient: LLMClient): ContextRelevanceGuardrail =
    new ContextRelevanceGuardrail(
      llmClient,
      threshold = 0.7,
      minRelevantRatio = 0.75,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Balanced mode - good default for most applications.
   */
  def balanced(llmClient: LLMClient): ContextRelevanceGuardrail =
    new ContextRelevanceGuardrail(
      llmClient,
      threshold = 0.5,
      minRelevantRatio = 0.5,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Lenient mode - only fails when most chunks are irrelevant.
   */
  def lenient(llmClient: LLMClient): ContextRelevanceGuardrail =
    new ContextRelevanceGuardrail(
      llmClient,
      threshold = 0.3,
      minRelevantRatio = 0.25,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Monitoring mode - warns but doesn't block.
   */
  def monitoring(llmClient: LLMClient): ContextRelevanceGuardrail =
    new ContextRelevanceGuardrail(
      llmClient,
      threshold = 0.5,
      minRelevantRatio = 0.5,
      onFail = GuardrailAction.Warn
    )
}
