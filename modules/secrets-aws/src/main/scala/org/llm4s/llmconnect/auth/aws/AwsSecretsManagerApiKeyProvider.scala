package org.llm4s.llmconnect.auth.aws

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ AuthenticationError, ConfigurationError, NotFoundError, ServiceError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.types.Result

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.{ Clock, Instant }
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/**
 * An [[org.llm4s.llmconnect.auth.ApiKeyProvider ApiKeyProvider]] that reads the API key from AWS Secrets Manager
 * (#2479) with its `GetSecretValue` action, for a deployment that cannot run the Secrets Store CSI driver or External
 * Secrets Operator to write the key to a file (`apiKeyFile`).
 *
 * The key is read on the first `current()`, then reused for [[AwsSecretsManagerSettings.refreshInterval]], then read
 * again, so a key rotated in Secrets Manager is sent from the first request after the interval. Concurrent callers
 * share one read. A read that fails is a `Left` and is tried again on the next call; the last key is not served after
 * its interval, since a rotated-out key may already be revoked.
 *
 * {{{
 * for
 *   keys   <- AwsSecretsManagerApiKeyProvider(
 *               AwsSecretsManagerSettings("eu-west-1", AwsCredentials(accessKeyId, Secret(secretAccessKey)), "llm4s/openai")
 *                 .withField("apiKey")
 *             )
 *   config <- Llm4sConfig.provider(ProviderName("openai-main")).flatMap(_.withApiKeyProvider(keys))
 *   client <- LLMConnect.getClient(config)
 * yield client
 * }}}
 *
 * Errors never hold the key or a credential: an `AuthenticationError` when AWS refuses the credentials or denies
 * access, a `NotFoundError` when there is no such secret or version stage, a `ConfigurationError` when the secret has
 * no usable string (binary only, a missing or blank field, more than one line), a `ServiceError` for any other
 * answer, and the HTTP client's own error when Secrets Manager cannot be reached.
 *
 * It depends on no AWS SDK: one `POST` with llm4s's own HTTP client, signed with Signature Version 4. Getting the
 * credentials - from an instance profile, IRSA or an assumed role - stays the caller's job. Close the provider to
 * close the HTTP client it created; one passed to `AwsSecretsManagerApiKeyProvider(settings, httpClient)` stays the
 * caller's to close.
 */
@Experimental
final class AwsSecretsManagerApiKeyProvider private (
  settings: AwsSecretsManagerSettings,
  http: Llm4sHttpClient,
  ownsClient: Boolean,
  clock: Clock
) extends ApiKeyProvider
    with AutoCloseable:

  import AwsSecretsManagerApiKeyProvider.*

  private val cached = new AtomicReference[Option[Cached]](None)
  private val lock   = new Object
  private val uri    = URI.create(settings.endpointUrl + "/")

  override def current(): Result[ApiKey] =
    fresh().map(Right(_)).getOrElse {
      lock.synchronized {
        // Another caller may have read the key while this one waited.
        fresh().map(Right(_)).getOrElse {
          val readAt = clock.instant()
          read(readAt).map { key =>
            cached.set(Some(Cached(key, readAt.plusNanos(settings.refreshInterval.toNanos))))
            key
          }
        }
      }
    }

  private def fresh(): Option[ApiKey] =
    cached.get().collect { case c if clock.instant().isBefore(c.expires) => c.key }

  private def read(at: Instant): Result[ApiKey] =
    for
      credentials <- Try(settings.credentials()).toEither.left
        .map(_ => ConfigurationError(s"AWS credentials for ${settings.location} could not be obtained"))
        .flatten
      valid    <- validCredentials(credentials)
      response <- send(valid, at)
      key      <- answer(response, valid)
    yield key

  private def send(credentials: AwsCredentials, at: Instant): Result[HttpResponse] =
    val payload = ujson
      .write(ujson.Obj("SecretId" -> settings.secretId, "VersionStage" -> settings.versionStage))
      .getBytes(StandardCharsets.UTF_8)
    val headers = Map("Content-Type" -> ContentType, "X-Amz-Target" -> Target)
    val signed  = SigV4.sign("POST", uri, headers, payload, credentials, settings.region, Service, at)
    http.postBytes(uri.toString, headers ++ signed + ("Accept" -> ContentType), payload, settings.requestTimeout)

  private def answer(response: HttpResponse, credentials: AwsCredentials): Result[ApiKey] =
    if response.statusCode == 200 then keyOf(response.body)
    else
      val (kind, detail) = errorOf(response, credentials)
      val status         = response.statusCode
      val what           = s"reading ${settings.location} (HTTP $status${kind.fold("")(", " + _)})$detail"
      kind match
        case Some("ResourceNotFoundException") =>
          Left(NotFoundError(s"AWS Secrets Manager has no secret ${settings.location}$detail", settings.location))
        case Some(k) if AuthFailures.contains(k) =>
          Left(AuthenticationError(Provider, s"AWS refused the credentials $what"))
        case _ if status == 401 || status == 403 =>
          Left(AuthenticationError(Provider, s"AWS refused the credentials $what"))
        case _ =>
          Left(ServiceError(status, Provider, s"AWS Secrets Manager answered $what"))

  private def keyOf(body: String): Result[ApiKey] =
    val reply  = Try(ujson.read(body)).toOption
    val text   = reply.flatMap(r => Try(r("SecretString")).toOption).flatMap(_.strOpt)
    val binary = reply.exists(r => Try(r("SecretBinary")).isSuccess)
    text match
      case None if binary => Left(failure("holds only SecretBinary, not a SecretString"))
      case None           => Left(failure("came back with no SecretString"))
      case Some(secret) =>
        settings.field
          .fold(Option(secret))(field => Try(ujson.read(secret)(field)).toOption.flatMap(_.strOpt))
          .map(_.trim)
          .filter(_.nonEmpty)
          .toRight(failure(settings.field.fold("has a blank SecretString")(f => s"has no non-blank string field '$f'")))
          .flatMap { key =>
            // A line break in a header value would be refused by the HTTP client, or split the header.
            if key.exists(c => c == '\n' || c == '\r') then Left(failure("holds more than one line"))
            else Right(ApiKey(key))
          }

  private def failure(reason: String): ConfigurationError =
    ConfigurationError(s"AWS Secrets Manager secret ${settings.location} $reason")

  /**
   * AWS's error type (without its namespace) and message, such as `AccessDeniedException` and the policy that denied
   * it. Only an error reply's body is read here, never a secret's, and the credentials are removed should AWS ever
   * echo them.
   */
  private def errorOf(response: HttpResponse, credentials: AwsCredentials): (Option[String], String) =
    val json = Try(ujson.read(response.body)).toOption
    def str(key: String): Option[String] =
      json.flatMap(j => Try(j(key)).toOption).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
    val kind = str("__type")
      .orElse(response.header("x-amzn-ErrorType").map(_.takeWhile(_ != ':')))
      .map(_.split('#').last)
    val secrets = credentials.secretAccessKey.reveal +: credentials.sessionToken.map(_.reveal).toSeq
    val message = str("message").orElse(str("Message")).map { m =>
      secrets.filter(_.nonEmpty).foldLeft(m)((text, secret) => text.replace(secret, "***"))
    }
    (kind, message.fold("")(": " + _))

  /** Closes the HTTP client this provider created; one the caller passed in is left open. */
  override def close(): Unit = if ownsClient then http.close()

  override def toString: String = s"AwsSecretsManagerApiKeyProvider(${settings.endpointUrl}, ${settings.location})"

@Experimental
object AwsSecretsManagerApiKeyProvider:

  private val Provider    = "aws-secrets-manager"
  private val Service     = "secretsmanager"
  private val Target      = "secretsmanager.GetSecretValue"
  private val ContentType = "application/x-amz-json-1.1"

  /** The error types AWS answers when it does not accept the credentials or the signature, or denies the read. */
  private val AuthFailures = Set(
    "AccessDeniedException",
    "UnrecognizedClientException",
    "InvalidSignatureException",
    "IncompleteSignature",
    "MissingAuthenticationToken",
    "ExpiredTokenException",
    "InvalidClientTokenId",
    "SignatureDoesNotMatch"
  )

  final private case class Cached(key: ApiKey, expires: Instant)

  /**
   * A provider reading the key `settings` locates, over an HTTP client of its own that
   * [[AwsSecretsManagerApiKeyProvider.close]] closes. A `ConfigurationError` when a setting is blank or invalid;
   * Secrets Manager is not contacted, nor are the credentials asked for, until the first `current()`.
   */
  def apply(settings: AwsSecretsManagerSettings): Result[AwsSecretsManagerApiKeyProvider] =
    AwsSecretsManagerSettings
      .validated(settings)
      .map(valid =>
        new AwsSecretsManagerApiKeyProvider(valid, Llm4sHttpClient.create(), ownsClient = true, Clock.systemUTC())
      )

  /**
   * A provider reading the key `settings` locates over `httpClient` - one configured with a proxy or custom TLS, say -
   * which stays the caller's to close.
   */
  def apply(settings: AwsSecretsManagerSettings, httpClient: Llm4sHttpClient): Result[AwsSecretsManagerApiKeyProvider] =
    apply(settings, httpClient, Clock.systemUTC())

  private[aws] def apply(
    settings: AwsSecretsManagerSettings,
    httpClient: Llm4sHttpClient,
    clock: Clock
  ): Result[AwsSecretsManagerApiKeyProvider] =
    AwsSecretsManagerSettings
      .validated(settings)
      .map(valid => new AwsSecretsManagerApiKeyProvider(valid, httpClient, ownsClient = false, clock))

  /** Refuses credentials with a blank part or a line break, naming the part but never its value. */
  private def validCredentials(credentials: AwsCredentials): Result[AwsCredentials] =
    def blank(s: Secret)      = Option(s).forall(_.reveal.trim.isEmpty)
    def multiLine(s: Secret)  = s.reveal.exists(c => c == '\n' || c == '\r')
    def invalid(what: String) = Left(ConfigurationError(s"AWS credentials: $what"))
    Option(credentials) match
      case None => invalid("none were given")
      case Some(c) =>
        val id = Option(c.accessKeyId).getOrElse("").trim
        if id.isEmpty then invalid("accessKeyId is blank")
        else if id.exists(_.isWhitespace) then invalid("accessKeyId holds whitespace")
        else if blank(c.secretAccessKey) then invalid("secretAccessKey is blank")
        else if multiLine(c.secretAccessKey) then invalid("secretAccessKey holds more than one line")
        else if c.sessionToken.exists(blank) then invalid("sessionToken is blank")
        else if c.sessionToken.exists(multiLine) then invalid("sessionToken holds more than one line")
        else Right(AwsCredentials(id, c.secretAccessKey).withSessionToken(c.sessionToken))
