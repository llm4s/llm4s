package org.llm4s.javaapi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Agents built from plain Java with {@code Llm4s.agentBuilder}, for {@code JAgentBuilderSpec} (#2476). */
final class AgentBuilderCheck {

    private AgentBuilderCheck() {}

    /** A tool that adds two numbers. */
    static JTool add() {
        return JTool.builder("add", "Adds two numbers")
            .param("a", long.class, "First")
            .param("b", long.class, "Second")
            .handler(args -> args.getLong("a") + args.getLong("b"))
            .build()
            .get();
    }

    /** Every option set, the output list mixing a guardrail of both sides with an output-only one. */
    static LlmResult<JAgent> everything(JLlmClient client) {
        return Llm4s.agentBuilder("support", client)
            .systemPrompt("You are a support assistant.")
            .tools(List.of(add()))
            .inputGuardrails(List.of(JGuardrails.length(1, 50)))
            .outputGuardrails(List.of(JGuardrails.length(1, 100), JGuardrails.json()))
            .contextWindow(JContextWindow.lastMessages(3))
            .streaming(true)
            .build();
    }

    /** An agent with only input guardrails. */
    static JAgent guardingInput(JLlmClient client, List<JGuardrail> input) {
        return Llm4s.agentBuilder("guarded", client).inputGuardrails(input).build().get();
    }

    /** An agent with only output guardrails. */
    static JAgent guardingOutput(JLlmClient client, List<? extends JOutputGuardrail> output) {
        return Llm4s.agentBuilder("guarded", client).outputGuardrails(output).build().get();
    }

    /** An agent whose model calls are sent the last {@code messages} messages, or the whole thread for 0. */
    static JAgent windowed(JLlmClient client, int messages) {
        JAgentBuilder builder = Llm4s.agentBuilder("windowed", client);
        return (messages == 0 ? builder : builder.contextWindow(JContextWindow.lastMessages(messages))).build().get();
    }

    /** A later setting replaces an earlier one: the second prompt, no tools, streaming off. */
    static LlmResult<JAgent> replaced(JLlmClient client) {
        return Llm4s.agentBuilder("replaced", client)
            .systemPrompt("first")
            .systemPrompt("second")
            .tools(List.of(add()))
            .tools(List.of())
            .streaming(true)
            .streaming(false)
            .build();
    }

    /** The error message of each builder that cannot build, in a fixed order; never an exception. */
    static List<String> refusals(JLlmClient client) {
        JTool tool = add();
        List<LlmResult<JAgent>> refused = List.of(
            Llm4s.agentBuilder(null, client).build(),
            Llm4s.agentBuilder("  ", client).build(),
            Llm4s.agentBuilder("a", null).build(),
            Llm4s.agentBuilder("a", client).systemPrompt(null).build(),
            Llm4s.agentBuilder("a", client).tools(null).build(),
            Llm4s.agentBuilder("a", client).tools(Arrays.asList(tool, null)).build(),
            Llm4s.agentBuilder("a", client).tools(List.of(tool, tool)).build(),
            Llm4s.agentBuilder("a", client).inputGuardrails(null).build(),
            Llm4s.agentBuilder("a", client).inputGuardrails(Arrays.asList((JGuardrail) null)).build(),
            Llm4s.agentBuilder("a", client).outputGuardrails(null).build(),
            Llm4s.agentBuilder("a", client).contextWindow(null).build(),
            Llm4s.agentBuilder("a", client).inputGuardrails(List.of(JGuardrails.length(-1, 5))).build(),
            Llm4s.agentBuilder("a", client).outputGuardrails(List.of(JGuardrails.length(5, 1))).build(),
            Llm4s.agentBuilder("a", client).inputGuardrails(List.of(JGuardrails.regex(null))).build(),
            Llm4s.agentBuilder("a", client).inputGuardrails(List.of(JGuardrails.regex(null, "m"))).build(),
            Llm4s.agentBuilder("a", client).inputGuardrails(List.of(JGuardrails.regex("a", null))).build(),
            Llm4s.agentBuilder("a", client).contextWindow(JContextWindow.lastMessages(0)).build()
        );
        List<String> messages = new ArrayList<>();
        for (LlmResult<JAgent> r : refused) {
            messages.add(r.isFailure() ? r.getError().getMessage() : "built");
        }
        return messages;
    }
}
