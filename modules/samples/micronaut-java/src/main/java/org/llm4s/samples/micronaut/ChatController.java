package org.llm4s.samples.micronaut;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;

/** {@code POST /chat} answers a plain-text question with the model's reply; {@code /chat/stream} streams it. */
@Controller("/chat")
public class ChatController {

    private final JLlmClient client;

    public ChatController(JLlmClient client) {
        this.client = client;
    }

    // JLlmClient.complete blocks until the model answers, so it must not run on a Netty event loop: @ExecuteOn moves
    // the call to the blocking executor (a virtual thread per request on JDK 21).
    @Post(consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_PLAIN)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<String> chat(@Body String question) {
        if (question == null || question.isBlank()) {
            return HttpResponse.badRequest("Send a question as the request body");
        }
        LlmResult<String> answer = client.complete(ConversationBuilder.create()
            .system("You are a concise assistant.")
            .user(question)
            .build());
        if (answer.isSuccess()) {
            return HttpResponse.ok(answer.get());
        }
        LlmException error = answer.getError();
        return HttpResponse.<String>status(HttpStatus.valueOf(statusOf(error)))
            .body(error.getKind() + ": " + error.getMessage());
    }

    // The reply as server-sent events, one per piece of text. streamAsPublisher is a JDK Flow.Publisher that runs the
    // call on a thread of its own, so nothing blocks the event loop; FlowAdapters turns it into the Reactive Streams
    // Publisher Micronaut writes out, and each piece becomes an Event (a bare String would be written unframed).
    @Post(value = "/stream", consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> stream(@Body String question) {
        Publisher<String> pieces = FlowAdapters.toPublisher(client.streamAsPublisher(ConversationBuilder.create()
            .system("You are a concise assistant.")
            .user(question)
            .build()));
        return Publishers.map(pieces, Event::of);
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
