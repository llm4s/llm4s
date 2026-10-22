package org.llm4s.javaapi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/**
 * {@link JEmbeddingClient}, {@link JEmbeddings} and {@link JEmbeddingPurpose} from real Java source, compiled by sbt
 * against the module's public API and driven by {@code JEmbeddingClientSpec}. Nothing here names a Scala type.
 */
final class EmbeddingCheck {

  private EmbeddingCheck() {}

  /** Both overloads of {@code embed}, and what a result reads back, with Java types only. */
  static List<String> embedAndRead(JEmbeddingClient client) {
    List<String> log = new ArrayList<>();
    String configured = client.model();
    OptionalInt configuredDimensions = client.dimensions();
    log.add("client:" + configured + ":" + configuredDimensions.getAsInt());

    LlmResult<JEmbeddings> documents = client.embed(List.of("first", "second"));
    LlmResult<JEmbeddings> queries = client.embed(List.of("question"), JEmbeddingPurpose.QUERY);

    JEmbeddings embeddings = documents.get();
    String model = embeddings.model();
    int dimensions = embeddings.dimensions();
    List<float[]> vectors = embeddings.vectors();
    log.add("model:" + model);
    log.add("dimensions:" + dimensions);
    for (float[] vector : vectors) {
      log.add("vector:" + Arrays.toString(vector));
    }
    log.add("queries:" + queries.get().vectors().size());
    double similarity = JEmbeddings.cosineSimilarity(vectors.get(0), vectors.get(1));
    log.add("similarity:" + similarity);
    return log;
  }

  /** A {@code switch} expression with no {@code default} covers every purpose, or this does not compile. */
  static String describe(JEmbeddingPurpose purpose) {
    return switch (purpose) {
      case DOCUMENT -> "document";
      case QUERY -> "query";
    };
  }

  /** Every {@code null} argument, read back as a failure's kind and message: nothing throws. */
  static List<String> nulls(JEmbeddingClient client) {
    List<String> texts = new ArrayList<>();
    texts.add("fine");
    texts.add(null);
    List<String> log = new ArrayList<>();
    for (LlmResult<JEmbeddings> result : List.of(
        client.embed(null),
        client.embed(null, JEmbeddingPurpose.QUERY),
        client.embed(List.of("fine"), null),
        client.embed(texts))) {
      LlmException e = result.getError();
      log.add(e.getKind() + ":" + e.getMessage());
    }
    return log;
  }

  /**
   * {@code Llm4s.createEmbeddingClient} for each model named, as a Java caller reads the outcome: the model and its
   * declared dimensions, or "undeclared" and the length of the vectors a call returns; or the failure's kind (#1781).
   */
  static List<String> createFor(List<String> models) {
    List<String> log = new ArrayList<>();
    for (String model : models) {
      LlmResult<JEmbeddingClient> created = Llm4s.createEmbeddingClient(model);
      if (created.isFailure()) {
        log.add(model + " -> " + created.getError().getKind());
        continue;
      }
      JEmbeddingClient client = created.get();
      OptionalInt declared = client.dimensions();
      if (declared.isPresent()) {
        log.add(model + " -> " + client.model() + ", " + declared.getAsInt() + " dimensions");
      } else {
        int made = client.embed(List.of("a text")).get().dimensions();
        log.add(model + " -> " + client.model() + ", undeclared, made " + made);
      }
    }
    return log;
  }

  /** What {@code cosineSimilarity} throws, caught as a Java caller would. */
  static List<String> cosineMisuse() {
    List<String> log = new ArrayList<>();
    try {
      JEmbeddings.cosineSimilarity(null, new float[] {1f});
    } catch (NullPointerException e) {
      log.add("npe:" + e.getMessage());
    }
    try {
      JEmbeddings.cosineSimilarity(new float[] {1f, 2f}, new float[] {1f});
    } catch (IllegalArgumentException e) {
      log.add("iae:" + e.getMessage());
    }
    log.add("zero:" + JEmbeddings.cosineSimilarity(new float[] {0f, 0f}, new float[] {1f, 2f}));
    return log;
  }
}
