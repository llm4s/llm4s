package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{ Graph, Node, Edge }
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError

/**
 * Thread-safe in-memory implementation of GraphStore.
 *
 * Suitable for testing and small graphs.
 * All mutations are synchronized.
 */
class InMemoryGraphStore extends GraphStore {

  @volatile private var graph: Graph = Graph.empty

  /* ------------------- Upsert ------------------- */

  override def upsertNode(node: Node): Result[Unit] = synchronized {
    graph = graph.addNode(node)
    Right(())
  }

  override def upsertEdge(edge: Edge): Result[Unit] = synchronized {
    if (!graph.hasNode(edge.source) || !graph.hasNode(edge.target))
      Left(ProcessingError("missing_node", "Edge endpoints must exist"))
    else {
      graph = graph.addEdge(edge)
      Right(())
    }
  }

  /* ------------------- Read ------------------- */

  override def getNode(id: String): Result[Option[Node]] =
    synchronized(Right(graph.nodes.get(id)))

  override def getNeighbors(
    nodeId: String,
    direction: Direction
  ): Result[Seq[(Edge, Node)]] =
    synchronized {
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
    synchronized(Right(graph))

  override def stats(): Result[GraphStats] =
    synchronized(Right(GraphStats(graph.nodes.size, graph.edges.size)))

  /* ------------------- Query ------------------- */

  override def query(filter: GraphFilter): Result[Graph] =
    synchronized {

      val filteredNodes = graph.nodes.filter { case (_, node) =>
        val labelMatch =
          filter.label.forall(_ == node.label)

        val propertyMatch =
          (filter.propertyKey, filter.propertyValue) match {
            case (Some(k), Some(v)) =>
              node.properties.get(k).exists {
                case ujson.Str(s)  => s == v
                case ujson.Num(n)  => n.toString == v
                case ujson.Bool(b) => b.toString == v
                case _             => false
              }
            case _ => true
          }

        labelMatch && propertyMatch
      }

      val filteredEdges =
        graph.edges.filter { e =>
          val relationshipMatch =
            filter.relationship.forall(_ == e.relationship)

          val nodesPresent =
            filteredNodes.contains(e.source) &&
              filteredNodes.contains(e.target)

          relationshipMatch && nodesPresent
        }

      Right(Graph(filteredNodes, filteredEdges))
    }

  /* ------------------- Traversal (BFS) ------------------- */

  override def traverse(
    startId: String,
    config: TraversalConfig
  ): Result[Seq[Node]] =
    synchronized {

      if (!graph.hasNode(startId))
        return Left(
          ProcessingError("missing_node", s"Start node $startId does not exist")
        )

      var visited  = Set.empty[String]
      var frontier = List((startId, 0))

      while (frontier.nonEmpty) {
        val (current, depth) = frontier.head
        frontier = frontier.tail

        if (!visited.contains(current) && depth <= config.maxDepth) {
          visited += current

          val neighbors =
            graph.getNeighbors(current).map(_.id).filterNot(visited.contains)

          frontier ++= neighbors.map(n => (n, depth + 1))
        }
      }

      Right(visited.flatMap(graph.nodes.get).toSeq)
    }

  /* ------------------- Delete ------------------- */

  override def deleteNode(id: String): Result[Unit] = synchronized {
    if (!graph.hasNode(id))
      Left(ProcessingError("missing_node", s"Node $id does not exist"))
    else {
      graph = Graph(
        graph.nodes - id,
        graph.edges.filterNot(e => e.source == id || e.target == id)
      )
      Right(())
    }
  }

  override def deleteEdge(
    source: String,
    target: String,
    relationship: String
  ): Result[Unit] = synchronized {

    graph = graph.copy(
      edges = graph.edges.filterNot(e =>
        e.source == source &&
          e.target == target &&
          e.relationship == relationship
      )
    )

    Right(())
  }
}
