package org.llm4s.vectorstore.opensearch

import org.llm4s.vectorstore.MetadataFilter
import org.llm4s.vectorstore.elasticsearch.ElasticsearchQueries

/**
 * What [[OpenSearchVectorStore]] sends differently from Elasticsearch: the `knn_vector` mapping with `index.knn`
 * set, and the `knn` query. Documents, metadata filters and every other request are
 * [[org.llm4s.vectorstore.elasticsearch.ElasticsearchQueries]]'.
 */
private[opensearch] object OpenSearchQueries {

  import ElasticsearchQueries.{ EmbeddingField, filterQuery, mappings, vector }

  /** The body of `PUT /<index>`: k-NN enabled, and an HNSW `knn_vector` field with the configured space and engine. */
  def indexDefinition(dimensions: Int, config: OpenSearchConfig): ujson.Obj = {
    val method = ujson.Obj(
      "name"       -> "hnsw",
      "space_type" -> config.spaceType.wireName,
      "engine"     -> config.engine.wireName
    )
    val parameters = ujson.Obj()
    config.m.foreach(m => parameters("m") = m)
    config.efConstruction.foreach(ef => parameters("ef_construction") = ef)
    if (parameters.value.nonEmpty) method("parameters") = parameters
    ujson.Obj(
      "settings" -> ujson.Obj("index" -> ujson.Obj("knn" -> true)),
      "mappings" -> mappings(ujson.Obj("type" -> "knn_vector", "dimension" -> dimensions, "method" -> method))
    )
  }

  /**
   * The `_search` body of a kNN search for `topK` hits, asking each shard for `k` neighbours.
   *
   * With `filterInsideKnn` the filter goes inside the `knn` query (efficient filtering); otherwise the `knn` query is
   * a `bool` query's `must` clause and the filter its `filter` clause, which drops non-matching neighbours afterwards.
   */
  def knnSearch(
    queryVector: Array[Float],
    topK: Int,
    k: Int,
    filter: Option[MetadataFilter],
    filterInsideKnn: Boolean
  ): ujson.Obj = {
    val field = ujson.Obj("vector" -> vector(queryVector), "k" -> k)
    def knn   = ujson.Obj("knn" -> ujson.Obj(EmbeddingField -> field))
    val query = filter match {
      case None => knn
      case Some(f) if filterInsideKnn =>
        field("filter") = filterQuery(f)
        knn
      case Some(f) =>
        ujson.Obj("bool" -> ujson.Obj("must" -> ujson.Arr(knn), "filter" -> ujson.Arr(filterQuery(f))))
    }
    ujson.Obj("size" -> topK, "query" -> query)
  }
}
