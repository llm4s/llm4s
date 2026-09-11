package org.llm4s.algorithms.lancedb

import scala.collection.mutable

class VectorTable(val name: String, val schema: Schema):

  private val records = mutable.ArrayBuffer.empty[VectorRecord]
  private val vectors = mutable.ArrayBuffer.empty[Array[Float]]
  private var nextId: Long = 0L
  private var index: Option[IVFIndex] = None

  def size: Int = records.size

  def add(data: Seq[(Array[Float], Map[String, MetadataValue])]): Seq[Long] =
    data.map: (vector, metadata) =>
      require(
        vector.length == schema.dimension,
        s"Expected dimension ${schema.dimension}, got ${vector.length}"
      )
      val id = nextId
      nextId += 1
      records += VectorRecord(id, vector, metadata)
      vectors += vector
      index.foreach(_.addVector(records.size - 1, vector))
      id

  def addVectors(vecs: Seq[Array[Float]]): Seq[Long] =
    add(vecs.map(v => (v, Map.empty[String, MetadataValue])))

  def get(id: Long): Option[VectorRecord] =
    records.find(_.id == id)

  def delete(ids: Set[Long]): Int =
    val before = records.size
    val keep = records.indices.filterNot(i => ids.contains(records(i).id))
    val keptRecords = keep.map(records(_))
    val keptVectors = keep.map(vectors(_))
    records.clear()
    vectors.clear()
    records ++= keptRecords
    vectors ++= keptVectors
    index = None
    before - records.size

  def createIndex(config: IVFConfig = IVFConfig()): Unit =
    require(records.nonEmpty, "Cannot index empty table")
    val ivf = new IVFIndex(schema.dimension, schema.metric, config)
    ivf.train(vectors.toIndexedSeq)
    index = Some(ivf)

  def hasIndex: Boolean = index.isDefined

  def search(query: Array[Float]): SearchQuery =
    require(
      query.length == schema.dimension,
      s"Query dimension ${query.length} != schema dimension ${schema.dimension}"
    )
    new SearchQuery(this, query, schema.metric)

  private[lancedb] def executeSearch(
      query: Array[Float],
      k: Int,
      metric: DistanceMetric,
      nProbes: Int,
      filter: Option[MetadataFilter]
  ): Seq[SearchResult] =
    if records.isEmpty then return Seq.empty

    val ranked: Seq[(Int, Float)] = index match
      case Some(ivf) =>
        val overFetch = if filter.isDefined then k * 4 else k
        ivf.search(query, vectors.toIndexedSeq, overFetch, nProbes)
      case None =>
        records.indices
          .map(i => (i, DistanceMetric.compute(metric, query, vectors(i))))
          .sortBy(_._2)

    val filtered = filter match
      case Some(f) => ranked.filter((idx, _) => f(records(idx).metadata))
      case None    => ranked

    filtered.take(k).map((idx, dist) => SearchResult(records(idx), dist))
