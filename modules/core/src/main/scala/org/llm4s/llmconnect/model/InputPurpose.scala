package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

/**
 * What a text being embedded is for: a document to be indexed, or a query to be run against
 * documents that were indexed.
 *
 * Several embedding models are trained to embed the two differently, and a mismatch between how
 * texts were indexed and how a query is embedded quietly degrades retrieval. Jina calls the
 * distinction `task`, Cohere and Voyage call it `input_type`. A caller always knows which side
 * it is on - indexing or querying - so the request says so, through
 * [[EmbeddingRequest.purpose]], and each provider maps it onto its own parameter.
 *
 * A provider whose models embed both alike (OpenAI, Ollama) ignores it. A provider author does
 * not need to handle it unless the vendor's API has an equivalent; see the provider guide.
 *
 * `Document` is the default of every [[EmbeddingRequest]], so code that does not say which side
 * it is on keeps embedding documents, as it always did.
 */
@Stable
enum InputPurpose:
  /** A text to be indexed and later retrieved: a chunk of a document, a memory, a record. */
  case Document

  /** A search query to be run against texts that were indexed as [[Document]]. */
  case Query
