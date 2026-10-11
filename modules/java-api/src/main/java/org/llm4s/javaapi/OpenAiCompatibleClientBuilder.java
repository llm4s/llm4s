package org.llm4s.javaapi;

import java.time.Duration;

/**
 * Builds a {@link JLlmClient} for an OpenAI-compatible server (vLLM, LM Studio, LiteLLM, a gateway and so on) in code, with no {@code application.conf} and no Scala type: start
 * with {@link Llm4s#openAiCompatible()}, set what the call needs and {@link #build()} it.
 *
 * <pre>{@code
 * LlmResult<JLlmClient> client = Llm4s.openAiCompatible()
 *     .baseUrl("http://localhost:8000/v1")
 *     .model("local-model")
 *     .build();
 * }</pre>
 *
 * <p>{@code baseUrl} and {@code model} are required; everything else has the default the library's own configuration gives
 * it. Immutable: every method returns a new builder, so one can be shared, and forked into several clients. A
 * setter never throws: a value that is missing or out of range makes {@link #build()} return a failed result of
 * kind {@code VALIDATION} that names the field.
 */
public final class OpenAiCompatibleClientBuilder {

  private final ClientSettings settings;

  OpenAiCompatibleClientBuilder(ClientSettings settings) {
    this.settings = settings;
  }

  /**
   * The API key, sent as {@code Authorization: Bearer}. Never logged: this builder's {@code toString} prints it as {@code ***}. A {@code null} or blank
   * key is the same as none: many local servers need none.
   */
  public OpenAiCompatibleClientBuilder apiKey(String apiKey) {
    return new OpenAiCompatibleClientBuilder(settings.apiKey(apiKey));
  }

  /** The model to call, such as {@code local-model} - required; a {@code null} or blank model is the same as none. */
  public OpenAiCompatibleClientBuilder model(String model) {
    return new OpenAiCompatibleClientBuilder(settings.model(model));
  }

  /** The API's base URL, up to the path segment before {@code /chat/completions}, such as {@code http://localhost:8000/v1} - required; {@code null} or blank is the same as none. */
  public OpenAiCompatibleClientBuilder baseUrl(String baseUrl) {
    return new OpenAiCompatibleClientBuilder(settings.baseUrl(baseUrl));
  }

  /**
   * The model's context window in tokens, at least 1, instead of the one the library knows for the model (or guesses
   * from its name).
   */
  public OpenAiCompatibleClientBuilder contextWindow(int contextWindow) {
    return new OpenAiCompatibleClientBuilder(settings.contextWindow(contextWindow));
  }

  /** The tokens of the context window kept for the reply, at least 0, instead of the library's figure for the model. */
  public OpenAiCompatibleClientBuilder reserveCompletion(int reserveCompletion) {
    return new OpenAiCompatibleClientBuilder(settings.reserveCompletion(reserveCompletion));
  }

  /** How long a call may take, more than zero; {@code null} for the provider's default. */
  public OpenAiCompatibleClientBuilder requestTimeout(Duration requestTimeout) {
    return new OpenAiCompatibleClientBuilder(settings.requestTimeout(requestTimeout));
  }

  /** How long a streamed call may go without a chunk, more than zero; {@code null} for the provider's default. */
  public OpenAiCompatibleClientBuilder streamTimeout(Duration streamTimeout) {
    return new OpenAiCompatibleClientBuilder(settings.streamTimeout(streamTimeout));
  }

  /**
   * A header sent with every request, such as a gateway's tenant id; a {@code null} value removes it, and a
   * {@code null} name changes nothing. Values are kept out of this builder's {@code toString}, which lists header
   * names only, since a header can carry a credential too.
   */
  public OpenAiCompatibleClientBuilder header(String name, String value) {
    return name == null ? this : new OpenAiCompatibleClientBuilder(settings.header(name, value));
  }

  /**
   * The client, or why there is none: a required field that is missing, or a value out of range, is a failed result
   * of kind {@code VALIDATION} naming the field. Sends no request and never throws.
   */
  public LlmResult<JLlmClient> build() {
    return ClientBuilds.openAiCompatible(settings);
  }

  /** The fields set so far, the API key as {@code ***} and headers by name only. */
  @Override
  public String toString() {
    return settings.describe("OpenAiCompatibleClientBuilder");
  }
}
