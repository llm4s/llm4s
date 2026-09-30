package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ GuardrailAction, InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.llm4s.util.SecretPatterns
import org.llm4s.util.SecretPatterns.SecretType
import org.slf4j.LoggerFactory

/**
 * Detects and redacts secrets / credentials in LLM input and output.
 *
 * Prevents two classes of leak:
 *  - **Input leak**: a user accidentally pastes an API key into a prompt that
 *    is then logged, cached, or forwarded to third-party LLM providers.
 *  - **Output leak**: the LLM echoes a secret back in its response (e.g. when
 *    asked to summarise a config file that contains credentials).
 *
 * Detected credential types (defaults):
 *  - OpenAI API keys (sk-... / sk-proj-...)
 *  - Anthropic API keys (sk-ant-...)
 *  - Google API keys (AIza...)
 *  - Voyage API keys (pa-...)
 *  - Langfuse keys (pk-lf-... / sk-lf-...)
 *  - AWS Access Key IDs (AKIA...)
 *  - JWT tokens (eyJ...eyJ...sig)
 *
 * Behaviour is controlled by [[GuardrailAction]]:
 *  - `Block` (default) – reject the text and return a `Left` error.
 *  - `Fix`             – replace secrets with typed placeholders and continue
 *                        (e.g. `[REDACTED_OPENAI_KEY]`).
 *  - `Warn`            – allow the text through unchanged; the caller may
 *                        inspect the `Right` and decide what to log.
 *
 * Example usage:
 * {{{
 * // Block any input that contains a credential
 * agent.run(
 *   query          = userInput,
 *   tools          = tools,
 *   inputGuardrails = Seq(SecretLeakGuardrail())
 * )
 *
 * // Mask secrets automatically and let the query proceed
 * agent.run(
 *   query          = userInput,
 *   tools          = tools,
 *   inputGuardrails = Seq(SecretLeakGuardrail.masking)
 * )
 *
 * // Also scrub LLM responses
 * agent.run(
 *   query           = userInput,
 *   tools           = tools,
 *   outputGuardrails = Seq(SecretLeakGuardrail.masking)
 * )
 * }}}
 *
 * @param secretTypes Secret types to detect (default: all common provider keys)
 * @param onFail      Action to take when a secret is detected (default: Block)
 */
class SecretLeakGuardrail(
  val secretTypes: Seq[SecretType] = SecretType.default,
  val onFail: GuardrailAction = GuardrailAction.Block
) extends InputGuardrail
    with OutputGuardrail {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Validate the text for secrets.
   *
   * Returns:
   *  - `Right(original)`  if no secrets found, or `onFail == Warn`
   *  - `Right(masked)`    if `onFail == Fix`
   *  - `Left(error)`      if `onFail == Block` and secrets are present
   */
  def validate(value: String): Result[String] = {
    val detected = SecretPatterns.detect(value, secretTypes)
    if (detected.isEmpty) {
      Right(value)
    } else {
      onFail match {
        case GuardrailAction.Block =>
          val names = detected.map(_.secretType.name).distinct.mkString(", ")
          Left(
            ValidationError.invalid(
              "input",
              s"Potential credentials detected [$names]. " +
                "Remove or rotate the secrets before processing."
            )
          )

        case GuardrailAction.Fix =>
          Right(transform(value))

        case GuardrailAction.Warn =>
          val names = detected.map(_.secretType.name).distinct.mkString(", ")
          logger.warn(
            s"SecretLeakGuardrail detected potential credentials [$names] — passing through in warn mode"
          )
          Right(value)
      }
    }
  }

  /**
   * Replace every detected secret with its type-specific placeholder.
   *
   * e.g. `sk-abc123... → [REDACTED_OPENAI_KEY]`
   *
   * This is called automatically when `onFail == Fix`. It can also be called
   * directly for unconditional sanitisation regardless of the guardrail mode.
   */
  override def transform(input: String): String =
    SecretPatterns.redactAll(input, secretTypes)

  val name: String = "SecretLeakGuardrail"

  override val description: Option[String] = Some(
    s"Detects credentials in text: ${secretTypes.map(_.name).mkString(", ")}"
  )

  /** True if the text contains at least one detectable secret. */
  def containsSecret(text: String): Boolean =
    SecretPatterns.containsSecret(text, secretTypes)

  /** Returns a map of secret-type name → count for the given text. */
  def summarize(text: String): Map[String, Int] =
    SecretPatterns.summarize(text, secretTypes)
}

object SecretLeakGuardrail {

  /** Create a guardrail with default secret types and Block behaviour. */
  def apply(): SecretLeakGuardrail = new SecretLeakGuardrail()

  /**
   * Create a guardrail with a specific failure action.
   *
   * @param onFail Behaviour when a secret is detected
   */
  def apply(onFail: GuardrailAction): SecretLeakGuardrail =
    new SecretLeakGuardrail(onFail = onFail)

  /**
   * Create a guardrail that scans only for specific secret types.
   *
   * @param secretTypes The credential types to detect
   */
  def apply(secretTypes: Seq[SecretType]): SecretLeakGuardrail =
    new SecretLeakGuardrail(secretTypes = secretTypes)

  /**
   * Create a fully configured guardrail.
   *
   * @param secretTypes Credential types to detect
   * @param onFail      Behaviour when a secret is detected
   */
  def apply(secretTypes: Seq[SecretType], onFail: GuardrailAction): SecretLeakGuardrail =
    new SecretLeakGuardrail(secretTypes, onFail)

  /**
   * Preset: automatically mask secrets and allow the request to proceed.
   * Secrets are replaced with typed placeholders (e.g. [REDACTED_OPENAI_KEY]).
   */
  def masking: SecretLeakGuardrail =
    new SecretLeakGuardrail(onFail = GuardrailAction.Fix)

  /**
   * Preset: scan for all known secret types, including PEM private keys.
   * Blocks on detection.
   */
  def all: SecretLeakGuardrail =
    new SecretLeakGuardrail(secretTypes = SecretType.all)

  /**
   * Preset: monitoring mode — warn but do not block or mask.
   * Useful for auditing traffic without affecting functionality.
   */
  def monitoring: SecretLeakGuardrail =
    new SecretLeakGuardrail(onFail = GuardrailAction.Warn)
}
