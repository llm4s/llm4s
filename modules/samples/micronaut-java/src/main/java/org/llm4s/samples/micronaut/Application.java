package org.llm4s.samples.micronaut;

import io.micronaut.runtime.Micronaut;

/** Starts the server on port 8080; {@code gradle run}. */
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
