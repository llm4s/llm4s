package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{ Graph, Node, Edge }
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError

import java.util.concurrent.atomic.AtomicReference

/**
 * Fully thread-safe in-memory implementation of GraphStore.
 *
 * Uses lock-free CAS loops for all mutations.
 * Reads operate on immutable snapshot references.
 *
 * Linearizable and safe under concurrent access.
 */
class InMemoryGraphStore extends GraphStore {

  private val graphRef =
    new AtomicReference[Graph](Graph.empty)

  /* ------------------- Upsert ------------------- */

  override def upsertNode(node: Node): Result[Unit] = {
    var done = false
    while (!done) {
      val current = graphRef.get()
      val updated = current.addNode(node)
      done = graphRef.compareAndSet(current, updated)
    }
    Right(())
  }

  override def upsertEdge(edge: Edge): Result[Unit] = {
    var done                 = false
    var result: Result[Unit] = Right(())

    while (!done) {
      val current = graphRef.get()

      if (!current.hasNode(edge.source) || !current.hasNode(edge.target)) {
        result = Left(
          ProcessingError("missing_node", "Edge endpoints must exist")
        )
        done = true
      } else {
        val updated = current.addEdge(edge)
        done = graphRef.compareAndSet(current, updated)
      }
    }

    result
  }

  /* ------------------- Read ------------------- */

  override def getNode(id: String): Result[Option[Node]] =
    Right(graphRef.get().nodes.get(id))

  override def getNeighbors(
    nodeId: String,
    direction: Direction
  ): Result[Seq[(Edge, Node)]] = {
    val snapshot = graphRef.get()

    val edges = direction match {
      case Direction.Outgoing => snapshot.getOutgoingEdges(nodeId)
      case Direction.Incoming => snapshot.getIncomingEdges(nodeId)
      case Direction.Both     => snapshot.getConnectedEdges(nodeId)
    }

    val result = edges.flatMap { e =>
      val neighborId =
        if (e.source == nodeId) e.target else e.source

      snapshot.nodes.get(neighborId).map(n => (e, n))
    }

    Right(result)
  }

  override def loadAll(): Result[Graph] =
    Right(graphRef.get())

  override def stats(): Result[GraphStats] = {
    val snapshot = graphRef.get()
    Right(GraphStats(snapshot.nodes.size, snapshot.edges.size))
  }

  /* ------------------- Query ------------------- */

  override def query(filter: GraphFilter): Result[Graph] = {
    val snapshot = graphRef.get()

    val filteredNodes = snapshot.nodes.filter { case (_, node) =>
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
      snapshot.edges.filter { e =>
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
  ): Result[Seq[Node]] = {

    val snapshot = graphRef.get()

    if (!snapshot.hasNode(startId))
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
          snapshot.getNeighbors(current).map(_.id).filterNot(visited.contains)

        frontier ++= neighbors.map(n => (n, depth + 1))
      }
    }

    Right(visited.flatMap(snapshot.nodes.get).toSeq)
  }

  /* ------------------- Delete ------------------- */

  override def deleteNode(id: String): Result[Unit] = {
    var done                 = false
    var result: Result[Unit] = Right(())

    while (!done) {
      val current = graphRef.get()

      if (!current.hasNode(id)) {
        result = Left(
          ProcessingError("missing_node", s"Node $id does not exist")
        )
        done = true
      } else {
        val updated =
          Graph(
            current.nodes - id,
            current.edges.filterNot(e => e.source == id || e.target == id)
          )

        done = graphRef.compareAndSet(current, updated)
      }
    }

    result
  }

  override def deleteEdge(
    source: String,
    target: String,
    relationship: String
  ): Result[Unit] = {
    var done = false

    while (!done) {
      val current = graphRef.get()

      val updated =
        current.copy(
          edges = current.edges.filterNot(e =>
            e.source == source &&
              e.target == target &&
              e.relationship == relationship
          )
        )

      done = graphRef.compareAndSet(current, updated)
    }

    Right(())
  }
}
