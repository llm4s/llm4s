package org.llm4s.knowledgegraph

import org.llm4s.types.Result
import org.llm4s.error.ProcessingError

/**
 * Represents a node (entity) in the knowledge graph.
 *
 * @example
 * {{{
 * val person = Node("alice", "Person", Map("name" -> ujson.Str("Alice Smith")))
 * val org = Node("acme", "Organization")
 * }}}
 *
 * @param id Unique identifier for the node
 * @param label The type or category of the entity (e.g., "Person", "Organization")
 * @param properties Additional attributes of the entity (preserves JSON types)
 */
case class Node(
  id: String,
  label: String,
  properties: Map[String, ujson.Value] = Map.empty
)

/**
 * Represents an edge (relationship) between two nodes.
 *
 * @example
 * {{{
 * val worksFor = Edge("alice", "acme", "WORKS_FOR")
 * val located = Edge("acme", "sf", "LOCATED_IN", Map("since" -> ujson.Str("2020")))
 * }}}
 *
 * @param source The ID of the source node
 * @param target The ID of the target node
 * @param relationship The type of relationship (e.g., "WORKS_FOR", "LOCATED_IN")
 * @param properties Additional attributes of the relationship (preserves JSON types)
 */
case class Edge(
  source: String,
  target: String,
  relationship: String,
  properties: Map[String, ujson.Value] = Map.empty
)

/**
 * Represents a Knowledge Graph containing nodes and edges.
 *
 * @example
 * {{{
 * val alice = Node("alice", "Person", Map("name" -> ujson.Str("Alice")))
 * val acme = Node("acme", "Organization", Map("name" -> ujson.Str("Acme Corp")))
 * val edge = Edge("alice", "acme", "WORKS_FOR")
 *
 * val graph = Graph.empty
 *   .addNode(alice)
 *   .addNode(acme)
 *   .addEdge(edge)
 *
 * graph.getNeighbors("alice") // Set(acme)
 * graph.findNodesByLabel("Person") // List(alice)
 * }}}
 *
 * @param nodes Map of Node ID to Node object
 * @param edges List of Edges in the graph
 */
case class Graph(
  nodes: Map[String, Node],
  edges: List[Edge]
) {

  /**
   * Validates graph integrity - ensures all edge endpoints exist in node set.
   * @return Right(()) if valid, Left(error) if invalid
   */
  def validate(): Result[Unit] = {
    val invalidEdges = edges.filter(edge => !nodes.contains(edge.source) || !nodes.contains(edge.target))

    if (invalidEdges.nonEmpty) {
      val errorMsg = invalidEdges
        .take(5)
        .map { e =>
          val missingSource = if (!nodes.contains(e.source)) s"source=${e.source}" else ""
          val missingTarget = if (!nodes.contains(e.target)) s"target=${e.target}" else ""
          val missing       = List(missingSource, missingTarget).filter(_.nonEmpty).mkString(", ")
          s"Edge(${e.source}->${e.target}): missing nodes: $missing"
        }
        .mkString("; ")

      Left(
        ProcessingError(
          "graph_integrity_violation",
          s"Graph contains ${invalidEdges.size} edge(s) with missing endpoints: $errorMsg"
        )
      )
    } else {
      Right(())
    }
  }

  /**
   * Adds a node to the graph. If a node with the same ID exists, it is replaced.
   *
   * @param node The node to add
   * @return A new graph with the node added
   */
  def addNode(node: Node): Graph = copy(nodes = nodes + (node.id -> node))

  /**
   * Adds an edge to the graph.
   *
   * @param edge The edge to add
   * @return A new graph with the edge appended
   */
  def addEdge(edge: Edge): Graph = copy(edges = edges :+ edge)

  /**
   * Merges another graph into this one. Nodes are merged by ID; duplicate edges are removed.
   *
   * @param other The graph to merge in
   * @return A new graph containing nodes and edges from both graphs
   */
  def merge(other: Graph): Graph =
    Graph(
      nodes ++ other.nodes,
      (edges ++ other.edges).distinct
    )

  /**
   * Gets outgoing edges from a node.
   *
   * @param nodeId The ID of the source node
   * @return All edges originating from the specified node
   */
  def getOutgoingEdges(nodeId: String): List[Edge] =
    edges.filter(_.source == nodeId)

  /**
   * Gets incoming edges to a node.
   *
   * @param nodeId The ID of the target node
   * @return All edges pointing to the specified node
   */
  def getIncomingEdges(nodeId: String): List[Edge] =
    edges.filter(_.target == nodeId)

  /**
   * Gets all edges connected to a node (both incoming and outgoing).
   *
   * @param nodeId The ID of the node
   * @return All edges where the node is either source or target
   */
  def getConnectedEdges(nodeId: String): List[Edge] =
    edges.filter(e => e.source == nodeId || e.target == nodeId)

  /**
   * Gets neighbor nodes (both incoming and outgoing).
   *
   * @param nodeId The ID of the node
   * @return All nodes directly connected to the specified node
   */
  def getNeighbors(nodeId: String): Set[Node] = {
    val neighborIds = getConnectedEdges(nodeId).flatMap { e =>
      if (e.source == nodeId) Some(e.target)
      else if (e.target == nodeId) Some(e.source)
      else None
    }.toSet
    neighborIds.flatMap(nodes.get)
  }

  /**
   * Checks if a node exists in the graph.
   *
   * @param nodeId The ID of the node to check
   * @return True if a node with the given ID exists
   */
  def hasNode(nodeId: String): Boolean = nodes.contains(nodeId)

  /**
   * Checks if an edge exists between two nodes.
   *
   * @param source The source node ID
   * @param target The target node ID
   * @param relationship Optional relationship type filter
   * @return True if a matching edge exists
   */
  def hasEdge(source: String, target: String, relationship: Option[String] = None): Boolean =
    relationship match {
      case Some(rel) => edges.exists(e => e.source == source && e.target == target && e.relationship == rel)
      case None      => edges.exists(e => e.source == source && e.target == target)
    }

  /**
   * Finds nodes by label.
   *
   * @param label The label to match (e.g., "Person")
   * @return All nodes with the given label
   */
  def findNodesByLabel(label: String): List[Node] =
    nodes.values.filter(_.label == label).toList

  /**
   * Finds nodes by property value.
   * Compares against the JSON string representation of the value.
   */
  def findNodesByProperty(key: String, value: String): List[Node] =
    nodes.values.filter { node =>
      node.properties.get(key).exists { v =>
        v match {
          case ujson.Str(s) => s == value
          case other        => other.toString == value
        }
      }
    }.toList
}

object Graph {

  /** Creates an empty graph with no nodes or edges. */
  def empty: Graph = Graph(Map.empty, List.empty)
}
