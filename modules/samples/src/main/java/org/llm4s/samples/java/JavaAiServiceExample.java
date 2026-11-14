package org.llm4s.samples.java;

import java.util.List;
import org.llm4s.javaapi.FakeLlmClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.javaapi.services.MemoryId;
import org.llm4s.javaapi.services.SystemMessage;
import org.llm4s.javaapi.services.UserMessage;
import org.llm4s.javaapi.services.V;

/**
 * A declarative AI service (#2277): an annotated interface that llm4s implements, run against a scripted model with no
 * API key or network access:
 *
 * <pre>
 * sbt "samples/runMain org.llm4s.samples.java.JavaAiServiceExample"
 * </pre>
 *
 * <p>To use a real model, pass the client of {@link Llm4s#createDefaultClient()} (or {@code Llm4s.openAi()...build()})
 * to {@link #assistant(JLlmClient)} instead of the fake's.
 */
public final class JavaAiServiceExample {

  private JavaAiServiceExample() {}

  public enum Sentiment { POSITIVE, NEGATIVE, NEUTRAL }

  public record Ticket(String title, int priority) {}

  /** The service: each method's prompt is declared, and its return type says what the answer becomes. */
  @SystemMessage("You are a support agent for {{product}}. Answer briefly.")
  public interface SupportAssistant {

    String answer(@UserMessage String question, @V("product") String product);

    @SystemMessage("You classify the sentiment of customer messages.")
    @UserMessage("Classify the sentiment of: {{text}}")
    Sentiment classify(@V("text") String text);

    @SystemMessage("You turn complaints into support tickets; priority 1 is the most urgent.")
    LlmResult<Ticket> draftTicket(@UserMessage String complaint);

    @SystemMessage("You are a friendly support agent who remembers the customer.")
    String chat(@MemoryId String customer, @UserMessage String message);
  }

  /** The implementation llm4s builds over {@code client}. */
  public static SupportAssistant assistant(JLlmClient client) {
    return Llm4s.aiService(SupportAssistant.class).client(client).build();
  }

  /** The replies the scripted model gives, in order: text, an enum and a record as JSON, then a conversation. */
  public static FakeLlmClient scriptedModel() {
    return FakeLlmClient.replying(
        "Open Settings, choose Security, then Reset password.",
        "{\"value\":\"NEGATIVE\"}",
        "{\"title\":\"Charged twice in March\",\"priority\":1}",
        "Nice to meet you, Ada.",
        "Your name is Ada.");
  }

  /** Every method called once, each result as a line. */
  public static List<String> run(SupportAssistant assistant) {
    String answer = assistant.answer("How do I reset my password?", "Acme Cloud");
    Sentiment sentiment = assistant.classify("The app keeps crashing and nobody answers my emails.");
    LlmResult<Ticket> ticket = assistant.draftTicket("I was charged twice in March.");
    assistant.chat("ada", "Hi, I am Ada.");
    String remembered = assistant.chat("ada", "What is my name?");
    return List.of(
        "answer: " + answer,
        "sentiment: " + sentiment,
        "ticket: " + ticket.map(t -> t.title() + " (priority " + t.priority() + ")").getOrNull(),
        "memory: " + remembered);
  }

  public static void main(String[] args) {
    FakeLlmClient model = scriptedModel();
    try (JLlmClient client = model.client()) {
      run(assistant(client)).forEach(System.out::println);
      System.out.println("Prompts sent: " + model.prompts());
    }
  }
}
