package org.llm4s.javaapi;

import java.time.Duration;
import org.llm4s.llmconnect.auth.AccessTokenProvider;
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
 * <p>A resource that forbids keys authenticates with Microsoft Entra ID instead (#2274): {@link #entraId()} uses
 * Azure's {@code DefaultAzureCredential} - a managed identity, workload identity or the Azure CLI's login - and needs
 * {@code org.llm4s:llm4s-azure-identity} on the classpath; {@link #tokenProvider(AccessTokenProvider)} takes tokens from
 * any provider, such as {@code AzureEntraId.tokenProvider(credential)} over another {@code TokenCredential}.
 *
 * <pre>{@code
 * LlmResult<JLlmClient> client = Llm4s.azureOpenAi()
 *     .endpoint("https://my-resource.openai.azure.com")
 *     .entraId()
 *     .model("my-gpt-4o-deployment")
 *     .build();
 * }</pre>
 *
 * <p>{@code endpoint}, {@code model} (the deployment name) and one credential - {@code apiKey}, {@code entraId} or
 * {@code tokenProvider} - are required; everything else has the default the library's own configuration gives
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

  /**
   * Authenticates with Microsoft Entra ID through Azure's {@code DefaultAzureCredential} instead of an API key: a
   * system-assigned managed identity, workload identity, environment credentials or a developer's Azure CLI login.
   * Needs {@code org.llm4s:llm4s-azure-identity} on the classpath; without it {@link #build()} fails with kind
   * {@code CONFIGURATION}. Replaces an earlier {@link #tokenProvider(AccessTokenProvider)}.
   */
  public AzureOpenAiClientBuilder entraId() {
    return new AzureOpenAiClientBuilder(settings.entraId(null));
  }

  /**
   * {@link #entraId()} with the client id of the user-assigned managed identity (or workload identity) to use; a
   * {@code null} or blank id is the default identity.
   */
  public AzureOpenAiClientBuilder entraId(String clientId) {
    return new AzureOpenAiClientBuilder(settings.entraId(clientId));
  }

  /**
   * Authenticates with bearer tokens from {@code tokenProvider} instead of an API key, such as
   * {@code AzureEntraId.tokenProvider(credential)} from {@code llm4s-azure-identity} over any Azure
   * {@code TokenCredential}. Replaces an earlier {@link #entraId()}; {@code null} is the same as none.
   */
  public AzureOpenAiClientBuilder tokenProvider(AccessTokenProvider tokenProvider) {
    return new AzureOpenAiClientBuilder(settings.tokenProvider(tokenProvider));
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
