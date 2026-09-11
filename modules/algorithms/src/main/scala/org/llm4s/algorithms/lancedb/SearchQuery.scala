package org.llm4s.algorithms.lancedb

class SearchQuery private[lancedb] (
    private[lancedb] val table: VectorTable,
    private[lancedb] val query: Array[Float],
    private val distanceMetric: DistanceMetric,
    private val k: Int = 10,
    private val probes: Int = 20,
    private val filter: Option[MetadataFilter] = None
):
  def limit(n: Int): SearchQuery =
    new SearchQuery(table, query, distanceMetric, n, probes, filter)

  def metric(m: DistanceMetric): SearchQuery =
    new SearchQuery(table, query, m, k, probes, filter)

  def nProbes(n: Int): SearchQuery =
    new SearchQuery(table, query, distanceMetric, k, n, filter)

  def where(f: MetadataFilter): SearchQuery =
    new SearchQuery(table, query, distanceMetric, k, probes, Some(f))

  def execute(): Seq[SearchResult] =
    table.executeSearch(query, k, distanceMetric, probes, filter)
