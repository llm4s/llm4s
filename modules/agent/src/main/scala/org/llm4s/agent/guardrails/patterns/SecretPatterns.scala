package org.llm4s.agent.guardrails.patterns

import org.llm4s.annotation.Stable
import org.llm4s.util.{ SecretPatterns => CorePatterns }

import scala.util.matching.Regex

/**
 * Library of credential-detection patterns used by
 * [[org.llm4s.agent.guardrails.builtin.SecretLeakGuardrail SecretLeakGuardrail]].
 *
 * All matching is performed via [[SecretPatterns.SecretType SecretType]] instances. Each type knows its own pattern,
 * human-readable name, and the placeholder text to use when redacting. The built-in types share their patterns with
 * the redaction llm4s applies to its own logs and error messages, and each takes time linear in the text.
 */
@Stable
object SecretPatterns {

  /**
   * A single match of a secret inside a larger string.
   *
   * @param secretType  The [[SecretType]] that produced this match.
   * @param value       The matched secret text (un-redacted).
   * @param startIndex  Inclusive start position in the original string.
   * @param endIndex    Exclusive end position in the original string.
   */
  final case class SecretMatch private (
    secretType: SecretType,
    value: String,
    startIndex: Int,
    endIndex: Int
  ) {

    /** The placeholder that should replace [[value]] when masking. */
    def redactedValue: String = secretType.placeholder
  }

  object SecretMatch {

    /** Creates a [[SecretMatch]]. */
    def apply(secretType: SecretType, value: String, startIndex: Int, endIndex: Int): SecretMatch =
      new SecretMatch(secretType, value, startIndex, endIndex)
  }

  /**
   * A single category of secret credential.
   *
   * The built-in types are in the companion. The class is open: an application adds its own credential format with
   * `new SecretType("Acme token", """acme_[A-Za-z0-9]{32}""".r, "[REDACTED_ACME_TOKEN]")` and passes it to
   * `SecretLeakGuardrail` beside the built-in ones. A pattern runs on text a model or a user supplies, so write one
   * that takes linear time: no nested quantifiers, and no scan to the end of a run from every position in it.
   *
   * @param name        human-readable label used in error messages
   * @param pattern     the regex that recognises this credential. When it has a capturing group, group 1 is the
   *                    secret and the rest of the match is context that is kept; otherwise the whole match is
   * @param placeholder what to write in place of the secret when masking
   */
  class SecretType(
    val name: String,
    val pattern: Regex,
    val placeholder: String
  ) {

    private val secretGroup = CorePatterns.secretGroup(pattern)

    /**
     * Return all non-overlapping matches found in `text`.
     * Results are ordered by start position.
     */
    def findAll(text: String): Seq[SecretMatch] =
      pattern
        .findAllMatchIn(text)
        .collect {
          case m if m.start(secretGroup) >= 0 =>
            SecretMatch(this, m.group(secretGroup), m.start(secretGroup), m.end(secretGroup))
        }
        .toSeq

    /** `text` with every match of this type replaced by [[placeholder]]. */
    def redact(text: String): String = {
      val out    = new java.lang.StringBuilder(text.length)
      var copied = 0
      findAll(text).foreach { m =>
        out.append(text, copied, m.startIndex).append(placeholder)
        copied = m.endIndex
      }
      if (copied == 0) text else out.append(text, copied, text.length).toString
    }

    override def toString: String = s"SecretType($name)"
  }

  object SecretType {

    /**
     * OpenAI API keys: `sk-...`, and the project, service-account and admin keys `sk-proj-...`, `sk-svcacct-...`
     * and `sk-admin-...`, which also hold `-` and `_`.
     */
    case object OpenAIKey extends SecretType("OpenAI API Key", CorePatterns.OpenAIKey, "[REDACTED_OPENAI_KEY]")

    /** Anthropic API keys: `sk-ant-...` */
    case object AnthropicKey
        extends SecretType("Anthropic API Key", CorePatterns.AnthropicKey, "[REDACTED_ANTHROPIC_KEY]")

    /** Google API keys: `AIza...` */
    case object GoogleApiKey extends SecretType("Google API Key", CorePatterns.GoogleApiKey, "[REDACTED_GOOGLE_KEY]")

    /** Voyage AI keys: `pa-...` */
    case object VoyageKey extends SecretType("Voyage API Key", CorePatterns.VoyageKey, "[REDACTED_VOYAGE_KEY]")

    /** Langfuse public and secret keys: `pk-lf-...` / `sk-lf-...` */
    case object LangfuseKey extends SecretType("Langfuse API Key", CorePatterns.LangfuseKey, "[REDACTED_LANGFUSE_KEY]")

    /** AWS Access Key IDs: `AKIA...` (20 uppercase alphanumeric chars) */
    case object AwsAccessKey extends SecretType("AWS Access Key ID", CorePatterns.AwsAccessKey, "[REDACTED_AWS_KEY]")

    /**
     * JWT tokens: three base64url segments separated by dots.
     * The first segment always decodes to a JSON header starting with `eyJ`.
     */
    case object JwtToken extends SecretType("JWT Token", CorePatterns.JwtToken, "[REDACTED_JWT]")

    /**
     * A PEM private key (PKCS#8, RSA, EC, DSA, OpenSSH, encrypted): the whole block, from the
     * `-----BEGIN ... PRIVATE KEY-----` line through `-----END ... PRIVATE KEY-----`. A block cut off before its
     * footer matches through the base64 lines after the header.
     */
    case object PrivateKeyPem
        extends SecretType("PEM Private Key", CorePatterns.PrivateKeyPem, "[REDACTED_PRIVATE_KEY]")

    /** The 7 types checked by default (excludes [[PrivateKeyPem]]). */
    val default: Seq[SecretType] = Seq(
      OpenAIKey,
      AnthropicKey,
      GoogleApiKey,
      VoyageKey,
      LangfuseKey,
      AwsAccessKey,
      JwtToken
    )

    /** All 8 built-in types, including [[PrivateKeyPem]]. */
    val all: Seq[SecretType] = default :+ PrivateKeyPem
  }

  /**
   * Find every secret in `text` using the supplied `types`
   * (defaults to [[SecretType.default]]).
   */
  def detect(
    text: String,
    types: Seq[SecretType] = SecretType.default
  ): Seq[SecretMatch] =
    types.flatMap(_.findAll(text))

  /**
   * Returns `true` if `text` contains at least one secret from `types`.
   */
  def containsSecret(
    text: String,
    types: Seq[SecretType] = SecretType.default
  ): Boolean =
    types.exists(_.findAll(text).nonEmpty)

  /**
   * Replace each matched secret with its type-specific placeholder
   * (e.g. `sk-abc... → [REDACTED_OPENAI_KEY]`).
   *
   * The types are applied left to right, each to the text the ones before it left, so where two types would match
   * the same text the earlier one in `types` redacts it.
   */
  def redactAll(
    text: String,
    types: Seq[SecretType] = SecretType.default
  ): String =
    types.foldLeft(text)((acc, t) => t.redact(acc))

  /**
   * Count occurrences of each credential type in `text`.
   *
   * @return A map from [[SecretType.name]] to the number of matches found.
   *         Types with zero matches are omitted.
   */
  def summarize(
    text: String,
    types: Seq[SecretType] = SecretType.default
  ): Map[String, Int] =
    types.flatMap { t =>
      val count = t.findAll(text).size
      if (count > 0) Some(t.name -> count) else None
    }.toMap
}
