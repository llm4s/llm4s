package org.llm4s.knowledgegraph.engine

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }

import scala.annotation.tailrec

/**
 * Engine for traversing and querying the Knowledge Graph.
 *
 * @param graph The graph to traverse
 */
class GraphEngine(graph: Graph) {

  /**
   * Traverses the graph starting from a given node using Breadth-First Search (BFS).
   *
   * @param startNodeId The ID of the starting node
   * @param maxDepth The maximum depth of traversal
   * @param filter A predicate to filter nodes and edges during traversal.
   *               Returns true if the traversal should continue through this edge to the target node.
   * @return A Set of visited Nodes
   */
  def traverse(
    startNodeId: String,
    maxDepth: Int,
    filter: (Node, Edge) => Boolean = (_, _) => true
  ): Set[Node] = {
    if (!graph.nodes.contains(startNodeId)) return Set.empty

    var visited = Set.empty[String]
    var queue   = scala.collection.immutable.Queue((startNodeId, 0)) // (nodeId, depth)
    var result  = Set.empty[Node]

    while (queue.nonEmpty) {
      val ((currentNodeId, depth), newQueue) = queue.dequeue
      queue = newQueue

      if (!visited.contains(currentNodeId)) {
        graph.nodes.get(currentNodeId) match {
          case Some(currentNode) =>
            visited += currentNodeId
            result += currentNode

            if (depth < maxDepth) {
              val neighbors = graph.edges
                .filter(_.source == currentNodeId)
                .flatMap { edge =>
                  val targetNode = graph.nodes.get(edge.target)
                  targetNode match {
                    case Some(node) if !visited.contains(node.id) && filter(node, edge) =>
                      Some((node.id, depth + 1))
                    case _ => None
                  }
                }
              queue = queue.enqueueAll(neighbors)
            }
          case None =>
            // Skip node if it doesn't exist (dangling edge reference)
            visited += currentNodeId
        }
      }
    }

    result
  }

  /**
   * Traverses the graph using Depth-First Search (DFS).
   *
   * @param startNodeId The ID of the starting node
   * @param maxDepth The maximum depth of traversal
   * @param filter A predicate to filter nodes and edges during traversal
   * @return A Set of visited Nodes
   */
  def traverseDFS(
    startNodeId: String,
    maxDepth: Int,
    filter: (Node, Edge) => Boolean = (_, _) => true
  ): Set[Node] = {
    if (!graph.nodes.contains(startNodeId)) return Set.empty

    @tailrec
    def dfsHelper(stack: List[(String, Int)], visited: Set[String], result: Set[Node]): Set[Node] =
      if (stack.isEmpty) result
      else {
        val (currentNodeId, depth) = stack.head
        val newStack               = stack.tail

        if (visited.contains(currentNodeId)) {
          dfsHelper(newStack, visited, result)
        } else {
          graph.nodes.get(currentNodeId) match {
            case Some(currentNode) =>
              val newVisited = visited + currentNodeId
              val newResult  = result + currentNode

              if (depth < maxDepth) {
                val neighbors = graph.edges
                  .filter(_.source == currentNodeId)
                  .flatMap { edge =>
                    graph.nodes.get(edge.target).flatMap { node =>
                      if (!newVisited.contains(node.id) && filter(node, edge)) {
                        Some((node.id, depth + 1))
                      } else None
                    }
                  }
                dfsHelper(neighbors.toList ++ newStack, newVisited, newResult)
              } else {
                dfsHelper(newStack, newVisited, newResult)
              }
            case None =>
              // Skip node if it doesn't exist (dangling edge reference)
              dfsHelper(newStack, visited + currentNodeId, result)
          }
        }
      }

    dfsHelper(List((startNodeId, 0)), Set.empty, Set.empty)
  }

  /**
   * Finds the shortest path between two nodes using BFS.
   *
   * @param startNodeId The ID of the starting node
   * @param endNodeId The ID of the target node
   * @return Option containing the list of edges representing the path, or None if no path found
   */
  def findShortestPath(startNodeId: String, endNodeId: String): Option[List[Edge]] = {
    if (!graph.nodes.contains(startNodeId) || !graph.nodes.contains(endNodeId)) return None

    @tailrec
    def bfsHelper(
      queue: scala.collection.immutable.Queue[(String, List[Edge])],
      visited: Set[String]
    ): Option[List[Edge]] =
      if (queue.isEmpty) None
      else {
        val ((currentNodeId, path), newQueue) = queue.dequeue

        if (currentNodeId == endNodeId) Some(path)
        else if (visited.contains(currentNodeId)) {
          bfsHelper(newQueue, visited)
        } else {
          val newVisited = visited + currentNodeId
          val neighbors = graph.edges
            .filter(_.source == currentNodeId)
            .filterNot(edge => newVisited.contains(edge.target))
            .map(edge => (edge.target, path :+ edge))

          bfsHelper(newQueue.enqueueAll(neighbors), newVisited)
        }
      }

    bfsHelper(scala.collection.immutable.Queue((startNodeId, List.empty)), Set.empty)
  }

  /**
   * Finds all paths between two nodes up to a maximum length.
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
    if (!graph.nodes.contains(startNodeId) || !graph.nodes.contains(endNodeId)) return List.empty

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
        graph.edges
          .filter(_.source == currentId)
          .filterNot(edge => newVisited.contains(edge.target))
          .flatMap(edge => pathsHelper(edge.target, currentPath :+ edge, newVisited))
          .toList
      }

    pathsHelper(startNodeId, List.empty, Set.empty)
  }

  /**
   * Gets all nodes within a certain distance.
   *
   * @param startNodeId The ID of the starting node
   * @param distance Exact distance (number of hops)
   * @return Set of nodes at the specified distance
   */
  def getNodesAtDistance(startNodeId: String, distance: Int): Set[Node] = {
    val allNodes    = traverse(startNodeId, distance)
    val closerNodes = if (distance > 0) traverse(startNodeId, distance - 1) else Set.empty
    allNodes -- closerNodes
  }
}
