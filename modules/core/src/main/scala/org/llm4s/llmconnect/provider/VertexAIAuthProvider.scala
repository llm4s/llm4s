// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoSystemGetenv
package org.llm4s.llmconnect.provider

import org.llm4s.error.AuthenticationError
import org.llm4s.error.ThrowableOps._
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import scala.util.Try

/**
 * Provides Google Cloud OAuth2 access tokens for Vertex AI via ADC.
 *
 * Token resolution order:
 *  1. `GOOGLE_ACCESS_TOKEN` environment variable — direct token; useful for CI and testing.
 *  2. JSON credential file resolved from [[credentialFilePath]], then
 *     `GOOGLE_APPLICATION_CREDENTIALS`, then
 *     `~/.config/gcloud/application_default_credentials.json`.
 *     Supports both `authorized_user` (refresh-token flow) and `service_account`
 *     (RS256 JWT assertion flow, no extra dependencies).
 *  3. GCE / GKE metadata server — Workload Identity path for production deployments.
 *
 * Tokens are cached until one minute before expiry to minimise round-trips.
 *
 * @param credentialFilePath Optional explicit path to a Google JSON credential file.
 * @param httpClient         HTTP client for token-endpoint calls.
 * @param envReader          Environment variable reader; injectable for testing.
 * @param fileReader         File reader; injectable for testing.
 */
class VertexAIAuthProvider(
  credentialFilePath: Option[String],
  httpClient: Llm4sHttpClient,
  envReader: String => Option[String] = key => Option(System.getenv(key)),
  fileReader: String => Result[String] = path =>
    Try(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).toEither.left.map(_.toLLMError)
) {
  import VertexAIAuthProvider._

  private val logger = LoggerFactory.getLogger(getClass)

  @volatile private var cachedToken: Option[CachedToken] = None

  /** Returns a valid OAuth2 access token, fetching or refreshing as needed. */
  def getAccessToken(): Result[String] =
    cachedToken match {
      case Some(t) if !t.isExpired => Right(t.token)
      case _                       => fetchAndCacheToken()
    }

  private def fetchAndCacheToken(): Result[String] =
    fetchToken().map { t =>
      cachedToken = Some(t)
      t.token
    }

  private def fetchToken(): Result[CachedToken] = {
    envReader("GOOGLE_ACCESS_TOKEN") match {
      case Some(tok) if tok.nonEmpty =>
        logger.debug("[VertexAI] Using GOOGLE_ACCESS_TOKEN from environment")
        return Right(CachedToken(tok, Long.MaxValue))
      case _ => ()
    }

    resolveCredentialFilePath() match {
      case Some(path) =>
        fileReader(path).flatMap(fetchTokenFromCredentialFile)
      case None =>
        fetchTokenFromMetadataServer()
    }
  }

  private def resolveCredentialFilePath(): Option[String] =
    credentialFilePath
      .orElse(envReader("GOOGLE_APPLICATION_CREDENTIALS"))
      .orElse {
        val defaultPath =
          s"${System.getProperty("user.home")}/.config/gcloud/application_default_credentials.json"
        if (new java.io.File(defaultPath).exists()) Some(defaultPath) else None
      }

  private def fetchTokenFromCredentialFile(content: String): Result[CachedToken] =
    Try(ujson.read(content)).toEither.left.map(_.toLLMError).flatMap { json =>
      Try(json("type").str).toOption match {
        case Some("authorized_user") => refreshTokenFlow(json)
        case Some("service_account") => serviceAccountFlow(json)
        case Some(other)             => Left(AuthenticationError("vertexai", s"Unsupported credential type: $other"))
        case None                    => Left(AuthenticationError("vertexai", "Credential file missing 'type' field"))
      }
    }

  private def refreshTokenFlow(json: ujson.Value): Result[CachedToken] = {
    val clientId     = json("client_id").str
    val clientSecret = json("client_secret").str
    val refreshToken = json("refresh_token").str

    val body =
      s"client_id=${enc(clientId)}&client_secret=${enc(clientSecret)}" +
        s"&grant_type=refresh_token&refresh_token=${enc(refreshToken)}"

    val response = httpClient.post(
      TOKEN_ENDPOINT,
      Map("Content-Type" -> "application/x-www-form-urlencoded"),
      body
    )

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
          "vertexai",
          s"Token refresh failed (HTTP ${response.statusCode}): ${response.body}"
        )
      )
    }
  }

  private def serviceAccountFlow(json: ujson.Value): Result[CachedToken] = {
    val clientEmail   = json("client_email").str
    val privateKeyPem = json("private_key").str
    val tokenUri      = Try(json("token_uri").str).getOrElse(TOKEN_ENDPOINT)

    for {
      privateKey <- parsePrivateKey(privateKeyPem)
      jwt = createJwt(clientEmail, privateKey, tokenUri)
      token <- exchangeJwtForToken(jwt, tokenUri)
    } yield token
  }

  private def parsePrivateKey(pem: String): Result[java.security.PrivateKey] =
    Try {
      val cleaned = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "")
      val keyBytes = Base64.getDecoder.decode(cleaned)
      KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes))
    }.toEither.left.map(e => AuthenticationError("vertexai", s"Failed to parse private key: ${e.getMessage}"))

  private def createJwt(serviceAccountEmail: String, privateKey: java.security.PrivateKey, tokenUri: String): String = {
    val now     = System.currentTimeMillis() / 1000
    val encoder = Base64.getUrlEncoder.withoutPadding()

    val header = encoder.encodeToString(
      """{"alg":"RS256","typ":"JWT"}""".getBytes(StandardCharsets.UTF_8)
    )
    val payload = encoder.encodeToString(
      s"""{"iss":"$serviceAccountEmail","scope":"https://www.googleapis.com/auth/cloud-platform","aud":"$tokenUri","exp":${now + 3600},"iat":$now}"""
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
    val response = httpClient.post(
      tokenUri,
      Map("Content-Type" -> "application/x-www-form-urlencoded"),
      body
    )

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
          "vertexai",
          s"JWT token exchange failed (HTTP ${response.statusCode}): ${response.body}"
        )
      )
    }
  }

  private def fetchTokenFromMetadataServer(): Result[CachedToken] = {
    logger.debug("[VertexAI] Attempting to fetch token from GCE metadata server")
    Try {
      val response = httpClient.get(
        METADATA_TOKEN_URL,
        Map("Metadata-Flavor" -> "Google")
      )
      if (response.statusCode >= 200 && response.statusCode < 300) {
        Try {
          val json      = ujson.read(response.body)
          val token     = json("access_token").str
          val expiresIn = Try(json("expires_in").num.toInt).getOrElse(3600)
          val expiresAt = System.currentTimeMillis() + (expiresIn.toLong - 60) * 1000
          CachedToken(token, expiresAt)
        }.toEither.left.map(_.toLLMError)
      } else {
        Left(
          AuthenticationError(
            "vertexai",
            "No credentials found. Set GOOGLE_ACCESS_TOKEN, GOOGLE_APPLICATION_CREDENTIALS, " +
              "run 'gcloud auth application-default login', or deploy on GCE/GKE for Workload Identity."
          )
        )
      }
    }.toEither.left.map(_.toLLMError).flatten
  }

  private def enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

object VertexAIAuthProvider {
  private[provider] val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
  private[provider] val METADATA_TOKEN_URL =
    "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"

  private[provider] case class CachedToken(token: String, expiresAtMillis: Long) {
    def isExpired: Boolean = System.currentTimeMillis() >= expiresAtMillis
  }
}
