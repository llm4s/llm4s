package org.llm4s.benchcmp.springai;

import org.llm4s.benchcmp.harness.ChatLibrary;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;

/**
 * Spring AI, without Spring Boot: an {@code OpenAiChatModel} from {@code spring-ai-openai} built with its builder, and
 * a {@code ChatClient} over it for chat, streaming and the {@code @Tool} loop (the model's default tool-calling
 * manager runs the tools). Retries off.
 */
public final class SpringAiLibrary implements ChatLibrary {

  /** The tool the agent scenario calls. */
  public static final class LookupTools {
    @Tool(description = "Looks up the value stored under a key")
    public String lookup(@ToolParam(description = "The key to look up") String key) {
      return "value-" + key;
    }
  }

  private final ChatClient client;
  /**
   * Resolved once, as LangChain4j's {@code AiServices} and the llm4s {@code Agent} build their tool specifications once:
   * {@code .tools(pojo)} on each request would reflect over the class and generate its JSON schema on every call.
   */
  private final ToolCallback[] tools = ToolCallbacks.from(new LookupTools());

  public SpringAiLibrary(String baseUrl) {
    OpenAiChatModel model = OpenAiChatModel.builder()
        .options(OpenAiChatOptions.builder()
            .baseUrl(baseUrl)
            .apiKey("bench-key")
            .model("bench-model")
            .maxRetries(0)
            .build())
        // OkHttp keeps 5 idle connections by default, so each batch of 200 concurrent calls would close 195 and,
        // within seconds, run the machine out of ephemeral ports. LangChain4j's and llm4s's JDK HttpClient keep every
        // idle connection; this gives Spring AI the same pool for the concurrency scenario (5 minutes is OkHttp's
        // default keep-alive, which Spring AI requires to be set with the pool size).
        .httpClientBuilderCustomizer(builder -> builder.maxIdleConnections(256).keepAliveDuration(Duration.ofMinutes(5)))
        .build();
    this.client = ChatClient.create(model);
  }

  @Override
  public String chat(String prompt) {
    return client.prompt(prompt).call().content();
  }

  @Override
  public int stream(String prompt) {
    Long chunks = client.prompt(prompt).stream().content().filter(s -> !s.isEmpty()).count().block();
    return chunks == null ? 0 : chunks.intValue();
  }

  /**
   * No: on JDK 21, 200 concurrent calls on virtual threads deadlocked in a test run. okio's {@code AsyncTimeout}
   * (under OkHttp, under the OpenAI Java SDK) pinned every carrier thread while it waited for a lock, so the virtual
   * threads holding socket reads could never run again. JDK 24 no longer pins on {@code synchronized} (JEP 491).
   */
  @Override
  public boolean virtualThreads() {
    return false;
  }

  @Override
  public String toolAgent(String prompt) {
    return client.prompt(prompt).toolCallbacks(tools).call().content();
  }
}
