package org.llm4s.samples.rag;

import java.nio.file.Path;
import java.util.Map;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.javaapi.rag.JRag;
import org.llm4s.javaapi.rag.JRagAnswer;
import org.llm4s.javaapi.rag.JRagSource;

/**
 * RAG from Java with {@code llm4s-java-rag} (#1491): ingest documents, then ask a question that a model answers from
 * them, printing the answer and its sources. See {@code docs/guide/java-rag.md}.
 *
 * <p>The chat model is the default section of {@code application.conf} ({@code ollama-local} for the samples), and the
 * embedding model is {@code EMBEDDING_MODEL}, such as {@code ollama/nomic-embed-text} or
 * {@code openai/text-embedding-3-small}. The first argument, when given, is a file or directory to ingest, and the
 * second the question:
 *
 * <pre>
 * EMBEDDING_MODEL=ollama/nomic-embed-text sbt "samples/runMain org.llm4s.samples.rag.JavaRagExample docs 'What is LLM4S?'"
 * </pre>
 */
public final class JavaRagExample {

  private JavaRagExample() {}

  public static void main(String[] args) {
    LlmResult<JEmbeddingClient> embedder = Llm4s.createDefaultEmbeddingClient();
    LlmResult<JLlmClient> client = Llm4s.createDefaultClient();
    if (embedder.isFailure() || client.isFailure()) {
      embedder.ifFailure(e -> System.err.println("No embedding model: " + e.getMessage()));
      client.ifFailure(e -> System.err.println("No chat model: " + e.getMessage()));
      return;
    }

    String question = args.length > 1 ? args[1] : "How long do refunds take?";
    try (JRag rag = JRag.builder().embeddings(embedder.get()).llm(client.get()).build().get()) {
      if (args.length > 0) {
        rag.ingest(Path.of(args[0]))
            .ifSuccess(chunks -> System.out.println("Ingested " + args[0] + ": " + chunks + " chunks"))
            .ifFailure(e -> System.err.println("Could not ingest " + args[0] + ": " + e.getMessage()));
      } else {
        rag.ingestText("Refunds are paid within 14 days of the return arriving.", "refunds", Map.of("team", "billing"));
        rag.ingestText("Orders ship within two working days.", "shipping", Map.of("team", "warehouse"));
      }

      LlmResult<JRagAnswer> result = rag.ask(question);
      result.ifFailure(e -> System.err.println(e.getKind() + ": " + e.getMessage()));
      result.ifSuccess(answer -> {
        System.out.println("Q: " + answer.question());
        System.out.println("A: " + answer.answer());
        for (JRagSource source : answer.sources()) {
          System.out.printf("  %s (%.3f): %s%n", source.documentId(), source.score(), source.content());
        }
      });
    } finally {
      client.get().close();
    }
  }
}
