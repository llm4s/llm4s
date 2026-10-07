package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
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

  /**
   * How long this provider's HTTP calls may take: the `timeouts` block of its section.
   *
   * The default is [[ProviderTimeouts.default]], which leaves every client on its own default, so a
   * config that predates the block, or a provider supplied by another module that does not read it,
   * behaves as before. A config that carries the setting overrides this and its client reads it with
   * [[ProviderTimeouts.requestOr]] / [[ProviderTimeouts.streamOr]].
   */
  def timeouts: ProviderTimeouts = ProviderTimeouts.default
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
}
