package org.llm4s.javaapi;

/**
 * What the texts passed to {@link JEmbeddingClient#embed(java.util.List, JEmbeddingPurpose)} are for: documents to be
 * indexed, or queries to be run against them.
 *
 * <p>Each constant is one of core's {@code InputPurpose} cases. Several embedding models embed the two differently,
 * and a query embedded as a document quietly retrieves worse, so say which side you are on: {@link #DOCUMENT} when
 * indexing, {@link #QUERY} when searching. Voyage and Cohere send it as {@code input_type} and Jina as {@code task};
 * a provider whose models embed both alike, such as OpenAI or Ollama, ignores it. {@code embed(List)} without a
 * purpose embeds documents, as core does.
 *
 * <p>A Java enum, not a Scala 3 one: Java reads a Scala 3 enum's cases through its companion object,
 * and a {@code switch} that runs before that object is initialised finds them {@code null}.
 */
public enum JEmbeddingPurpose {
  /** A text to be indexed and later retrieved: a chunk of a document, a record, a note. The default. */
  DOCUMENT,

  /** A search query, to be compared with texts embedded as {@link #DOCUMENT}. */
  QUERY
}
