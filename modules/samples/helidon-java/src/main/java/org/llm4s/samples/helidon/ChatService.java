package org.llm4s.samples.helidon;

import io.helidon.http.Status;
import io.helidon.http.sse.SseEvent;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.sse.SseSink;
import org.llm4s.javaapi.CompletionStream;
import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;

/**
 * {@code POST /chat} answers a plain-text question with the model's reply; {@code POST /chat/stream} streams it as
 * server-sent events. The service owns the client and closes it when the server stops.
 */
public class ChatService implements HttpService {

    private final JLlmClient client;

    public ChatService(JLlmClient client) {
        this.client = client;
    }

    @Override
    public void routing(HttpRules rules) {
        rules.post("/", this::chat)
            .post("/stream", this::stream);
    }

    @Override
    public void afterStop() {
        client.close();
    }

    // Helidon SE 4 runs each request on its own virtual thread, so the handler may block on the model: no executor,
    // no reactive type. JLlmClient.complete does not pin the carrier thread while it waits.
    private void chat(ServerRequest req, ServerResponse res) {
        String question = req.content().as(String.class);
        if (question.isBlank()) {
            res.status(Status.BAD_REQUEST_400).send("Send a question as the request body");
            return;
        }
        LlmResult<String> answer = client.complete(conversation(question).build());
        if (answer.isSuccess()) {
            res.send(answer.get());
            return;
        }
        LlmException error = answer.getError();
        res.status(Status.create(statusOf(error))).send(error.getKind() + ": " + error.getMessage());
    }

    // Each piece of the reply is one event. The listener runs on the stream's own thread; this request's virtual
    // thread waits for the whole reply before the sink is closed and the response ends.
    private void stream(ServerRequest req, ServerResponse res) {
        String question = req.content().as(String.class);
        try (SseSink sink = res.sink(SseSink.TYPE)) {
            LlmResult<CompletionStream> stream =
                client.stream(conversation(question).build(), piece -> sink.emit(SseEvent.create(piece)));
            if (stream.isSuccess()) {
                stream.get().await();
            }
        }
    }

    private static ConversationBuilder conversation(String question) {
        return ConversationBuilder.create()
            .system("You are a concise assistant.")
            .user(question);
    }

    /** A failure is the model's, not the caller's: report it as a gateway error, keeping a rate limit visible. */
    private static int statusOf(LlmException error) {
        return switch (error.getKind()) {
            case RATE_LIMIT -> 429;
            case TIMEOUT -> 504;
            default -> 502;
        };
    }
}
