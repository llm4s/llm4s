package org.llm4s.llmconnect.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for IBM watsonx.ai.
 *
 * watsonx.ai hosts IBM Granite, Llama, Mistral and other models behind a governed API. Auth is
 * IBM Cloud IAM: the `apiKey` is exchanged for a short-lived bearer token. Inference runs in a
 * project (`projectId`) or a deployment space (`spaceId`); setting both is rejected.
 * Prefer [[WatsonXConfig.fromValues]] over the primary constructor.
 *
 * @param apiKey        IBM Cloud API key exchanged for IAM bearer tokens; redacted in `toString`.
 * @param projectId     watsonx project ID; may be empty only when `spaceId` is set.
 * @param spaceId       Optional watsonx deployment space ID, used in place of the project (never together with it).
 * @param model         Model identifier, e.g. `"ibm/granite-13b-instruct-v2"`.
 * @param baseUrl       watsonx.ai ML API base URL, e.g. `"https://us-south.ml.cloud.ibm.com"`.
 * @param apiVersion    watsonx API version date, e.g. `"2024-05-31"`.
 * @param iamUrl        IBM Cloud IAM token endpoint.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param timeouts      how long a request and a stream may take: the section's `timeouts` block. An absent
 *                      value keeps the client's own default ([[ProviderTimeouts]]).
 */
final case class WatsonXConfig(
  apiKey: String,
  projectId: String,
  spaceId: Option[String],
  model: String,
  baseUrl: String,
  apiVersion: String,
  iamUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts = ProviderTimeouts.default
) extends ProviderConfig:
  override val providerId: ProviderId                                  = ProviderId("watsonx")
  override def endpointUrl: Option[String]                             = Some(baseUrl)
  override def withModel(model: String): WatsonXConfig                 = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): WatsonXConfig = copy(timeouts = timeouts)
  override def toString: String =
    s"WatsonXConfig(apiKey=${Redaction.secret(apiKey)}, projectId=$projectId, spaceId=$spaceId, model=$model, " +
      s"baseUrl=$baseUrl, apiVersion=$apiVersion, iamUrl=$iamUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion, timeouts=$timeouts)"

object WatsonXConfig:
  val DEFAULT_BASE_URL: String    = "https://us-south.ml.cloud.ibm.com"
  val DEFAULT_API_VERSION: String = "2024-05-31"
  val DEFAULT_IAM_URL: String     = "https://iam.cloud.ibm.com/identity/token"

  private val DefaultContextWindow     = 8192
  private val DefaultReserveCompletion = 4096

  private def watsonxFallback(modelName: String): (Int, Int) =
    modelName match
      case name if name.contains("mistral-large") => (32768, DefaultReserveCompletion)
      case name if name.contains("llama-2")       => (4096, DefaultReserveCompletion)
      case _                                      => (DefaultContextWindow, DefaultReserveCompletion)

  private val LocalHosts = Set("localhost", "127.0.0.1", "::1", "[::1]")

  /** `https`, or `http` only to a loopback host: both URLs carry the API key or a bearer token. */
  private def requireHttps(field: String, url: String): Result[Unit] =
    val parsed = scala.util.Try(java.net.URI.create(url)).toOption
    val scheme = parsed.flatMap(u => Option(u.getScheme)).map(_.toLowerCase(java.util.Locale.ROOT))
    val host   = parsed.flatMap(u => Option(u.getHost)).map(_.toLowerCase(java.util.Locale.ROOT))
    val ok = scheme match
      case Some("https") => host.exists(_.nonEmpty)
      case Some("http")  => host.exists(LocalHosts.contains)
      case _             => false
    Either.cond(
      ok,
      (),
      ConfigurationError(s"watsonx $field must be an https URL (http is allowed only for localhost)", List(field))
    )

  /**
   * Constructs a [[WatsonXConfig]], resolving `contextWindow` and `reserveCompletion` from the
   * model name. The `apiKey` is trimmed. A blank `apiKey`, `baseUrl`, `apiVersion` or `iamUrl`, a
   * `baseUrl` or `iamUrl` that is not `https` (`http` is allowed for `localhost`, `127.0.0.1` and
   * `::1`), neither a `projectId` nor a `spaceId`, or both, is a `ConfigurationError`.
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    projectId: Option[String],
    spaceId: Option[String] = None,
    baseUrl: String = DEFAULT_BASE_URL,
    apiVersion: String = DEFAULT_API_VERSION,
    iamUrl: String = DEFAULT_IAM_URL
  )(using resolver: ContextWindowResolver): Result[WatsonXConfig] =
    // A trailing slash would put `//` in every endpoint (`$baseUrl/ml/v1/...`); stray whitespace
    // would make the URL unparseable.
    val key     = apiKey.trim
    val base    = baseUrl.trim.replaceAll("/+$", "")
    val version = apiVersion.trim
    val iam     = iamUrl.trim
    val project = projectId.map(_.trim).filter(_.nonEmpty)
    val space   = spaceId.map(_.trim).filter(_.nonEmpty)
    for
      _ <- ProviderConfig.nonEmpty("watsonx", "apiKey", key)
      _ <- ProviderConfig.nonEmpty("watsonx", "baseUrl", base)
      _ <- ProviderConfig.nonEmpty("watsonx", "apiVersion", version)
      _ <- ProviderConfig.nonEmpty("watsonx", "iamUrl", iam)
      _ <- requireHttps("baseUrl", base)
      _ <- requireHttps("iamUrl", iam)
      _ <- Either.cond(
        !(project.isDefined && space.isDefined),
        (),
        ConfigurationError("watsonx takes a projectId or a spaceId, not both", List("projectId", "spaceId"))
      )
      _ <- Either.cond(
        project.isDefined || space.isDefined,
        (),
        ConfigurationError("watsonx needs a projectId or a spaceId", List("projectId", "spaceId"))
      )
    yield
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("watsonx"),
        modelName = modelName,
        defaultContextWindow = DefaultContextWindow,
        defaultReserve = DefaultReserveCompletion,
        fallbackResolver = watsonxFallback
      )
      WatsonXConfig(
        apiKey = key,
        projectId = project.getOrElse(""),
        spaceId = space,
        model = modelName,
        baseUrl = base,
        apiVersion = version,
        iamUrl = iam,
        contextWindow = cw,
        reserveCompletion = rc
      )
