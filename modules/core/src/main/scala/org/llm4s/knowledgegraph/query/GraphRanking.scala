package org.llm4s.knowledgegraph.query

import org.llm4s.knowledgegraph.Graph

import scala.annotation.tailrec

/**
 * Graph ranking algorithms for entity importance scoring.
 *
 * Provides PageRank, betweenness centrality, closeness centrality, and degree centrality
 * implementations that operate on the immutable [[Graph]] data structure.
 *
 * These scores are used by [[GraphQAPipeline]] to prioritize which entities
 * to include in LLM context when the result set exceeds context limits.
 */
object GraphRanking {

  /**
   * Computes PageRank scores for all nodes in the graph.
   *
   * PageRank measures the importance of a node based on the link structure.
   * Nodes with many incoming edges from other important nodes get higher scores.
   *
   * @param graph The graph to analyze
   * @param dampingFactor The probability of following a link (typically 0.85)
   * @param maxIterations Maximum number of iterations before convergence
   * @param tolerance Convergence threshold — stops when max score change < tolerance
   * @return Map of node ID to PageRank score (scores sum to ~1.0)
   */
  def pageRank(
    graph: Graph,
    dampingFactor: Double = 0.85,
    maxIterations: Int = 100,
    tolerance: Double = 1e-6
  ): Map[String, Double] = {
    val nodeIds  = graph.nodes.keys.toSeq
    val numNodes = nodeIds.size

    if (numNodes == 0) return Map.empty

    val initialScore = 1.0 / numNodes
    val baseScore    = (1.0 - dampingFactor) / numNodes

    // Precompute outgoing edges for each node
    val outgoing: Map[String, Seq[String]] = nodeIds.map(id => id -> graph.getOutgoingEdges(id).map(_.target)).toMap

    // Precompute incoming edges for each node
    val incoming: Map[String, Seq[String]] = nodeIds.map(id => id -> graph.getIncomingEdges(id).map(_.source)).toMap

    // Identify dangling nodes (no outgoing edges) whose rank must be redistributed
    val danglingNodes = nodeIds.filter(id => outgoing.getOrElse(id, Seq.empty).isEmpty)

    @tailrec
    def iterate(scores: Map[String, Double], iteration: Int): Map[String, Double] =
      if (iteration >= maxIterations) scores
      else {
        // Dangling node contribution: redistribute their rank evenly to all nodes
        val danglingSum          = danglingNodes.map(id => scores.getOrElse(id, 0.0)).sum
        val danglingContribution = dampingFactor * danglingSum / numNodes

        val newScores = nodeIds.map { nodeId =>
          val incomingScore = incoming
            .getOrElse(nodeId, Seq.empty)
            .map { sourceId =>
              val sourceOutDegree = outgoing.getOrElse(sourceId, Seq.empty).size
              if (sourceOutDegree > 0) scores.getOrElse(sourceId, 0.0) / sourceOutDegree
              else 0.0
            }
            .sum

          nodeId -> (baseScore + danglingContribution + dampingFactor * incomingScore)
        }.toMap

        // Check convergence
        val maxDiff = nodeIds.map(id => math.abs(newScores.getOrElse(id, 0.0) - scores.getOrElse(id, 0.0))).max

        if (maxDiff < tolerance) newScores
        else iterate(newScores, iteration + 1)
      }

    val initialScores = nodeIds.map(_ -> initialScore).toMap
    iterate(initialScores, 0)
  }

  /**
   * Computes degree centrality for all nodes in the graph.
   *
   * Degree centrality is the simplest centrality measure, equal to the number
   * of edges connected to a node normalized by the maximum possible degree.
   *
   * @param graph The graph to analyze
   * @return Map of node ID to degree centrality score (0.0 to 1.0)
   */
  def degreeCentrality(graph: Graph): Map[String, Double] = {
    val numNodes = graph.nodes.size
    if (numNodes <= 1) return graph.nodes.keys.map(_ -> 0.0).toMap

    val maxDegree = numNodes - 1

    graph.nodes.keys.map { nodeId =>
      val degree = graph.getConnectedEdges(nodeId).size
      nodeId -> degree.toDouble / maxDegree
    }.toMap
  }

  /**
   * Computes betweenness centrality for all nodes in the graph.
   *
   * Betweenness centrality measures the fraction of shortest paths between
   * all pairs of nodes that pass through a given node. High betweenness
   * indicates a node is a bridge or broker in the network.
   *
   * Uses Brandes' algorithm for efficient computation.
   *
   * @param graph The graph to analyze
   * @return Map of node ID to betweenness centrality score (normalized by (n-1)(n-2)/2)
   */
  def betweennessCentrality(graph: Graph): Map[String, Double] = {
    val nodeIds  = graph.nodes.keys.toSeq
    val numNodes = nodeIds.size

    if (numNodes <= 2) return nodeIds.map(_ -> 0.0).toMap

    // Initialize betweenness scores
    var betweenness = nodeIds.map(_ -> 0.0).toMap

    // Precompute adjacency (treating as undirected for centrality)
    val adjacency: Map[String, Seq[String]] = nodeIds.map { id =>
      val outgoing = graph.getOutgoingEdges(id).map(_.target)
      val incoming = graph.getIncomingEdges(id).map(_.source)
      id -> (outgoing ++ incoming).distinct
    }.toMap

    // Brandes' algorithm: BFS from each source node
    for (source <- nodeIds) {
      // BFS
      var stack = List.empty[String]
      var pred  = nodeIds.map(_ -> List.empty[String]).toMap
      var sigma = nodeIds.map(_ -> 0.0).toMap.updated(source, 1.0)
      var dist  = nodeIds.map(_ -> -1).toMap.updated(source, 0)
      var queue = scala.collection.immutable.Queue(source)

      while (queue.nonEmpty) {
        val (v, newQueue) = queue.dequeue
        queue = newQueue
        stack = v :: stack

        for (w <- adjacency.getOrElse(v, Seq.empty)) {
          // First visit?
          if (dist(w) < 0) {
            dist = dist.updated(w, dist(v) + 1)
            queue = queue.enqueue(w)
          }

          // Shortest path via v?
          if (dist(w) == dist(v) + 1) {
            sigma = sigma.updated(w, sigma(w) + sigma(v))
            pred = pred.updated(w, v :: pred(w))
          }
        }
      }

      // Accumulate dependencies
      var delta = nodeIds.map(_ -> 0.0).toMap

      for (w <- stack if w != source) {
        for (v <- pred(w)) {
          val contribution = (sigma(v) / sigma(w)) * (1.0 + delta(w))
          delta = delta.updated(v, delta(v) + contribution)
        }
        betweenness = betweenness.updated(w, betweenness(w) + delta(w))
      }
    }

    // Normalize for undirected graphs by (n-1)(n-2)/2 so scores lie in [0, 1]
    val normalizationFactor =
      if (numNodes > 2) ((numNodes - 1.0) * (numNodes - 2.0)) / 2.0 else 1.0
    betweenness.map { case (id, score) => id -> score / normalizationFactor }
  }

  /**
   * Computes closeness centrality for all nodes in the graph.
   *
   * Closeness centrality measures the reciprocal of the average shortest path
   * distance from a node to all other reachable nodes. Nodes that are "close"
   * to all others have high closeness centrality.
   *
   * @param graph The graph to analyze
   * @return Map of node ID to closeness centrality score (0.0 to 1.0)
   */
  def closenessCentrality(graph: Graph): Map[String, Double] = {
    val nodeIds  = graph.nodes.keys.toSeq
    val numNodes = nodeIds.size

    if (numNodes <= 1) return nodeIds.map(_ -> 0.0).toMap

    // Precompute adjacency (treating as undirected)
    val adjacency: Map[String, Seq[String]] = nodeIds.map { id =>
      val outgoing = graph.getOutgoingEdges(id).map(_.target)
      val incoming = graph.getIncomingEdges(id).map(_.source)
      id -> (outgoing ++ incoming).distinct
    }.toMap

    nodeIds.map { source =>
      val distances = bfsDistances(source, adjacency)
      val reachable = distances.values.filter(_ > 0)

      val score = if (reachable.nonEmpty) {
        val totalDistance  = reachable.sum.toDouble
        val reachableCount = reachable.size.toDouble
        // Wasserman-Faust normalized closeness
        (reachableCount / (numNodes - 1.0)) * (reachableCount / totalDistance)
      } else {
        0.0
      }

      source -> score
    }.toMap
  }

  /**
   * BFS to compute distances from a source node to all reachable nodes.
   */
  private def bfsDistances(
    source: String,
    adjacency: Map[String, Seq[String]]
  ): Map[String, Int] = {
    @tailrec
    def bfs(
      queue: scala.collection.immutable.Queue[(String, Int)],
      visited: Map[String, Int]
    ): Map[String, Int] =
      if (queue.isEmpty) visited
      else {
        val ((current, dist), newQueue) = queue.dequeue
        if (visited.contains(current)) {
          bfs(newQueue, visited)
        } else {
          val newVisited = visited + (current -> dist)
          val neighbors = adjacency
            .getOrElse(current, Seq.empty)
            .filterNot(newVisited.contains)
          val newEntries = neighbors.map(n => (n, dist + 1))
          bfs(newQueue.enqueueAll(newEntries), newVisited)
        }
      }

    bfs(scala.collection.immutable.Queue((source, 0)), Map.empty)
  }
}
