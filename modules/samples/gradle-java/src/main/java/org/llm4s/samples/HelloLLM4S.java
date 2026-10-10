package org.llm4s.samples;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.llm4s.agent.events.AgentEvents;
import org.llm4s.agent.graph.StreamEvent;
import org.llm4s.javaapi.AgentStream;
import org.llm4s.javaapi.Answer;
import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JCompletion;
import org.llm4s.javaapi.JCompletionOptions;
import org.llm4s.javaapi.JAgent;
import org.llm4s.javaapi.JAgentResult;
import org.llm4s.javaapi.JAgentStatus;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.javaapi.PendingInterrupt;
import org.llm4s.javaapi.StreamEvents;
import org.llm4s.llmconnect.model.Conversation;
import org.llm4s.toolapi.ToolRegistry;

/**
 * Calling llm4s from Java with {@code llm4s-java-api}.
 *
 * <p>Every call returns an {@link LlmResult}, so Java code never touches Scala's {@code Either}, {@code Option}
 * or {@code Nil}. A failure is a value to check, not an exception to catch: the sample shows both ways to read
 * one ({@code ifSuccess} / {@code ifFailure}, and {@code isFailure} then {@code getError}).
 *
 * <p>The provider comes from {@code application.conf} (see {@code src/main/resources}); with
 * {@code OPENAI_API_KEY} set it needs nothing else.
 */
public final class HelloLLM4S {

    private HelloLLM4S() {}

    public static void main(String[] args) {
        System.exit(run());
    }

    /** Runs the demos and returns the process exit code: 0 when every call succeeded, 1 otherwise. */
    static int run() {
        LlmResult<JLlmClient> created = Llm4s.createDefaultClient();
        if (created.isFailure()) {
            // The error says what is missing, for example no default provider or no API key.
            System.err.println("Could not create a client: " + created.getError().getMessage());
            return 1;
        }

        // JLlmClient is AutoCloseable: it holds an HTTP client, so close it.
        try (JLlmClient client = created.get()) {
            boolean first = oneQuestion(client);
            boolean second = aConversation(client);
            boolean third = aStreamedAgentTurn(client);
            return first && second && third ? 0 : 1;
        } catch (Exception e) {
            System.err.println("Closing the client failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * One question, with the whole reply: {@code completion} returns a {@link JCompletion} - the text, the model that
     * answered and the tokens it used - where {@code complete} returns the text alone. A failure is read by its
     * {@code getKind()}, a Java enum.
     */
    private static boolean oneQuestion(JLlmClient client) {
        System.out.println("== One question");
        LlmResult<JCompletion> answer =
            client.completion("Explain the difference between a class and an object in 2 sentences.");

        // ifSuccess and ifFailure each take a lambda and return the result, so they chain.
        answer
            .ifSuccess(reply -> {
                System.out.println(reply.content());
                reply.usage().ifPresent(usage -> System.out.println("(" + reply.model() + ", "
                    + usage.promptTokens() + " + " + usage.completionTokens() + " tokens)"));
            })
            .ifFailure(error -> System.err.println("The call failed (" + error.getKind() + ", "
                + (error.isRecoverable() ? "worth retrying" : "not worth retrying") + "): " + error.getMessage()));
        return answer.isSuccess();
    }

    /**
     * A conversation with a system message, built with {@link ConversationBuilder}, sent with options built with
     * {@link JCompletionOptions}: a low temperature and a token limit.
     */
    private static boolean aConversation(JLlmClient client) {
        System.out.println("== A conversation");
        Conversation conversation = ConversationBuilder.create()
            .system("You answer in one short sentence.")
            .user("What is a monad?")
            .build();
        JCompletionOptions options = JCompletionOptions.builder().temperature(0.2).maxTokens(200).build();

        LlmResult<String> answer = client.complete(conversation, options);
        if (answer.isFailure()) {
            System.err.println("The call failed: " + answer.getError().getMessage());
            return false;
        }
        System.out.println(answer.get());
        return true;
    }

    /**
     * An agent turn as a stream: the listener (a lambda) receives each event of the turn as it happens, on the
     * stream's own thread - here the answer's text as it is written - and {@code await} returns the turn's
     * result once the listener has seen the last one.
     */
    private static boolean aStreamedAgentTurn(JLlmClient client) {
        System.out.println("== A streamed agent turn");
        // streaming = true: the agent's model calls stream, so the turn carries text deltas
        JAgent agent = Llm4s.createAgent(client, ToolRegistry.empty(), true);
        String threadId = UUID.randomUUID().toString();

        LlmResult<AgentStream> started = agent.stream(threadId, "Name three JVM languages, comma separated.", event -> {
            StreamEvents.decode(AgentEvents.TextDelta(), event).ifPresent(delta -> System.out.print(delta.text()));
            if (event instanceof StreamEvent.LiveGap) {
                System.out.print("[" + ((StreamEvent.LiveGap) event).dropped() + " live events dropped]");
            }
        });
        if (started.isFailure()) {
            System.err.println("The turn was refused: " + started.getError().getMessage());
            return false;
        }

        // stream.cancel() would cancel the turn instead
        LlmResult<JAgentResult> result = started.get().await();
        if (result.isFailure()) {
            System.err.println("The turn failed: " + result.getError().getMessage());
            return false;
        }
        System.out.println();
        JAgentStatus status = result.get().status();
        switch (status.kind()) {
            case COMPLETED -> System.out.println("(completed, " + result.get().messages().size() + " messages, "
                + result.get().usage().inputTokens() + " + " + result.get().usage().outputTokens() + " tokens)");
            case BLOCKED -> System.out.println("(blocked by " + status.guardrail().orElse("?") + ": "
                + status.reason().orElse("") + ")");
            case STEP_LIMIT_REACHED -> System.out.println("(reached the step limit)");
            case SUSPENDED -> System.out.println("(waiting for " + status.pending().size() + " answers)");
        }

        LlmResult<JAgentResult> answered = approveWhatItWaitsFor(agent, result);
        // the conversation stays in the agent's runtime until forgotten
        agent.forget(answered.isSuccess() ? answered.get() : result.get());
        return answered.isSuccess();
    }

    /**
     * A turn whose tools need approval, or ask a question, ends {@code SUSPENDED}: its status's
     * {@code pending()} lists what it waits for, as Strings and a Java enum, and {@code resume} answers it and continues, blocking like
     * {@code run}. This sample's agent has no tools, so its turns wait for nothing and the loop does not run; an
     * agent built with {@code Agent.builder}, approval middleware and tools, wrapped with {@code Llm4s.wrapAgent},
     * stops here until each call is answered. Approving every call is for the sample: a real caller would show
     * each one to a person, and {@code Answer.reject(id, reason)} or {@code Answer.edit(id, argumentsJson)} it.
     * Questions are reported and left pending: the turn is resumed with the approvals alone, and returned
     * {@code SUSPENDED} once only questions remain.
     */
    private static LlmResult<JAgentResult> approveWhatItWaitsFor(JAgent agent, LlmResult<JAgentResult> turn) {
        while (turn.isSuccess()) {
            List<Answer> answers = new ArrayList<>();
            for (PendingInterrupt pending : turn.get().status().pending()) {
                switch (pending.kind()) {
                    case APPROVAL -> {
                        System.out.println("Approving " + pending.toolName() + " " + pending.argumentsJson()
                            + ": " + pending.reason().orElse(""));
                        answers.add(Answer.approve(pending.id()));
                    }
                    // the answer is JSON of the asking tool's answer type, Answer.reply(id, json), which only the
                    // application knows; the sample leaves the question pending
                    case QUESTION -> System.out.println(
                        "Leaving pending: " + pending.toolName() + " asks " + pending.questionJson().orElse(""));
                }
            }
            if (answers.isEmpty()) {
                return turn; // nothing pending, or only questions
            }
            turn = agent.resume(turn.get().threadId(), answers);
        }
        // a turn that failed or was cancelled can be continued with agent.recover(threadId)
        System.err.println("The turn failed: " + turn.getError().getMessage());
        return turn;
    }
}
