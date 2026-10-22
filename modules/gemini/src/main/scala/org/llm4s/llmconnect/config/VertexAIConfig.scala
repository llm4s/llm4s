package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Configuration for Google Cloud Vertex AI.
 *
 * Uses the Gemini-compatible REST format (`aiplatform.googleapis.com`) with
 * OAuth2 bearer-token authentication via Application Default Credentials (ADC).
 *
 * Prefer [[VertexAIConfig.fromValues]], which validates the values and resolves
 * `contextWindow` and `reserveCompletion` from the bundled model catalogue.
 * The constructor is private: build one with the companion `apply` and adjust
 * it with the `with*` setters. Java and Kotlin, which cannot see Scala default
 * arguments, use `VertexAIConfig.apply(projectId, model)` and the setters, so
 * adding a field never breaks them.
 *
 * @param projectId          GCP project ID that owns the Vertex AI resources.
 * @param location           GCP region, e.g. `"us-central1"`.
 * @param model              Model identifier, e.g. `"gemini-2.0-flash"`.
 * @param credentialFilePath Optional path to a Google JSON credential file.
 *                           Falls back to `GOOGLE_APPLICATION_CREDENTIALS` env,
 *                           then gcloud's `application_default_credentials.json`
 *                           (`$CLOUDSDK_CONFIG` if set; else `%APPDATA%\gcloud` on Windows;
 *                           else `~/.config/gcloud`),
 *                           then the GCE metadata server.
 * @param contextWindow      Maximum token capacity for prompt + completion combined.
 * @param reserveCompletion  Tokens reserved for the completion response.
 * @param wellKnownCredentialPath where gcloud's well-known `application_default_credentials.json` would be,
 *                 resolved at the configuration edge by [[org.llm4s.config.VertexAIAdcConfigLoader]] (the descriptor
 *                 sets it when it builds the client). `None` skips that lookup.
 * @param timeouts how long a request and a stream may take: the section's `timeouts` block. An absent
 *                 value keeps the client's own default ([[ProviderTimeouts]])
 */
@Stable
final case class VertexAIConfig private (
  projectId: String,
  location: String,
  model: String,
  credentialFilePath: Option[String],
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts,
  wellKnownCredentialPath: Option[String],
  googleSearch: Boolean
) extends ProviderConfig:
  override val providerId: ProviderId                                   = ProviderId("vertexai")
  override def withModel(model: String): VertexAIConfig                 = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): VertexAIConfig = copy(timeouts = timeouts)

  def withProjectId(projectId: String): VertexAIConfig                  = copy(projectId = projectId)
  def withLocation(location: String): VertexAIConfig                    = copy(location = location)
  def withCredentialFilePath(path: String): VertexAIConfig              = copy(credentialFilePath = Some(path))
  def withCredentialFilePath(path: Option[String]): VertexAIConfig      = copy(credentialFilePath = path)
  def withContextWindow(contextWindow: Int): VertexAIConfig             = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): VertexAIConfig     = copy(reserveCompletion = reserveCompletion)
  def withWellKnownCredentialPath(path: String): VertexAIConfig         = copy(wellKnownCredentialPath = Some(path))
  def withWellKnownCredentialPath(path: Option[String]): VertexAIConfig = copy(wellKnownCredentialPath = path)

  /** Whether every request carries Vertex AI's `googleSearch` grounding tool; see `GeminiConfig.googleSearch`. */
  def withGoogleSearch(enabled: Boolean): VertexAIConfig = copy(googleSearch = enabled)

  /** Base URL derived from the location, e.g. `"https://us-central1-aiplatform.googleapis.com/v1"`. */
  def computedBaseUrl: String = s"https://$location-aiplatform.googleapis.com/v1"

  override def endpointUrl: Option[String] = Some(computedBaseUrl)

  override def toString: String =
    s"VertexAIConfig(projectId=$projectId, location=$location, model=$model, " +
      s"credentialFilePath=${credentialFilePath.map(_ => "<redacted>").getOrElse("none")}, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion, googleSearch=$googleSearch)"

object VertexAIConfig:

  /**
   * The GCP region used when a named provider section sets no `location`.
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
   * Builds a config without validating it; [[fromValues]] validates. `timeouts` defaults to
   * [[ProviderTimeouts.default]], `wellKnownCredentialPath` to `None` and `googleSearch` to `false`.
   */
  def apply(
    projectId: String,
    location: String,
    model: String,
    credentialFilePath: Option[String],
    contextWindow: Int,
    reserveCompletion: Int,
    timeouts: ProviderTimeouts = ProviderTimeouts.default,
    wellKnownCredentialPath: Option[String] = None,
    googleSearch: Boolean = false
  ): VertexAIConfig =
    new VertexAIConfig(
      projectId,
      location,
      model,
      credentialFilePath,
      contextWindow,
      reserveCompletion,
      timeouts,
      wellKnownCredentialPath,
      googleSearch
    )

  /**
   * The project and model, every other field at its default: the entry point for Java and Kotlin,
   * which do not see Scala default arguments. The location is [[DEFAULT_LOCATION]], there is no
   * credential file (Application Default Credentials are used), Google Search grounding is off, and
   * the context window is 1M tokens with an 8192-token completion reserve; set them, and the rest,
   * with the `with*` setters, or use [[fromValues]] to consult the bundled model catalogue.
   */
  def apply(projectId: String, model: String): VertexAIConfig = {
    val (cw, rc) = vertexAiFallback(model)
    apply(projectId, DEFAULT_LOCATION, model, None, cw, rc)
  }

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
