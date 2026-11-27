package org.llm4s.knowledgegraph.engine

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.knowledgegraph.storage.Direction

import scala.annotation.tailrec

/**
 * Engine for traversing and querying the Knowledge Graph.
 *
 * @example
 * {{{
 * val graph = Graph.empty
 *   .addNode(Node("a", "Person"))
 *   .addNode(Node("b", "Person"))
 *   .addNode(Node("c", "Person"))
 *   .addEdge(Edge("a", "b", "KNOWS"))
 *   .addEdge(Edge("b", "c", "KNOWS"))
 *
 * val engine = new GraphEngine(graph)
 * val reachable = engine.traverse("a", maxDepth = 2) // Set(a, b, c)
 * val path = engine.findShortestPath("a", "c") // Some(List(a->b, b->c))
 * }}}
 *
 * @param graph The graph to traverse
 */
class GraphEngine(graph: Graph) {

  // Built once, in one pass over the edges; an edge to a node outside the graph is left out
  private lazy val outgoing: Map[String, Vector[Edge]] =
    graph.edges.filter(e => graph.nodes.contains(e.source) && graph.nodes.contains(e.target)).toVector.groupBy(_.source)
  private lazy val incoming: Map[String, Vector[Edge]] =
    graph.edges.filter(e => graph.nodes.contains(e.source) && graph.nodes.contains(e.target)).toVector.groupBy(_.target)

  /** The edges leaving `id` in `direction`, each with the node it leads to. */
  private def steps(id: String, direction: Direction): Vector[(Edge, String)] = {
    lazy val out = outgoing.getOrElse(id, Vector.empty).map(e => e -> e.target)
    lazy val in  = incoming.getOrElse(id, Vector.empty).map(e => e -> e.source)
    direction match {
      case Direction.Outgoing => out
      case Direction.Incoming => in
      // A self-loop is both outgoing and incoming: once is enough
      case Direction.Both => out ++ in.filterNot { case (e, _) => e.source == e.target }
    }
  }

  /**
   * Traverses the graph starting from a given node using Breadth-First Search (BFS).
   *
   * @param startNodeId The ID of the starting node
   * @param maxDepth The maximum depth of traversal; negative gives nothing
   * @param filter A predicate to filter nodes and edges during traversal.
   *               Returns true if the traversal should continue through this edge to the target node.
   * @param direction Which edges to follow (default `Outgoing`)
   * @return A Set of visited Nodes
   */
  def traverse(
    startNodeId: String,
    maxDepth: Int,
    filter: (Node, Edge) => Boolean = (_, _) => true,
    direction: Direction = Direction.Outgoing
  ): Set[Node] =
    if (!graph.nodes.contains(startNodeId) || maxDepth < 0) Set.empty
    else {
      @tailrec
      def loop(frontier: Vector[String], depth: Int, visited: Set[String]): Set[String] =
        if (frontier.isEmpty || depth >= maxDepth) visited
        else {
          val next = frontier.flatMap { id =>
            steps(id, direction).collect { case (e, to) if !visited.contains(to) && filter(graph.nodes(to), e) => to }
          }.distinct
          loop(next, depth + 1, visited ++ next)
        }
      loop(Vector(startNodeId), 0, Set(startNodeId)).flatMap(graph.nodes.get)
    }

  /**
   * Traverses the graph using Depth-First Search (DFS). Returns the same nodes as [[traverse]]: a node first reached
   * by a long route is expanded again when a shorter route reaches it, so nothing within `maxDepth` is missed (#2974).
   *
   * @param startNodeId The ID of the starting node
   * @param maxDepth The maximum depth of traversal; negative gives nothing
   * @param filter A predicate to filter nodes and edges during traversal
   * @param direction Which edges to follow (default `Outgoing`)
   * @return A Set of visited Nodes
   */
  def traverseDFS(
    startNodeId: String,
    maxDepth: Int,
    filter: (Node, Edge) => Boolean = (_, _) => true,
    direction: Direction = Direction.Outgoing
  ): Set[Node] =
    if (!graph.nodes.contains(startNodeId) || maxDepth < 0) Set.empty
    else {
      @tailrec
      def dfs(stack: List[(String, Int)], best: Map[String, Int]): Map[String, Int] =
        stack match {
          case Nil => best
          case (id, depth) :: rest =>
            if (depth >= maxDepth) dfs(rest, best)
            else {
              val next = steps(id, direction).collect {
                case (e, to) if best.get(to).forall(_ > depth + 1) && filter(graph.nodes(to), e) => to
              }.distinct
              dfs(next.map(_ -> (depth + 1)).toList ++ rest, best ++ next.map(_ -> (depth + 1)))
            }
        }
      dfs(List(startNodeId -> 0), Map(startNodeId -> 0)).keySet.flatMap(graph.nodes.get)
    }

  /**
   * Finds the shortest path between two nodes using BFS, along outgoing edges.
   *
   * @param startNodeId The ID of the starting node
   * @param endNodeId The ID of the target node
   * @return Option containing the list of edges representing the path, or None if no path found
   */
  def findShortestPath(startNodeId: String, endNodeId: String): Option[List[Edge]] =
    if (startNodeId == endNodeId) { if (graph.nodes.contains(startNodeId)) Some(Nil) else None }
    else findShortestPaths(startNodeId, endNodeId, Int.MaxValue, Direction.Outgoing, maxPaths = 1).headOption

  /**
   * The shortest paths between two distinct nodes, by breadth-first search: every path of the shortest length up to
   * `maxLength` edges, at most `maxPaths` of them. With `Direction.Both` a path may walk an edge against its
   * direction (the edge is returned as stored). The same node, a missing node or no path within `maxLength` gives no
   * paths (#2974).
   */
  def findShortestPaths(
    startNodeId: String,
    endNodeId: String,
    maxLength: Int,
    direction: Direction = Direction.Both,
    maxPaths: Int = 10
  ): List[List[Edge]] =
    if (
      startNodeId == endNodeId || !graph.nodes.contains(startNodeId) || !graph.nodes.contains(endNodeId) ||
      maxLength < 1 || maxPaths < 1
    ) Nil
    else {
      // Level by level, recording for each node the edges that reach it by a shortest route
      @tailrec
      def bfs(
        frontier: Vector[String],
        depth: Int,
        dist: Map[String, Int],
        preds: Map[String, Vector[(Edge, String)]]
      ): Map[String, Vector[(Edge, String)]] =
        if (frontier.isEmpty || depth >= maxLength || dist.contains(endNodeId)) preds
        else {
          val reached = for {
            from    <- frontier
            (e, to) <- steps(from, direction)
            if !dist.get(to).exists(_ <= depth)
          } yield (to, (e, from))
          val newPreds = reached.groupMap(_._1)(_._2)
          val next     = newPreds.keys.toVector.sorted
          bfs(
            next,
            depth + 1,
            dist ++ next.map(_ -> (depth + 1)),
            preds ++ newPreds.map { case (k, v) => k -> v.distinct }
          )
        }
      val preds = bfs(Vector(startNodeId), 0, Map(startNodeId -> 0), Map.empty)
      if (!preds.contains(endNodeId)) Nil
      else {
        // Walk back from the end, depth first, stopping at maxPaths
        def back(node: String, suffix: List[Edge], budget: Int): List[List[Edge]] =
          if (node == startNodeId) List(suffix)
          else
            preds.getOrElse(node, Vector.empty).foldLeft(List.empty[List[Edge]]) { case (found, (e, from)) =>
              if (found.size >= budget) found
              else found ++ back(from, e :: suffix, budget - found.size)
            }
        back(endNodeId, Nil, maxPaths)
      }
    }

  /**
   * Finds all simple paths between two nodes up to a maximum length, along outgoing edges. Exponential on a dense
   * graph: prefer [[findShortestPaths]]. The same node gives no paths.
   *
   * @param startNodeId The ID of the starting node
   * @param endNodeId The ID of the target node
   * @param maxLength Maximum path length (number of edges)
   * @return List of all paths found
   */
  def findAllPaths(
    startNodeId: String,
    endNodeId: String,
    maxLength: Int
  ): List[List[Edge]] = {
    if (startNodeId == endNodeId || !graph.nodes.contains(startNodeId) || !graph.nodes.contains(endNodeId))
      return List.empty

    def pathsHelper(
      currentId: String,
      currentPath: List[Edge],
      visited: Set[String]
    ): List[List[Edge]] =
      if (currentId == endNodeId) {
        List(currentPath)
      } else if (currentPath.length >= maxLength) {
        List.empty
      } else {
        val newVisited = visited + currentId
        steps(currentId, Direction.Outgoing)
          .filterNot { case (_, to) => newVisited.contains(to) }
          .flatMap { case (edge, to) => pathsHelper(to, currentPath :+ edge, newVisited) }
          .toList
      }

    pathsHelper(startNodeId, List.empty, Set.empty)
  }

  /**
   * Gets all nodes within a certain distance.
   *
   * @param startNodeId The ID of the starting node
   * @param distance Exact distance (number of hops); negative gives nothing
   * @return Set of nodes at the specified distance
   */
  def getNodesAtDistance(startNodeId: String, distance: Int): Set[Node] =
    if (distance < 0) Set.empty
    else {
      val allNodes    = traverse(startNodeId, distance)
      val closerNodes = if (distance > 0) traverse(startNodeId, distance - 1) else Set.empty
      allNodes -- closerNodes
    }
}
