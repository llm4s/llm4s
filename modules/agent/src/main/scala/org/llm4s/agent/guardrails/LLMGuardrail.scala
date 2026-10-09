package org.llm4s.agent.guardrails

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

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
 * **Scoring:** a reply is a score only when the whole reply is one plain decimal number from 0 to 1, optionally
 * labelled `Score:`, and the content passes when the score reaches `threshold` (a score equal to the threshold
 * passes). Any other reply is refused as unreadable and fails the guardrail; a number is never clamped into
 * range, because a clamped value would read as 1.0 for the usual mistakes (a 0 to 100 answer, a fraction, a
 * percentage) and approve the content. The rules, in full:
 *  - Accepted: `0.9`, `.5`, `0`, `1`, `1.0`, with surrounding whitespace or a trailing newline, with markdown
 *    emphasis, quotes, brackets or code-fence backticks before or after the number (`**0.9**`, `"0.9"`,
 *    ` ```0.9``` `), and with a `Score:` label in any case before it (`Score: 0.9`, `score:0.9`,
 *    `**Score:** 0.9`). The wrapping characters before and after the number are read independently and need
 *    not match or balance, so `((0.9` and `0.9 *` are accepted too; they carry no meaning either way.
 *  - Refused, as unreadable: everything else. That includes a number outside 0 to 1 (`85`, `1.5`); a sign,
 *    glued or apart (`-0.5`, `- 1`, `negative 1`, `+0.5`); a percentage, fraction, exponent or any scale, as a
 *    sign or in words (`85%`, `1 %`, `1 percent`, `1 per ten thousand`, `100 bps`, `8/10`, `0.7 out of 1`,
 *    `1e-3`); a decimal comma (`0,9`); a trailing full stop (`0.85.`); any other label or sentence
 *    (`Rating: 0.9`, `The score is 0.9`, `0.9, because ...`); more than one number; and a reply with no number.
 *    The grammar lists what is accepted rather than what is refused, so a scale or sign written in a form no one
 *    anticipated is refused too.
 *  - The edges of the grammar: the label is strict, so emphasis may wrap `Score:` as a whole (`**Score:** 0.9`)
 *    but not split it (`**Score**: 0.9` is refused); a code fence is accepted only bare, so a fence with a
 *    language tag (`text` after the opening backticks, on the line before `0.7`) is refused, the tag being a
 *    word the grammar does not admit; and the number is at most 64 characters, so a reply of thousands of digits
 *    is refused before it is parsed.
 *  - The score is compared at the precision the judge wrote it, and the threshold as the decimal it is written
 *    as: `1.0000000000000001` is out of range, and `0.79999999999999999` does not reach a threshold of 0.8, though
 *    both would round to the bound as a `Double`.
 *  - The judge is not asked to be lenient: the fixed system message asks for only a number between 0 and 1.
 *    Before this rule the reply was reduced to its digits and dots and clamped, so `85`, `85%`, `8/10`, `1e-3`
 *    and `0,9` all read as 1.0 and passed any threshold up to 1.0, and `0.7 out of 1` read as 0.71.
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
   * say in the criteria what 0 and 1 mean, because a reply on any other scale is refused as unreadable and fails
   * the guardrail (see the scoring notes on the trait).
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
      if (LLMGuardrail.reaches(score, threshold)) {
        Right(value)
      } else {
        Left(
          ValidationError.invalid(
            "output",
            s"LLM judge score (${"%.2f".format(score.bigDecimal)}) below threshold (${"%.2f".format(threshold)}) for $name"
          )
        )
      }
    }

  /**
   * Makes the judge call and reads its score; `validate` compares it with the threshold.
   *
   * @param content the text to judge, inserted verbatim between triple quotes after the criteria
   * @return the score, from 0 to 1, as the exact decimal the judge wrote; a `ValidationError` on field
   *         `llm_response` when the reply is not one plain decimal number from 0 to 1 (see the scoring notes on the
   *         trait); or the error of the failed `llmClient.complete` call
   */
  protected def evaluateWithLLM(content: String): Result[BigDecimal] = {
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
   * Reads a score from the reply, or fails: see the scoring notes on the trait for the rules. An unreadable reply
   * fails with a `ValidationError` on field `llm_response` that quotes the reply and says what was expected.
   */
  private def parseScore(response: String): Result[BigDecimal] =
    LLMGuardrail
      .readScore(response)
      .toRight(
        ValidationError.invalid(
          "llm_response",
          s"Could not parse LLM judge score from response: '$response'. Expected a single number from 0 to 1."
        )
      )
}

object LLMGuardrail {

  /**
   * The whole grammar of a score reply, matched against the entire reply, case-insensitively:
   *  - optional leading whitespace and wrapping characters (markdown emphasis, quotes, brackets, code-fence
   *    backticks), but no word, so a code fence with a language tag (`text` after the opening backticks) is
   *    refused;
   *  - an optional label `score:`, wrapped only as a whole (`**Score:** 0.9`, not `**Score**: 0.9`);
   *  - one plain decimal: digits with an optional fraction, or a fraction alone; no sign, exponent, comma or
   *    percent;
   *  - optional trailing wrapping characters and whitespace.
   *
   * The wrapping before and after the number is two independent character runs, not a pair: it need not match
   * or balance (`((0.9` and `0.9 *` are accepted), which is harmless because wrapping carries no meaning.
   *
   * Nothing else may appear, so a sign, unit, scale, denominator or explanation anywhere in the reply, however it
   * is written, makes the reply unreadable rather than leaving a bare number behind to be read. The grammar lists
   * what is accepted, not what is refused: there is no list of scale words or signs to keep complete.
   */
  private val ScoreReply = java.util.regex.Pattern.compile(
    """(?i)\A[\s*_`"'(\[{]*(?:score\s*:[\s*_`"'(\[{]*)?([0-9]+(?:\.[0-9]+)?|\.[0-9]+)[\s*_`"')\]}]*\z"""
  )

  /**
   * The longest number, in characters, read as a score. Parsing a decimal string into a `BigDecimal` costs more
   * than linear time in its length (a million digits take tens of seconds of CPU), and the reply comes from a
   * model, so an over-long number is refused before it is parsed. 64 characters is far more than any genuine
   * score needs - a `Double` prints in at most 24, and the default reply cap is 10 tokens - while still admitting
   * long fractions (`0.123456789`), extra leading zeros (`0000.5`) and the 17-digit values the precision rules
   * above are about.
   */
  private val MaxScoreLength = 64

  /**
   * The score a judge's reply holds, or `None` when the reply is not one plain decimal number from 0 to 1 (see the
   * scoring notes on the trait). The value stays a decimal at full precision, so neither the range check here nor
   * the threshold comparison in `validate` sees a rounded `Double`: `1.0000000000000001` is out of range, and
   * `0.79999999999999999` is below a threshold of 0.8. A number longer than 64 characters is refused unparsed.
   */
  private[guardrails] def readScore(reply: String): Option[BigDecimal] = {
    val matcher = ScoreReply.matcher(reply)
    if (!matcher.matches()) None
    else {
      val number = matcher.group(1)
      if (number.length > MaxScoreLength) None
      else Some(BigDecimal(number)).filter(d => d >= 0 && d <= 1)
    }
  }

  /**
   * Whether `score` reaches `threshold`. The threshold is read as the decimal it is written as (`0.8`, not the
   * nearest binary fraction), so a reply of exactly `0.8` passes it. A NaN threshold passes nothing; an infinite
   * one passes every score (negative) or none (positive).
   */
  private[guardrails] def reaches(score: BigDecimal, threshold: Double): Boolean =
    if (threshold.isNaN) false
    else if (threshold.isInfinite) threshold < 0
    else score >= BigDecimal.decimal(threshold)

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
