package org.llm4s.llmconnect.auth

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.provider.HttpErrorMapper
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.net.{ URI, URLEncoder }
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Locale
import scala.concurrent.duration.*
import scala.util.Try

/**
 * An RFC 8693 token exchange: present `identityToken` at `tokenUrl`, get back a short-lived
 * bearer token. Databricks workload identity federation is one such endpoint
 * (`https://<workspace>/oidc/v1/token`, `scope = all-apis`, `clientId` = the service principal).
 */
@Experimental
final case class TokenExchangeConfig(
  identityToken: IdentitySource,
  tokenUrl: String,
  clientId: Option[String] = None,
  scope: Option[String] = None,
  audience: Option[String] = None
)

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

  private val Provider     = "token-exchange"
  private val Ipv4Loopback = """127(?:\.\d{1,3}){3}""".r

  /**
   * Whether `url` may receive the identity token: `https`, or `http` only when the URL's real host - the
   * one after any `userinfo@`, so `http://localhost@evil.example/` is `evil.example` - is a loopback
   * literal (`localhost`, `127.x.y.z`, `[::1]`; names are not resolved). The refusal does not echo the URL,
   * which may carry credentials.
   */
  private[llm4s] def requireSecureUrl(url: String): Result[Unit] =
    val refusal = Left(
      ConfigurationError(
        "tokenUrl must be an https URL (plain http is accepted only for a loopback host such as localhost or " +
          "127.0.0.1), because the request carries the identity token"
      )
    )
    Try(new URI(url.trim)).toOption.flatMap(uri =>
      Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT)).map(uri -> _)
    ) match
      case Some((uri, "https")) if Option(uri.getHost).exists(_.nonEmpty) => Right(())
      case Some((uri, "http")) if Option(uri.getHost).exists(isLoopback)  => Right(())
      case _                                                              => refusal

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
        _   <- requireSecureUrl(config.tokenUrl)
        jwt <- subject.fetch()
        response <- httpClient.post(
          config.tokenUrl,
          Map("Content-Type" -> "application/x-www-form-urlencoded", "Accept" -> "application/json"),
          form(config, jwt),
          timeout
        )
        token <- parse(response, jwt, clock)
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

  private def parse(response: HttpResponse, jwt: String, clock: Clock): Result[AccessToken] =
    // Some identity providers quote the rejected assertion back; it must not reach a log line.
    val safeBody = Redaction.truncateForLog(response.body.replace(jwt, "***"), 512)
    response.statusCode match
      case status if status >= 200 && status < 300 =>
        Try(ujson.read(response.body)).toOption
          .flatMap(_.objOpt)
          .flatMap { obj =>
            for
              access <- obj.get("access_token").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
              expiry <- obj.get("expires_in").flatMap(v => v.numOpt.orElse(v.strOpt.flatMap(_.trim.toDoubleOption)))
            yield access -> expiry
          }
          .toRight(AuthenticationError(Provider, "token endpoint reply has no access_token or expires_in"))
          .flatMap { case (access, expiry) =>
            // A non-finite or negative lifetime is the endpoint's bug, not a token to cache; a huge one is clamped.
            Either.cond(
              !expiry.isNaN && !expiry.isInfinite && expiry >= 0,
              AccessToken(access, clock.instant().plusSeconds(math.min(expiry.toLong, MaxLifetime.toSeconds))),
              AuthenticationError(
                Provider,
                "token endpoint reply has an invalid expires_in (not a finite, non-negative number)"
              )
            )
          }
      case status @ (400 | 401 | 403) =>
        Left(AuthenticationError(Provider, s"token endpoint rejected the identity token (HTTP $status): $safeBody"))
      case status =>
        HttpErrorMapper.mapHttpError(status, safeBody, Provider, response.headers)
