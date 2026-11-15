package org.llm4s.javaapi.services;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.llm4s.javaapi.FakeLlmClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmErrorKind;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.javaapi.Param;
import org.llm4s.javaapi.Tool;

/**
 * The Java blocks of {@code docs/guide/java-ai-services.md}, compiled by javac and run by {@code
 * JavaAiServicesGuideSpec} (#2277). Edit the guide and this file together.
 */
public final class AiServiceSnippets {

    private AiServiceSnippets() {}

    // ---- Declaring a service

    enum Sentiment { POSITIVE, NEGATIVE, NEUTRAL }

    record Ticket(String title, int priority) {}

    interface SupportAssistant {

        @SystemMessage("You are a support agent for {{product}}. Answer briefly.")
        String answer(@UserMessage String question, @V("product") String product);

        @UserMessage("Classify the sentiment of: {{text}}")
        Sentiment classify(@V("text") String text);

        LlmResult<Ticket> draftTicket(@UserMessage String complaint);
    }

    // ---- Building and calling it

    static String building(JLlmClient client) {
        SupportAssistant assistant = Llm4s.aiService(SupportAssistant.class)
            .client(client)
            .build();

        String answer = assistant.answer("How do I reset my password?", "Acme Cloud");
        Sentiment sentiment = assistant.classify("The new release is great");
        LlmResult<Ticket> ticket = assistant.draftTicket("I was charged twice this month");
        return answer + " | " + sentiment + " | " + ticket.get().title();
    }

    // ---- Failures

    static String failures(SupportAssistant assistant) {
        try {
            return assistant.answer("How do I reset my password?", "Acme Cloud");
        } catch (LlmException e) {
            return "failed: " + e.getKind();
        }
    }

    static String failuresAsResult(SupportAssistant assistant) {
        LlmResult<Ticket> ticket = assistant.draftTicket("I was charged twice this month");
        if (ticket.isFailure() && ticket.getError().getKind() == LlmErrorKind.RATE_LIMIT) {
            return "try again later";
        }
        return ticket.get().title();
    }

    // ---- Asynchronous and streamed answers

    interface Writer {

        @UserMessage("Write a haiku about {{topic}}")
        CompletableFuture<String> haiku(@V("topic") String topic);

        Flow.Publisher<String> story(@UserMessage String prompt);
    }

    static String async(Writer writer) {
        CompletableFuture<String> haiku = writer.haiku("autumn");
        return haiku.join();
    }

    // ---- Memory

    interface Concierge {

        @SystemMessage("You are a hotel concierge.")
        String chat(@MemoryId String guest, @UserMessage String message);
    }

    static String memory(JLlmClient client) {
        ChatMemoryStore store = ChatMemoryStore.inMemory(20);   // the newest 20 turns of each guest
        Concierge concierge = Llm4s.aiService(Concierge.class)
            .client(client)
            .memoryStore(store)
            .build();

        concierge.chat("room-101", "My name is Ada.");
        concierge.chat("room-202", "My name is Alan.");
        return concierge.chat("room-101", "What is my name?");   // sees only room-101's turns
    }

    // ---- Tools

    public static final class OrderTools {

        @Tool(description = "The shipping status of an order")
        public String orderStatus(@Param(name = "orderId", description = "The order id") String orderId) {
            return "Order " + orderId + " shipped yesterday";
        }
    }

    interface OrderAssistant {

        @SystemMessage("Answer questions about orders. Look them up with the tools.")
        String ask(@UserMessage String question);
    }

    static String tools(JLlmClient client) {
        OrderAssistant assistant = Llm4s.aiService(OrderAssistant.class)
            .client(client)
            .tools(new OrderTools())
            .build();
        return assistant.ask("Where is order 42?");
    }

    // ---- Mistakes fail at build()

    interface Mistaken {

        @UserMessage("Summarise {{document}}")
        String summarise(@V("doc") String document);   // {{document}} is named by no @V
    }

    static String mistakes(JLlmClient client) {
        try {
            Llm4s.aiService(Mistaken.class).client(client).build();
            return "built";
        } catch (LlmException e) {
            return e.getMessage();
        }
    }

    // ---- Testing a service

    static List<String> testing() {
        FakeLlmClient fake = FakeLlmClient.replying("{\"value\":\"POSITIVE\"}");
        SupportAssistant assistant = Llm4s.aiService(SupportAssistant.class)
            .client(fake.client())
            .build();

        Sentiment sentiment = assistant.classify("Love it");

        assert sentiment == Sentiment.POSITIVE;
        assert fake.lastPrompt().orElseThrow().equals("Classify the sentiment of: Love it");
        return List.of(sentiment.name(), fake.lastPrompt().orElseThrow());
    }
}
