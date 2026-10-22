package org.llm4s.samples.quarkus;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;

/** {@code POST /chat} with a question as plain text answers with the model's reply as plain text. */
@Path("/chat")
public class ChatResource {

    @Inject
    JLlmClient client;

    // JLlmClient.complete blocks until the model answers, so it must not run on the Vert.x event loop: @Blocking puts
    // the call on a worker thread (@RunOnVirtualThread would put it on a virtual thread instead).
    @POST
    @Blocking
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public Response chat(String question) {
        if (question == null || question.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Send a question as the request body").build();
        }
        LlmResult<String> answer = client.complete(ConversationBuilder.create()
            .system("You are a concise assistant.")
            .user(question)
            .build());
        if (answer.isSuccess()) {
            return Response.ok(answer.get()).build();
        }
        LlmException error = answer.getError();
        return Response.status(statusOf(error)).entity(error.getKind() + ": " + error.getMessage()).build();
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
