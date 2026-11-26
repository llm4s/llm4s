package org.llm4s.llmconnect.auth.vault

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ AuthenticationError, ConfigurationError, NotFoundError, ServiceError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.auth.{ ApiKeyProvider, SingleFlightCache }
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import scala.util.Try

/**
 * An [[org.llm4s.llmconnect.auth.ApiKeyProvider ApiKeyProvider]] that reads the API key from HashiCorp Vault's
 * key/value secrets engine over Vault's HTTP API (#2479), for a deployment that cannot run Vault Agent or a CSI
 * driver to write the key to a file (`apiKeyFile`).
 *
 * The key is read on the first `current()`, then reused for [[VaultSettings.refreshInterval]], then read again, so a
 * key rotated in Vault is sent from the first request after the interval. The last key is not served after its
 * interval, even when the read that would replace it fails, since a rotated-out key may already be revoked.
 *
 * Concurrent callers share one read, and its failure: a read that fails is a `Left` for the callers waiting behind it
 * and for those arriving in the next second (a retryable error: network, timeout, 429, 5xx) or five seconds (anything
 * else, such as a refused token or a missing secret); the first call after that reads again. So an outage costs the
 * callers queued behind a read one [[VaultSettings.requestTimeout]], not one each in turn. A caller interrupted
 * while it reads, or while it waits for another caller's read, gets a `CancelledError` at once with its interrupt
 * flag set; a cancellation is never shared or cached. No monitor is held across the read, so a virtual thread is not
 * pinned by it.
 *
 * {{{
 * for
 *   keys   <- VaultApiKeyProvider(VaultSettings(vaultAddress, Secret(vaultToken), "llm4s/openai", "apiKey"))
 *   config <- Llm4sConfig.provider(ProviderName("openai-main")).flatMap(_.withApiKeyProvider(keys))
 *   client <- LLMConnect.getClient(config)
 * yield client
 * }}}
 *
 * Errors never hold the key or the Vault token: an `AuthenticationError` when Vault refuses the token (401, 403), a
 * `NotFoundError` when there is no secret at the path, a `ConfigurationError` when the secret has no usable string
 * field, a `ServiceError` for any other status, and the HTTP client's own error when Vault cannot be reached.
 *
 * It depends on no Vault SDK: one `GET` with llm4s's own HTTP client. Close it to close the HTTP client it created;
 * one passed to `VaultApiKeyProvider(settings, httpClient)` stays the caller's to close.
 */
@Experimental
final class VaultApiKeyProvider private (
  settings: VaultSettings,
  http: Llm4sHttpClient,
  ownsClient: Boolean,
  clock: Clock
) extends ApiKeyProvider
    with AutoCloseable:

  // One read in flight, its key cached for the interval, its failure shared briefly, a cancellation never.
  private val keys = new SingleFlightCache[ApiKey](VaultApiKeyProvider.Operation, clock)

  override def current(): Result[ApiKey] =
    keys.get { () =>
      // Taken before the read: the interval counts from when Vault was asked, not from when it answered.
      val readAt = clock.instant()
      read().map(key => key -> readAt.plusNanos(settings.refreshInterval.toNanos))
    }

  private def url: String =
    val path  = settings.path.split('/').filter(_.nonEmpty).map(VaultApiKeyProvider.encode).mkString("/")
    val mount = settings.mount.split('/').filter(_.nonEmpty).map(VaultApiKeyProvider.encode).mkString("/")
    settings.kvVersion match
      case VaultSettings.KvVersion.V2 => s"${settings.address}/v1/$mount/data/$path"
      case VaultSettings.KvVersion.V1 => s"${settings.address}/v1/$mount/$path"

  private def read(): Result[ApiKey] =
    val headers = Map("X-Vault-Token" -> settings.token.reveal, "Accept" -> "application/json") ++
      settings.namespace.map("X-Vault-Namespace" -> _)
    http.get(url, headers = headers, timeout = settings.requestTimeout).flatMap(answer)

  private def answer(response: HttpResponse): Result[ApiKey] =
    response.statusCode match
      case 200 =>
        Try(ujson.read(response.body)).toOption
          .flatMap(json => Try(dataOf(json)(settings.field)).toOption)
          .flatMap(_.strOpt)
          .map(_.trim)
          .filter(_.nonEmpty)
          .toRight(failure(s"has no non-blank string field '${settings.field}'"))
          .flatMap { key =>
            // A line break in a header value would be refused by the HTTP client, or split the header.
            if key.exists(c => c == '\n' || c == '\r') then
              Left(failure(s"field '${settings.field}' holds more than one line"))
            else Right(ApiKey(key))
          }
      case 404 =>
        Left(NotFoundError(s"Vault has no secret at ${settings.mount}/${settings.path}", settings.location))
      case status @ (401 | 403) =>
        Left(
          AuthenticationError(
            VaultApiKeyProvider.Provider,
            s"Vault refused the token reading ${settings.location} (HTTP $status)${errorsOf(response)}"
          )
        )
      case status =>
        Left(
          ServiceError(
            status,
            VaultApiKeyProvider.Provider,
            s"Vault answered HTTP $status reading ${settings.location}${errorsOf(response)}"
          )
        )

  private def dataOf(json: ujson.Value): ujson.Value = settings.kvVersion match
    case VaultSettings.KvVersion.V2 => json("data")("data")
    case VaultSettings.KvVersion.V1 => json("data")

  private def failure(reason: String): ConfigurationError =
    ConfigurationError(s"Vault secret ${settings.mount}/${settings.path} $reason")

  /**
   * Vault's own `errors` array, such as `permission denied`, which names no secret value. Only an error reply's body
   * is read here, never a secret's, and the token is removed should Vault ever echo it.
   */
  private def errorsOf(response: HttpResponse): String =
    Try(ujson.read(response.body)("errors").arr.flatMap(_.strOpt).toSeq).toOption
      .filter(_.nonEmpty)
      .map(_.mkString(": ", "; ", "").replace(settings.token.reveal, "***"))
      .getOrElse("")

  /** Closes the HTTP client this provider created; one the caller passed in is left open. */
  override def close(): Unit = if ownsClient then http.close()

  override def toString: String =
    s"VaultApiKeyProvider(${Redaction.url(settings.address)}, ${settings.location})"

@Experimental
object VaultApiKeyProvider:

  private val Provider = ProviderId("vault")

  /** The operation a `CancelledError` names when a caller is interrupted reading the key or waiting for a read. */
  private val Operation = "vault.read-api-key"

  /**
   * A provider reading the key `settings` locates, over an HTTP client of its own that [[VaultApiKeyProvider.close]]
   * closes. A `ConfigurationError` when a setting is blank or the address is not an http(s) URL; Vault is not
   * contacted until the first `current()`.
   */
  def apply(settings: VaultSettings): Result[VaultApiKeyProvider] =
    VaultSettings
      .validated(settings)
      .map(valid => new VaultApiKeyProvider(valid, Llm4sHttpClient.create(), ownsClient = true, Clock.systemUTC()))

  /**
   * A provider reading the key `settings` locates over `httpClient` - one configured with a proxy or custom TLS, say -
   * which stays the caller's to close.
   */
  def apply(settings: VaultSettings, httpClient: Llm4sHttpClient): Result[VaultApiKeyProvider] =
    apply(settings, httpClient, Clock.systemUTC())

  private[vault] def apply(
    settings: VaultSettings,
    httpClient: Llm4sHttpClient,
    clock: Clock
  ): Result[VaultApiKeyProvider] =
    VaultSettings
      .validated(settings)
      .map(valid => new VaultApiKeyProvider(valid, httpClient, ownsClient = false, clock))

  private def encode(segment: String): String =
    URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
