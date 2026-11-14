package org.llm4s.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Assertions on the exception a failed build or start reports. */
final class Failures {

    private Failures() {
    }

    /** True when {@code failure} or one of its causes has a message containing every one of {@code parts}. */
    static void assertMessageNames(Throwable failure, String... parts) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        for (String part : parts) {
            assertTrue(messages.toString().contains(part), "expected '" + part + "' in:\n" + messages);
        }
    }
}
