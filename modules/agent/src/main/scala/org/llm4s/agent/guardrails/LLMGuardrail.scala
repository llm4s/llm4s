package org.llm4s.agent.guardrails

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.util.Try

/**
 * Base trait for LLM-based guardrails (LLM-as-Judge pattern).
 *
 * LLM guardrails use a language model to evaluate content against
 * natural language criteria. This enables validation of subjective
 * qualities like tone, factual accuracy, and safety that cannot be
 * easily validated with deterministic rules.
 *
 * Unlike function-based guardrails, LLM guardrails:
 * - Use natural language evaluation prompts
 * - Return a score between 0.0 and 1.0
 * - Pass if score >= threshold
 * - Can use a separate model for judging (to avoid self-evaluation bias)
 *
 * @note LLM guardrails have higher latency than function-based guardrails
 *       due to the LLM API call. Consider using them only when deterministic
 *       validation is insufficient.
 *
 * @example
 * {{{
 * class MyCustomLLMGuardrail(client: LLMClient) extends LLMGuardrail {
 *   val llmClient = client
 *   val evaluationPrompt = "Rate if this response is helpful (0-1)"
 *   val threshold = 0.7
 *   val name = "HelpfulnessGuardrail"
 * }
 * }}}
 */
trait LLMGuardrail extends OutputGuardrail {

  /**
   * The LLM client to use for evaluation.
   * Can be the same client used by the agent or a different one.
   */
  def llmClient: LLMClient

  /**
   * Natural language prompt describing the evaluation criteria.
   *
   * The prompt should instruct the model to return a score between 0 and 1.
   * The content being evaluated will be provided separately.
   *
   * @example "Rate if this response is professional in tone. Return only a number between 0 and 1."
   */
  def evaluationPrompt: String

  /**
   * Minimum score required to pass validation (0.0 to 1.0).
   * Default is 0.7 (70% confidence).
   */
  def threshold: Double = 0.7

  /**
   * Optional completion options for the judge LLM call.
   * Override to customize temperature, max tokens, etc.
   */
  def completionOptions: CompletionOptions = CompletionOptions(
    temperature = 0.0,   // Deterministic for consistent judging
    maxTokens = Some(10) // We only need a short numeric response
  )

  /**
   * Validate content using the LLM as a judge.
   *
   * The implementation:
   * 1. Constructs a prompt with evaluation criteria and content
   * 2. Calls the LLM to get a score
   * 3. Parses the score and compares to threshold
   * 4. Returns success if score >= threshold, error otherwise
   */
  override def validate(value: String): Result[String] =
    evaluateWithLLM(value).flatMap { score =>
      if (score >= threshold) {
        Right(value)
      } else {
        Left(
          ValidationError.invalid(
            "output",
            s"LLM judge score (${"%.2f".format(score)}) below threshold (${"%.2f".format(threshold)}) for $name"
          )
        )
      }
    }

  /**
   * Evaluate content with the LLM and return a score.
   *
   * @param content The content to evaluate
   * @return Score between 0.0 and 1.0, or error
   */
  protected def evaluateWithLLM(content: String): Result[Double] = {
    val systemPrompt =
      """You are an evaluation assistant. Your task is to rate content based on specific criteria.
        |You MUST respond with ONLY a single number between 0 and 1 (e.g., 0.85).
        |Do not include any other text, explanation, or formatting.
        |0 = completely fails the criteria
        |1 = perfectly meets the criteria""".stripMargin

    val userPrompt = s"""Evaluation criteria: $evaluationPrompt

Content to evaluate:
\"\"\"
$content
\"\"\"

Score (0-1):"""

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    for {
      completion <- llmClient.complete(conversation, completionOptions)
      score      <- parseScore(completion.message.content)
    } yield score
  }

  /**
   * Parse a numeric score from the LLM response.
   */
  private def parseScore(response: String): Result[Double] = {
    val cleaned = response.trim.replaceAll("[^0-9.]", "")

    Try(cleaned.toDouble).toOption match {
      case Some(score) if score >= 0.0 && score <= 1.0 =>
        Right(score)
      case Some(score) =>
        // Clamp to valid range
        Right(Math.max(0.0, Math.min(1.0, score)))
      case None =>
        Left(
          ValidationError.invalid(
            "llm_response",
            s"Could not parse LLM judge score from response: '$response'"
          )
        )
    }
  }
}

object LLMGuardrail {

  /**
   * Create a custom LLM guardrail with the specified parameters.
   *
   * @param client The LLM client to use for evaluation
   * @param prompt The evaluation prompt describing criteria
   * @param passThreshold Minimum score to pass (default: 0.7)
   * @param guardrailName Name for logging and error messages
   * @param guardrailDescription Optional description
   */
  def apply(
    client: LLMClient,
    prompt: String,
    passThreshold: Double = 0.7,
    guardrailName: String = "CustomLLMGuardrail",
    guardrailDescription: Option[String] = None
  ): LLMGuardrail = new LLMGuardrail {
    val llmClient: LLMClient       = client
    val evaluationPrompt: String   = prompt
    override val threshold: Double = passThreshold
    val name: String               = guardrailName
    override val description: Option[String] = guardrailDescription.orElse(
      Some(s"LLM-based validation: $prompt (threshold: $passThreshold)")
    )
  }
}
