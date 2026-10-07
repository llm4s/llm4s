package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.llmconnect.auth.IdentitySource
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Identifies a specific LLM provider, model, and connection details.
 *
 * Each subtype carries the credentials, endpoint URL, and context-window
 * metadata needed to construct an [[org.llm4s.llmconnect.LLMClient]] via
 * [[org.llm4s.llmconnect.LLMConnect]]. Instances are normally obtained from
 * [[org.llm4s.config.Llm4sConfig.defaultProvider]] or
 * [[org.llm4s.config.Llm4sConfig.provider(name)*]], which resolve configured
 * named providers under `llm4s.providers`.
 *
 * Prefer each subtype's `fromValues` factory over its primary constructor:
 * `fromValues` resolves `contextWindow` and `reserveCompletion` automatically
 * from the model name, so you only need to supply credentials and endpoint.
 * It returns a `Result`: a blank credential or endpoint is a
 * [[org.llm4s.error.ConfigurationError]] naming the provider and the field,
 * never a thrown exception.
 *
 * This trait is deliberately '''not''' `sealed`. In Scala 3 `sealed` confines
 * subtypes to the same source file, which would keep every provider's config in
 * this one file forever and make a provider supplied by another module
 * impossible. Implementations are therefore expected from outside `llm4s-core`,
 * and consumers must not assume the set of subtypes is closed: describe a config
 * through [[providerId]], [[endpointUrl]] and [[withModel]] rather than by
 * pattern-matching on its runtime type.
 */
@Stable
trait ProviderConfig {

  /** Canonical id of the provider this config addresses, e.g. `ProviderId("openai")`. */
  def providerId: ProviderId

  /** Model identifier forwarded verbatim to the provider API (e.g. `"gpt-4o"`, `"claude-sonnet-4-5-latest"`). */
  def model: String

  /** Maximum token capacity of the model across both prompt and completion combined. */
  def contextWindow: Int

  /**
   * Tokens reserved for the model's completion response.
   *
   * Context-compression logic caps the prompt history at
   * `contextWindow - reserveCompletion`, ensuring the model always has at
   * least this many tokens available to generate a reply.
   */
  def reserveCompletion: Int

  /**
   * The endpoint this config will contact, when it is known statically.
   *
   * Used by policy checks and diagnostics that need to know where traffic will
   * go without knowing which provider it belongs to. `None` means the config
   * carries no single URL — not that it makes no network calls.
   */
  def endpointUrl: Option[String]

  /** The same provider and credentials, pointed at a different model. */
  def withModel(model: String): ProviderConfig
}

object ProviderConfig {

  /**
   * The check behind every `fromValues` factory: `value` must not be blank.
   *
   * A blank credential or endpoint is a configuration mistake, so it is reported
   * as a [[org.llm4s.error.ConfigurationError]] naming the provider and the
   * field, not thrown.
   *
   * @param provider the provider's display name, e.g. `"OpenAI"`.
   * @param field    the parameter name, e.g. `"apiKey"`; also reported as the missing key.
   */
  private[llm4s] def nonEmpty(provider: String, field: String, value: String): Result[Unit] =
    Either.cond(
      value.trim.nonEmpty,
      (),
      ConfigurationError(s"$provider $field must be non-empty", List(field))
    )

  /** [[nonEmpty]] for an optional value: absent is fine, but a value that is set must not be blank. */
  private[llm4s] def nonEmptyIfSet(provider: String, field: String, value: Option[String]): Result[Unit] =
    value.fold[Result[Unit]](Right(()))(nonEmpty(provider, field, _))

  /**
   * [[nonEmpty]] for a workload identity's token source: a literal token must not be blank, nor a
   * file's path. The field reported is `<prefix>identityToken` or `<prefix>identityTokenFile`, the
   * `auth` key each comes from.
   */
  private[llm4s] def nonEmptyIdentity(
    provider: String,
    prefix: String,
    source: IdentitySource
  ): Result[Unit] =
    source match
      case IdentitySource.File(path) =>
        nonEmpty(provider, s"${prefix}identityTokenFile", path.toString)
      case IdentitySource.Literal(token) =>
        nonEmpty(provider, s"${prefix}identityToken", token)

  /**
   * Roots a `fromValues` refusal at the named section it was built from, so the section path
   * reports the same check every other construction path does, under the key the user wrote: a
   * [[org.llm4s.error.ConfigurationError]] naming one field `k` becomes
   * `llm4s.providers.<providerName>.<k>: <message>`, with `k` first renamed through `sectionKeys`
   * where the section spells it differently (`tokenExchange.tokenUrl` is `auth.tokenUrl`).
   * Any other error is returned unchanged.
   */
  private[llm4s] def inSection(providerName: String, sectionKeys: Map[String, String] = Map.empty)(
    error: LLMError
  ): LLMError =
    error match
      case ConfigurationError(message, List(field)) =>
        val key = sectionKeys.getOrElse(field, field)
        ConfigurationError(s"llm4s.providers.$providerName.$key: $message", List(key))
      case other => other
}
