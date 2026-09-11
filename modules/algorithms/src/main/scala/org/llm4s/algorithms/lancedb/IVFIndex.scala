package org.llm4s.algorithms.lancedb

import scala.collection.mutable
import scala.util.Random

case class IVFConfig(
    nPartitions: Int = 256,
    nProbes: Int = 20,
    maxKMeansIterations: Int = 50
)

class IVFIndex(dimension: Int, metric: DistanceMetric, config: IVFConfig):

  private var centroids: Array[Array[Float]] = Array.empty
  private var partitions: Array[mutable.ArrayBuffer[Int]] = Array.empty
  private var _trained: Boolean = false

  def isTrained: Boolean = _trained

  def train(vectors: IndexedSeq[Array[Float]], rng: Random = new Random(42)): Unit =
    require(vectors.nonEmpty, "Cannot train on empty dataset")
    val k = math.min(config.nPartitions, vectors.size)
    centroids = kmeans(vectors, k, config.maxKMeansIterations, rng)
    partitions = Array.fill(centroids.length)(mutable.ArrayBuffer.empty)
    vectors.indices.foreach: i =>
      partitions(nearestCentroid(vectors(i))) += i
    _trained = true

  def search(
      query: Array[Float],
      vectors: IndexedSeq[Array[Float]],
      k: Int,
      nProbes: Int = config.nProbes
  ): IndexedSeq[(Int, Float)] =
    require(_trained, "Index not trained")
    val probes = math.min(nProbes, centroids.length)
    val centroidDists = centroids.indices
      .map(i => (i, DistanceMetric.compute(metric, query, centroids(i))))
      .sortBy(_._2)
      .take(probes)

    val candidates = mutable.ArrayBuffer.empty[(Int, Float)]
    centroidDists.foreach: (pIdx, _) =>
      partitions(pIdx).foreach: vecIdx =>
        candidates += ((vecIdx, DistanceMetric.compute(metric, query, vectors(vecIdx))))

    candidates.sortBy(_._2).take(k).toIndexedSeq

  def addVector(index: Int, vector: Array[Float]): Unit =
    require(_trained, "Index not trained")
    partitions(nearestCentroid(vector)) += index

  private def nearestCentroid(v: Array[Float]): Int =
    var best = 0
    var bestDist = Float.MaxValue
    var i = 0
    while i < centroids.length do
      val d = DistanceMetric.compute(metric, v, centroids(i))
      if d < bestDist then
        bestDist = d
        best = i
      i += 1
    best

  // ---- K-means with k-means++ initialization ----

  private def kmeans(
      vectors: IndexedSeq[Array[Float]],
      k: Int,
      maxIter: Int,
      rng: Random
  ): Array[Array[Float]] =
    import scala.util.boundary, boundary.break
    val centers = kmeansppInit(vectors, k, rng)
    val assignments = new Array[Int](vectors.size)

    boundary:
      for _ <- 0 until maxIter do
        var changed = false
        vectors.indices.foreach: i =>
          val c = nearest(vectors(i), centers)
          if assignments(i) != c then
            changed = true
            assignments(i) = c

        if !changed then break(centers)

        centers.indices.foreach: c =>
          val members = vectors.indices.filter(assignments(_) == c)
          if members.nonEmpty then
            val avg = new Array[Float](dimension)
            members.foreach: i =>
              var d = 0
              while d < dimension do
                avg(d) += vectors(i)(d)
                d += 1
            val n = members.size.toFloat
            var d = 0
            while d < dimension do
              avg(d) /= n
              d += 1
            centers(c) = avg

      centers

  private def nearest(v: Array[Float], centers: Array[Array[Float]]): Int =
    var best = 0
    var bestDist = Float.MaxValue
    var i = 0
    while i < centers.length do
      val d = DistanceMetric.l2Squared(v, centers(i))
      if d < bestDist then
        bestDist = d
        best = i
      i += 1
    best

  private def kmeansppInit(
      vectors: IndexedSeq[Array[Float]],
      k: Int,
      rng: Random
  ): Array[Array[Float]] =
    val chosen = mutable.ArrayBuffer(rng.nextInt(vectors.size))
    val minDist = Array.fill(vectors.size)(Float.MaxValue)

    while chosen.size < k do
      val last = vectors(chosen.last)
      var i = 0
      while i < vectors.size do
        val d = DistanceMetric.l2Squared(vectors(i), last)
        if d < minDist(i) then minDist(i) = d
        i += 1

      val total = minDist.foldLeft(0.0)(_ + _.toDouble)
      if total <= 0.0 then
        val remaining = vectors.indices.filterNot(chosen.toSet)
        chosen ++= remaining.take(k - chosen.size)
        return chosen.take(k).map(i => vectors(i).clone()).toArray

      val r = rng.nextDouble() * total
      var cum = 0.0
      var selected = 0
      var j = 0
      var found = false
      while j < vectors.size && !found do
        cum += minDist(j)
        if cum >= r then
          selected = j
          found = true
        j += 1

      chosen += selected

    chosen.map(i => vectors(i).clone()).toArray
