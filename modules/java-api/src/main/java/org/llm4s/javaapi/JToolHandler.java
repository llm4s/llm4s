package org.llm4s.javaapi;

/**
 * The body of a tool built with {@link JTool#builder(String, String)}: it reads its arguments from {@link JToolArgs}
 * and returns the result the model sees (#1484). A {@code String} is passed as it is; a number, a boolean, an enum
 * (its name), a {@code java.util.Map} or a {@code java.util.Collection} of these become JSON; anything else is passed as
 * its {@code toString()}. A handler that throws does not fail the agent: the model receives a tool error with the
 * exception's message and may try again.
 */
@FunctionalInterface
public interface JToolHandler {

    /** Runs the tool with the model's arguments. */
    Object call(JToolArgs args) throws Exception;
}
