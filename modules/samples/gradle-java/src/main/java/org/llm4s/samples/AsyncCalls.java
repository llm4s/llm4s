package org.llm4s.samples;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.llm4s.javaapi.FakeLlmClient;
import org.llm4s.javaapi.JAgent;
import org.llm4s.javaapi.JAgentResult;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmErrorKind;
import org.llm4s.javaapi.LlmException;

/**
 * Asynchronous calls with {@code llm4s-java-api}: {@code completeAsync}, {@code runAsync} and the rest return a
 * {@link CompletableFuture} at once, so calls fan out with {@code allOf}, take a timeout with {@code orTimeout} and
 * stop with {@code cancel(true)}.
 *
 * <p>It runs with no API key and no network: the model is a {@link FakeLlmClient}, which answers from a script.
 * Swap {@code fake.client()} for {@code Llm4s.createDefaultClient().get()} to call a real provider.
 */
public final class AsyncCalls {

    private AsyncCalls() {}

    public static void main(String[] args) throws Exception {
        FakeLlmClient fake = FakeLlmClient.builder()
            .reply("A short summary.")   // the two fanned-out calls may arrive in either order
            .reply("A short summary.")
            .reply("Ship on Friday.")
            .failWith(LlmErrorKind.RATE_LIMIT, "slow down")
            .reply("4")
            .build();

        try (JLlmClient client = fake.client()) {
            // Fan out: two completions run at once, each on a virtual thread of the client's own.
            CompletableFuture<String> revenue = client.completeAsync("Summarise the revenue report");
            CompletableFuture<String> costs = client.completeAsync("Summarise the cost report");
            CompletableFuture.allOf(revenue, costs).join();

            // An agent turn, as a future too.
            JAgent agent = Llm4s.createAgent(client);
            CompletableFuture<JAgentResult> plan = agent.runAsync("When should we ship?");
            System.out.println("revenue: " + revenue.join());
            System.out.println("costs:   " + costs.join());
            System.out.println("plan:    " + plan.get(30, TimeUnit.SECONDS).answer().orElse("(no answer)"));

            // A failure completes the future exceptionally with the LlmException the blocking call would return.
            try {
                client.completeAsync("One more?").get();
            } catch (ExecutionException e) {
                LlmException error = (LlmException) e.getCause();
                System.out.println("failed:  " + error.getKind() + " (recoverable: " + error.isRecoverable() + ")");
            }

            // Your own executor, here a bounded pool: the client never shuts it down.
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                String answer = client.withAsyncExecutor(pool)
                    .completeAsync("What is 2+2?")
                    .orTimeout(30, TimeUnit.SECONDS)
                    .join();
                System.out.println("pooled:  " + answer);
            } finally {
                pool.shutdown();
            }
        }

        System.out.println("calls:   " + fake.callCount() + ", the first two " + fake.prompts().subList(0, 2));
    }
}
