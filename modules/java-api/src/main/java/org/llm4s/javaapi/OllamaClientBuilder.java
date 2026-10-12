package org.llm4s.javaapi;

import java.time.Duration;
import org.llm4s.metrics.MetricsCollector;

/**
 * Builds a {@link JLlmClient} for Ollama in code, with no {@code application.conf} and no Scala type: start
 * with {@link Llm4s#ollama()}, set what the call needs and {@link #build()} it.
 *
 * <pre>{@code
 * LlmResult<JLlmClient> client = Llm4s.ollama()
 *     .model("llama3.2")
 *     .build();
 * }</pre>
 *
 * <p>{@code model} is required; everything else has the default the library's own configuration gives
 * it. Immutable: every method returns a new builder, so one can be shared, and forked into several clients. A
 * setter never throws: a value that is missing or out of range makes {@link #build()} return a failed result of
 * kind {@code VALIDATION} that names the field.
 */
public final class OllamaClientBuilder {

  private final ClientSettings settings;

  OllamaClientBuilder(ClientSettings settings) {
    this.settings = settings;
  }

  /** The model to call, such as {@code llama3.2} - required; a {@code null} or blank model is the same as none. */
  public OllamaClientBuilder model(String model) {
    return new OllamaClientBuilder(settings.model(model));
  }

  /** The API's base URL - the Ollama server - when not {@code http://localhost:11434}; {@code null} for {@code http://localhost:11434}. */
  public OllamaClientBuilder baseUrl(String baseUrl) {
    return new OllamaClientBuilder(settings.baseUrl(baseUrl));
  }

  /**
   * The model's context window in tokens, at least 1, instead of the one the library knows for the model (or guesses
   * from its name).
   */
  public OllamaClientBuilder contextWindow(int contextWindow) {
    return new OllamaClientBuilder(settings.contextWindow(contextWindow));
  }

  /** The tokens of the context window kept for the reply, at least 0, instead of the library's figure for the model. */
  public OllamaClientBuilder reserveCompletion(int reserveCompletion) {
    return new OllamaClientBuilder(settings.reserveCompletion(reserveCompletion));
  }

  /** How long a call may take, more than zero; {@code null} for the provider's default. */
  public OllamaClientBuilder requestTimeout(Duration requestTimeout) {
    return new OllamaClientBuilder(settings.requestTimeout(requestTimeout));
  }

  /** How long a streamed call may go without a chunk, more than zero; {@code null} for the provider's default. */
  public OllamaClientBuilder streamTimeout(Duration streamTimeout) {
    return new OllamaClientBuilder(settings.streamTimeout(streamTimeout));
  }

  /**
   * Where the client reports every call - request outcome, duration, tokens and cost - such as a
   * {@code MicrometerMetrics} from {@code llm4s-observability-micrometer} over the application's
   * {@code MeterRegistry}, as {@link Llm4s#createClient(org.llm4s.llmconnect.config.ProviderConfig, MetricsCollector)}
   * does for a config object; {@code null}, the default, records nothing.
   */
  public OllamaClientBuilder metrics(MetricsCollector metrics) {
    return new OllamaClientBuilder(settings.metrics(metrics));
  }

  /**
   * The client, or why there is none: a required field that is missing, or a value out of range, is a failed result
   * of kind {@code VALIDATION} naming the field. Sends no request and never throws.
   */
  public LlmResult<JLlmClient> build() {
    return ClientBuilds.ollama(settings);
  }

  /** The fields set so far, the API key as {@code ***} and headers by name only. */
  @Override
  public String toString() {
    return settings.describe("OllamaClientBuilder");
  }
}
