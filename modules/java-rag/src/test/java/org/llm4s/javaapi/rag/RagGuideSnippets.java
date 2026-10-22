package org.llm4s.javaapi.rag;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;

/**
 * The Java blocks of {@code docs/guide/java-rag.md}, compiled by sbt with javac against the module's public API and run
 * by {@code JavaRagGuideSpec}, which also checks that every block of the guide is here.
 */
final class RagGuideSnippets {

  private RagGuideSnippets() {}

  /** "Build a pipeline": the clients {@code application.conf} configures. */
  static JRag fromConfiguration() {
    JRag rag = JRag.builder()
        .embeddings(Llm4s.createDefaultEmbeddingClient().get())
        .llm(Llm4s.createDefaultClient().get())
        .build()
        .get();
    return rag;
  }

  /** "Ingest documents". */
  static int ingest(JRag rag, Path docs) {
    int chunks = rag.ingest(docs).get();
    chunks += rag.ingestText("Refunds are paid within 14 days.", "refund-policy", Map.of("team", "billing")).get();
    return chunks;
  }

  /** "Ask a question". */
  static void ask(JRag rag) {
    JRagAnswer answer = rag.ask("How long do refunds take?").get();
    System.out.println(answer.answer());
    for (JRagSource source : answer.sources()) {
      System.out.println("  " + source.documentId() + " (" + source.score() + "): " + source.content());
    }
  }

  /** "Search without a model". */
  static List<JRagSource> search(JRag rag) {
    List<JRagSource> sources = rag.search("refunds", 3).get();
    return sources;
  }

  /** "Choose a store". */
  static JRag sqlite(JEmbeddingClient embedder, JLlmClient client, Path dir) {
    JRag rag = JRag.builder()
        .embeddings(embedder)
        .llm(client)
        .store(JRag.sqlite(dir.resolve("docs.db")))   // and docs-fts.db beside it
        .chunking(800, 150)
        .topK(5)
        .build()
        .get();
    return rag;
  }

  /** "Close it". */
  static void closing(JEmbeddingClient embedder, JLlmClient client, Path docs) {
    try (JRag rag = JRag.builder().embeddings(embedder).llm(client).build().get()) {
      rag.ingest(docs).get();
      System.out.println(rag.ask("How long do refunds take?").get().answer());
    }
  }

  /** "Handling a failure". */
  static void handle(JRag rag) {
    LlmResult<JRagAnswer> result = rag.ask("How long do refunds take?");
    if (result.isFailure()) {
      LlmException error = result.getError();
      switch (error.getKind()) {
        case VALIDATION -> System.err.println("Fix the call: " + error.getMessage());
        case AUTHENTICATION, CONFIGURATION -> System.err.println("Fix the setup: " + error.getMessage());
        case RATE_LIMIT -> System.err.println("Try again later");
        default -> System.err.println("Failed: " + error.getMessage());
      }
    }
  }
}
