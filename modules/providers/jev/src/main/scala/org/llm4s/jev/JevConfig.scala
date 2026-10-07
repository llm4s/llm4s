package org.llm4s.jev

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import java.net.URI
import scala.concurrent.duration.*
import scala.util.Try

/**
 * What [[JevClient]] needs to call TypeSafe's API.
 *
 * Load it from configuration with [[org.llm4s.config.JevConfigLoader]], which reads the `llm4s.jev` block and the API key from
 * `TYPESAFE_API_KEY`, or build one in code:
 *
 * {{{
 * val config = JevConfig(apiKey = key).withModel("jev-1.13.0")
 * }}}
 *
 * A config is checked by [[JevConfig#validate]], which [[JevClient]] runs when it is built.
 *
 * @param apiKey  the API key; never printed by `toString` and never put into an error or a log line
 * @param baseUrl the API root, without a path. It must be `https`: the key is sent as a bearer token, so a plain
 *                `http` URL is accepted only for a loopback host (`localhost`, `127.0.0.0/8`, `::1`), which is
 *                how a test points the client at a local server.
 * @param model   the model a request uses unless it names one: `jev-latest`, or a versioned id such as `jev-1.13.0`
 *                to pin the answers
 * @param timeout how long each HTTP attempt may take. The API documents no figure: 30 s is this client's choice,
 *                matching the retry budget of TypeSafe's SDKs. An attempt never waits past what is left of the retry
 *                budget, so the budget bounds the whole call.
 * @param retry   how a transient failure is retried
 * @param headers extra HTTP headers for every request (a request can add its own); the client sets the credentials,
 *                content type and `Accept` header itself and refuses to have them replaced
 */
final case class JevConfig private (
  apiKey: String,
  baseUrl: String,
  model: String,
  timeout: FiniteDuration,
  retry: JevRetryPolicy,
  headers: Map[String, String]
) {

  def withApiKey(apiKey: String): JevConfig                = copy(apiKey = apiKey)
  def withBaseUrl(baseUrl: String): JevConfig              = copy(baseUrl = baseUrl)
  def withModel(model: String): JevConfig                  = copy(model = model)
  def withTimeout(timeout: FiniteDuration): JevConfig      = copy(timeout = timeout)
  def withRetry(retry: JevRetryPolicy): JevConfig          = copy(retry = retry)
  def withHeaders(headers: Map[String, String]): JevConfig = copy(headers = headers)

  // The key is a credential: only the rest is shown, and the values of extra headers (which can carry one) are not.
  override def toString: String =
    s"JevConfig(apiKey=***, baseUrl=$baseUrl, model=$model, timeout=$timeout, retry=$retry, " +
      s"headers=${headers.keys.toSeq.sorted.mkString("[", ", ", "]")})"

  /** The URL a request is POSTed to. */
  private[jev] def evaluateUrl: String = baseUrl.stripSuffix("/") + JevConfig.EvaluatePath

  /** Checks the config; the first problem is reported, naming the setting. */
  def validate: Result[JevConfig] =
    for {
      _ <- JevConfig.checkApiKey(apiKey)
      _ <- JevConfig.checkBaseUrl(baseUrl)
      _ <-
        if (model.trim.nonEmpty && !model.exists(_.isControl)) Right(())
        else Left(ConfigurationError("llm4s.jev.model must not be blank"))
      _ <-
        if (timeout > Duration.Zero) Right(())
        else Left(ConfigurationError("llm4s.jev.timeout must be positive"))
      _ <- retry.validate
      _ <- JevHeaders.validate("llm4s.jev.headers", headers).left.map(e => ConfigurationError(e.message))
    } yield this
}

object JevConfig {

  /** TypeSafe's API root. */
  val DefaultBaseUrl: String = "https://api.typesafe.ai"

  /** The alias for the most recent stable Jev release. */
  val DefaultModel: String = "jev-latest"

  /** How long each HTTP attempt may take unless configured otherwise. */
  val DefaultTimeout: FiniteDuration = 30.seconds

  /** The path of the evaluation endpoint. */
  private[jev] val EvaluatePath: String = "/v1/systemone"

  /** Creates a config for `apiKey`. Named arguments are the supported way to set the rest. */
  def apply(
    apiKey: String,
    baseUrl: String = DefaultBaseUrl,
    model: String = DefaultModel,
    timeout: FiniteDuration = DefaultTimeout,
    retry: JevRetryPolicy = JevRetryPolicy.default,
    headers: Map[String, String] = Map.empty
  ): JevConfig = new JevConfig(apiKey, baseUrl, model, timeout, retry, headers)

  /**
   * TypeSafe's SDKs reject an empty key, whitespace inside it, control characters and non-ASCII; a key that
   * breaks one of those cannot be a key, and one with a line break would corrupt the `Authorization` header.
   */
  private def checkApiKey(key: String): Result[Unit] =
    if (key.trim.isEmpty) Left(ConfigurationError("The Jev API key is blank"))
    else if (key.exists(c => c.isWhitespace || c.isControl || c > '~'))
      Left(ConfigurationError("The Jev API key contains whitespace, a control character or a non-ASCII character"))
    else Right(())

  private def isLoopback(host: String): Boolean =
    host.equalsIgnoreCase("localhost") || host == "[::1]" || {
      val octets = host.split('.')
      octets.length == 4 && octets(0) == "127" &&
      octets.forall(o => o.nonEmpty && o.forall(_.isDigit) && o.length <= 3 && o.toInt <= 255)
    }

  private def checkBaseUrl(url: String): Result[Unit] = {
    def bad(why: String): Result[Unit] = Left(ConfigurationError(s"llm4s.jev.baseUrl $why"))
    if (url.exists(c => c.isWhitespace || c.isControl)) bad("must not contain whitespace or control characters")
    else
      Try(new URI(url)).toOption match {
        case None => bad("is not a valid URL")
        case Some(uri) =>
          val scheme = Option(uri.getScheme).map(_.toLowerCase)
          val host   = Option(uri.getHost)
          if (host.isEmpty || scheme.isEmpty) bad("must be an absolute URL with a host")
          else if (uri.getRawUserInfo != null) bad("must not carry credentials")
          else if (uri.getRawQuery != null || uri.getRawFragment != null) bad("must not have a query or a fragment")
          else if (scheme.contains("https")) Right(())
          else if (scheme.contains("http") && isLoopback(host.get)) Right(())
          else bad("must be https (http is accepted only for localhost, 127.0.0.0/8 or ::1)")
      }
  }
}
