package org.llm4s.javaapi;

import java.time.Duration;
import org.llm4s.metrics.MetricsCollector;

/**
 * Builds a {@link JLlmClient} for Anthropic in code, with no {@code application.conf} and no Scala type: start
 * with {@link Llm4s#anthropic()}, set what the call needs and {@link #build()} it.
 *
 * <pre>{@code
 * LlmResult<JLlmClient> client = Llm4s.anthropic()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .model("claude-sonnet-4-20250514")
 *     .build();
 * }</pre>
 *
 * <p>{@code apiKey} and {@code model} are required; everything else has the default the library's own configuration gives
 * it. Immutable: every method returns a new builder, so one can be shared, and forked into several clients. A
 * setter never throws: a value that is missing or out of range makes {@link #build()} return a failed result of
 * kind {@code VALIDATION} that names the field.
 */
public final class AnthropicClientBuilder {

  private final ClientSettings settings;

  AnthropicClientBuilder(ClientSettings settings) {
    this.settings = settings;
  }

  /**
   * The API key - required. Never logged: this builder's {@code toString} prints it as {@code ***}. A {@code null} or blank
   * key is the same as none.
   */
  public AnthropicClientBuilder apiKey(String apiKey) {
    return new AnthropicClientBuilder(settings.apiKey(apiKey));
  }

  /** The model to call, such as {@code claude-sonnet-4-20250514} - required; a {@code null} or blank model is the same as none. */
  public AnthropicClientBuilder model(String model) {
    return new AnthropicClientBuilder(settings.model(model));
  }

  /** The API's base URL, when not Anthropic's own {@code https://api.anthropic.com}; {@code null} for Anthropic's. */
  public AnthropicClientBuilder baseUrl(String baseUrl) {
    return new AnthropicClientBuilder(settings.baseUrl(baseUrl));
  }

  /**
   * The model's context window in tokens, at least 1, instead of the one the library knows for the model (or guesses
   * from its name).
   */
  public AnthropicClientBuilder contextWindow(int contextWindow) {
    return new AnthropicClientBuilder(settings.contextWindow(contextWindow));
  }

  /** The tokens of the context window kept for the reply, at least 0, instead of the library's figure for the model. */
  public AnthropicClientBuilder reserveCompletion(int reserveCompletion) {
    return new AnthropicClientBuilder(settings.reserveCompletion(reserveCompletion));
  }

  /** How long a call may take, more than zero; {@code null} for the provider's default. */
  public AnthropicClientBuilder requestTimeout(Duration requestTimeout) {
    return new AnthropicClientBuilder(settings.requestTimeout(requestTimeout));
  }

  /** How long a streamed call may go without a chunk, more than zero; {@code null} for the provider's default. */
  public AnthropicClientBuilder streamTimeout(Duration streamTimeout) {
    return new AnthropicClientBuilder(settings.streamTimeout(streamTimeout));
  }

  /**
   * Where the client reports every call - request outcome, duration, tokens and cost - such as a
   * {@code MicrometerMetrics} from {@code llm4s-observability-micrometer} over the application's
   * {@code MeterRegistry}, as {@link Llm4s#createClient(org.llm4s.llmconnect.config.ProviderConfig, MetricsCollector)}
   * does for a config object; {@code null}, the default, records nothing.
   */
  public AnthropicClientBuilder metrics(MetricsCollector metrics) {
    return new AnthropicClientBuilder(settings.metrics(metrics));
  }

  /**
   * The client, or why there is none: a required field that is missing, or a value out of range, is a failed result
   * of kind {@code VALIDATION} naming the field. Sends no request and never throws.
   */
  public LlmResult<JLlmClient> build() {
    return ClientBuilds.anthropic(settings);
  }

  /** The fields set so far, the API key as {@code ***} and headers by name only. */
  @Override
  public String toString() {
    return settings.describe("AnthropicClientBuilder");
  }
}
