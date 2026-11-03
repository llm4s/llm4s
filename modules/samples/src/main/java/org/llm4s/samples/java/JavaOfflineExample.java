package org.llm4s.samples.java;

import org.llm4s.javaapi.FakeLlmClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmErrorKind;
import org.llm4s.javaapi.LlmResult;

/**
 * Run application code against a scripted model, without an API key or network access:
 *
 * <pre>
 * sbt "samples/runMain org.llm4s.samples.java.JavaOfflineExample"
 * </pre>
 *
 * <p>To use a real model, call {@link org.llm4s.javaapi.Llm4s#createDefaultClient()}, check the returned result for
 * failure, and pass the successful {@link JLlmClient} to the same {@link TicketClassifier}. Configure that provider
 * and its credentials first, and close the client when finished. This example only creates a fake client.
 */
public final class JavaOfflineExample {

  private JavaOfflineExample() {}

  /** Application code depends on the public client API, not on the fake used to test it. */
  public static final class TicketClassifier {

    private final JLlmClient client;

    public TicketClassifier(JLlmClient client) {
      this.client = client;
    }

    /** Unknown replies become OTHER; provider failures remain failed results. */
    public LlmResult<String> classify(String ticket) {
      String prompt = "Classify this support ticket as BILLING, BUG, or OTHER. Reply with only the label.\n\nTicket: "
          + ticket;
      return client.complete(prompt).map(reply -> {
        if ("BILLING".equals(reply) || "BUG".equals(reply) || "OTHER".equals(reply)) {
          return reply;
        }
        return "OTHER";
      });
    }
  }

  public static void main(String[] args) {
    FakeLlmClient fake = FakeLlmClient.builder()
        .reply("BILLING")
        .reply("not sure")
        .failWith(LlmErrorKind.RATE_LIMIT, "slow down")
        .build();

    try (JLlmClient client = fake.client()) {
      TicketClassifier classifier = new TicketClassifier(client);
      String[] tickets = {
          "Please refund my duplicate charge.",
          "I have a question about my account.",
          "The application crashes when I sign in."
      };
      for (String ticket : tickets) {
        LlmResult<String> result = classifier.classify(ticket);
        if (result.isFailure()) {
          System.out.println(ticket + " -> " + result.getError().getKind() + ": " + result.getError().getMessage());
        } else {
          System.out.println(ticket + " -> " + result.get());
        }
      }
      System.out.println("Recorded prompts: " + fake.prompts());
    }
  }
}
