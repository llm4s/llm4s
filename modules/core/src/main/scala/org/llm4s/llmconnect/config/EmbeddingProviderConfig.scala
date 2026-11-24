package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.llmconnect.auth.{ ApiKeyProvider, CachingAccessTokenProvider }
import org.llm4s.security.Secret
import org.llm4s.types.Result

/**
 * Configuration required to connect to an embedding provider endpoint.
 *
 * @param baseUrl base URL of the embedding service (e.g. `https://api.openai.com/v1`)
 * @param model   name of the embedding model to use
 * @param apiKey  authentication key for the provider, `None` for one that takes none, such as a local Ollama
 *                (#2805: it was a mandatory `Secret`, so a keyless provider passed `Secret("")`); redacted in
 *                `toString`
 * @param timeouts how long an embedding request may take: the section's `timeouts.request`. Absent
 *                 means the provider's own default (two minutes). `timeouts.stream` has no meaning for
 *                 an embedding call.
 * @param network  the proxy and TLS settings its HTTP calls use: `llm4s.http`. The default,
 *                 [[org.llm4s.http.HttpNetworkSettings.default]], is no proxy and the JDK's trust.
 * @param apiKeyProvider supplies the key per request in place of `apiKey`, so a rotated key is used without
 *                 rebuilding the provider (#2478): a block's `apiKeyFile` (or the vendor's shared
 *                 `llm4s.credentials.<id>.apiKeyFile`) sets [[org.llm4s.llmconnect.auth.ApiKeyProvider.file]],
 *                 and code sets any implementation with [[withApiKeyProvider]]. `apiKey` then holds the key read
 *                 at load. An embedding provider reads the key with [[currentApiKey]].
 * @param extras   the provider-specific keys of the section, as the provider declares them in
 *                 `EmbeddingConfigSpec.extras`: how a declared key reaches the provider that
 *                 `EmbeddingProviderDescriptor.build` constructs. Empty for a provider that declares none.
 *                 Redacted in `toString`.
 *
 * Build one with [[EmbeddingProviderConfig.apply]] and change it with the `with*` setters; the constructor and `copy`
 * are private, so a new field never breaks callers.
 */
@Stable
final case class EmbeddingProviderConfig private (
  baseUrl: String,
  model: String,
  apiKey: Option[Secret],
  timeouts: ProviderTimeouts,
  network: HttpNetworkSettings,
  apiKeyProvider: Option[ApiKeyProvider],
  extras: Map[String, String]
) {

  /** Sets the provider-specific keys. */
  def withExtras(extras: Map[String, String]): EmbeddingProviderConfig = copy(extras = extras)

  /** The value of a provider-specific key the provider declares in `EmbeddingConfigSpec.extras`. */
  def extra(key: String): Option[String] = extras.get(key)

  /** Sets the base URL of the embedding service. */
  def withBaseUrl(baseUrl: String): EmbeddingProviderConfig = copy(baseUrl = baseUrl)

  /** Sets the embedding model. */
  def withModel(model: String): EmbeddingProviderConfig = copy(model = model)

  /** Sets the authentication key. */
  def withApiKey(apiKey: Secret): EmbeddingProviderConfig = copy(apiKey = Some(apiKey))

  /** Sets the authentication key, or clears it with `None` for a provider that takes none. */
  def withApiKey(apiKey: Option[Secret]): EmbeddingProviderConfig = copy(apiKey = apiKey)

  /** Sets how long an embedding request may take. */
  def withTimeouts(timeouts: ProviderTimeouts): EmbeddingProviderConfig = copy(timeouts = timeouts)

  /** Sets the proxy and TLS settings its HTTP calls use. */
  def withNetwork(network: HttpNetworkSettings): EmbeddingProviderConfig = copy(network = network)

  /** Supplies the key per request from `provider` rather than sending `apiKey`. */
  def withApiKeyProvider(provider: ApiKeyProvider): EmbeddingProviderConfig = copy(apiKeyProvider = Some(provider))

  /** Supplies the key per request from `provider`, or sends `apiKey` when it is `None`. */
  def withApiKeyProvider(provider: Option[ApiKeyProvider]): EmbeddingProviderConfig = copy(apiKeyProvider = provider)

  /**
   * The key to send with the next request: [[apiKeyProvider]]'s current key when one is set, otherwise `apiKey`.
   * Neither set is a [[org.llm4s.error.ConfigurationError]] naming `apiKey`: a provider that takes no key reads
   * `apiKey` itself rather than calling this. A key file that has become unreadable or empty is a `Left` naming its path, and a provider that throws is an
   * `AuthenticationError` naming the exception's class, as it is for a chat client.
   */
  def currentApiKey(): Result[Secret] =
    apiKeyProvider.fold[Result[Secret]](
      apiKey.toRight(ConfigurationError(s"Embedding model '$model' has no apiKey set", List("apiKey")))
    )(keys => CachingAccessTokenProvider.guarded(keys.current()).map(key => Secret(key.asKey)))

  override def toString: String =
    s"EmbeddingProviderConfig(baseUrl=$baseUrl, model=$model, apiKey=${apiKey.fold("none")(_.toString)}" +
      apiKeyProvider.fold("")(p => s", apiKeyProvider=$p") +
      (if (extras.isEmpty) "" else extras.keys.toSeq.sorted.map(k => s"$k=***").mkString(", extras=[", ", ", "]")) +
      ")"
}

object EmbeddingProviderConfig {

  /**
   * Creates an [[EmbeddingProviderConfig]]. `timeouts` defaults to [[ProviderTimeouts.default]], the provider's own
   * default, `network` to [[org.llm4s.http.HttpNetworkSettings.default]], no proxy, `apiKeyProvider` to none,
   * so `apiKey` is sent, and `extras` to none.
   */
  def apply(
    baseUrl: String,
    model: String,
    apiKey: Option[Secret],
    timeouts: ProviderTimeouts = ProviderTimeouts.default,
    network: HttpNetworkSettings = HttpNetworkSettings.default,
    apiKeyProvider: Option[ApiKeyProvider] = None,
    extras: Map[String, String] = Map.empty
  ): EmbeddingProviderConfig =
    new EmbeddingProviderConfig(baseUrl, model, apiKey, timeouts, network, apiKeyProvider, extras)

}
