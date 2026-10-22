package org.llm4s.vectorstore.elasticsearch

import org.llm4s.vectorstore.MetadataFilter

/**
 * The Elasticsearch documents and query DSL [[ElasticsearchVectorStore]] sends.
 *
 * A record is one document whose `_id` is the record's ID:
 *
 * {{{
 * { "id": "doc-1", "embedding": [0.1, 0.2], "content": "text", "metadata": { "lang": "en" }, "seq": 1730000000000000 }
 * }}}
 *
 * `id` repeats `_id` as a `keyword`, because a prefix query on `_id` is not allowed; `seq` is a per-write sequence
 * that `list` sorts on, so records come back in insertion order. Every metadata value is mapped as a `keyword`
 * (a dynamic template under `metadata`), which is what makes exact, set, existence and substring matches possible.
 *
 * OpenSearch shares all of this but the vector field and the kNN query; those are in
 * [[org.llm4s.vectorstore.opensearch.OpenSearchQueries]].
 */
private[vectorstore] object ElasticsearchQueries {

  val IdField        = "id"
  val EmbeddingField = "embedding"
  val ContentField   = "content"
  val MetadataField  = "metadata"
  val SeqField       = "seq"

  /** The body of `PUT /<index>`: the mapping, with the vector sized and the similarity set. */
  def indexDefinition(dimensions: Int, similarity: ElasticsearchSimilarity): ujson.Obj =
    ujson.Obj(
      "mappings" -> mappings(
        ujson.Obj(
          "type"       -> "dense_vector",
          "dims"       -> dimensions,
          "index"      -> true,
          "similarity" -> similarity.wireName
        )
      )
    )

  /** The index mapping around `vectorField`, the mapping of the `embedding` field. */
  def mappings(vectorField: ujson.Obj): ujson.Obj =
    ujson.Obj(
      "dynamic_templates" -> ujson.Arr(
        ujson.Obj(
          "llm4s_metadata_keywords" -> ujson.Obj(
            "path_match"         -> s"$MetadataField.*",
            "match_mapping_type" -> "string",
            "mapping"            -> ujson.Obj("type" -> "keyword")
          )
        )
      ),
      "properties" -> ujson.Obj(
        IdField        -> ujson.Obj("type" -> "keyword"),
        EmbeddingField -> vectorField,
        ContentField   -> ujson.Obj("type" -> "text"),
        MetadataField  -> ujson.Obj("type" -> "object", "dynamic" -> true),
        SeqField       -> ujson.Obj("type" -> "long")
      )
    )

  /** A vector as a JSON array. */
  def vector(values: Array[Float]): ujson.Arr = ujson.Arr.from(values.iterator.map(f => ujson.Num(f.toDouble)))

  /** The source document of a record. */
  def document(
    id: String,
    embedding: Array[Float],
    content: Option[String],
    metadata: Map[String, String],
    seq: Long
  ): ujson.Obj = {
    val doc = ujson.Obj(
      IdField        -> id,
      EmbeddingField -> vector(embedding),
      MetadataField  -> ujson.Obj.from(metadata.map { case (k, v) => k -> ujson.Str(v) }),
      SeqField       -> ujson.Num(seq.toDouble)
    )
    content.foreach(c => doc(ContentField) = c)
    doc
  }

  /** A query that matches every document. */
  val MatchAll: ujson.Obj = ujson.Obj("match_all" -> ujson.Obj())

  /** A query that matches no document. */
  val MatchNone: ujson.Obj = ujson.Obj("match_none" -> ujson.Obj())

  /** The query DSL clause for `filter`. */
  def filterQuery(filter: MetadataFilter): ujson.Obj = filter match {
    case MetadataFilter.All =>
      MatchAll

    case MetadataFilter.Equals(key, value) =>
      ujson.Obj("term" -> ujson.Obj(metadataField(key) -> value))

    case MetadataFilter.Contains(key, substring) =>
      ujson.Obj(
        "wildcard" -> ujson.Obj(metadataField(key) -> ujson.Obj("value" -> s"*${escapeWildcard(substring)}*"))
      )

    case MetadataFilter.HasKey(key) =>
      ujson.Obj("exists" -> ujson.Obj("field" -> metadataField(key)))

    case MetadataFilter.In(_, values) if values.isEmpty =>
      MatchNone

    case MetadataFilter.In(key, values) =>
      ujson.Obj("terms" -> ujson.Obj(metadataField(key) -> ujson.Arr.from(values.toSeq.sorted.map(ujson.Str(_)))))

    case MetadataFilter.And(left, right) =>
      ujson.Obj("bool" -> ujson.Obj("filter" -> ujson.Arr(filterQuery(left), filterQuery(right))))

    case MetadataFilter.Or(left, right) =>
      ujson.Obj(
        "bool" -> ujson.Obj(
          "should"               -> ujson.Arr(filterQuery(left), filterQuery(right)),
          "minimum_should_match" -> 1
        )
      )

    case MetadataFilter.Not(inner) =>
      ujson.Obj("bool" -> ujson.Obj("must_not" -> ujson.Arr(filterQuery(inner))))
  }

  /** The query for an optional filter: every document when there is none. */
  def query(filter: Option[MetadataFilter]): ujson.Obj = filter.fold(MatchAll)(filterQuery)

  /** The query matching records whose ID starts with `prefix`. */
  def idPrefixQuery(prefix: String): ujson.Obj =
    ujson.Obj("prefix" -> ujson.Obj(IdField -> ujson.Obj("value" -> prefix)))

  /** The `_search` body of a kNN search. */
  def knnSearch(queryVector: Array[Float], topK: Int, numCandidates: Int, filter: Option[MetadataFilter]): ujson.Obj = {
    val knn = ujson.Obj(
      "field"          -> EmbeddingField,
      "query_vector"   -> vector(queryVector),
      "k"              -> topK,
      "num_candidates" -> numCandidates
    )
    filter.foreach(f => knn("filter") = filterQuery(f))
    ujson.Obj("knn" -> knn, "size" -> topK)
  }

  /** The `_search` body of one page of records in insertion order. */
  def listPage(limit: Int, offset: Int, filter: Option[MetadataFilter]): ujson.Obj =
    ujson.Obj(
      "query" -> query(filter),
      "from"  -> offset,
      "size"  -> limit,
      "sort"  -> ujson.Arr(ujson.Obj(SeqField -> "asc"), ujson.Obj(IdField -> "asc"))
    )

  private def metadataField(key: String): String = s"$MetadataField.$key"

  /** Escapes the wildcard query's special characters, so a substring matches literally. */
  private def escapeWildcard(s: String): String =
    s.flatMap {
      case c @ ('\\' | '*' | '?') => s"\\$c"
      case c                      => c.toString
    }
}
