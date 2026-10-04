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
 * project (`projectId`) or a deployment space (`spaceId`, which takes precedence when set).
 * Prefer [[WatsonXConfig.fromValues]] over the primary constructor.
 *
 * @param apiKey        IBM Cloud API key exchanged for IAM bearer tokens; redacted in `toString`.
 * @param projectId     watsonx project ID; may be empty only when `spaceId` is set.
 * @param spaceId       Optional watsonx deployment space ID, used in place of the project.
 * @param model         Model identifier, e.g. `"ibm/granite-13b-instruct-v2"`.
 * @param baseUrl       watsonx.ai ML API base URL, e.g. `"https://us-south.ml.cloud.ibm.com"`.
 * @param apiVersion    watsonx API version date, e.g. `"2024-05-31"`.
 * @param iamUrl        IBM Cloud IAM token endpoint.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
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
  reserveCompletion: Int
) extends ProviderConfig:
  override val providerId: ProviderId                  = ProviderId("watsonx")
  override def endpointUrl: Option[String]             = Some(baseUrl)
  override def withModel(model: String): WatsonXConfig = copy(model = model)
  override def toString: String =
    s"WatsonXConfig(apiKey=${Redaction.secret(apiKey)}, projectId=$projectId, spaceId=$spaceId, model=$model, " +
      s"baseUrl=$baseUrl, apiVersion=$apiVersion, iamUrl=$iamUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

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

  /**
   * Constructs a [[WatsonXConfig]], resolving `contextWindow` and `reserveCompletion` from the
   * model name. A blank `apiKey`, `baseUrl`, `apiVersion` or `iamUrl`, or neither a `projectId`
   * nor a `spaceId`, is a `ConfigurationError`.
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
    val project = projectId.map(_.trim).filter(_.nonEmpty)
    val space   = spaceId.map(_.trim).filter(_.nonEmpty)
    for
      _ <- ProviderConfig.nonEmpty("watsonx", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("watsonx", "baseUrl", baseUrl)
      _ <- ProviderConfig.nonEmpty("watsonx", "apiVersion", apiVersion)
      _ <- ProviderConfig.nonEmpty("watsonx", "iamUrl", iamUrl)
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
        apiKey = apiKey,
        projectId = project.getOrElse(""),
        spaceId = space,
        model = modelName,
        baseUrl = baseUrl,
        apiVersion = apiVersion,
        iamUrl = iamUrl,
        contextWindow = cw,
        reserveCompletion = rc
      )
