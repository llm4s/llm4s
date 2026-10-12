package org.llm4s.javaapi

import org.llm4s.config.Llm4sConfig
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.config.{
  AnthropicConfig,
  AzureConfig,
  ContextWindowResolver,
  GeminiConfig,
  OllamaConfig,
  OpenAICompatibleConfig,
  OpenAIConfig,
  ProviderConfig,
  ProviderTimeouts
}
import org.llm4s.llmconnect.provider.OpenAICompatibleProvider
import org.llm4s.types.ProviderModelTypes.{ ApiKey, BaseUrl, ModelName, ProviderId }
import org.llm4s.types.Result

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*

/**
 * Not part of the API: what each client builder's `build()` does with what it was told (#1489). Every config is made
 * the way the provider's descriptor makes it from a section of `application.conf` - through the provider's public
 * `fromValues`, which reads the model catalogue for the context window, or for an OpenAI-compatible server through
 * the descriptor itself, which also infers the catalogue's provider from the base URL's host - so the same values give
 * the same config either way (`ClientBuildersSpec` compares them). Sizes and timeouts the caller set are then applied
 * with the config's `with` setters; Gemini's and Azure's configs have no setter for the sizes, so theirs use `copy`.
 * The client then comes from [[Llm4s.createClient]], with the collector the builder was given, as for a config a
 * caller made.
 *
 * Every check of the caller's values is a [[ValidationError]] naming the builder's field, so a Java caller reads one
 * kind, `VALIDATION`, for anything it set wrong or left out.
 */
private[javaapi] object ClientBuilds {

  /** Where Ollama listens unless told otherwise, as its embedding provider and its documentation assume. */
  val OllamaDefaultBaseUrl: String = "http://localhost:11434"

  /** The section name an OpenAI-compatible builder's config is made under, as a named section's would be. */
  private val SectionName: String = "openAiCompatible"

  def openAi(s: ClientSettings): LlmResult[JLlmClient] = client(openAiConfig(s), s)

  def anthropic(s: ClientSettings): LlmResult[JLlmClient] = client(anthropicConfig(s), s)

  def gemini(s: ClientSettings): LlmResult[JLlmClient] = client(geminiConfig(s), s)

  def ollama(s: ClientSettings): LlmResult[JLlmClient] = client(ollamaConfig(s), s)

  def openAiCompatible(s: ClientSettings): LlmResult[JLlmClient] = client(openAiCompatibleConfig(s), s)

  def azureOpenAi(s: ClientSettings): LlmResult[JLlmClient] = client(azureOpenAiConfig(s), s)

  def openAiConfig(s: ClientSettings): Result[ProviderConfig] =
    for {
      apiKey   <- required("apiKey", s.apiKey)
      model    <- required("model", s.model)
      resolver <- resolver
      config <- OpenAIConfig.fromValues(
        model,
        apiKey,
        present(s.organization),
        present(s.baseUrl).getOrElse(OpenAIConfig.DEFAULT_BASE_URL)
      )(using resolver)
      sized    <- sizes(s, config.contextWindow, config.reserveCompletion)
      timeouts <- timeouts(s)
    } yield config.withContextWindow(sized._1).withReserveCompletion(sized._2).withTimeouts(timeouts)

  def anthropicConfig(s: ClientSettings): Result[ProviderConfig] =
    for {
      apiKey   <- required("apiKey", s.apiKey)
      model    <- required("model", s.model)
      resolver <- resolver
      config <- AnthropicConfig
        .fromValues(model, apiKey, present(s.baseUrl).getOrElse(AnthropicConfig.DEFAULT_BASE_URL))(using resolver)
      sized    <- sizes(s, config.contextWindow, config.reserveCompletion)
      timeouts <- timeouts(s)
    } yield config.withContextWindow(sized._1).withReserveCompletion(sized._2).withTimeouts(timeouts)

  def geminiConfig(s: ClientSettings): Result[ProviderConfig] =
    for {
      apiKey   <- required("apiKey", s.apiKey)
      model    <- required("model", s.model)
      resolver <- resolver
      config <- GeminiConfig.fromValues(model, apiKey, present(s.baseUrl).getOrElse(GeminiConfig.DEFAULT_BASE_URL))(
        using resolver
      )
      sized    <- sizes(s, config.contextWindow, config.reserveCompletion)
      timeouts <- timeouts(s)
      // GeminiConfig has no `with` setter for the sizes: `copy` is its public way to change one
    } yield config.copy(contextWindow = sized._1, reserveCompletion = sized._2).withTimeouts(timeouts)

  def ollamaConfig(s: ClientSettings): Result[ProviderConfig] =
    for {
      model    <- required("model", s.model)
      resolver <- resolver
      config   <- OllamaConfig.fromValues(model, present(s.baseUrl).getOrElse(OllamaDefaultBaseUrl))(using resolver)
      sized    <- sizes(s, config.contextWindow, config.reserveCompletion)
      timeouts <- timeouts(s)
    } yield config.withContextWindow(sized._1).withReserveCompletion(sized._2).withTimeouts(timeouts)

  /**
   * The config a section with these values makes: the descriptor's own, so the context window is the section's, else
   * the model catalogue's for the provider the base URL's host names (Groq, Together, Fireworks, xAI, Perplexity),
   * else the default, and the reserve a quarter of the window up to the default unless set.
   */
  def openAiCompatibleConfig(s: ClientSettings): Result[ProviderConfig] =
    for {
      baseUrl  <- required("baseUrl", s.baseUrl)
      model    <- required("model", s.model)
      window   <- counts(s)
      resolver <- resolver
      section = NamedProviderConfig(
        provider = ProviderId(OpenAICompatibleConfig.ProviderIdName),
        model = ModelName(model),
        baseUrl = Some(BaseUrl(baseUrl)),
        apiKey = present(s.apiKey).map(ApiKey(_)),
        headers = s.headers.asScala.toMap,
        extras = window.map(w => OpenAICompatibleProvider.ContextWindowKey -> w.toString).toMap
      )
      config   <- OpenAICompatibleProvider.configOf(SectionName, section)(using resolver)
      reserve  <- reserveWithin(s, config.contextWindow)
      timeouts <- timeouts(s)
    } yield reserve.fold(config)(config.withReserveCompletion).withTimeouts(timeouts)

  def azureOpenAiConfig(s: ClientSettings): Result[ProviderConfig] =
    for {
      endpoint <- required("endpoint", s.endpoint)
      apiKey   <- required("apiKey", s.apiKey)
      model    <- required("model", s.model)
      resolver <- resolver
      config <- AzureConfig.fromValues(
        model,
        endpoint,
        apiKey,
        present(s.apiVersion).getOrElse(AzureConfig.DEFAULT_API_VERSION)
      )(using resolver)
      sized    <- sizes(s, config.contextWindow, config.reserveCompletion)
      timeouts <- timeouts(s)
      // AzureConfig has no `with` setter for the sizes: `copy` is its public way to change one
    } yield config.copy(contextWindow = sized._1, reserveCompletion = sized._2).withTimeouts(timeouts)

  /** The client for `config`, reporting to the builder's collector, or the first thing wrong with what it was told. */
  private def client(config: Result[ProviderConfig], s: ClientSettings): LlmResult[JLlmClient] =
    config.fold(LlmResult.failure, Llm4s.createClient(_, s.metrics))

  /** The model catalogue's view of context windows, as a configured provider's section is read with. */
  private def resolver: Result[ContextWindowResolver] =
    Llm4sConfig.modelRegistryService().map(new ContextWindowResolver(_))

  /** `value`, which the builder must have been given: `null` or blank is not given. */
  private def required(field: String, value: String): Result[String] =
    present(value).toRight(ValidationError.required(field))

  /** `value` when it says something: `null` and blank say nothing. */
  private def present(value: String): Option[String] = Option(value).map(_.trim).filter(_.nonEmpty)

  /**
   * The context window the caller set, if any, once each size it set is in range: a window of at least 1, a reserve of
   * at least 0, and, when it set both, a reserve less than the window.
   */
  private def counts(s: ClientSettings): Result[Option[Int]] = {
    val window  = Option(s.contextWindow).map(_.intValue)
    val reserve = Option(s.reserveCompletion).map(_.intValue)
    (window, reserve) match {
      case (Some(w), _) if w < 1 => invalid("contextWindow", s"must be at least 1, got $w")
      case (_, Some(r)) if r < 0 => invalid("reserveCompletion", s"must be at least 0, got $r")
      case _                     => Right(window)
    }
  }

  /** The reserve the caller set, if any, which must leave room for the conversation in `window`. */
  private def reserveWithin(s: ClientSettings, window: Int): Result[Option[Int]] =
    Option(s.reserveCompletion).map(_.intValue) match {
      case Some(r) if r >= window =>
        invalid("reserveCompletion", s"the reply's reserve, $r, must be less than the context window, $window")
      case reserve => Right(reserve)
    }

  /**
   * The context window and reply reserve: what the builder was given, else what the provider's factory chose for the
   * model. A window set alone keeps a quarter of itself for the reply, up to the provider's reserve, as an
   * OpenAI-compatible section's `contextWindow` does; a reserve set must leave room for the conversation.
   */
  private def sizes(s: ClientSettings, defaultWindow: Int, defaultReserve: Int): Result[(Int, Int)] =
    for {
      window <- counts(s)
      w = window.getOrElse(defaultWindow)
      reserve <- reserveWithin(s, w)
    } yield (w, reserve.getOrElse(if (window.isDefined) math.min(defaultReserve, w / 4) else defaultReserve))

  private def invalid(field: String, reason: String): Result[Nothing] = Left(ValidationError.invalid(field, reason))

  /** The timeouts the builder was given, each more than zero; unset ones keep the provider's default. */
  private def timeouts(s: ClientSettings): Result[ProviderTimeouts] =
    for {
      request <- positive("requestTimeout", s.requestTimeout)
      stream  <- positive("streamTimeout", s.streamTimeout)
    } yield ProviderTimeouts(request, stream)

  /** The longest duration a `FiniteDuration` holds: `Long.MaxValue` nanoseconds, about 292 years. */
  private val Longest: Duration = Duration.ofNanos(Long.MaxValue)

  private def positive(field: String, value: Duration): Result[Option[FiniteDuration]] =
    Option(value) match {
      case Some(d) if d.isNegative || d.isZero => invalid(field, s"must be more than zero, got $d")
      case Some(d) if d.compareTo(Longest) > 0 => invalid(field, s"must be at most $Longest, got $d")
      case other                               => Right(other.map(_.toScala))
    }
}
