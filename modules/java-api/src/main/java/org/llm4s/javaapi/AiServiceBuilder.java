package org.llm4s.javaapi;

import java.util.Objects;
import org.llm4s.annotation.Experimental;
import org.llm4s.javaapi.services.ChatMemoryStore;

/**
 * Builds the implementation of an AI service interface (#2277): an interface whose methods are annotated with
 * {@link org.llm4s.javaapi.services.SystemMessage}, {@link org.llm4s.javaapi.services.UserMessage},
 * {@link org.llm4s.javaapi.services.V} and {@link org.llm4s.javaapi.services.MemoryId}. Start with
 * {@link Llm4s#aiService(Class)}. Immutable: each setter returns a new builder. {@link #build()} reads the interface
 * once, checks every method and returns a {@code java.lang.reflect.Proxy} that renders the prompt, calls the model and
 * converts the answer.
 *
 * <pre>{@code
 * SupportAssistant assistant = Llm4s.aiService(SupportAssistant.class)
 *     .client(client)
 *     .tools(new OrderTools())
 *     .build();
 * }</pre>
 *
 * <p>Experimental: the shape may change after a release of feedback.
 *
 * @param <T> the service interface
 */
@Experimental
public final class AiServiceBuilder<T> {

  private final AiServices.Spec<T> spec;

  AiServiceBuilder(AiServices.Spec<T> spec) {
    this.spec = spec;
  }

  /**
   * The client every call goes to; required. The service does not own it: closing the client is the caller's job.
   *
   * @throws NullPointerException when {@code client} is {@code null}
   */
  public AiServiceBuilder<T> client(JLlmClient client) {
    return new AiServiceBuilder<>(spec.withClient(Objects.requireNonNull(client, "client must not be null")));
  }

  /**
   * Tools the model may call: each argument is a {@link JTool}, or an object of a public class whose public
   * {@code @Tool} methods become tools, as {@link JTool#fromAnnotated(Object)} reads them. A service with tools answers
   * each call with one agent turn, which runs the tools the model asks for and returns its final answer; one without
   * asks the model once. Replaces the tools set before. An object with no {@code @Tool} method, or two tools with one
   * name, is an error of {@link #build()}.
   *
   * @throws NullPointerException when an argument is {@code null}
   */
  public AiServiceBuilder<T> tools(Object... tools) {
    for (Object tool : Objects.requireNonNull(tools, "tools must not be null")) {
      Objects.requireNonNull(tool, "tools must not contain null");
    }
    return new AiServiceBuilder<>(spec.withTools(tools.clone()));
  }

  /**
   * Where the conversations of {@code @MemoryId} methods are kept; {@link ChatMemoryStore#inMemory()} by default.
   *
   * @throws NullPointerException when {@code store} is {@code null}
   */
  public AiServiceBuilder<T> memoryStore(ChatMemoryStore store) {
    return new AiServiceBuilder<>(spec.withStore(Objects.requireNonNull(store, "store must not be null")));
  }

  /**
   * The implementation of the interface. Every method is checked here, so a mistake fails at startup rather than at
   * the first call: a return type that is not supported, a parameter with none (or more than one) of
   * {@code @UserMessage}, {@code @V} and {@code @MemoryId}, a method with no user message or with two, a
   * {@code {{placeholder}}} that no {@code @V} parameter names, a {@code @V} name used twice, two {@code @MemoryId}
   * parameters, a streaming method on a service with tools or memory, a {@code JCompletion} method on a service with
   * tools, and tools that cannot be offered together. All of them are reported at once, each naming its method.
   *
   * @throws LlmException of kind {@code VALIDATION} when the interface or the settings are refused
   */
  public T build() {
    return AiServices.built(spec);
  }
}
