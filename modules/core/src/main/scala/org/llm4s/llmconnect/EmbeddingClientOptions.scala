package org.llm4s.llmconnect

import org.llm4s.annotation.Stable
import org.llm4s.http.Llm4sHttpClient

/**
 * Construction options for [[EmbeddingClient.from]]: what [[LlmClientOptions]] is to a chat client.
 *
 * Build one with [[EmbeddingClientOptions.apply]] (or start from [[EmbeddingClientOptions.default]]) and change
 * it with the `with*` setters; the constructor and `copy` are private, so a new option never breaks callers.
 *
 * @param httpClient the HTTP client the embedding provider sends its requests through instead of building its
 *                   own; `None` (the default) builds one from the config's proxy and TLS settings. The caller
 *                   owns a supplied client: the provider does not close it, and the config's `network` settings
 *                   do not apply to it.
 */
@Stable
final case class EmbeddingClientOptions private (httpClient: Option[Llm4sHttpClient]):

  /** Sends the provider's HTTP requests through `httpClient`, which the caller keeps owning and closes. */
  def withHttpClient(httpClient: Llm4sHttpClient): EmbeddingClientOptions = copy(httpClient = Some(httpClient))

  /** Sets the HTTP client to send requests through; `None` lets the provider build its own. */
  def withHttpClient(httpClient: Option[Llm4sHttpClient]): EmbeddingClientOptions = copy(httpClient = httpClient)

object EmbeddingClientOptions:

  /** Creates [[EmbeddingClientOptions]]; with no arguments, the provider's own HTTP client. */
  def apply(httpClient: Option[Llm4sHttpClient] = None): EmbeddingClientOptions =
    new EmbeddingClientOptions(httpClient)

  /** The provider's own HTTP client. */
  val default: EmbeddingClientOptions = EmbeddingClientOptions()
