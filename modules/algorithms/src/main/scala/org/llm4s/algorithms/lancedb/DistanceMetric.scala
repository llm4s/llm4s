package org.llm4s.algorithms.lancedb

enum DistanceMetric:
  case L2, Cosine, Dot

object DistanceMetric:

  def compute(metric: DistanceMetric, a: Array[Float], b: Array[Float]): Float =
    require(a.length == b.length, s"Dimension mismatch: ${a.length} vs ${b.length}")
    metric match
      case DistanceMetric.L2     => l2Squared(a, b)
      case DistanceMetric.Cosine => cosineDistance(a, b)
      case DistanceMetric.Dot    => negativeDot(a, b)

  def l2Squared(a: Array[Float], b: Array[Float]): Float =
    var sum = 0.0f
    var i = 0
    while i < a.length do
      val d = a(i) - b(i)
      sum += d * d
      i += 1
    sum

  def cosineDistance(a: Array[Float], b: Array[Float]): Float =
    var dot = 0.0f
    var normA = 0.0f
    var normB = 0.0f
    var i = 0
    while i < a.length do
      dot += a(i) * b(i)
      normA += a(i) * a(i)
      normB += b(i) * b(i)
      i += 1
    val denom = math.sqrt(normA.toDouble * normB.toDouble).toFloat
    if denom == 0.0f then 1.0f else 1.0f - dot / denom

  def negativeDot(a: Array[Float], b: Array[Float]): Float =
    var dot = 0.0f
    var i = 0
    while i < a.length do
      dot += a(i) * b(i)
      i += 1
    -dot
