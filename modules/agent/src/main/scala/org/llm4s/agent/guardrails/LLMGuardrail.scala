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
 * **Cost, latency and privacy:** every validation adds one LLM round trip and its token cost to the run, so
 * these guardrails do not belong on latency-sensitive paths. The content being judged is sent to whichever
 * provider `llmClient` talks to. A cheaper or separate model can serve as judge, which also avoids a model
 * grading its own answer.
 *
 * **Scoring:** a reply is a score only when it holds exactly one plain decimal number from 0 to 1, and the content
 * passes when `score >= threshold` (a score equal to the threshold passes). Any other reply is refused as
 * unreadable and fails the guardrail; a number is never clamped into range, because a clamped value would read
 * as 1.0 for the usual mistakes (a 0 to 100 answer, a fraction, a percentage) and approve the content. The
 * rules, in full:
 *  - Accepted: `0.9`, `.5`, `0`, `1`, `1.0`, with surrounding whitespace or a trailing newline, and with
 *    markdown emphasis, quotes, brackets or a code fence around it (`**0.9**`, `"0.9"`, ` ```0.9``` `); a
 *    trailing comma, semicolon or colon on the number is ignored. A label or sentence around one number is fine:
 *    `Score: 0.9` and `The score is 0.9` read as 0.9.
 *  - Refused, as unreadable: a number outside 0 to 1 (`85`, `100`, `1.5`, `-0.2`); a sign (`-0.5`, `+0.5`); a
 *    percentage, fraction or exponent (`85%`, `8/10`, `1e-3`), and any reply that names a percentage even apart
 *    from the number (`1 %`, `1 percent`, `1 per cent`); a decimal comma (`0,9`); a trailing full stop
 *    (`0.85.`); a label glued to the number (`Score:0.9`); digits inside a word (`gpt4`); and more than one
 *    number anywhere in the reply (`0.5 or 0.6`, `0.7 out of 1`). A reply with no ASCII digit at all (`NaN`, an
 *    empty reply, a refusal in words) is refused too.
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
   * @return the score, from 0.0 to 1.0; a `ValidationError` on field `llm_response` when the reply is not exactly one
   *         plain decimal number from 0 to 1 (see the scoring notes on the trait); or the error of the failed
   *         `llmClient.complete` call
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
   * Reads a score from the reply, or fails: see the scoring notes on the trait for the rules. An unreadable reply
   * fails with a `ValidationError` on field `llm_response` that quotes the reply and says what was expected.
   */
  private def parseScore(response: String): Result[Double] =
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

  /** One plain decimal: digits with an optional fraction, or a fraction alone. No sign, exponent, comma or percent. */
  private val PlainDecimal = java.util.regex.Pattern.compile("""^(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)$""")

  /** Markdown emphasis, quotes, brackets and code-fence backticks that may wrap a number. */
  private val Wrapping = "*_`\"'()[]{}"

  /** Punctuation that may follow a number when it ends a clause. A full stop is deliberately not here. */
  private val ClauseEnd = ",;:"

  private def hasDigit(token: String): Boolean = token.exists(c => c >= '0' && c <= '9')

  /** Percent and per-mille signs, ASCII and fullwidth, that rescale a number wherever they stand in the reply. */
  private val PercentSigns = "%\u2030\uFF05\uFE6A"

  /** Words that, after `per`, name a rescaling: `per cent`, `per mille`, `per mil`, `per hundred`, `per thousand`. */
  private val PerScales = Seq("cent", "mil", "hundred", "thousand")

  /**
   * Whether the reply names a percentage or per-mille scale anywhere, even apart from the number: a percent or
   * per-mille sign (`1 %`, `1 ‰`) or a word such as `percent`, `percentage`, `percentile`, `pct`, `permille`,
   * `per cent`, `per mille` or `per thousand` (`1 percent`, `1 per mille`). A number standing next to one of these
   * is on another scale, so the reply is refused rather than read as that number.
   */
  private def namesPercentage(reply: String): Boolean =
    reply.exists(isIn(PercentSigns, _)) || {
      val words = reply.toLowerCase(java.util.Locale.ROOT).split("[^a-z]+").toList.filter(_.nonEmpty)
      words.exists(w => w.startsWith("percent") || w.startsWith("permil") || w == "pct") ||
      words.zip(words.drop(1)).exists { case (a, b) => a == "per" && PerScales.exists(b.startsWith) }
    }

  private def isIn(chars: String, c: Char): Boolean = chars.indexOf(c.toInt) >= 0

  private def trimWrapping(token: String): String =
    token.dropWhile(isIn(Wrapping, _)).reverse.dropWhile(c => isIn(Wrapping, c) || isIn(ClauseEnd, c)).reverse

  /**
   * The score a judge's reply holds, or `None` when the reply is not exactly one plain decimal number from 0 to 1.
   *
   * Every whitespace-separated word with an ASCII digit in it counts as a number: there must be exactly one, and
   * once its wrapping and trailing clause punctuation are trimmed it must be a plain decimal within 0 to 1. A value
   * outside the range is refused, never clamped. A reply that names a percentage anywhere (`1 %`, `1 percent`) is
   * refused too, as is one that names a per-mille scale (`1 per mille`), because the marker need not
   * touch the number.
   */
  private[guardrails] def readScore(reply: String): Option[Double] =
    reply.split("\\s+").toList.filter(hasDigit) match {
      case word :: Nil if !namesPercentage(reply) =>
        val number = trimWrapping(word)
        if (PlainDecimal.matcher(number).matches()) number.toDoubleOption.filter(score => score >= 0.0 && score <= 1.0)
        else None
      case _ => None
    }

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
