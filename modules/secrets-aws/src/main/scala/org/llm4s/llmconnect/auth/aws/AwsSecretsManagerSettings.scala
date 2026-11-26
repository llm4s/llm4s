package org.llm4s.llmconnect.auth.aws

import org.llm4s.annotation.Experimental
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.net.URI
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Where [[AwsSecretsManagerApiKeyProvider]] reads an API key in AWS Secrets Manager (#2479): the secret `secretId`
 * (its name or ARN) in `region`, either its whole `SecretString` or, with `withField`, one field of a `SecretString`
 * that is a JSON object, as the console's key/value secrets are.
 *
 * {{{
 * // the field apiKey of the key/value secret llm4s/openai, in eu-west-1
 * val settings = AwsSecretsManagerSettings("eu-west-1", AwsCredentials(accessKeyId, Secret(secretAccessKey)), "llm4s/openai")
 *   .withField("apiKey")
 *   .withRefreshInterval(1.minute)
 * }}}
 *
 * Credentials that expire - an assumed role's, a pod identity's - are passed as a function, which is called again
 * for every read from Secrets Manager: `withCredentials(() => myCredentials())`.
 *
 * @param region          the AWS region, such as `us-east-1`; picks the endpoint and is part of the signature
 * @param credentials     called once per read for the credentials that sign it; never printed by `toString`
 * @param secretId        the secret's name or ARN
 * @param field           the key of a JSON-object `SecretString` that holds the API key; none for a plain-string
 *                        secret, whose whole string is the key (a JSON-object `SecretString` with no field is refused)
 * @param versionStage    the version stage read: `AWSCURRENT` by default, the version rotation last promoted
 * @param endpoint        the Secrets Manager endpoint; `https://secretsmanager.<region>.amazonaws.com` by default,
 *                        set for a VPC endpoint or a FIPS endpoint; no user info, path, query or fragment. `http` is
 *                        refused unless the host is loopback or `allowCleartext` is set
 * @param refreshInterval how long a key read from Secrets Manager is used before it is read again
 * @param requestTimeout  how long one read from Secrets Manager may take
 * @param allowCleartext  accepts an `http://` endpoint whose host is not loopback. Off by default: with it on, the
 *                        signed request (including the session token header) and the secret in the reply cross the
 *                        network unencrypted, readable and alterable by anything on the path. Loopback
 *                        (`localhost`, 127.0.0.0/8, `::1`) needs no opt-in
 */
@Experimental
final case class AwsSecretsManagerSettings private (
  region: String,
  credentials: () => Result[AwsCredentials],
  secretId: String,
  field: Option[String],
  versionStage: String,
  endpoint: Option[String],
  refreshInterval: FiniteDuration,
  requestTimeout: FiniteDuration,
  allowCleartext: Boolean
):

  /** Fixed credentials, used for every read. */
  def withCredentials(credentials: AwsCredentials): AwsSecretsManagerSettings =
    copy(credentials = () => Right(credentials))

  /** Credentials called for again on every read, for temporary ones that expire; a `Left` fails that read. */
  def withCredentials(credentials: () => Result[AwsCredentials]): AwsSecretsManagerSettings =
    copy(credentials = credentials)

  /** The key of the JSON-object `SecretString` that holds the API key; required for a key/value secret. */
  def withField(field: String): AwsSecretsManagerSettings = copy(field = Some(field))

  /** The key of the JSON-object `SecretString` that holds the API key, or none for the whole string. */
  def withField(field: Option[String]): AwsSecretsManagerSettings = copy(field = field)

  /** The version stage read, such as `AWSPENDING` or a stage of your own. */
  def withVersionStage(versionStage: String): AwsSecretsManagerSettings = copy(versionStage = versionStage)

  /** The Secrets Manager endpoint, `http(s)://host[:port]`, for a VPC or FIPS endpoint. */
  def withEndpoint(endpoint: String): AwsSecretsManagerSettings = copy(endpoint = Some(endpoint))

  /** The Secrets Manager endpoint, or none for the region's public one. */
  def withEndpoint(endpoint: Option[String]): AwsSecretsManagerSettings = copy(endpoint = endpoint)

  /** How long a key read from Secrets Manager is used before it is read again. */
  def withRefreshInterval(refreshInterval: FiniteDuration): AwsSecretsManagerSettings =
    copy(refreshInterval = refreshInterval)

  /** How long one read from Secrets Manager may take. */
  def withRequestTimeout(requestTimeout: FiniteDuration): AwsSecretsManagerSettings =
    copy(requestTimeout = requestTimeout)

  /**
   * Accepts, or again refuses, an `http://` endpoint whose host is not loopback. With it on the session token
   * header and the secret travel unencrypted, so use it only on a network you trust end to end.
   */
  def withCleartext(allowed: Boolean): AwsSecretsManagerSettings = copy(allowCleartext = allowed)

  /** The endpoint requests go to: [[endpoint]], else the region's public one. */
  def endpointUrl: String = endpoint.getOrElse {
    val domain = if region.startsWith("cn-") then "amazonaws.com.cn" else "amazonaws.com"
    s"https://secretsmanager.$region.$domain"
  }

  /** The secret's location, as `secretId[#field]`; holds nothing secret. */
  def location: String = secretId + field.fold("")("#" + _)

  override def toString: String =
    s"AwsSecretsManagerSettings($region, credentials=***, $location, versionStage=$versionStage, " +
      s"endpoint=${Redaction.url(endpointUrl)}, refreshInterval=$refreshInterval, requestTimeout=$requestTimeout" +
      (if allowCleartext then ", cleartext=allowed)" else ")")

object AwsSecretsManagerSettings:

  /** `AWSCURRENT`, the stage Secrets Manager moves to a secret's newest version when it is rotated. */
  val DefaultVersionStage: String = "AWSCURRENT"

  /** Five minutes: a rotated key is in use at most this long after Secrets Manager has it. */
  val DefaultRefreshInterval: FiniteDuration = 5.minutes

  /** Ten seconds. */
  val DefaultRequestTimeout: FiniteDuration = 10.seconds

  /** The whole `SecretString` of `secretId`'s `AWSCURRENT` version in `region`, read again every five minutes. */
  def apply(region: String, credentials: AwsCredentials, secretId: String): AwsSecretsManagerSettings =
    new AwsSecretsManagerSettings(
      region,
      () => Right(credentials),
      secretId,
      None,
      DefaultVersionStage,
      None,
      DefaultRefreshInterval,
      DefaultRequestTimeout,
      false
    )

  /**
   * Whether `host`, as `URI.getHost` returns it, is a loopback literal: `localhost` (any case), an IPv4 address in
   * 127.0.0.0/8 or the IPv6 `::1`. Decided from the text alone, with no DNS lookup, so a name that merely resolves to
   * a loopback address is not one.
   */
  private[aws] def isLoopbackHost(host: String): Boolean =
    val h = host.stripPrefix("[").stripSuffix("]").toLowerCase(java.util.Locale.ROOT)
    h == "localhost" || h == "::1" || (h match
      case Ipv4(a, b, c, d) => a == "127" && Seq(b, c, d).forall(_.toInt <= 255)
      case _                => false
    )

  private val Ipv4 = """(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""".r

  private val RegionPattern = "[a-z0-9]+(-[a-z0-9]+)+".r

  /**
   * `settings` with blank parts refused and the rest trimmed. A failure is a `ConfigurationError` that names the bad
   * setting and never holds a credential.
   */
  private[aws] def validated(settings: AwsSecretsManagerSettings): Result[AwsSecretsManagerSettings] =
    def trimmed(s: String): String = Option(s).getOrElse("").trim
    val region                     = trimmed(settings.region)
    val secretId                   = trimmed(settings.secretId)
    val stage                      = trimmed(settings.versionStage)
    val endpoint                   = settings.endpoint.map(e => trimmed(e).stripSuffix("/"))
    def invalid(what: String)      = Left(ConfigurationError(s"AWS Secrets Manager settings: $what"))
    def httpUrl(url: String): Boolean = Try(new URI(url)).toOption.exists { u =>
      Set("http", "https").contains(Option(u.getScheme).getOrElse("")) && u.getHost != null &&
      Option(u.getRawPath).forall(_.isEmpty) && u.getRawQuery == null && u.getRawFragment == null &&
      u.getRawUserInfo == null
    }
    def cleartext(url: String): Boolean =
      Try(new URI(url)).toOption.exists(u => u.getScheme == "http" && !isLoopbackHost(u.getHost))
    if !RegionPattern.matches(region) then invalid(s"region '$region' is not an AWS region such as us-east-1")
    else if secretId.isEmpty then invalid("secretId is blank")
    else if settings.field.exists(_.trim.isEmpty) then invalid("field is blank")
    else if stage.isEmpty then invalid("versionStage is blank")
    else if endpoint.exists(e => !httpUrl(e)) then
      invalid(
        s"endpoint '${Redaction.url(endpoint.getOrElse(""))}' is not an http(s) URL with no user info, path, " +
          "query or fragment"
      )
    else if !settings.allowCleartext && endpoint.exists(cleartext) then
      invalid(
        s"endpoint '${Redaction.url(endpoint.getOrElse(""))}' is http to a host that is not loopback, so the " +
          "request and the secret would travel unencrypted: use https, or withCleartext(true) on a network you trust"
      )
    else if settings.refreshInterval <= Duration.Zero then
      invalid(s"refreshInterval must be positive, got ${settings.refreshInterval}")
    else if settings.requestTimeout <= Duration.Zero then
      invalid(s"requestTimeout must be positive, got ${settings.requestTimeout}")
    else
      Right(
        settings.copy(
          region = region,
          secretId = secretId,
          field = settings.field.map(_.trim),
          versionStage = stage,
          endpoint = endpoint
        )
      )
