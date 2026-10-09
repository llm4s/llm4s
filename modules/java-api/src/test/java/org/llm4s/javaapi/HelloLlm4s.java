// The first-call example of docs/guide/java.md, compiled and run by JavaGuideSpec. From the first import to the
// closing brace it is the guide's snippet word for word (the spec checks that), so change the guide and this file
// together.
package org.llm4s.javaapi;

import java.io.PrintStream;

import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;

public final class HelloLlm4s {

    private HelloLlm4s() {}

    public static void main(String[] args) {
        System.exit(run(System.out, System.err));
    }

    static int run(PrintStream out, PrintStream err) {
        LlmResult<JLlmClient> created = Llm4s.createDefaultClient();
        if (created.isFailure()) {
            err.println("Could not create a client: " + created.getError().getMessage());
            return 1;
        }

        // JLlmClient is AutoCloseable: it holds an HTTP client, so close it.
        try (JLlmClient client = created.get()) {
            LlmResult<String> answer = client.complete("What is a monad? Answer in one sentence.");
            answer
                .ifSuccess(text -> out.println(text))
                .ifFailure(error -> err.println("The call failed: " + error.getMessage()));
            return answer.isSuccess() ? 0 : 1;
        }
    }
}
