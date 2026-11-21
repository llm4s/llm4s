package org.llm4s.javaapi.services;

import java.util.Objects;
import org.llm4s.annotation.Experimental;

/**
 * One exchange of an AI service conversation, as a {@link ChatMemoryStore} keeps it (#2277): the rendered user
 * message and the model's answer - the text, or the JSON of a structured answer. Tool calls made on the way are not
 * kept.
 *
 * @param userMessage the user message that was sent
 * @param answer the answer that came back
 */
@Experimental
public record ChatTurn(String userMessage, String answer) {

    /** @throws NullPointerException when either field is {@code null} */
    public ChatTurn {
        Objects.requireNonNull(userMessage, "userMessage must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
    }
}
