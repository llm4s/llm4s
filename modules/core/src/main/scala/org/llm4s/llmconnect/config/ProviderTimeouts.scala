package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

/**
 * How long a provider's HTTP calls may take, as configured in the `timeouts` block of a named provider
 * section:
 *
 * {{{
 * llm4s.providers.my-openai {
 *   provider = "openai"
 *   model    = "gpt-4o"
 *   timeouts { request = 3m, stream = 15m }
 * }
 * }}}
 *
 * Each value is optional. An absent value means ''this provider's own default'', which differs by client
 * (for example 2 minutes for a Gemini or Ollama request and 10 minutes for its stream), so a client reads
 * a timeout with [[requestOr]] or [[streamOr]] and supplies its own default. A section without a
 * `timeouts` block behaves exactly as it did before the block existed.
 *
 *  - `request` bounds a call that returns one response: a completion, an embedding.
 *  - `stream` bounds a streamed completion, from the request to the end of the stream. It is separate
 *    because a long generation legitimately outlives a request that returns at once.
 *
 * A value must be positive and finite: `java.net.http.HttpRequest.timeout` rejects anything else, and
 * `Duration.Inf` is not a `FiniteDuration`. Loading a section checks it ([[validated]]); a value set with
 * `withRequest` or `withStream` on a hand-built config is not checked until a client uses it.
 *
 * The constructor is private: build one with the companion `apply` or [[ProviderTimeouts.default]] and
 * adjust it with the `with*` setters, so adding a field later never breaks a caller.
 *
 * @param request the timeout of a call that returns one response, or `None` for the client's default
 * @param stream  the timeout of a streamed completion, or `None` for the client's default
 */
@Stable
final case class ProviderTimeouts private (
  request: Option[FiniteDuration],
  stream: Option[FiniteDuration]
):

  def withRequest(request: FiniteDuration): ProviderTimeouts         = copy(request = Some(request))
  def withRequest(request: Option[FiniteDuration]): ProviderTimeouts = copy(request = request)
  def withStream(stream: FiniteDuration): ProviderTimeouts           = copy(stream = Some(stream))
  def withStream(stream: Option[FiniteDuration]): ProviderTimeouts   = copy(stream = stream)

  /** The configured request timeout, or `default` when the section sets none. */
  def requestOr(default: FiniteDuration): FiniteDuration = request.getOrElse(default)

  /** The configured stream timeout, or `default` when the section sets none. */
  def streamOr(default: FiniteDuration): FiniteDuration = stream.getOrElse(default)

  /** `true` when neither timeout is set, so every client keeps its own default. */
  def isDefault: Boolean = request.isEmpty && stream.isEmpty

object ProviderTimeouts:

  /** No timeout set: each client keeps its own default. */
  val default: ProviderTimeouts = new ProviderTimeouts(None, None)

  /** Builds [[ProviderTimeouts]] without checking the values; [[validated]] checks them. */
  def apply(
    request: Option[FiniteDuration] = None,
    stream: Option[FiniteDuration] = None
  ): ProviderTimeouts =
    new ProviderTimeouts(request, stream)

  /**
   * Builds [[ProviderTimeouts]], rejecting a value that is not positive.
   *
   * The error names the key as it is written in a section (`timeouts.request`), so the caller can
   * prefix the section's path.
   *
   * @param request the request timeout, if set
   * @param stream  the stream timeout, if set
   */
  def validated(
    request: Option[FiniteDuration],
    stream: Option[FiniteDuration]
  ): Result[ProviderTimeouts] =
    validatedAt("timeouts", request, stream)

  /** [[validated]] with the errors naming the keys under `path`, e.g. `llm4s.providers.main.timeouts`. */
  private[llm4s] def validatedAt(
    path: String,
    request: Option[FiniteDuration],
    stream: Option[FiniteDuration]
  ): Result[ProviderTimeouts] =
    for
      _ <- positive(s"$path.request", request)
      _ <- positive(s"$path.stream", stream)
    yield new ProviderTimeouts(request, stream)

  private def positive(key: String, value: Option[FiniteDuration]): Result[Unit] =
    value match
      case Some(d) if d.length <= 0 =>
        Left(
          ConfigurationError(
            s"`$key` must be greater than zero, but is $d",
            List(key)
          )
        )
      case _ => Right(())
