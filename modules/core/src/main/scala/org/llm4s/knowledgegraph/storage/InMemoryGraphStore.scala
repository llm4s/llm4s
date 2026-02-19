package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/**
 * In-memory implementation of GraphStore using atomic references for thread-safe updates.
 *
 * Thread-safety: This implementation is thread-safe via CAS (Compare-And-Swap) atomic operations.
 * All mutations use linearizable atomic updates to ensure consistency under concurrent access.
 *
 * Performance: Optimal for testing and small to medium graphs (<100k nodes).
 * Not suitable for graphs requiring persistence.
 *
 * @param initialGraph Optional initial graph state (defaults to empty)
 */
class InMemoryGraphStore(initialGraph: Graph = Graph.empty) extends GraphStore {
  // Thread-safe state management via atomic reference
  private val graphRef = new AtomicReference[Graph](initialGraph)

  override def upsertNode(node: Node): Result[Unit] = {
    val updated = atomicUpdate(graph => Right(graph.addNode(node)))
    updated.map(_ => ())
  }

  override def upsertEdge(edge: Edge): Result[Unit] = {
    val updated = atomicUpdate { graph =>
      // CAS correctness: re-check node existence on every retry
      if (!graph.hasNode(edge.source) || !graph.hasNode(edge.target)) {
        Left(ProcessingError("validation", "source or target node does not exist"))
      } else {
        // Enforce upsert semantics: remove any existing edge with the same
        // source, target, and relationship before adding the new edge
        val updatedEdges = graph.edges.filterNot { e =>
          e.source == edge.source &&
          e.target == edge.target &&
          e.relationship == edge.relationship
        } :+ edge
        Right(graph.copy(edges = updatedEdges))
      }
    }
    updated.map(_ => ())
  }

  override def getNode(id: String): Result[Option[Node]] =
    Right(graphRef.get().nodes.get(id))

  override def getNeighbors(nodeId: String, direction: Direction = Direction.Both): Result[Seq[EdgeNodePair]] = {
    val graph = graphRef.get()

    if (!graph.hasNode(nodeId)) {
      Right(Seq.empty)
    } else {
      val edges = direction match {
        case Direction.Outgoing => graph.getOutgoingEdges(nodeId)
        case Direction.Incoming => graph.getIncomingEdges(nodeId)
        case Direction.Both     => graph.getConnectedEdges(nodeId)
      }

      val pairs = edges.flatMap { edge =>
        val neighborId = if (edge.source == nodeId) edge.target else edge.source
        graph.nodes.get(neighborId).map(node => EdgeNodePair(edge, node))
      }

      Right(pairs)
    }
  }

  override def query(filter: GraphFilter): Result[Graph] = {
    val graph = graphRef.get()

    // Apply label filter
    val nodesByLabel = filter.nodeLabel match {
      case Some(label) =>
        graph.findNodesByLabel(label).map(n => n.id -> n).toMap
      case None => graph.nodes
    }

    // Apply property filter
    val nodesByProperty = (filter.propertyKey, filter.propertyValue) match {
      case (Some(key), Some(value)) =>
        graph
          .findNodesByProperty(key, value)
          .map(n => n.id -> n)
          .toMap
          .view
          .filterKeys(nodesByLabel.contains)
          .toMap
      case _ => nodesByLabel
    }

    // Filter edges by relationship type and node membership
    val filteredEdges = filter.relationshipType match {
      case Some(rel) =>
        graph.edges.filter { e =>
          e.relationship == rel &&
          nodesByProperty.contains(e.source) &&
          nodesByProperty.contains(e.target)
        }
      case None =>
        graph.edges.filter { e =>
          nodesByProperty.contains(e.source) &&
          nodesByProperty.contains(e.target)
        }
    }

    Right(Graph(nodesByProperty, filteredEdges))
  }

  override def traverse(startId: String, config: TraversalConfig = TraversalConfig()): Result[Seq[Node]] = {
    val graph = graphRef.get()

    GraphTraversal.bfs(startId, config)(
      nodeId => Right(graph.nodes.get(nodeId)),
      (currentNodeId, direction) => {
        val neighbors = direction match {
          case Direction.Outgoing => graph.getOutgoingEdges(currentNodeId)
          case Direction.Incoming => graph.getIncomingEdges(currentNodeId)
          case Direction.Both     => graph.getConnectedEdges(currentNodeId)
        }

        val nextIds = neighbors.flatMap { edge =>
          val targetId = direction match {
            case Direction.Outgoing => edge.target
            case Direction.Incoming => edge.source
            case Direction.Both =>
              if (edge.source == currentNodeId) edge.target else edge.source
          }
          graph.nodes.get(targetId).map(_.id)
        }

        Right(nextIds)
      }
    )
  }

  override def deleteNode(id: String): Result[Unit] = {
    val updated = atomicUpdate { graph =>
      Right(
        graph.copy(
          nodes = graph.nodes - id,
          edges = graph.edges.filter(e => e.source != id && e.target != id)
        )
      )
    }
    updated.map(_ => ())
  }

  override def deleteEdge(source: String, target: String, relationship: String): Result[Unit] = {
    val updated = atomicUpdate { graph =>
      Right(
        graph.copy(
          edges = graph.edges.filter { e =>
            !(e.source == source && e.target == target && e.relationship == relationship)
          }
        )
      )
    }
    updated.map(_ => ())
  }

  override def loadAll(): Result[Graph] =
    Right(graphRef.get())

  override def stats(): Result[GraphStats] = {
    val graph     = graphRef.get()
    val nodeCount = graph.nodes.size.toLong
    val edgeCount = graph.edges.size.toLong
    val avgDegree = if (nodeCount > 0) (edgeCount * 2.0) / nodeCount else 0.0

    val densestNodeId = graph.nodes.keys
      .maxByOption(nodeId => graph.getConnectedEdges(nodeId).size)

    Right(GraphStats(nodeCount, edgeCount, avgDegree, densestNodeId))
  }

  /**
   * Atomically updates the graph state using Compare-And-Swap.
   * Ensures linearizable consistency for concurrent access.
   *
   * @param fn Function to apply to current graph state
   * @return Updated graph or error
   */
  @tailrec
  private def atomicUpdate(fn: Graph => Result[Graph]): Result[Graph] = {
    val current = graphRef.get()
    fn(current) match {
      case Left(err) => Left(err)
      case Right(next) =>
        if (graphRef.compareAndSet(current, next)) Right(next)
        else atomicUpdate(fn) // lost the CAS race — retry with fresh snapshot
    }
  }

  /**
   * Returns current graph snapshot.
   * Safe for concurrent reads due to immutability.
   */
  def snapshot(): Graph = graphRef.get()
}
