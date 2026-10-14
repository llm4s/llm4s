package org.llm4s.javaapi;

import java.time.Duration;
import org.llm4s.metrics.MetricsCollector;

/**
 * Builds a {@link JLlmClient} for Azure OpenAI in code, with no {@code application.conf} and no Scala type: start
 * with {@link Llm4s#azureOpenAi()}, set what the call needs and {@link #build()} it.
 *
 * <pre>{@code
 * LlmResult<JLlmClient> client = Llm4s.azureOpenAi()
 *     .endpoint("https://my-resource.openai.azure.com")
 *     .apiKey(System.getenv("AZURE_OPENAI_API_KEY"))
 *     .model("my-gpt-4o-deployment")
 *     .build();
 * }</pre>
 *
 * <p>{@code endpoint}, {@code apiKey} and {@code model} (the deployment name) are required; everything else has the default the library's own configuration gives
 * it. Immutable: every method returns a new builder, so one can be shared, and forked into several clients. A
 * setter never throws: a value that is missing or out of range makes {@link #build()} return a failed result of
 * kind {@code VALIDATION} that names the field.
 */
public final class AzureOpenAiClientBuilder {

  private final ClientSettings settings;

  AzureOpenAiClientBuilder(ClientSettings settings) {
    this.settings = settings;
  }

  /**
   * The Azure OpenAI resource's endpoint, such as {@code https://my-resource.openai.azure.com} - required; a
   * {@code null} or blank endpoint is the same as none.
   */
  public AzureOpenAiClientBuilder endpoint(String endpoint) {
    return new AzureOpenAiClientBuilder(settings.endpoint(endpoint));
  }

  /**
   * The API key - required. Never logged: this builder's {@code toString} prints it as {@code ***}. A {@code null} or blank
   * key is the same as none.
   */
  public AzureOpenAiClientBuilder apiKey(String apiKey) {
    return new AzureOpenAiClientBuilder(settings.apiKey(apiKey));
  }

  /** The model to call, such as {@code my-gpt-4o-deployment} - required; a {@code null} or blank model is the same as none. */
  public AzureOpenAiClientBuilder model(String model) {
    return new AzureOpenAiClientBuilder(settings.model(model));
  }

  /** The Azure OpenAI API version; {@code null} for the library's default. */
  public AzureOpenAiClientBuilder apiVersion(String apiVersion) {
    return new AzureOpenAiClientBuilder(settings.apiVersion(apiVersion));
  }

  /**
   * The model's context window in tokens, at least 1, instead of the one the library knows for the model (or guesses
   * from its name).
   */
  public AzureOpenAiClientBuilder contextWindow(int contextWindow) {
    return new AzureOpenAiClientBuilder(settings.contextWindow(contextWindow));
  }

  /** The tokens of the context window kept for the reply, at least 0, instead of the library's figure for the model. */
  public AzureOpenAiClientBuilder reserveCompletion(int reserveCompletion) {
    return new AzureOpenAiClientBuilder(settings.reserveCompletion(reserveCompletion));
  }

  /** How long a call may take, more than zero; {@code null} for the provider's default. */
  public AzureOpenAiClientBuilder requestTimeout(Duration requestTimeout) {
    return new AzureOpenAiClientBuilder(settings.requestTimeout(requestTimeout));
  }

  /** How long a streamed call may go without a chunk, more than zero; {@code null} for the provider's default. */
  public AzureOpenAiClientBuilder streamTimeout(Duration streamTimeout) {
    return new AzureOpenAiClientBuilder(settings.streamTimeout(streamTimeout));
  }

  /**
   * Where the client reports every call - request outcome, duration, tokens and cost - such as a
   * {@code MicrometerMetrics} from {@code llm4s-observability-micrometer} over the application's
   * {@code MeterRegistry}, as {@link Llm4s#createClient(org.llm4s.llmconnect.config.ProviderConfig, MetricsCollector)}
   * does for a config object; {@code null}, the default, records nothing.
   */
  public AzureOpenAiClientBuilder metrics(MetricsCollector metrics) {
    return new AzureOpenAiClientBuilder(settings.metrics(metrics));
  }

  /**
   * The client, or why there is none: a required field that is missing, or a value out of range, is a failed result
   * of kind {@code VALIDATION} naming the field. Sends no request and never throws.
   */
  public LlmResult<JLlmClient> build() {
    return ClientBuilds.azureOpenAi(settings);
  }

  /** The fields set so far, the API key as {@code ***} and headers by name only. */
  @Override
  public String toString() {
    return settings.describe("AzureOpenAiClientBuilder");
  }
}
