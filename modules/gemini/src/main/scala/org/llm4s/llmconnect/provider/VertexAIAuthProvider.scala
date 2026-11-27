// scalafix:off DisableSyntax.NoSystemGetenv
package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.error.{ AuthenticationError, NetworkError }
import org.llm4s.syntax.toLLMError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.security.{ Secret, TokenGenerations }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.Try
import org.llm4s.util.Redaction

/**
 * Provides Google Cloud OAuth2 access tokens for Vertex AI via ADC.
 *
 * Token resolution order (#3126: a configured file is never overridden by the environment):
 *  1. The JSON credential file at [[credentialFilePath]], when the section configures one.
 *  2. `GOOGLE_ACCESS_TOKEN` environment variable — direct token; useful for CI and testing. It is cached for
 *     five minutes only, so a rotated value is picked up.
 *  3. Application Default Credentials: the JSON credential file at `GOOGLE_APPLICATION_CREDENTIALS`, then
 *     gcloud's well-known `application_default_credentials.json`, found as Google's client
 *     libraries find it: in `$CLOUDSDK_CONFIG` when that is set (any OS), else
 *     `%APPDATA%\gcloud` on Windows, else `~/.config/gcloud`. `APPDATA` and `CLOUDSDK_CONFIG`
 *     are resolved at the config edge ([[org.llm4s.config.VertexAIAdcConfigLoader]]) and arrive as
 *     [[wellKnownCredentialPath]], not through [[envReader]].
 *     Supports both `authorized_user` (refresh-token flow) and `service_account`
 *     (RS256 JWT assertion flow, no extra dependencies).
 *     Then the GCE / GKE metadata server — Workload Identity path for production deployments.
 *
 * Tokens are cached until one minute before expiry to minimise round-trips. A credential file missing a field its
 * flow needs is an `AuthenticationError` naming the field, never its value.
 *
 * == Config-boundary exemption ==
 *
 * This class reads `GOOGLE_ACCESS_TOKEN` and `GOOGLE_APPLICATION_CREDENTIALS`
 * directly (hence the `scalafix:off NoSystemGetenv` at the top of the file),
 * rather than receiving them through [[org.llm4s.config.Llm4sConfig]] like other
 * settings. This is deliberate: Application Default Credentials is a runtime
 * discovery protocol whose inputs (a possibly-rotated access token, the ambient
 * credentials path, and the GCE/GKE metadata server) must be resolved lazily at
 * token-fetch time, not frozen into static config at startup. The reads are
 * funnelled through the injectable [[envReader]] so the class stays fully
 * testable without touching the real environment.
 *
 * @param credentialFilePath Optional explicit path to a Google JSON credential file.
 * @param httpClient         HTTP client for token-endpoint calls.
 * @param envReader          Environment variable reader; injectable for testing.
 * @param fileReader         File reader; injectable for testing.
 * @param wellKnownCredentialPath Location of gcloud's well-known credential file, as resolved by the config loader; `None` skips that lookup.
 */
@Stable
class VertexAIAuthProvider(
  credentialFilePath: Option[String],
  httpClient: Llm4sHttpClient,
  envReader: String => Option[String] = key => Option(System.getenv(key)),
  fileReader: String => Result[String] = path =>
    Try(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).toEither.left.map(_.toLLMError),
  wellKnownCredentialPath: Option[String] = None
) {
  import VertexAIAuthProvider._

  private val logger = LoggerFactory.getLogger(getClass)

  @volatile private var cachedToken: Option[CachedToken] = None
  // the `quota_project_id` of the `authorized_user` file the cached token came from
  @volatile private var quotaProjectId: Option[String] = None
  private val refreshLock                              = new Object
  // Each access token and each signed JWT assertion is registered with KnownSecrets before it is sent, so an
  // error body echoing one is scrubbed; the generation before the previous one is released.
  private val accessTokens = new TokenGenerations
  private val assertions   = new TokenGenerations

  /** Returns a valid OAuth2 access token, fetching or refreshing as needed. */
  def getAccessToken(): Result[String] =
    cachedToken match {
      case Some(t) if !t.isExpired => Right(t.token)
      case _ =>
        refreshLock.synchronized {
          cachedToken match {
            case Some(t) if !t.isExpired => Right(t.token)
            case _                       => fetchAndCacheToken()
          }
        }
    }

  /**
   * Drops the cached token when it is still `token`, so the next [[getAccessToken]] fetches a fresh one: for a token
   * the service refused (HTTP 401), as when it was revoked before its expiry.
   */
  private[llm4s] def invalidate(token: String): Unit =
    refreshLock.synchronized {
      if (cachedToken.exists(_.token == token)) cachedToken = None
    }

  /**
   * The `quota_project_id` of the `authorized_user` credential file the current token came from, which Vertex AI
   * bills user credentials to through the `x-goog-user-project` header.
   */
  private[llm4s] def quotaProject: Option[String] = quotaProjectId

  private def fetchAndCacheToken(): Result[String] =
    fetchToken().map { t =>
      accessTokens.rotate(t.token)
      cachedToken = Some(t)
      t.token
    }

  private def fetchToken(): Result[CachedToken] =
    credentialFilePath match {
      case Some(path) => fromFile(path)
      case None =>
        envReader("GOOGLE_ACCESS_TOKEN").filter(_.nonEmpty) match {
          case Some(tok) =>
            logger.debug("[VertexAI] Using GOOGLE_ACCESS_TOKEN from environment")
            quotaProjectId = None
            Right(CachedToken(tok, System.currentTimeMillis() + EnvTokenLife.toMillis))
          case None =>
            adcCredentialFilePath() match {
              case Some(path) => fromFile(path)
              case None =>
                quotaProjectId = None
                fetchTokenFromMetadataServer()
            }
        }
    }

  private def fromFile(path: String): Result[CachedToken] =
    fileReader(path).flatMap(fetchTokenFromCredentialFile)

  private def adcCredentialFilePath(): Option[String] =
    envReader("GOOGLE_APPLICATION_CREDENTIALS")
      .filter(_.nonEmpty)
      .orElse {
        wellKnownCredentialPath.filter(p => new java.io.File(p).exists())
      }

  private def fetchTokenFromCredentialFile(content: String): Result[CachedToken] =
    Try(ujson.read(content)).toEither.left.map(_.toLLMError).flatMap { json =>
      Try(json("type").str).toOption match {
        case Some("authorized_user") =>
          refreshTokenFlow(json).map { token =>
            quotaProjectId = optionalField(json, "quota_project_id")
            token
          }
        case Some("service_account") =>
          serviceAccountFlow(json).map { token =>
            quotaProjectId = None
            token
          }
        case Some(other) => Left(AuthenticationError(ProviderId("vertexai"), s"Unsupported credential type: $other"))
        case None        => Left(AuthenticationError(ProviderId("vertexai"), "Credential file missing 'type' field"))
      }
    }

  private def refreshTokenFlow(json: ujson.Value): Result[CachedToken] =
    for {
      clientId     <- requiredField(json, "authorized_user", "client_id")
      clientSecret <- requiredField(json, "authorized_user", "client_secret").map(Secret(_))
      refreshToken <- requiredField(json, "authorized_user", "refresh_token").map(Secret(_))
      token        <- exchangeRefreshToken(clientId, clientSecret, refreshToken)
    } yield token

  private def exchangeRefreshToken(
    clientId: String,
    clientSecret: Secret,
    refreshToken: Secret
  ): Result[CachedToken] = {
    val body =
      s"client_id=${enc(clientId)}&client_secret=${enc(clientSecret.reveal)}" +
        s"&grant_type=refresh_token&refresh_token=${enc(refreshToken.reveal)}"

    httpClient
      .post(
        TOKEN_ENDPOINT,
        Map("Content-Type" -> "application/x-www-form-urlencoded"),
        body,
        timeout = TokenRequestTimeout
      )
      .flatMap { response =>
        if (response.statusCode >= 200 && response.statusCode < 300) {
          Try {
            val respJson  = ujson.read(response.body)
            val token     = respJson("access_token").str
            val expiresIn = Try(respJson("expires_in").num.toInt).getOrElse(3600)
            val expiresAt = System.currentTimeMillis() + (expiresIn.toLong - 60) * 1000
            CachedToken(token, expiresAt)
          }.toEither.left.map(_.toLLMError)
        } else {
          Left(
            AuthenticationError(
              ProviderId("vertexai"),
              s"Token refresh failed (HTTP ${response.statusCode}): ${Redaction.safeBody(response.body)}"
            )
          )
        }
      }
  }

  private def serviceAccountFlow(json: ujson.Value): Result[CachedToken] = {
    val tokenUri = optionalField(json, "token_uri").getOrElse(TOKEN_ENDPOINT)
    for {
      clientEmail   <- requiredField(json, "service_account", "client_email")
      privateKeyPem <- requiredField(json, "service_account", "private_key")
      privateKey    <- parsePrivateKey(privateKeyPem)
      jwt <- Try(createJwt(clientEmail, privateKey, tokenUri)).toEither.left.map(e =>
        AuthenticationError(
          ProviderId("vertexai"),
          s"Failed to sign the service-account JWT: ${e.getClass.getSimpleName}"
        )
      )
      _ = assertions.rotate(jwt)
      token <- exchangeJwtForToken(jwt, tokenUri)
    } yield token
  }

  /** The string field `name` of a `kind` credential file, or an error naming the field (never a value). */
  private def requiredField(json: ujson.Value, kind: String, name: String): Result[String] =
    optionalField(json, name).toRight(
      AuthenticationError(ProviderId("vertexai"), s"The $kind credential file has no '$name' string field")
    )

  private def optionalField(json: ujson.Value, name: String): Option[String] =
    json.objOpt.flatMap(_.get(name)).flatMap(_.strOpt).filter(_.nonEmpty)

  private def parsePrivateKey(pem: String): Result[java.security.PrivateKey] =
    Try {
      val cleaned = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "")
      val keyBytes = Base64.getDecoder.decode(cleaned)
      KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes))
    }.toEither.left.map(e =>
      AuthenticationError(ProviderId("vertexai"), s"Failed to parse private key: ${e.getMessage}")
    )

  private def createJwt(serviceAccountEmail: String, privateKey: java.security.PrivateKey, tokenUri: String): String = {
    val now     = System.currentTimeMillis() / 1000
    val encoder = Base64.getUrlEncoder.withoutPadding()

    val header = encoder.encodeToString(
      ujson.Obj("alg" -> "RS256", "typ" -> "JWT").render().getBytes(StandardCharsets.UTF_8)
    )
    val payload = encoder.encodeToString(
      ujson
        .Obj(
          "iss"   -> serviceAccountEmail,
          "scope" -> "https://www.googleapis.com/auth/cloud-platform",
          "aud"   -> tokenUri,
          // ujson serialises Long as a JSON string to avoid precision loss; JWT
          // NumericDate claims must be JSON numbers, so build them as ujson.Num.
          "exp" -> ujson.Num((now + 3600).toDouble),
          "iat" -> ujson.Num(now.toDouble)
        )
        .render()
        .getBytes(StandardCharsets.UTF_8)
    )

    val signingInput = s"$header.$payload"
    val signer       = Signature.getInstance("SHA256withRSA")
    signer.initSign(privateKey)
    signer.update(signingInput.getBytes(StandardCharsets.UTF_8))
    val sig = encoder.encodeToString(signer.sign())

    s"$signingInput.$sig"
  }

  private def exchangeJwtForToken(jwt: String, tokenUri: String): Result[CachedToken] = {
    val body = s"grant_type=${enc("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${enc(jwt)}"
    httpClient
      .post(
        tokenUri,
        Map("Content-Type" -> "application/x-www-form-urlencoded"),
        body,
        timeout = TokenRequestTimeout
      )
      .flatMap { response =>
        if (response.statusCode >= 200 && response.statusCode < 300) {
          Try {
            val respJson  = ujson.read(response.body)
            val token     = respJson("access_token").str
            val expiresIn = Try(respJson("expires_in").num.toInt).getOrElse(3600)
            val expiresAt = System.currentTimeMillis() + (expiresIn.toLong - 60) * 1000
            CachedToken(token, expiresAt)
          }.toEither.left.map(_.toLLMError)
        } else {
          Left(
            AuthenticationError(
              ProviderId("vertexai"),
              s"JWT token exchange failed (HTTP ${response.statusCode}): ${Redaction.safeBody(response.body)}"
            )
          )
        }
      }
  }

  private def fetchTokenFromMetadataServer(): Result[CachedToken] = {
    logger.debug("[VertexAI] Attempting to fetch token from GCE metadata server")
    // The metadata server is only reachable on GCE/GKE. An unresolved host means we are
    // off-GCP (no credentials), while other failures are transient and must stay retryable.
    httpClient.get(METADATA_TOKEN_URL, Map("Metadata-Flavor" -> "Google"), timeout = TokenRequestTimeout) match {
      case Right(response) if response.statusCode >= 200 && response.statusCode < 300 =>
        Try {
          val json      = ujson.read(response.body)
          val token     = json("access_token").str
          val expiresIn = Try(json("expires_in").num.toInt).getOrElse(3600)
          val expiresAt = System.currentTimeMillis() + (expiresIn.toLong - 60) * 1000
          CachedToken(token, expiresAt)
        }.toEither.left.map(_.toLLMError)
      case Right(response) =>
        logger.debug(s"[VertexAI] Metadata server returned HTTP ${response.statusCode}")
        Left(noCredentialsError)
      case Left(e: NetworkError) if e.cause.exists(isUnknownHost) =>
        // Host does not resolve, so this process is not on GCE/GKE — there genuinely are no
        // credentials. Give actionable guidance (non-recoverable) rather than a network error.
        logger.debug(s"[VertexAI] Metadata server host unresolved (not on GCE/GKE): ${e.message}")
        Left(noCredentialsError)
      case Left(e) =>
        // Host resolved but the request failed transiently (timeout, connection refused).
        // The HTTP client reports it as a recoverable error, so a ReliableClient can retry
        // through a momentary metadata-server outage on GCE/GKE.
        logger.debug(s"[VertexAI] Metadata server request failed transiently: ${e.message}")
        Left(e)
    }
  }

  /** True if `t` or any exception in its cause chain is a [[java.net.UnknownHostException]]. */
  private def isUnknownHost(t: Throwable): Boolean = {
    @annotation.tailrec
    def loop(cause: Throwable): Boolean = cause match {
      case null                             => false
      case _: java.net.UnknownHostException => true
      case other                            => loop(other.getCause)
    }
    loop(t)
  }

  private def enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

object VertexAIAuthProvider {
  private[provider] val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

  /** Timeout for token-endpoint and metadata-server requests (the client's default). */
  private val TokenRequestTimeout: FiniteDuration = 10.seconds
  private[provider] val METADATA_TOKEN_URL =
    "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"

  private val noCredentialsError: AuthenticationError =
    AuthenticationError(
      ProviderId("vertexai"),
      "No credentials found. Set GOOGLE_ACCESS_TOKEN, GOOGLE_APPLICATION_CREDENTIALS, " +
        "run 'gcloud auth application-default login', or deploy on GCE/GKE for Workload Identity."
    )

  /** How long a `GOOGLE_ACCESS_TOKEN` from the environment is used before the variable is read again. */
  private[provider] val EnvTokenLife: FiniteDuration = 5.minutes

  private[provider] case class CachedToken(token: String, expiresAtMillis: Long) {
    def isExpired: Boolean = System.currentTimeMillis() >= expiresAtMillis
  }
}
