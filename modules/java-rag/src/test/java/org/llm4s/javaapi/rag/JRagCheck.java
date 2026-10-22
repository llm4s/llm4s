package org.llm4s.javaapi.rag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;

/**
 * {@link JRag} and its builder, answer and sources from real Java source, compiled by sbt with javac against the
 * module's public API and driven by {@code JRagSpec}. Nothing here names a Scala type.
 */
final class JRagCheck {

  private JRagCheck() {}

  /**
   * Ingests two documents, asks a question one of them answers and reads the answer back: its text, then each source
   * as {@code documentId|content}, best first, then the usage the model reported.
   */
  static List<String> ingestTwoAndAsk(JEmbeddingClient embeddings, JLlmClient llm, String question) {
    List<String> log = new ArrayList<>();
    try (JRag rag = JRag.builder().embeddings(embeddings).llm(llm).store(JRag.inMemory()).build().get()) {
      int cats = rag.ingestText("The cat sat on the mat.", "cats").get();
      int dogs = rag.ingestText("The dog chewed a bone.", "dogs", Map.of("topic", "dogs")).get();
      log.add("chunks:" + cats + "+" + dogs + " documents:" + rag.documentCount() + " total:" + rag.chunkCount());

      JRagAnswer answer = rag.ask(question).get();
      log.add("question:" + answer.question());
      log.add("answer:" + answer.answer());
      for (JRagSource source : answer.sources()) {
        log.add("source:" + source.documentId() + "|" + source.content());
      }
      answer.usage().ifPresent(u -> log.add("usage:" + u.promptTokens() + "/" + u.completionTokens()));
      log.add("top1:" + documentIds(rag.ask(question, 1).get().sources()));
    }
    return log;
  }

  /** {@code search}, with the builder's {@code topK} and with one given, as {@code documentId} lists. */
  static List<String> search(JEmbeddingClient embeddings, String query) {
    List<String> log = new ArrayList<>();
    try (JRag rag = JRag.builder().embeddings(embeddings).topK(2).build().get()) {
      rag.ingestText("The cat sat on the mat.", "cats").get();
      rag.ingestText("The dog chewed a bone.", "dogs").get();
      rag.ingestText("Scala runs on the JVM.", "scala").get();
      log.add("default:" + documentIds(rag.search(query).get()));
      log.add("top1:" + documentIds(rag.search(query, 1).get()));
    }
    return log;
  }

  /** Every file of a directory, ingested in one call, and the sources a question finds in them. */
  static List<String> ingestDirectory(JEmbeddingClient embeddings, JLlmClient llm, Path dir, String question) {
    List<String> log = new ArrayList<>();
    try (JRag rag = JRag.builder().embeddings(embeddings).llm(llm).chunking(200, 20).build().get()) {
      log.add("chunks:" + rag.ingest(dir, Map.of("team", "docs")).get());
      for (JRagSource source : rag.ask(question).get().sources()) {
        Map<String, String> metadata = source.metadata();
        log.add(source.documentId() + "|" + metadata.get("source") + "|" + metadata.get("team") + "|"
            + metadata.get("path").endsWith(source.documentId()));
      }
    }
    return log;
  }

  /** What a pipeline with nothing indexed answers, and finds. */
  static List<String> empty(JEmbeddingClient embeddings, JLlmClient llm) {
    List<String> log = new ArrayList<>();
    try (JRag rag = JRag.builder().embeddings(embeddings).llm(llm).build().get()) {
      JRagAnswer answer = rag.ask("Where did the cat sit?").get();
      log.add("answer:" + answer.answer());
      log.add("sources:" + answer.sources().size());
      log.add("search:" + rag.search("cat").get().size());
      log.add("documents:" + rag.documentCount() + " chunks:" + rag.chunkCount());
    }
    return log;
  }

  /** A pipeline in SQLite, closed and opened again over the same file: what was ingested is still found. */
  static List<String> sqlite(JEmbeddingClient embeddings, Path file) {
    List<String> log = new ArrayList<>();
    JRagStore store = JRag.sqlite(file);
    log.add("store:" + store.isInMemory() + ":" + store.file().map(Path::getFileName).orElseThrow());
    try (JRag first = JRag.builder().embeddings(embeddings).store(store).build().get()) {
      log.add("ingested:" + first.ingestText("The cat sat on the mat.", "cats").get());
    }
    try (JRag second = JRag.builder().embeddings(embeddings).store(store).build().get()) {
      log.add("found:" + documentIds(second.search("cat").get()));
    }
    return log;
  }

  /** Each call, read as a Java caller reads a failure: its kind and message, nothing thrown. */
  static List<String> failures(JRag rag) {
    List<String> log = new ArrayList<>();
    log.add("ingest:" + describe(rag.ingestText("The cat sat on the mat.", "cats")));
    log.add("ask:" + describe(rag.ask("Where did the cat sit?")));
    log.add("search:" + describe(rag.search("cat")));
    return log;
  }

  /** A {@code switch} on the failure's kind, as the guide shows it. */
  static String react(LlmResult<JRagAnswer> result) {
    if (result.isSuccess()) {
      return "answered";
    }
    LlmException error = result.getError();
    return switch (error.getKind()) {
      case AUTHENTICATION, CONFIGURATION -> "fix the setup: " + error.getMessage();
      case RATE_LIMIT -> "wait and retry";
      default -> "failed: " + error.getKind();
    };
  }

  /** Every argument a builder or a pipeline refuses, as {@code kind:message}. */
  static List<String> invalidArguments(JEmbeddingClient embeddings) {
    List<String> log = new ArrayList<>();
    JRagBuilder builder = JRag.builder().embeddings(embeddings);
    for (LlmResult<JRag> built : List.of(
        JRag.builder().build(),
        builder.embeddings(null).build(),
        builder.store(null).build(),
        builder.store(JRag.sqlite(null)).build(),
        builder.chunking(0, 0).build(),
        builder.chunking(100, 100).build(),
        builder.chunking(100, -1).build(),
        builder.topK(0).build())) {
      log.add("build " + describe(built));
    }

    Map<String, String> nullValue = new HashMap<>();
    nullValue.put("topic", null);
    Map<String, String> nullKey = new HashMap<>();
    nullKey.put(null, "x");
    try (JRag rag = builder.build().get()) {
      log.add("ingestText " + describe(rag.ingestText(null, "id")));
      log.add("ingestText " + describe(rag.ingestText("text", null)));
      log.add("ingestText " + describe(rag.ingestText("text", "  ")));
      log.add("ingestText " + describe(rag.ingestText("text", "id", null)));
      log.add("ingestText " + describe(rag.ingestText("text", "id", nullValue)));
      log.add("ingestText " + describe(rag.ingestText("text", "id", nullKey)));
      log.add("ingest " + describe(rag.ingest(null)));
      log.add("ingest " + describe(rag.ingest(Path.of("no", "such", "file.txt"))));
      log.add("ask " + describe(rag.ask(null)));
      log.add("ask " + describe(rag.ask(" ")));
      log.add("ask " + describe(rag.ask("question", 0)));
      log.add("search " + describe(rag.search(null)));
      log.add("search " + describe(rag.search("query", 0)));
      log.add("ask without llm " + describe(rag.ask("question")));
      log.add("documents:" + rag.documentCount());
    }
    return log;
  }

  private static List<String> documentIds(List<JRagSource> sources) {
    List<String> ids = new ArrayList<>();
    for (JRagSource source : sources) {
      ids.add(source.documentId());
    }
    return ids;
  }

  private static String describe(LlmResult<?> result) {
    if (result.isSuccess()) {
      return "ok:" + result.get();
    }
    LlmException error = result.getError();
    return error.getKind() + ":" + error.getMessage();
  }
}
