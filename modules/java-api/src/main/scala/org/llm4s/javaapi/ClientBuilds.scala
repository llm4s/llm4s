package org.llm4s.javaapi

import org.llm4s.config.Llm4sConfig
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
import org.llm4s.types.Result

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*

/**
 * Not part of the API: what each client builder's `build()` does with what it was told (#1489). Every config is made
 * through the provider's own public factory - `fromValues`, which reads the model catalogue for the context window
 * as `application.conf` does - and its `with` setters, never its constructor, so a field a provider gains later
 * leaves the builders compiling and their defaults intact. The client then comes from [[Llm4s.createClient]], as
 * for a config a caller made.
 *
 * Every check of the caller's values is a [[ValidationError]] naming the builder's field, so a Java caller reads one
 * kind, `VALIDATION`, for anything it set wrong or left out.
 */
private[javaapi] object ClientBuilds {

  /** Where Ollama listens unless told otherwise, as its embedding provider and its documentation assume. */
  val OllamaDefaultBaseUrl: String = "http://localhost:11434"

  def openAi(s: ClientSettings): LlmResult[JLlmClient] =
    client(for {
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
    } yield config.withContextWindow(sized._1).withReserveCompletion(sized._2).withTimeouts(timeouts))

  def anthropic(s: ClientSettings): LlmResult[JLlmClient] =
    client(for {
      apiKey   <- required("apiKey", s.apiKey)
      model    <- required("model", s.model)
      resolver <- resolver
      config <- AnthropicConfig
        .fromValues(model, apiKey, present(s.baseUrl).getOrElse(AnthropicConfig.DEFAULT_BASE_URL))(using
          resolver
        )
      sized    <- sizes(s, config.contextWindow, config.reserveCompletion)
      timeouts <- timeouts(s)
    } yield config.withContextWindow(sized._1).withReserveCompletion(sized._2).withTimeouts(timeouts))

  def gemini(s: ClientSettings): LlmResult[JLlmClient] =
    client(for {
      apiKey   <- required("apiKey", s.apiKey)
      model    <- required("model", s.model)
      resolver <- resolver
      config <- GeminiConfig.fromValues(model, apiKey, present(s.baseUrl).getOrElse(GeminiConfig.DEFAULT_BASE_URL))(
        using resolver
      )
      sized    <- sizes(s, config.contextWindow, config.reserveCompletion)
      timeouts <- timeouts(s)
    } yield config.copy(contextWindow = sized._1, reserveCompletion = sized._2).withTimeouts(timeouts))

  def ollama(s: ClientSettings): LlmResult[JLlmClient] =
    client(for {
      model    <- required("model", s.model)
      resolver <- resolver
      config   <- OllamaConfig.fromValues(model, present(s.baseUrl).getOrElse(OllamaDefaultBaseUrl))(using resolver)
      sized    <- sizes(s, config.contextWindow, config.reserveCompletion)
      timeouts <- timeouts(s)
    } yield config.withContextWindow(sized._1).withReserveCompletion(sized._2).withTimeouts(timeouts))

  def openAiCompatible(s: ClientSettings): LlmResult[JLlmClient] =
    client(
      for {
        baseUrl <- required("baseUrl", s.baseUrl)
        model   <- required("model", s.model)
        sized <- sizes(
          s,
          OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW,
          OpenAICompatibleConfig.DEFAULT_RESERVE_COMPLETION
        )
        timeouts <- timeouts(s)
      } yield OpenAICompatibleConfig(model, baseUrl)
        .withApiKey(present(s.apiKey))
        .withContextWindow(sized._1)
        .withReserveCompletion(sized._2)
        .withHeaders(s.headers.asScala.toMap)
        .withTimeouts(timeouts)
    )

  def azureOpenAi(s: ClientSettings): LlmResult[JLlmClient] =
    client(for {
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
    } yield config.copy(contextWindow = sized._1, reserveCompletion = sized._2).withTimeouts(timeouts))

  /** The client for `config`, or the first thing wrong with what the builder was told. */
  private def client(config: Result[ProviderConfig]): LlmResult[JLlmClient] =
    config.fold(LlmResult.failure, Llm4s.createClient)

  /** The model catalogue's view of context windows, as a configured provider's section is read with. */
  private def resolver: Result[ContextWindowResolver] =
    Llm4sConfig.modelRegistryService().map(new ContextWindowResolver(_))

  /** `value`, which the builder must have been given: `null` or blank is not given. */
  private def required(field: String, value: String): Result[String] =
    present(value).toRight(ValidationError.required(field))

  /** `value` when it says something: `null` and blank say nothing. */
  private def present(value: String): Option[String] = Option(value).map(_.trim).filter(_.nonEmpty)

  /**
   * The context window and reply reserve: what the builder was given, else what the provider's factory chose for the
   * model. The reserve must leave room for the conversation, whichever of the two the caller set.
   */
  private def sizes(s: ClientSettings, defaultWindow: Int, defaultReserve: Int): Result[(Int, Int)] = {
    val window  = Option(s.contextWindow).map(_.intValue)
    val reserve = Option(s.reserveCompletion).map(_.intValue)
    val (w, r)  = (window.getOrElse(defaultWindow), reserve.getOrElse(defaultReserve))
    if (window.exists(_ < 1)) invalid("contextWindow", s"must be at least 1, got $w")
    else if (reserve.exists(_ < 0)) invalid("reserveCompletion", s"must be at least 0, got $r")
    else if ((window.isDefined || reserve.isDefined) && r >= w)
      invalid(
        if (reserve.isDefined) "reserveCompletion" else "contextWindow",
        s"the reply's reserve, $r, must be less than the context window, $w"
      )
    else Right((w, r))
  }

  private def invalid(field: String, reason: String): Result[Nothing] = Left(ValidationError.invalid(field, reason))

  /** The timeouts the builder was given, each more than zero; unset ones keep the provider's default. */
  private def timeouts(s: ClientSettings): Result[ProviderTimeouts] =
    for {
      request <- positive("requestTimeout", s.requestTimeout)
      stream  <- positive("streamTimeout", s.streamTimeout)
    } yield ProviderTimeouts(request, stream)

  private def positive(field: String, value: Duration): Result[Option[FiniteDuration]] =
    Option(value) match {
      case Some(d) if d.isNegative || d.isZero => invalid(field, s"must be more than zero, got $d")
      case other                               => Right(other.map(_.toScala))
    }
}
