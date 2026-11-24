package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model.{ ContentPart, ThinkingBlock }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

/**
 * DeepSeek LLM client implementation using the OpenAI-compatible API.
 *
 * Provides access to DeepSeek models including DeepSeek-Chat (V3) and
 * DeepSeek-Reasoner (R1) for advanced reasoning tasks.
 *
 * An [[OpenAICompatibleClient]] with [[DeepSeekDialect]]: the standard
 * chat-completions format plus DeepSeek's `User-Agent`, and
 * `reasoning_content` - the reasoner's chain of thought - read back as
 * `Completion.thinking` and as streamed thinking deltas.
 *
 * @param config DeepSeek configuration containing API key, model, base URL, and context settings
 * @param metrics MetricsCollector for recording request metrics
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere
 *
 * The class is private to this module: it is an [[OpenAICompatibleClient]] with this vendor's settings and
 * dialect, and the companion's factory returns it as one. Outside the module, build it with that factory, or
 * let `LLMConnect.getClient` build it from a named section.
 */
final private[provider] class DeepSeekClient(
  config: DeepSeekConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  httpClient: Llm4sHttpClient = Llm4sHttpClient.FromConfig
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "deepseek",
        displayName = "DeepSeek",
        model = config.model,
        baseUrl = config.baseUrl,
        credential = OpenAICompatibleClient.keyCredential(config.apiKey, config.apiKeyProvider),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion,
        timeouts = config.timeouts,
        network = config.network
      ),
      DeepSeekDialect,
      metrics,
      exchangeLogging,
      httpClient
    )

@Stable
object DeepSeekClient {

  /**
   * The DeepSeek client for `config`, an [[OpenAICompatibleClient]], or why one cannot be built: a blank `apiKey` with no `ApiKeyProvider` is a
   * `Left(ConfigurationError)`, and nothing is thrown.
   *
   * @param config  the provider's configuration.
   * @param options the metrics collector, exchange logging and HTTP client of the client; none of them by default.
   *                `options.httpClient`, when set, carries every request and stays the caller's to close.
   */
  def apply(config: DeepSeekConfig, options: LlmClientOptions = LlmClientOptions())(using
    ModelRegistryService
  ): Result[OpenAICompatibleClient] =
    OpenAICompatibleClient.keyed("DeepSeek", config.apiKey, config.apiKeyProvider)(options)(
      new DeepSeekClient(config, _, _, _)
    )
}

/**
 * DeepSeek's departures from the standard format: a `User-Agent`, and the
 * reasoner's `reasoning_content`, which is its thinking. The old
 * `DeepSeekClient` ignored `reasoning_content`, so `deepseek-reasoner`'s
 * thinking was dropped ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 * DeepSeek reports reasoning tokens under `completion_tokens_details`. An assistant turn's
 * thinking is sent back as its `reasoning_content`, which thinking mode requires once tools are
 * in play.
 *
 * Streamed usage is asked for with the standard `stream_options.include_usage`, which
 * DeepSeek's chat-completion reference documents (it also reports usage on a stream's last
 * chunk unasked).
 */
private[llm4s] object DeepSeekDialect extends OpenAICompatibleDialect:
  override val headers: Seq[(String, String)] = Seq("User-Agent" -> "llm4s/1.0")

  /** Text only: DeepSeek's chat-completion reference takes no image or document input. */
  override def encodeUserPart: PartialFunction[ContentPart, ujson.Value] = OpenAICompatibleDialect.textPart

  override def thinking(obj: ujson.Value): Option[String] =
    OpenAICompatibleDialect.firstString(obj, "reasoning_content")

  /**
   * Sent back as the assistant turn's `reasoning_content`. DeepSeek's thinking-mode guide requires
   * it in every later request of a conversation that carries `tools` - leaving it out is a 400 -
   * and ignores it otherwise.
   */
  override def encodeThinking(message: ujson.Obj, thinking: Seq[ThinkingBlock]): Unit =
    ThinkingBlock.text(thinking).foreach(text => message("reasoning_content") = text)
