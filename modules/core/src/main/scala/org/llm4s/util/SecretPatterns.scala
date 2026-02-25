package org.llm4s.util

import scala.util.matching.Regex

/**
 * Library of credential-detection patterns used by [[org.llm4s.agent.guardrails.builtin.SecretLeakGuardrail]]
 * and `Redaction`.
 *
 * All regex matching is performed via [[SecretType]] instances.  Each type
 * knows its own pattern, human-readable name, and the placeholder text to use
 * when redacting.
 */
object SecretPatterns {

  /**
   * A single regex match of a secret inside a larger string.
   *
   * @param secretType  The [[SecretType]] that produced this match.
   * @param value       The matched secret text (un-redacted).
   * @param startIndex  Inclusive start position in the original string.
   * @param endIndex    Exclusive end position in the original string.
   */
  final case class SecretMatch(
    secretType: SecretType,
    value: String,
    startIndex: Int,
    endIndex: Int
  ) {

    /** The placeholder that should replace [[value]] when masking. */
    def redactedValue: String = secretType.placeholder
  }

  // ─────────────────────────────────────────────────────────────────────────
  // SecretType ADT
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * A single category of secret credential.
   *
   * Each instance carries:
   *  - `name`        – human-readable label used in error messages
   *  - `pattern`     – the [[scala.util.matching.Regex]] that recognises this credential
   *  - `placeholder` – what to write in place of the secret when masking
   */
  sealed abstract class SecretType(
    val name: String,
    val pattern: Regex,
    val placeholder: String
  ) {

    /**
     * Return all non-overlapping matches found in `text`.
     * Results are ordered by start position.
     */
    def findAll(text: String): Seq[SecretMatch] =
      pattern
        .findAllMatchIn(text)
        .map { m =>
          // Use group 1 if the pattern captures the secret in a group,
          // otherwise use the whole match.
          val (value, start, end) =
            if (m.groupCount >= 1 && m.group(1) != null)
              (m.group(1), m.start(1), m.end(1))
            else
              (m.matched, m.start, m.end)
          SecretMatch(this, value, start, end)
        }
        .toSeq
  }

  object SecretType {

    /** OpenAI API keys: `sk-...` and `sk-proj-...` */
    case object OpenAIKey
        extends SecretType(
          name = "OpenAI API Key",
          pattern = """sk-(?:proj-)?[A-Za-z0-9]{20,}""".r,
          placeholder = "[REDACTED_OPENAI_KEY]"
        )

    /** Anthropic API keys: `sk-ant-...` */
    case object AnthropicKey
        extends SecretType(
          name = "Anthropic API Key",
          pattern = """sk-ant-[A-Za-z0-9\-]{20,}""".r,
          placeholder = "[REDACTED_ANTHROPIC_KEY]"
        )

    /** Google API keys: `AIza...` */
    case object GoogleApiKey
        extends SecretType(
          name = "Google API Key",
          pattern = """AIza[A-Za-z0-9\-_]{35,}""".r,
          placeholder = "[REDACTED_GOOGLE_KEY]"
        )

    /** Voyage AI keys: `pa-...` */
    case object VoyageKey
        extends SecretType(
          name = "Voyage API Key",
          pattern = """pa-[A-Za-z0-9]{20,}""".r,
          placeholder = "[REDACTED_VOYAGE_KEY]"
        )

    /** Langfuse public and secret keys: `pk-lf-...` / `sk-lf-...` */
    case object LangfuseKey
        extends SecretType(
          name = "Langfuse API Key",
          pattern = """(?:pk|sk)-lf-[A-Za-z0-9]{10,}""".r,
          placeholder = "[REDACTED_LANGFUSE_KEY]"
        )

    /** AWS Access Key IDs: `AKIA...` (20 uppercase alphanumeric chars) */
    case object AwsAccessKey
        extends SecretType(
          name = "AWS Access Key ID",
          pattern = """AKIA[A-Z0-9]{16}""".r,
          placeholder = "[REDACTED_AWS_KEY]"
        )

    /**
     * JWT tokens: three base64url segments separated by dots.
     * The first segment always decodes to a JSON header starting with `eyJ`.
     */
    case object JwtToken
        extends SecretType(
          name = "JWT Token",
          pattern = """eyJ[A-Za-z0-9\-_]+\.eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+""".r,
          placeholder = "[REDACTED_JWT]"
        )

    /** PEM private key block headers (RSA, EC, PKCS8 variants). */
    case object PrivateKeyPem
        extends SecretType(
          name = "PEM Private Key",
          pattern = """-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----""".r,
          placeholder = "[REDACTED_PRIVATE_KEY]"
        )

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

    /** All 8 known types, including [[PrivateKeyPem]]. */
    val all: Seq[SecretType] = default :+ PrivateKeyPem
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Top-level helpers (operate on SecretType.default unless told otherwise)
  // ─────────────────────────────────────────────────────────────────────────

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
   * When multiple secrets of different types overlap, earlier matches in
   * the type list take precedence (leftmost-longest wins within a type;
   * types are applied left-to-right).
   */
  def redactAll(
    text: String,
    types: Seq[SecretType] = SecretType.default
  ): String =
    types.foldLeft(text)((acc, t) => t.pattern.replaceAllIn(acc, _ => t.placeholder))

  /**
   * Replace every matched secret with the supplied uniform `placeholder`
   * string (e.g. `"[REDACTED]"`).
   *
   * Useful when callers want a single marker regardless of credential type.
   */
  def redactAllWithPlaceholder(
    text: String,
    placeholder: String,
    types: Seq[SecretType] = SecretType.default
  ): String =
    types.foldLeft(text)((acc, t) => t.pattern.replaceAllIn(acc, _ => placeholder))

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
