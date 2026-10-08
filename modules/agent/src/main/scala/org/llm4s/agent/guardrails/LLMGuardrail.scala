package org.llm4s.agent.guardrails

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.util.Try

/**
 * Base trait for LLM-based guardrails (the LLM-as-Judge pattern).
 *
 * A judge guardrail asks a language model to rate the content against natural-language criteria and
 * passes the content when the rating reaches a threshold. It suits subjective qualities (tone, factual
 * support, safety in context) that deterministic rules cannot express. For plain word, pattern or length
 * checks prefer the rule-based guardrails (`ProfanityFilter`, `RegexValidator`, `PIIDetector`,
 * `LengthCheck`): they are free, instant and repeatable.
 *
 * **Side:** output only. `LLMGuardrail` extends [[OutputGuardrail]] and not `InputGuardrail`, so it belongs in
 * the output list of a `GuardrailMiddleware`, where it judges the agent's final answer.
 *
 * **What one validation does:** exactly one synchronous `llmClient.complete` call, made on the calling thread
 * even when the content is empty. The request holds two messages:
 *  - a fixed system message that asks for a bare number between 0 and 1;
 *  - a user message made of `evaluationPrompt` and the content between triple quotes.
 *
 * **Cost, latency and privacy:** every validation adds one `llmClient.complete` call to the run. With a plain
 * provider client that is one round trip and its token cost; a wrapping client changes the count -
 * `CachingLLMClient` first embeds the system and user messages (an embedding request that sends the judged
 * content to its embedding provider, hit or miss) and on a cache hit skips only the completion request, and
 * `ReliableClient` can retry a failed request or reject the call with its circuit breaker open. Either way
 * these guardrails do not belong on latency-sensitive paths. The content being judged is sent to whichever
 * provider `llmClient` talks to, including any embedding provider a caching wrapper uses. A cheaper or separate model can serve as judge, which also avoids a model
 * grading its own answer.
 *
 * **Scoring:** the reply is reduced to its digits and decimal points and read as a number; the number is
 * clamped into 0.0 to 1.0, and the content passes when `score >= threshold` (a score equal to the threshold
 * passes). The parsing is lenient, so know its edges:
 *  - `0.9`, `Score: 0.9`, `**0.9**` and `The score is 0.9` all read as 0.9.
 *  - A leading minus is dropped: `-0.5` reads as 0.5.
 *  - A number outside the range is clamped, not rejected: `85`, `85%`, `8/10`, `1e-3` and `0,9` all read
 *    as 1.0, which passes any threshold up to 1.0. Positive whole-number scores on a 0 to 100 scale
 *    therefore pass, but `0` stays 0.0 and fractional scores below the threshold still fail. Keep the
 *    prompt explicit about the scale.
 *  - Several numbers are not rejected as such: their digits run together. `0 or 1` reads as `01`, which is
 *    1.0, and `0.5 or 1` as `0.51`, so such a reply can pass. Only when the remainder is not a valid number -
 *    no digits, a trailing full stop (`0.85.`), or two decimal points (`0.5 or 0.6` becomes `0.50.6`) - is
 *    the reply a parse failure rather than a score.
 *
 * **Failures** are always `Left`; an error never lets content through:
 *  - Below the threshold: a [[org.llm4s.error.ValidationError]] on field `output` whose message names this
 *    guardrail, the score and the threshold to two decimals:
 *    `LLM judge score (0.40) below threshold (0.70) for <name>`. It does not say why the judge scored the
 *    content low. Because of the rounding, a score of 0.69999 against a threshold of 0.7 reads
 *    `0.70 below 0.70`.
 *  - An unreadable reply: a `ValidationError` on field `llm_response` that quotes the reply.
 *  - A failing `llmClient.complete` (network, rate limit, authentication): the client's own error, returned
 *    unchanged.
 *
 * Inside an agent run a failing guardrail blocks the run: `GuardrailMiddleware` reports the name of the first
 * failing guardrail together with the failure messages.
 *
 * **Limits:** `validate` is not pure here, unlike the contract of [[Guardrail]]: it performs a network call, and
 * the same content can score differently between calls (the default temperature of 0.0 reduces this but does
 * not remove it). The content is inserted into the prompt as is, without escaping, so text inside it that
 * addresses the judge can sway the score: treat the result as a probabilistic filter, not a security
 * boundary. `threshold` is not validated: above 1.0, or NaN, nothing passes, and at 0.0 or below everything
 * that parses passes.
 *
 * @note The default `completionOptions` cap the judge's reply at 10 tokens. Override them if your judge model
 *       needs more room than a bare number.
 *
 * @example
 * {{{
 * class MyCustomLLMGuardrail(client: LLMClient) extends LLMGuardrail {
 *   val llmClient = client
 *   val evaluationPrompt = "Rate if this response is helpful (0-1)"
 *   override val threshold = 0.7
 *   val name = "HelpfulnessGuardrail"
 * }
 * }}}
 */
trait LLMGuardrail extends OutputGuardrail {

  /**
   * The client that makes the judge call. It can be the agent's own client or a different one; a separate,
   * cheaper model is a common choice. Every validation makes one blocking `complete` call on it.
   */
  def llmClient: LLMClient

  /**
   * The evaluation criteria in natural language. It is sent after `Evaluation criteria:` and before the content,
   * which goes between triple quotes. The fixed system message already asks for a bare number between 0 and 1;
   * say in the criteria what 0 and 1 mean, because a reply on any other scale is clamped (see the scoring notes
   * on the trait).
   *
   * @example "Rate if this response is professional in tone. Return only a number between 0 and 1."
   */
  def evaluationPrompt: String

  /**
   * The lowest score that passes (a score equal to it passes). The default is 0.7. The value is not validated: above
   * 1.0 or NaN nothing passes, and at 0.0 or below every reply that parses passes. It is not a probability or a
   * confidence: it is a cut-off on whatever number the judge returns.
   */
  def threshold: Double = 0.7

  /**
   * The options of the judge call. The default is temperature 0.0, which makes the judge as repeatable as the
   * model allows, and a maximum of 10 tokens, enough for a bare number. Override to give a judge model more room
   * or to set other options.
   */
  def completionOptions: CompletionOptions = CompletionOptions(
    temperature = 0.0,   // Deterministic for consistent judging
    maxTokens = Some(10) // We only need a short numeric response
  )

  /**
   * Judges `value` with one LLM call and compares the score with `threshold`.
   *
   * @param value the text to judge, typically the agent's final answer
   * @return `Right(value)` unchanged when the score reaches the threshold; otherwise `Left`: a
   *         [[org.llm4s.error.ValidationError]] on field `output` when the score is too low, a `ValidationError` on
   *         field `llm_response` when the reply has no readable score, or the client's own error when the call fails
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
   * Makes the judge call and reads its score; `validate` compares it with the threshold.
   *
   * @param content the text to judge, inserted verbatim between triple quotes after the criteria
   * @return the score clamped into 0.0 to 1.0; a `ValidationError` on field `llm_response` when the reply holds no
   *         single readable number; or the error of the failed `llmClient.complete` call
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
   * Reads a score from the reply: every character other than a digit or `.` is dropped, the rest is read as a
   * number and clamped into 0.0 to 1.0. Several numbers run together (`0 or 1` reads as `01`). A reply whose
   * remainder is empty or not a valid number (`0.85.`, `0.5 or 0.6`) is an error.
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
   * Builds a judge guardrail from a prompt, without defining a class.
   *
   * @param client the client that makes the judge call
   * @param prompt the evaluation criteria, as for `evaluationPrompt`
   * @param passThreshold the lowest score that passes (default 0.7); not validated
   * @param guardrailName the name shown in failure messages (default `CustomLLMGuardrail`)
   * @param guardrailDescription the description; when absent it is `LLM-based validation: <prompt> (threshold: <t>)`
   * @return a guardrail that uses the default completion options
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
