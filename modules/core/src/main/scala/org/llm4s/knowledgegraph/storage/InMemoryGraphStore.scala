package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{ Graph, Node, Edge }
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError

class InMemoryGraphStore extends GraphStore {

  private var graph: Graph = Graph.empty

  /* ------------------- Upsert Operations ------------------- */

  override def upsertNode(node: Node): Result[Unit] = {
    graph = graph.addNode(node)
    Right(())
  }

  override def upsertEdge(edge: Edge): Result[Unit] =
    if (!graph.hasNode(edge.source) || !graph.hasNode(edge.target))
      Left(ProcessingError("missing_node", "Edge endpoints must exist"))
    else {
      graph = graph.addEdge(edge)
      Right(())
    }

  /* ------------------- Read Operations ------------------- */

  override def getNode(id: String): Result[Option[Node]] =
    Right(graph.nodes.get(id))

  override def getNeighbors(
    nodeId: String,
    direction: Direction
  ): Result[Seq[(Edge, Node)]] = {

    val edges = direction match {
      case Direction.Outgoing => graph.getOutgoingEdges(nodeId)
      case Direction.Incoming => graph.getIncomingEdges(nodeId)
      case Direction.Both     => graph.getConnectedEdges(nodeId)
    }

    val result = edges.flatMap { e =>
      val neighborId =
        if (e.source == nodeId) e.target else e.source

      graph.nodes.get(neighborId).map(n => (e, n))
    }

    Right(result)
  }

  override def loadAll(): Result[Graph] =
    Right(graph)

  override def stats(): Result[GraphStats] =
    Right(GraphStats(graph.nodes.size, graph.edges.size))

  /* ------------------- Query ------------------- */

  override def query(filter: GraphFilter): Result[Graph] = {
    val filteredNodes = graph.nodes.values
      .filter { node =>
        val labelMatch =
          filter.label.forall(_ == node.label)

        val propertyMatch =
          (filter.propertyKey, filter.propertyValue) match {
            case (Some(k), Some(v)) =>
              node.properties.get(k).exists(_.toString.replace("\"", "") == v)
            case _ => true
          }

        labelMatch && propertyMatch
      }
      .map(n => n.id -> n)
      .toMap

    val filteredEdges = graph.edges.filter(edge => filter.relationship.forall(_ == edge.relationship))

    Right(Graph(filteredNodes, filteredEdges))
  }

  /* ------------------- Traversal ------------------- */

  override def traverse(
    startId: String,
    config: TraversalConfig
  ): Result[Seq[Node]] = {

    def dfs(current: String, depth: Int, visited: Set[String]): Set[String] =
      if (depth > config.maxDepth || visited.contains(current))
        visited
      else {
        val neighbors = graph.getNeighbors(current).map(_.id)
        neighbors.foldLeft(visited + current)((acc, n) => dfs(n, depth + 1, acc))
      }

    if (!graph.hasNode(startId))
      Left(ProcessingError("missing_node", s"Start node $startId does not exist"))
    else {
      val visitedIds = dfs(startId, 0, Set.empty)
      Right(visitedIds.flatMap(graph.nodes.get).toSeq)
    }
  }

  /* ------------------- Delete ------------------- */

  override def deleteNode(id: String): Result[Unit] =
    if (!graph.hasNode(id))
      Left(ProcessingError("missing_node", s"Node $id does not exist"))
    else {
      val newNodes = graph.nodes - id
      val newEdges = graph.edges.filter(e => e.source != id && e.target != id)
      graph = Graph(newNodes, newEdges)
      Right(())
    }

  override def deleteEdge(
    source: String,
    target: String,
    relationship: String
  ): Result[Unit] = {

    val newEdges =
      graph.edges.filterNot(e =>
        e.source == source &&
          e.target == target &&
          e.relationship == relationship
      )

    graph = graph.copy(edges = newEdges)
    Right(())
  }
}
