package org.llm4s.javaapi.rag;

import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmResult;

/**
 * Builds a {@link JRag}: start with {@link JRag#builder()}, give it an embedding client - and a chat client, to
 * {@code ask} - and {@link #build()} it.
 *
 * <pre>{@code
 * JRag rag = JRag.builder()
 *     .embeddings(Llm4s.createDefaultEmbeddingClient().get())
 *     .llm(Llm4s.createDefaultClient().get())
 *     .store(JRag.inMemory())
 *     .chunking(800, 150)
 *     .build()
 *     .get();
 * }</pre>
 *
 * <p>{@code embeddings} is required; everything else has the default {@code llm4s-rag} gives it. Immutable: every
 * method returns a new builder, so one can be shared, and forked into several pipelines. A setter never throws: a
 * value that is missing or out of range makes {@link #build()} return a failed result of kind {@code VALIDATION} that
 * names the field.
 */
public final class JRagBuilder {

  static final JRagBuilder EMPTY =
      new JRagBuilder(null, null, JRagStore.inMemory(), null, null, null, null);

  private final JEmbeddingClient embeddings;
  private final JLlmClient llm;
  private final JRagStore store;
  private final Integer chunkSize;
  private final Integer chunkOverlap;
  private final Integer topK;
  private final String systemPrompt;

  private JRagBuilder(
      JEmbeddingClient embeddings,
      JLlmClient llm,
      JRagStore store,
      Integer chunkSize,
      Integer chunkOverlap,
      Integer topK,
      String systemPrompt) {
    this.embeddings = embeddings;
    this.llm = llm;
    this.store = store;
    this.chunkSize = chunkSize;
    this.chunkOverlap = chunkOverlap;
    this.topK = topK;
    this.systemPrompt = systemPrompt;
  }

  /**
   * The client that embeds the chunks ingested and the questions asked - required. Every chunk is embedded as a
   * document and every question as a query, with the client's model.
   */
  public JRagBuilder embeddings(JEmbeddingClient embeddings) {
    return new JRagBuilder(embeddings, llm, store, chunkSize, chunkOverlap, topK, systemPrompt);
  }

  /**
   * The chat client that answers a question from the chunks found for it; without one, {@code ask} is a failed result
   * of kind {@code CONFIGURATION} and only {@code search} works. {@code null} for none.
   */
  public JRagBuilder llm(JLlmClient llm) {
    return new JRagBuilder(embeddings, llm, store, chunkSize, chunkOverlap, topK, systemPrompt);
  }

  /** Where the chunks are kept: {@link JRag#inMemory()}, the default, or {@link JRag#sqlite(java.nio.file.Path)}. */
  public JRagBuilder store(JRagStore store) {
    return new JRagBuilder(embeddings, llm, store, chunkSize, chunkOverlap, topK, systemPrompt);
  }

  /**
   * How documents are split: chunks of about {@code size} characters, at least 1, each repeating the last
   * {@code overlap} characters of the one before, at least 0 and less than {@code size}. Chunks end at a sentence
   * where they can. The default is 800 and 150.
   */
  public JRagBuilder chunking(int size, int overlap) {
    return new JRagBuilder(embeddings, llm, store, size, overlap, topK, systemPrompt);
  }

  /** How many chunks a search returns, and a question is answered from, at least 1. The default is 5. */
  public JRagBuilder topK(int topK) {
    return new JRagBuilder(embeddings, llm, store, chunkSize, chunkOverlap, topK, systemPrompt);
  }

  /**
   * The instructions the model is given with the chunks, instead of the default, which tells it to answer from them
   * alone and to say when they are not enough. {@code null} for the default.
   */
  public JRagBuilder systemPrompt(String systemPrompt) {
    return new JRagBuilder(embeddings, llm, store, chunkSize, chunkOverlap, topK, systemPrompt);
  }

  /**
   * The pipeline, or why there is none: a missing embedding client or store, or a value out of range, is a failed
   * result of kind {@code VALIDATION} naming the field; a SQLite store that cannot be opened is a failed result too.
   * Sends no request and never throws.
   */
  public LlmResult<JRag> build() {
    return RagBuilds.build(embeddings, llm, store, chunkSize, chunkOverlap, topK, systemPrompt);
  }

  /** The fields set so far. */
  @Override
  public String toString() {
    return "JRagBuilder(embeddings="
        + embeddings
        + ", llm="
        + (llm == null ? "none" : "set")
        + ", store="
        + store
        + ", chunking="
        + (chunkSize == null ? "default" : chunkSize + "/" + chunkOverlap)
        + ", topK="
        + (topK == null ? "default" : topK)
        + ", systemPrompt="
        + (systemPrompt == null ? "default" : "set")
        + ")";
  }
}
