package org.llm4s.util

import scala.util.matching.Regex

/**
 * The credential regexes `Redaction` applies to every string it redacts, and which
 * `org.llm4s.agent.guardrails.patterns.SecretPatterns` (`llm4s-agent`) publishes as `SecretType`s for
 * `SecretLeakGuardrail`.
 *
 * Every pattern runs on text a model, a user or a provider supplies, so each takes time linear in the text: a
 * pattern may not start a scan to the end of a run from every position of that run. Where a pattern holds a
 * capturing group, group 1 is the secret and the rest of the match is text the pattern had to read to stay linear
 * ([[secretGroup]]).
 */
private[llm4s] object SecretPatterns {

  /**
   * OpenAI API keys: a legacy `sk-...` key is alphanumeric; a project, service-account or admin key
   * (`sk-proj-...`, `sk-svcacct-...`, `sk-admin-...`) also holds `-` and `_`.
   */
  val OpenAIKey: Regex = """sk-(?:(?:proj|svcacct|admin)-[A-Za-z0-9_\-]{20,}|[A-Za-z0-9]{20,})""".r

  /** Anthropic API keys: `sk-ant-...`, base64url after the prefix. */
  val AnthropicKey: Regex = """sk-ant-[A-Za-z0-9_\-]{20,}""".r

  /** Google API keys: `AIza...` */
  val GoogleApiKey: Regex = """AIza[A-Za-z0-9\-_]{35,}""".r

  /** Voyage AI keys: `pa-...` */
  val VoyageKey: Regex = """pa-[A-Za-z0-9]{20,}""".r

  /** Langfuse public and secret keys: `pk-lf-...` / `sk-lf-...` */
  val LangfuseKey: Regex = """(?:pk|sk)-lf-[A-Za-z0-9]{10,}""".r

  /** AWS Access Key IDs: `AKIA` and 16 uppercase alphanumeric characters. */
  val AwsAccessKey: Regex = """AKIA[A-Z0-9]{16}""".r

  /**
   * JWT tokens: three base64url segments separated by dots, the first two starting `eyJ` (a JSON object). Group 1
   * is the token.
   *
   * It finds what `eyJ[A-Za-z0-9\-_]+\.eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+` finds, in linear time (#2824). That
   * pattern scanned to the end of a run of base64url characters from every `eyJ` in it, which is quadratic on a
   * run of `eyJ` with no dot. Every `eyJ` of one run reaches the same dot or none, so only the first can start a
   * token: this pattern starts at the head of a run only, skips to the run's first `eyJ` and never backtracks.
   */
  val JwtToken: Regex =
    """(?<![A-Za-z0-9\-_])(?:(?!eyJ)[A-Za-z0-9\-_])*+(eyJ[A-Za-z0-9\-_]++\.eyJ[A-Za-z0-9\-_]++\.[A-Za-z0-9\-_]++)""".r

  /**
   * A PEM private key (PKCS#8, RSA, EC, DSA, OpenSSH, encrypted): the whole block, from `-----BEGIN ... PRIVATE
   * KEY-----` through `-----END ... PRIVATE KEY-----`, whatever lies between (base64 lines, RFC 1421 headers,
   * newlines written as `\n` in a JSON string).
   *
   * A block cut off before its footer matches through the base64 lines that follow the header, so a truncated
   * reply does not leave the key readable; a header followed by neither (prose that quotes it) matches alone.
   * Linear: the body stops at the next `-----`, so each header reads no further than the one after it.
   */
  val PrivateKeyPem: Regex = {
    val kind = "(?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?"
    val body = """(?:(?!-----)[\s\S])*+"""
    val cut  = """[\r\n\\][A-Za-z0-9+/=\r\n\\]*+"""
    s"-----BEGIN ${kind}PRIVATE KEY-----(?:$body-----END ${kind}PRIVATE KEY-----|$cut|)".r
  }

  /** The patterns `Redaction` applies: every one but [[PrivateKeyPem]]. */
  val default: Vector[Regex] =
    Vector(OpenAIKey, AnthropicKey, GoogleApiKey, VoyageKey, LangfuseKey, AwsAccessKey, JwtToken)

  /** The group of `pattern` that holds the secret: 1 when it has a capturing group, else 0, the whole match. */
  def secretGroup(pattern: Regex): Int =
    if (pattern.pattern.matcher("").groupCount >= 1) 1 else 0
}
