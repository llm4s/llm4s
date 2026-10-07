package org.llm4s.llmconnect.auth

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.provider.HttpErrorMapper
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.net.{ URI, URLEncoder }
import java.nio.charset.StandardCharsets
import java.time.{ Clock, Instant }
import java.util.Base64
import java.util.Locale
import scala.concurrent.duration.*
import scala.util.Try

/**
 * An RFC 8693 token exchange: present `identityToken` at `tokenUrl`, get back a short-lived
 * bearer token. Databricks workload identity federation is one such endpoint
 * (`https://<workspace>/oidc/v1/token`, `scope = all-apis`, `clientId` = the service principal).
 *
 * `toString` shows `tokenUrl` without its userinfo or query, which may carry credentials, and
 * redacts `clientId`.
 */
@Experimental
final case class TokenExchangeConfig private (
  identityToken: IdentitySource,
  tokenUrl: String,
  clientId: Option[String],
  scope: Option[String],
  audience: Option[String]
):
  def withIdentityToken(identityToken: IdentitySource): TokenExchangeConfig = copy(identityToken = identityToken)
  def withTokenUrl(tokenUrl: String): TokenExchangeConfig                   = copy(tokenUrl = tokenUrl)
  def withClientId(clientId: String): TokenExchangeConfig                   = copy(clientId = Some(clientId))
  def withClientId(clientId: Option[String]): TokenExchangeConfig           = copy(clientId = clientId)
  def withScope(scope: String): TokenExchangeConfig                         = copy(scope = Some(scope))
  def withScope(scope: Option[String]): TokenExchangeConfig                 = copy(scope = scope)
  def withAudience(audience: String): TokenExchangeConfig                   = copy(audience = Some(audience))
  def withAudience(audience: Option[String]): TokenExchangeConfig           = copy(audience = audience)

  override def toString: String =
    s"TokenExchangeConfig($identityToken, tokenUrl=${Redaction.url(tokenUrl)}, " +
      s"clientId=${Redaction.secretOpt(clientId)}, scope=$scope, audience=$audience)"

object TokenExchangeConfig:

  /** Creates a [[TokenExchangeConfig]]. Named arguments are the supported way to construct one. */
  def apply(
    identityToken: IdentitySource,
    tokenUrl: String,
    clientId: Option[String] = None,
    scope: Option[String] = None,
    audience: Option[String] = None
  ): TokenExchangeConfig =
    new TokenExchangeConfig(identityToken, tokenUrl, clientId, scope, audience)

  /**
   * The rules every [[TokenExchangeConfig]] must meet, whichever way it was built: the identity token
   * (a literal, or a file's path) and `tokenUrl` must not be blank, `clientId`, `scope` and `audience`
   * must not be blank when set - a blank one would be posted as an empty form field - and `tokenUrl`
   * must be `https`, or plain `http` to a loopback host ([[TokenExchange.requireSecureUrl]]). Every
   * exchange applies them, as do the configs that carry one. A refusal is a `ConfigurationError`
   * naming the one field at fault (`tokenUrl`, `clientId`, `identityTokenFile`, ...), which a caller
   * nesting this config renames to its own key.
   */
  private[llm4s] def validate(config: TokenExchangeConfig): Result[TokenExchangeConfig] =
    val label = "token exchange"
    for
      _ <- ProviderConfig.nonEmptyIdentity(label, "", config.identityToken)
      _ <- ProviderConfig.nonEmpty(label, "tokenUrl", config.tokenUrl)
      _ <- ProviderConfig.nonEmptyIfSet(label, "clientId", config.clientId)
      _ <- ProviderConfig.nonEmptyIfSet(label, "scope", config.scope)
      _ <- ProviderConfig.nonEmptyIfSet(label, "audience", config.audience)
      _ <- TokenExchange
        .requireSecureUrl(config.tokenUrl)
        .left
        .map(e => ConfigurationError(e.message, List("tokenUrl")))
    yield config

/** The RFC 8693 token exchange and its caching provider. */
@Experimental
object TokenExchange:

  val GrantType: String              = "urn:ietf:params:oauth:grant-type:token-exchange"
  val JwtTokenType: String           = "urn:ietf:params:oauth:token-type:jwt"
  val DefaultTimeout: FiniteDuration = 30.seconds

  /**
   * The longest lifetime a token is believed to have, whatever the endpoint's `expires_in` says: a reply
   * claiming more (`1e30`, say) is clamped to this, so a buggy or hostile endpoint cannot make a token
   * outlive any sensible rotation or overflow the arithmetic.
   */
  val MaxLifetime: FiniteDuration = 24.hours

  /**
   * The lifetime assumed for a token whose reply has no `expires_in` (RFC 6749 makes it optional) and
   * which is not a JWT carrying an `exp` claim: short, so a token that in fact lives less is not used
   * for long after it has expired - and a 401 refreshes it in any case.
   */
  val DefaultLifetime: FiniteDuration = 5.minutes

  private val Provider     = "token-exchange"
  private val Ipv4Loopback = """127(?:\.\d{1,3}){3}""".r

  /**
   * Whether `url` may receive the identity token: `https`, or `http` only when the URL's real host - the
   * one after any `userinfo@`, so `http://localhost@evil.example/` is `evil.example` - is a loopback
   * literal (`localhost`, `127.x.y.z`, `[::1]`; names are not resolved). The refusal does not echo the URL,
   * which may carry credentials; it names `key`, the setting the URL came from, and `carries`, the secret
   * the request would expose.
   */
  private[llm4s] def requireSecureUrl(
    url: String,
    key: String = "tokenUrl",
    carries: String = "the identity token"
  ): Result[Unit] =
    val refusal = Left(
      ConfigurationError(
        s"$key must be an https URL (plain http is accepted only for a loopback host such as localhost or " +
          s"127.0.0.1), because the request carries $carries"
      )
    )
    Try(new URI(url.trim)).toOption.flatMap(uri =>
      Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT)).map(uri -> _)
    ) match
      case Some((uri, "https")) if Option(uri.getHost).exists(_.nonEmpty) => Right(())
      case Some((uri, "http")) if Option(uri.getHost).exists(isLoopback)  => Right(())
      case _                                                              => refusal

  /**
   * Whether `url` may receive a vendor's workload-identity credential: `https` to a host that is exactly
   * one of `trustedHosts`, or `http`/`https` to a loopback literal (the same exemption as
   * [[requireSecureUrl]], for local test servers). It is an allow-list, so a third-party host, a lookalike
   * (`api.openai.com.evil.example`, `evilapi.openai.com`), a trailing-dot form or an IP address is refused.
   * The host compares case-insensitively; a URL with `userinfo@` is refused outright, since it can only be
   * a mistake or an attempt to dress one host up as another. `trustedHosts` must be lower case. The refusal
   * names `vendor` and `key` and lists `trustedHosts`, but not the URL, which may carry credentials.
   */
  private[llm4s] def requireTrustedHost(
    url: String,
    key: String,
    vendor: String,
    trustedHosts: Set[String]
  ): Result[Unit] =
    val refusal = Left(
      ConfigurationError(
        s"$key must be an https URL to ${trustedHosts.toSeq.sorted.mkString(" or ")} with $vendor workload " +
          s"identity, because requests to it carry the $vendor credential (plain http is accepted only for a " +
          "loopback host such as localhost or 127.0.0.1)",
        List(key)
      )
    )
    Try(new URI(url.trim)).toOption
      .filter(uri => uri.getRawUserInfo == null)
      .flatMap(uri =>
        for
          scheme <- Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT))
          host   <- Option(uri.getHost).map(_.toLowerCase(Locale.ROOT)).filter(_.nonEmpty)
        yield (scheme, host)
      ) match
      case Some(("https", host)) if trustedHosts.contains(host) => Right(())
      case Some(("https" | "http", host)) if isLoopback(host)   => Right(())
      case _                                                    => refusal

  private def isLoopback(host: String): Boolean =
    val h = host.toLowerCase(Locale.ROOT)
    h == "localhost" || h == "[::1]" || (Ipv4Loopback.matches(h) && h.split('.').forall(_.toInt <= 255))

  /** One exchange per call: read the identity token, post it, parse the reply. */
  def rfc8693(
    config: TokenExchangeConfig,
    httpClient: Llm4sHttpClient,
    clock: Clock = Clock.systemUTC(),
    timeout: FiniteDuration = DefaultTimeout
  ): () => Result[AccessToken] =
    val subject = IdentityTokenSource.from(config.identityToken)
    () =>
      for
        _   <- TokenExchangeConfig.validate(config)
        jwt <- subject.fetch()
        response <- httpClient.post(
          config.tokenUrl,
          Map("Content-Type" -> "application/x-www-form-urlencoded", "Accept" -> "application/json"),
          form(config, jwt),
          timeout
        )
        token <- parse(response, sensitiveValues(config, jwt), clock)
      yield token

  /** [[rfc8693]] behind a [[CachingAccessTokenProvider]]. */
  def provider(
    config: TokenExchangeConfig,
    httpClient: Llm4sHttpClient,
    refreshMargin: FiniteDuration = CachingAccessTokenProvider.DefaultRefreshMargin
  ): AccessTokenProvider =
    CachingAccessTokenProvider(rfc8693(config, httpClient), refreshMargin)

  private def form(config: TokenExchangeConfig, jwt: String): String =
    (Seq("grant_type" -> GrantType, "subject_token" -> jwt, "subject_token_type" -> JwtTokenType) ++
      config.clientId.map("client_id" -> _) ++
      config.scope.map("scope" -> _) ++
      config.audience.map("audience" -> _))
      .map((k, v) => s"${encode(k)}=${encode(v)}")
      .mkString("&")

  private def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  /**
   * The values a token-endpoint reply must never repeat into an error: the subject token posted, the
   * configured identity token when it is a literal, and the `clientId` (redacted in `toString` too).
   */
  private def sensitiveValues(config: TokenExchangeConfig, jwt: String): Seq[String] =
    Seq(jwt) ++ config.clientId ++ (config.identityToken match
      case IdentitySource.Literal(token) => Seq(token)
      case IdentitySource.File(_)        => Nil
    )

  /**
   * A token-endpoint reply's body fit for an error message: `secrets` scrubbed by exact match (with their
   * URL-encoded and JSON-escaped forms), credential fields such as `access_token`, `refresh_token`,
   * `id_token` or `client_secret` scrubbed by key, the general patterns (bearer tokens, JWTs, API keys)
   * redacted, and the result truncated - some identity providers quote the rejected assertion or the
   * client id back, and an error message reaches logs.
   */
  private[auth] def safeBody(body: String, secrets: Seq[String]): String =
    Redaction.remoteBody(body, secrets, MaxErrorBodyLength)

  private val MaxErrorBodyLength = 512

  /**
   * The reply's token and expiry. `expires_in` is optional: without it the expiry is the access token's
   * own `exp` claim when the token is a JWT, and otherwise [[DefaultLifetime]] from now.
   */
  private def parse(response: HttpResponse, secrets: Seq[String], clock: Clock): Result[AccessToken] =
    // Some identity providers quote the rejected assertion or client id back; it must not reach a log line.
    lazy val errorBody = safeBody(response.body, secrets)
    response.statusCode match
      case status if status >= 200 && status < 300 =>
        val obj = Try(ujson.read(response.body)).toOption.flatMap(_.objOpt)
        obj
          .flatMap(_.get("access_token").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty))
          .toRight(AuthenticationError(Provider, "token endpoint reply has no access_token"))
          .flatMap { access =>
            val now = clock.instant()
            obj.flatMap(_.get("expires_in")) match
              case Some(value) =>
                value.numOpt.orElse(value.strOpt.flatMap(_.trim.toDoubleOption)) match
                  // A non-finite or negative lifetime is the endpoint's bug, not a token to cache; a huge one is clamped.
                  case Some(expiry) if !expiry.isNaN && !expiry.isInfinite && expiry >= 0 =>
                    Right(AccessToken(access, now.plusSeconds(math.min(expiry.toLong, MaxLifetime.toSeconds))))
                  case _ =>
                    Left(
                      AuthenticationError(
                        Provider,
                        "token endpoint reply has an invalid expires_in (not a finite, non-negative number)"
                      )
                    )
              case None =>
                val expiresAt = jwtExpiry(access) match
                  case Some(exp) =>
                    val latest = now.plusSeconds(MaxLifetime.toSeconds)
                    if exp.isAfter(latest) then latest else exp
                  case None => now.plusSeconds(DefaultLifetime.toSeconds)
                Right(AccessToken(access, expiresAt))
          }
      case status @ (400 | 401 | 403) =>
        Left(AuthenticationError(Provider, s"token endpoint rejected the identity token (HTTP $status): $errorBody"))
      case status =>
        // Scrubbed of the exact values before HttpErrorMapper extracts and redacts its detail.
        HttpErrorMapper.mapHttpError(status, Redaction.scrubRemote(response.body, secrets), Provider, response.headers)

  /** The `exp` claim of `token`, if it is a JWT with a numeric one; the signature is not checked. */
  private def jwtExpiry(token: String): Option[Instant] =
    token.split('.') match
      case Array(_, payload, _) =>
        Try(new String(Base64.getUrlDecoder.decode(payload), StandardCharsets.UTF_8)).toOption
          .flatMap(json => Try(ujson.read(json)).toOption)
          .flatMap(_.objOpt)
          .flatMap(_.get("exp"))
          .flatMap(_.numOpt)
          // Bounded well inside Instant's range; the caller clamps to MaxLifetime anyway.
          .filter(exp => !exp.isNaN && !exp.isInfinite && exp > 0)
          .map(exp => Instant.ofEpochSecond(math.min(exp, 1e12).toLong))
      case _ => None
