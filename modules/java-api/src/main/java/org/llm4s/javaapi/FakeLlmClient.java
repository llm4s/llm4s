package org.llm4s.javaapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A fake model for testing Java code that calls llm4s, with no network and no API key (#1497). It answers from a
 * script - replies and failures, one per call, in order - and records the prompt of every call, so a test can check
 * what its code sent and what it did with the answer.
 *
 * <pre>{@code
 * FakeLlmClient fake = FakeLlmClient.replying("4");
 * String answer = new Calculator(fake.client()).ask("What is 2+2?");   // your code takes a JLlmClient
 * assertEquals("4", answer);
 * assertEquals(List.of("What is 2+2?"), fake.prompts());
 * }</pre>
 *
 * <p>{@link #client()} is a real {@link JLlmClient}, so the code under test needs no interface or mock: everything a
 * {@code JLlmClient} does with a reply happens as in production. A call beyond the script fails, as a failed
 * {@link LlmResult} whose message names the call and its prompt, rather than hanging or throwing. The fake is
 * thread-safe: concurrent calls each take their own entry of the script.
 */
public final class FakeLlmClient {

    private final FakeLlmClients.Scripted scripted;
    private final JLlmClient client;

    private FakeLlmClient(List<FakeLlmClients.Entry> entries) {
        this.scripted = new FakeLlmClients.Scripted(entries);
        this.client = Llm4s.wrapClient(scripted);
    }

    /** A fake that answers the first call with the first reply, the second with the second, and so on. */
    public static FakeLlmClient replying(String... replies) {
        Builder builder = builder();
        for (String reply : replies) {
            builder = builder.reply(reply);
        }
        return builder.build();
    }

    /** A builder for a script that mixes replies and failures. */
    public static Builder builder() {
        return new Builder(Collections.emptyList());
    }

    /** The client to give the code under test. */
    public JLlmClient client() {
        return client;
    }

    /** How many calls the code under test made, scripted or not. */
    public int callCount() {
        return scripted.callCount();
    }

    /** The prompt of each call, in order: the text of its last user message, or {@code ""} when it had none. */
    public List<String> prompts() {
        return scripted.prompts();
    }

    /** The prompt of the last call, or empty when there was none. */
    public Optional<String> lastPrompt() {
        List<String> prompts = scripted.prompts();
        return prompts.isEmpty() ? Optional.empty() : Optional.of(prompts.get(prompts.size() - 1));
    }

    /**
     * A script under construction: each {@code reply} or {@code failWith} answers one call, in the order they were
     * added. The builder is immutable; every method returns a new one.
     */
    public static final class Builder {

        private final List<FakeLlmClients.Entry> entries;

        private Builder(List<FakeLlmClients.Entry> entries) {
            this.entries = entries;
        }

        /** The next call is answered with {@code text}. */
        public Builder reply(String text) {
            return with(FakeLlmClients.reply(text));
        }

        /**
         * The next call fails with an error of {@code kind}, as a provider's would: {@link LlmException#getKind()}
         * reads {@code kind} back, and {@code message} is the error's message where the error type carries one (a
         * rate-limit, authentication or cancellation error words its own). A {@code null} kind is
         * {@link LlmErrorKind#OTHER}.
         */
        public Builder failWith(LlmErrorKind kind, String message) {
            return with(FakeLlmClients.failure(kind, message));
        }

        /** The fake that answers from this script. */
        public FakeLlmClient build() {
            return new FakeLlmClient(entries);
        }

        private Builder with(FakeLlmClients.Entry entry) {
            List<FakeLlmClients.Entry> next = new ArrayList<>(entries);
            next.add(entry);
            return new Builder(Collections.unmodifiableList(next));
        }
    }
}
