package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Configuration for Google Cloud Vertex AI.
 *
 * Uses the Gemini-compatible REST format (`aiplatform.googleapis.com`) with
 * OAuth2 bearer-token authentication via Application Default Credentials (ADC).
 *
 * @param projectId          GCP project ID that owns the Vertex AI resources.
 * @param location           GCP region, e.g. `"us-central1"`.
 * @param model              Model identifier, e.g. `"gemini-2.0-flash"`.
 * @param credentialFilePath Optional path to a Google JSON credential file.
 *                           Falls back to `GOOGLE_APPLICATION_CREDENTIALS` env,
 *                           then `~/.config/gcloud/application_default_credentials.json`,
 *                           then the GCE metadata server.
 * @param contextWindow      Maximum token capacity for prompt + completion combined.
 * @param reserveCompletion  Tokens reserved for the completion response.
 */
case class VertexAIConfig(
  projectId: String,
  location: String,
  model: String,
  credentialFilePath: Option[String],
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val providerId: ProviderId                   = ProviderId("vertexai")
  override def withModel(model: String): VertexAIConfig = copy(model = model)

  /** Base URL derived from the location, e.g. `"https://us-central1-aiplatform.googleapis.com/v1"`. */
  def computedBaseUrl: String = s"https://$location-aiplatform.googleapis.com/v1"

  override def endpointUrl: Option[String] = Some(computedBaseUrl)

  override def toString: String =
    s"VertexAIConfig(projectId=$projectId, location=$location, model=$model, " +
      s"credentialFilePath=${credentialFilePath.map(_ => "<redacted>").getOrElse("none")}, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion)"

object VertexAIConfig:

  /**
   * The GCP region used when a named provider section sets no `organization`.
   *
   * `DefaultConfig.DEFAULT_VERTEXAI_LOCATION` duplicated this in `llm4s-core`
   * until the provider moved to `llm4s-gemini`
   * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
   */
  val DEFAULT_LOCATION = "us-central1"

  private val DefaultContextWindow     = 1048576
  private val DefaultReserveCompletion = 8192

  private val vertexAiFallback: String => (Int, Int) =
    _ => (DefaultContextWindow, DefaultReserveCompletion)

  /**
   * Constructs a [[VertexAIConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model registry if available.
   *
   * @param modelName          Model identifier, e.g. `"gemini-2.0-flash"`.
   * @param projectId          GCP project ID.
   * @param location           GCP region (defaults to `"us-central1"`).
   * @param credentialFilePath Optional path to a Google JSON credential file.
   */
  def fromValues(
    modelName: String,
    projectId: String,
    location: String = DEFAULT_LOCATION,
    credentialFilePath: Option[String] = None
  )(using resolver: ContextWindowResolver): Result[VertexAIConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("Vertex AI", "projectId", projectId)
      _ <- ProviderConfig.nonEmpty("Vertex AI", "location", location)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("gemini", "vertexai"),
        modelName = modelName,
        defaultContextWindow = DefaultContextWindow,
        defaultReserve = DefaultReserveCompletion,
        fallbackResolver = vertexAiFallback
      )
      VertexAIConfig(
        projectId = projectId,
        location = location,
        model = modelName,
        credentialFilePath = credentialFilePath,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }
