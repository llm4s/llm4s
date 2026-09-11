package org.llm4s.algorithms.lancedb

enum MetadataValue:
  case StringVal(value: String)
  case IntVal(value: Int)
  case LongVal(value: Long)
  case FloatVal(value: Float)
  case DoubleVal(value: Double)
  case BoolVal(value: Boolean)

case class Schema(
    dimension: Int,
    metric: DistanceMetric = DistanceMetric.L2
)

case class VectorRecord(
    id: Long,
    vector: Array[Float],
    metadata: Map[String, MetadataValue] = Map.empty
):
  override def toString: String =
    s"VectorRecord(id=$id, dim=${vector.length}, metadata=$metadata)"

case class SearchResult(
    record: VectorRecord,
    distance: Float
):
  override def toString: String =
    s"SearchResult(id=${record.id}, distance=$distance, metadata=${record.metadata})"

type MetadataFilter = Map[String, MetadataValue] => Boolean

object MetadataFilter:
  def eq(field: String, value: MetadataValue): MetadataFilter =
    _.get(field).contains(value)

  def neq(field: String, value: MetadataValue): MetadataFilter =
    !_.get(field).contains(value)

  def in(field: String, values: Set[MetadataValue]): MetadataFilter =
    _.get(field).exists(values.contains)

  def and(filters: MetadataFilter*): MetadataFilter =
    m => filters.forall(_(m))

  def or(filters: MetadataFilter*): MetadataFilter =
    m => filters.exists(_(m))

  def not(f: MetadataFilter): MetadataFilter =
    m => !f(m)
