package org.llm4s.javaapi.services;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.llm4s.javaapi.JCompletion;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.javaapi.Param;
import org.llm4s.javaapi.Tool;

/** The interfaces {@code AiServiceSpec} builds services of (#2277). */
public final class AiServiceFixtures {

    private AiServiceFixtures() {}

    public enum Sentiment {
        POSITIVE,
        NEGATIVE,
        NEUTRAL
    }

    public record Ticket(String title, int priority) {}

    /** A record no JSON Schema here can describe. */
    public record Unschematic(Map<String, String> fields) {}

    /** Every return type, with the interface's system message and methods' own. */
    @SystemMessage("You are a support agent for {{product}}. Answer briefly.")
    public interface Support {

        String answer(@UserMessage String question, @V("product") String product);

        @SystemMessage("You classify sentiment.")
        @UserMessage("Classify the sentiment of: {{text}}")
        Sentiment classify(@V("text") String text);

        @SystemMessage("You draft tickets.")
        LlmResult<Ticket> draftTicket(@UserMessage String complaint);

        @SystemMessage("You draft tickets.")
        Ticket ticket(@UserMessage String complaint);

        @SystemMessage("You answer.")
        LlmResult<String> tryAnswer(@UserMessage String question);

        @SystemMessage("You answer.")
        JCompletion completion(@UserMessage String question);

        @SystemMessage("You answer.")
        CompletableFuture<String> answerLater(@UserMessage String question);

        @SystemMessage("You answer.")
        CompletableFuture<Sentiment> classifyLater(@UserMessage String text);

        @SystemMessage("You answer.")
        Flow.Publisher<String> stream(@UserMessage String question);

        @SystemMessage("Write a {{style}} greeting.")
        @UserMessage("Greet {{name}}, who is {{age}} years old, in {{style}} style.")
        String greet(@V("name") String name, @V("age") int age, @V("style") String style);

        default String shout(String question) {
            return answer(question, "everything").toUpperCase();
        }

        static String version() {
            return "1";
        }
    }

    /** No system message anywhere; one conversation per id. */
    public interface Chat {

        String chat(@MemoryId String user, @UserMessage String message);

        String once(@UserMessage String message);
    }

    /** What a service with tools answers. */
    public interface Agentic {

        String ask(@UserMessage String question);

        @SystemMessage("Look orders up with the tools.")
        Ticket ticket(@UserMessage String question);

        @SystemMessage("Look orders up with the tools.")
        LlmResult<Sentiment> mood(@UserMessage String question);

        String chat(@MemoryId int conversation, @UserMessage String message);
    }

    public static final class OrderTools {

        @Tool(description = "The status of an order")
        public String orderStatus(@Param(name = "id", description = "The order id") String id) {
            return "order " + id + " shipped";
        }
    }

    public static final class NoTools {

        public String notATool() {
            return "";
        }
    }

    public static final class OtherOrderTools {

        @Tool(name = "orderStatus", description = "Another tool of the same name")
        public String status(@Param(name = "id", description = "The order id") String id) {
            return id;
        }
    }

    /** Every mistake build() reports, one per method. */
    public interface Broken {

        int count(@UserMessage String question);

        String unannotated(String question);

        String twoRoles(@UserMessage @V("a") String question);

        @UserMessage("Tell me about {{missing}}")
        String unknownPlaceholder();

        @SystemMessage("You know {{secret}}.")
        String unknownInSystem(@UserMessage String question);

        @UserMessage("  ")
        String blankTemplate();

        @UserMessage("Hello {{a}}")
        String bothUserMessages(@UserMessage String question, @V("a") String a);

        String twoUserParameters(@UserMessage String first, @UserMessage String second);

        @UserMessage("{{a}}")
        String duplicateVariable(@V("a") String first, @V("a") String second);

        String twoMemoryIds(@MemoryId String first, @MemoryId String second, @UserMessage String question);

        LlmResult<Integer> resultOfInteger(@UserMessage String question);

        Flow.Publisher<Integer> publisherOfInteger(@UserMessage String question);

        List<String> list(@UserMessage String question);

        Unschematic unschematic(@UserMessage String question);

        Flow.Publisher<String> streamWithMemory(@MemoryId String id, @UserMessage String question);
    }

    /** Fine without tools; refused with them. */
    public interface ToolConflicts {

        Flow.Publisher<String> stream(@UserMessage String question);

        JCompletion completion(@UserMessage String question);
    }

    /** Has no method of its own. */
    public interface Empty {}

    /** A class, not an interface. */
    public static final class NotAnInterface {}
}
