package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.llmconnect.auth.{ ApiKeyProvider, IdentitySource }
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
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

  /** Model identifier forwarded verbatim to the provider API (e.g. `gpt-4o`, `claude-sonnet-4-5-latest`). */
  def model: ModelName

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
  def endpointUrl: Option[BaseUrl]

  /** The same provider and credentials, pointed at a different model. */
  def withModel(model: ModelName): ProviderConfig

  /**
   * How long this provider's HTTP calls may take: the `timeouts` block of its section.
   * [[ProviderTimeouts.default]] leaves the client on its own default; a client reads the value with
   * [[ProviderTimeouts.requestOr]] / [[ProviderTimeouts.streamOr]].
   */
  def timeouts: ProviderTimeouts

  /**
   * The same config with the given timeouts.
   *
   * The loader calls this on every config a descriptor builds, passing the section's `timeouts`
   * block, so a descriptor does not read the block itself. There is no default: a config states what it
   * does with them. One whose provider makes no timed call (a model run in-process) returns itself and
   * keeps [[timeouts]] at the default, and the loader then refuses a section that sets `timeouts` rather
   * than dropping them.
   */
  def withTimeouts(timeouts: ProviderTimeouts): ProviderConfig

  /**
   * The proxy and TLS settings this provider's HTTP calls use: `llm4s.http`, overridden key by key by the
   * section's own `http` block. [[org.llm4s.http.HttpNetworkSettings.default]] is no proxy and the JDK's trust.
   */
  def network: HttpNetworkSettings

  /**
   * The same config with the given proxy and TLS settings.
   *
   * The loader calls this on every config a descriptor builds, as it does [[withTimeouts]], so a descriptor
   * does not read the `http` block itself. There is no default, so a configured proxy is never bypassed by
   * omission. A config whose provider makes no HTTP call returns itself and keeps [[network]] at the default;
   * the loader then refuses a section with an `http` block of its own (the process-wide `llm4s.http` has
   * nothing to route there, so it is not an error).
   */
  def withNetwork(network: HttpNetworkSettings): ProviderConfig

  /**
   * Where the client gets the API key it sends with each request, when not from the key this config holds:
   * a section's `apiKeyFile`, or a provider set with [[withApiKeyProvider]] (#2313). `None` for a config that
   * sends the key it holds, and always for one that cannot take a provider.
   */
  def apiKeyProvider: Option[ApiKeyProvider]

  /**
   * The same config, its client asking `provider` for the key on each request, so a rotated key is used
   * without rebuilding the client.
   *
   * @return the config, or a [[org.llm4s.error.ConfigurationError]] from a config that cannot take one: a
   *         provider that authenticates without an API key (Ollama, Bedrock, Vertex AI), or one whose client
   *         reads the key once. [[ProviderConfig.noApiKeyProvider]] builds that error. It is never ignored.
   */
  def withApiKeyProvider(provider: ApiKeyProvider): Result[ProviderConfig]
}

object ProviderConfig {

  /**
   * What [[ProviderConfig.withApiKeyProvider]] returns from a config that cannot take an
   * [[org.llm4s.llmconnect.auth.ApiKeyProvider]].
   *
   * @param providerId the config's provider, named in the message
   * @param reason     why not, completing "provider '<id>' ...": by default `takes no API key`
   */
  def noApiKeyProvider(providerId: ProviderId, reason: String = "takes no API key"): Result[Nothing] =
    Left(
      ConfigurationError(
        s"provider '${providerId.asString}' $reason, so its config cannot take an ApiKeyProvider",
        List("apiKeyProvider")
      )
    )

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
        nonEmpty(provider, s"${prefix}identityToken", token.reveal)

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
      case ConfigurationError(message, Seq(field)) =>
        val key = sectionKeys.getOrElse(field, field)
        ConfigurationError(s"llm4s.providers.$providerName.$key: $message", Seq(key))
      case other => other
}
