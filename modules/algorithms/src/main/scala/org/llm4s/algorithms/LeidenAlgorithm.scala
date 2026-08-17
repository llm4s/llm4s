package org.llm4s.algorithms

import scala.collection.mutable
import scala.util.Random

object LeidenAlgorithm:

  // ---------------------------------------------------------
  // 1. Graph Data Structure
  // ---------------------------------------------------------
  class Graph(val numNodes: Int):
    val adjList: mutable.Map[Int, mutable.Map[Int, Double]] =
      mutable.Map.from((0 until numNodes).map(_ -> mutable.Map.empty[Int, Double]))
    var totalWeight: Double = 0.0

    def addUndirectedEdge(u: Int, v: Int, weight: Double): Unit =
      adjList(u)(v) = adjList(u).getOrElse(v, 0.0) + weight
      adjList(v)(u) = adjList(v).getOrElse(u, 0.0) + weight
      totalWeight += weight

    def getDegree(node: Int): Double =
      adjList(node).values.sum

  // ---------------------------------------------------------
  // 2. Main Leiden Execution Loop
  // ---------------------------------------------------------
  def runLeiden(graph: Graph, maxIterations: Int): Map[Int, Int] =
    import scala.util.boundary, boundary.break
    var currentGraph                           = graph
    var nodeToCommunity: mutable.Map[Int, Int] = mutable.Map.from((0 until graph.numNodes).map(i => i -> i))
    // Maps original node -> current macro-node ID
    var originalToMacro: Map[Int, Int] = (0 until graph.numNodes).map(i => i -> i).toMap

    boundary:
      for _ <- 0 until maxIterations do
        localMove(currentGraph, nodeToCommunity)

        val refinedPartition = refinePartition(currentGraph, nodeToCommunity)

        if refinedPartition.values.toSet.size == currentGraph.numNodes then
          break(originalToMacro.map { case (orig, macroId) => orig -> nodeToCommunity(macroId) })

        val subCommToNewId = refinedPartition.values.toSet.zipWithIndex.toMap
        // Update original -> macro mapping through this aggregation
        originalToMacro = originalToMacro.map { case (orig, macroId) =>
          orig -> subCommToNewId(refinedPartition(macroId))
        }

        currentGraph = aggregateGraph(currentGraph, refinedPartition, subCommToNewId)
        nodeToCommunity = mutable.Map.from(currentGraph.adjList.keys.map(n => n -> n))

      originalToMacro.map { case (orig, macroId) => orig -> nodeToCommunity(macroId) }

  // ---------------------------------------------------------
  // Step 1: Local Move Phase
  // ---------------------------------------------------------
  private def localMove(g: Graph, partition: mutable.Map[Int, Int], maxPasses: Int = 10): Unit =
    val m        = g.totalWeight
    var pass     = 0
    var improved = true

    while improved && pass < maxPasses do
      improved = false
      pass += 1
      for node <- Random.shuffle(g.adjList.keys.toList) do
        val currentComm   = partition(node)
        val neighborComms = g.adjList(node).keys.map(partition(_)).toSet

        var bestComm = currentComm
        var bestGain = 0.0

        for targetComm <- neighborComms if targetComm != currentComm do
          val gain = computeModularityGain(g, node, targetComm, partition, m)
          if gain > bestGain then
            bestGain = gain
            bestComm = targetComm

        if bestComm != currentComm then
          partition(node) = bestComm
          improved = true

  private def computeModularityGain(
    g: Graph,
    node: Int,
    targetComm: Int,
    partition: mutable.Map[Int, Int],
    m: Double
  ): Double =
    val currentComm = partition(node)
    val k_i         = g.getDegree(node)

    val k_in_new = g.adjList(node).collect { case (nb, w) if partition(nb) == targetComm => w }.sum
    val sum_tot_new = g.adjList.keys
      .filter(n => n != node && partition(n) == targetComm)
      .map(g.getDegree)
      .sum

    val k_in_old = g.adjList(node).collect { case (nb, w) if nb != node && partition(nb) == currentComm => w }.sum
    val sum_tot_old = g.adjList.keys
      .filter(n => n != node && partition(n) == currentComm)
      .map(g.getDegree)
      .sum

    (k_in_new / m - k_i * sum_tot_new / (2.0 * m * m)) -
      (k_in_old / m - k_i * sum_tot_old / (2.0 * m * m))

  // ---------------------------------------------------------
  // Step 2: Refinement Phase (DSU-based connectivity)
  // ---------------------------------------------------------
  private def refinePartition(g: Graph, partition: mutable.Map[Int, Int]): Map[Int, Int] =
    val parent = mutable.Map.from(g.adjList.keys.map(n => n -> n))

    for
      node     <- g.adjList.keys
      neighbor <- g.adjList(node).keys
      if partition(neighbor) == partition(node)
    do union(parent, node, neighbor)

    g.adjList.keys.map(n => n -> find(parent, n)).toMap

  private def find(parent: mutable.Map[Int, Int], i: Int): Int =
    if parent(i) == i then i
    else
      val root = find(parent, parent(i))
      parent(i) = root
      root

  private def union(parent: mutable.Map[Int, Int], i: Int, j: Int): Unit =
    val (ri, rj) = (find(parent, i), find(parent, j))
    if ri != rj then parent(ri) = rj

  // ---------------------------------------------------------
  // Step 3: Aggregation Phase
  // ---------------------------------------------------------
  private def aggregateGraph(g: Graph, refinedPartition: Map[Int, Int], subCommToNewId: Map[Int, Int]): Graph =
    val macroGraph = Graph(subCommToNewId.size)
    for
      u           <- g.adjList.keys
      (v, weight) <- g.adjList(u)
      if u <= v
    do
      val macroU = subCommToNewId(refinedPartition(u))
      val macroV = subCommToNewId(refinedPartition(v))
      macroGraph.addUndirectedEdge(macroU, macroV, weight)

    macroGraph

  // ---------------------------------------------------------
  // 3. Example Driver (two tightly connected clusters)
  // ---------------------------------------------------------
  @main def leidenExample(): Unit =
    val g = Graph(10)

    // Cluster 1 (Nodes 0-4)
    g.addUndirectedEdge(0, 1, 1.0)
    g.addUndirectedEdge(0, 2, 1.0)
    g.addUndirectedEdge(1, 2, 1.0)
    g.addUndirectedEdge(2, 3, 1.0)
    g.addUndirectedEdge(3, 4, 1.0)

    // Bridge between clusters
    g.addUndirectedEdge(4, 5, 0.5)

    // Cluster 2 (Nodes 5-9)
    g.addUndirectedEdge(5, 6, 1.0)
    g.addUndirectedEdge(6, 7, 1.0)
    g.addUndirectedEdge(7, 8, 1.0)
    g.addUndirectedEdge(8, 9, 1.0)
    g.addUndirectedEdge(5, 9, 1.0)

    println("Running Leiden Algorithm on custom graph...")
    val partition = runLeiden(g, 5)

    println("\nFinal Community Assignments:")
    partition.toSeq.sortBy(_._1).foreach((node, comm) => println(s"Node $node -> Community $comm"))
