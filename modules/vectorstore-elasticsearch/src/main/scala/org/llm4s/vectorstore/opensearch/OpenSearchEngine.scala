package org.llm4s.vectorstore.opensearch

/**
 * The k-NN library behind a `knn_vector` field's HNSW graph, fixed when the index is created.
 *
 * @param wireName           the value of the mapping's `method.engine` parameter
 * @param filtersInsideKnn   whether the engine supports efficient filtering - the metadata filter inside the `knn`
 *                           query, applied while the graph is searched, so `topK` results come back when that many
 *                           match. Without it the filter is applied to the kNN hits afterwards (a post-filter), which
 *                           can return fewer than `topK`.
 */
enum OpenSearchEngine(val wireName: String, val filtersInsideKnn: Boolean) {

  /** Apache Lucene's HNSW, the default: efficient filtering since OpenSearch 2.4. */
  case Lucene extends OpenSearchEngine("lucene", filtersInsideKnn = true)

  /**
   * Faiss's HNSW, OpenSearch's own default engine: efficient filtering since OpenSearch 2.9, `cosinesimil` since 2.19
   * (Faiss normalises the vectors it indexes to unit length).
   */
  case Faiss extends OpenSearchEngine("faiss", filtersInsideKnn = true)

  /**
   * NMSLIB's HNSW: no efficient filtering, so filters are post-filters. OpenSearch deprecates it in favour of Lucene
   * and Faiss, and a newer cluster may refuse to create an NMSLIB index; use it only for an index that already has it.
   */
  case Nmslib extends OpenSearchEngine("nmslib", filtersInsideKnn = false)
}

object OpenSearchEngine {

  /** The engine whose mapping name is `name`, ignoring case. */
  def fromString(name: String): Option[OpenSearchEngine] =
    values.find(_.wireName.equalsIgnoreCase(name.trim))
}
