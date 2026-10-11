package org.llm4s.javaapi.usage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.llm4s.javaapi.AnthropicClientBuilder;
import org.llm4s.javaapi.AzureOpenAiClientBuilder;
import org.llm4s.javaapi.GeminiClientBuilder;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.OllamaClientBuilder;
import org.llm4s.javaapi.OpenAiClientBuilder;
import org.llm4s.javaapi.OpenAiCompatibleClientBuilder;

/**
 * The client builders as a Java application in its own package uses them (#1489): no {@code application.conf}, no
 * Scala type, the key read by the caller and handed over. Driven by {@code ClientBuildersSpec}, which points each one
 * at a local server standing in for the provider.
 */
public final class ClientBuilderCheck {
  private ClientBuilderCheck() {}

  /** Asks each provider, at {@code baseUrl}, "Say hi", with the key and model the spec checks reach the request. */
  public static List<String> askEach(String baseUrl) {
    List<String> answers = new ArrayList<>();
    answers.add("openai: " + ask(Llm4s.openAi()
        .apiKey("sk-openai-java")
        .model("gpt-4o-mini")
        .baseUrl(baseUrl + "/v1")
        .organization("org-java")
        .requestTimeout(Duration.ofSeconds(30))
        .build()));
    answers.add("anthropic: " + ask(Llm4s.anthropic()
        .apiKey("sk-ant-java")
        .model("claude-sonnet-4-20250514")
        .baseUrl(baseUrl)
        .build()));
    answers.add("gemini: " + ask(Llm4s.gemini()
        .apiKey("gemini-java-key")
        .model("gemini-2.0-flash")
        .baseUrl(baseUrl)
        .build()));
    answers.add("ollama: " + ask(Llm4s.ollama()
        .model("llama3.2")
        .baseUrl(baseUrl)
        .contextWindow(8192)
        .reserveCompletion(1024)
        .build()));
    answers.add("openai-compatible: " + ask(Llm4s.openAiCompatible()
        .baseUrl(baseUrl + "/v1")
        .model("local-model")
        .apiKey("sk-local-java")
        .header("X-Team-Id", "team-7")
        .build()));
    answers.add("azure: " + ask(Llm4s.azureOpenAi()
        .endpoint(baseUrl)
        .apiKey("azure-java-key")
        .model("my-deployment")
        .build()));
    return answers;
  }

  private static String ask(LlmResult<JLlmClient> built) {
    try (JLlmClient client = built.get()) {
      return client.complete("Say hi").get();
    }
  }

  /**
   * What a Java caller reads when it leaves something out: each builder with nothing set, then OpenAI with a key read
   * from a variable that is not set ({@code System.getenv} returns {@code null}), then a context window of zero.
   */
  public static List<String> whatIsMissing() {
    List<String> failures = new ArrayList<>();
    for (LlmResult<JLlmClient> result : List.of(
        Llm4s.openAi().build(),
        Llm4s.anthropic().model("claude-sonnet-4-20250514").build(),
        Llm4s.gemini().apiKey("k").build(),
        Llm4s.ollama().build(),
        Llm4s.openAiCompatible().model("m").build(),
        Llm4s.azureOpenAi().apiKey("k").model("d").build(),
        Llm4s.openAi().apiKey(System.getenv("LLM4S_NO_SUCH_VARIABLE_1489")).model("gpt-4o").build(),
        Llm4s.ollama().model("llama3.2").contextWindow(0).build())) {
      LlmException error = result.getError();
      failures.add(error.getKind() + ": " + error.getMessage());
    }
    return failures;
  }

  /** Each builder's {@code toString} with its key set: what a log line would show. */
  public static List<String> described() {
    OpenAiClientBuilder openAi = Llm4s.openAi().apiKey("sk-secret-1").model("gpt-4o-mini");
    AnthropicClientBuilder anthropic = Llm4s.anthropic().apiKey("sk-secret-2").model("claude");
    GeminiClientBuilder gemini = Llm4s.gemini().apiKey("sk-secret-3").model("gemini-2.0-flash");
    OllamaClientBuilder ollama = Llm4s.ollama().model("llama3.2").baseUrl("http://gpu-box:11434");
    OpenAiCompatibleClientBuilder compatible = Llm4s.openAiCompatible()
        .baseUrl("http://localhost:8000/v1")
        .model("m")
        .apiKey("sk-secret-4")
        .header("Authorization-Extra", "sk-secret-5");
    AzureOpenAiClientBuilder azure = Llm4s.azureOpenAi()
        .endpoint("https://r.openai.azure.com")
        .apiKey("sk-secret-6")
        .model("d")
        .apiVersion("2024-10-21");
    return List.of(
        openAi.toString(),
        anthropic.toString(),
        gemini.toString(),
        ollama.toString(),
        compatible.toString(),
        azure.toString());
  }
}
