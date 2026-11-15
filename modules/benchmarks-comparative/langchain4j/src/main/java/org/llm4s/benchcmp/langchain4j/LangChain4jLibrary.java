package org.llm4s.benchcmp.langchain4j;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import org.llm4s.benchcmp.harness.ChatLibrary;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LangChain4j: {@code OpenAiChatModel} and {@code OpenAiStreamingChatModel} from {@code langchain4j-open-ai}, and an
 * {@code AiServices} assistant with a {@code @Tool} for the tool loop. Default HTTP client (the JDK's), retries off.
 */
public final class LangChain4jLibrary implements ChatLibrary {

  /** The tool the agent scenario calls. */
  public static final class LookupTools {
    @Tool("Looks up the value stored under a key")
    public String lookup(@P("The key to look up") String key) {
      return "value-" + key;
    }
  }

  /** The AI service the tool scenario runs. */
  public interface Assistant {
    String chat(String userMessage);
  }

  private final OpenAiChatModel model;
  private final OpenAiStreamingChatModel streamingModel;
  private final Assistant assistant;

  public LangChain4jLibrary(String baseUrl) {
    this.model = OpenAiChatModel.builder()
        .baseUrl(baseUrl)
        .apiKey("bench-key")
        .modelName("bench-model")
        .maxRetries(0)
        .build();
    this.streamingModel = OpenAiStreamingChatModel.builder()
        .baseUrl(baseUrl)
        .apiKey("bench-key")
        .modelName("bench-model")
        .build();
    this.assistant = AiServices.builder(Assistant.class)
        .chatModel(model)
        .tools(new LookupTools())
        // The handlers LangChain4j recommends setting explicitly (it warns when they are not).
        .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
        .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
        .build();
  }

  @Override
  public String chat(String prompt) {
    return model.chat(prompt);
  }

  @Override
  public int stream(String prompt) {
    AtomicInteger chunks = new AtomicInteger();
    CompletableFuture<ChatResponse> done = new CompletableFuture<>();
    streamingModel.chat(prompt, new StreamingChatResponseHandler() {
      @Override
      public void onPartialResponse(String partialResponse) {
        if (!partialResponse.isEmpty()) chunks.incrementAndGet();
      }

      @Override
      public void onCompleteResponse(ChatResponse completeResponse) {
        done.complete(completeResponse);
      }

      @Override
      public void onError(Throwable error) {
        done.completeExceptionally(error);
      }
    });
    done.join();
    return chunks.get();
  }

  @Override
  public String toolAgent(String prompt) {
    return assistant.chat(prompt);
  }
}
