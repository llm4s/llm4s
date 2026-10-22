///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS org.llm4s:llm4s-java-api_3:0.5.1
//DEPS ch.qos.logback:logback-classic:1.5.34
//FILES application.conf
//FILES logback.xml

import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;

/**
 * A first call to a model from one Java file, with no build tool: {@code jbang HelloLlm4s.java}.
 *
 * <p>The provider is the local Ollama that {@code application.conf} names (run {@code ollama serve} and
 * {@code ollama pull llama3} first). A failure, such as no server listening, is printed and the exit status is 1.
 */
public class HelloLlm4s {

    public static void main(String[] args) {
        LlmResult<JLlmClient> created = Llm4s.createDefaultClient();
        if (created.isFailure()) {
            System.err.println("Could not create a client: " + created.getError().getMessage());
            System.exit(1);
        }

        // JLlmClient is AutoCloseable: it holds an HTTP client, so close it.
        try (JLlmClient client = created.get()) {
            LlmResult<String> answer = client.complete("In one sentence, what is Scala?");
            if (answer.isFailure()) {
                System.err.println("The call failed: " + answer.getError().getMessage());
                System.exit(1);
            }
            System.out.println(answer.get());
        } catch (Exception e) {
            System.err.println("Closing the client failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
