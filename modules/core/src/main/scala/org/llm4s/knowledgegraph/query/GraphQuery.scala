package org.llm4s.knowledgegraph.query

import org.llm4s.knowledgegraph.{ Edge, Node }
import org.llm4s.knowledgegraph.storage.Direction

/**
 * Algebraic data type representing structured graph query operations.
 *
 * The LLM translates natural language questions into one of these query types,
 * which are then executed against a [[org.llm4s.knowledgegraph.storage.GraphStore]].
 *
 * This approach is engine-agnostic — the same query ADT works with in-memory stores,
 * JSON-backed stores, or future external graph database implementations.
 */
sealed trait GraphQuery

object GraphQuery {

  /**
   * Find nodes matching a label and/or property criteria.
   *
   * @param label Optional node label filter (e.g., "Person", "Organization")
   * @param properties Optional property key-value pairs to match
   */
  case class FindNodes(
    label: Option[String] = None,
    properties: Map[String, String] = Map.empty
  ) extends GraphQuery

  /**
   * Find neighbors of a specific node.
   *
   * @param nodeId The ID of the node to find neighbors for
   * @param direction Direction of traversal (Outgoing, Incoming, Both)
   * @param relationshipType Optional filter by relationship type
   * @param maxDepth Maximum traversal depth (defaults to 1 for direct neighbors)
   */
  case class FindNeighbors(
    nodeId: String,
    direction: Direction = Direction.Both,
    relationshipType: Option[String] = None,
    maxDepth: Int = 1
  ) extends GraphQuery

  /**
   * Find a path between two nodes.
   *
   * @param fromNodeId The starting node ID
   * @param toNodeId The target node ID
   * @param maxHops Maximum number of hops to search
   */
  case class FindPath(
    fromNodeId: String,
    toNodeId: String,
    maxHops: Int = 5
  ) extends GraphQuery

  /**
   * Get detailed information about a specific node and its immediate context.
   *
   * @param nodeId The ID of the node to describe
   * @param includeNeighbors Whether to include neighbor information
   */
  case class DescribeNode(
    nodeId: String,
    includeNeighbors: Boolean = true
  ) extends GraphQuery

  /**
   * Execute multiple queries in sequence, feeding results forward.
   *
   * @param steps The ordered list of queries to execute
   */
  case class CompositeQuery(
    steps: Seq[GraphQuery]
  ) extends GraphQuery
}

/**
 * Result of executing a graph query.
 *
 * @param nodes The nodes returned by the query
 * @param edges The edges returned by the query (relationships between result nodes)
 * @param paths Ordered paths found (for path queries)
 * @param summary A human-readable summary of the result
 */
case class GraphQueryResult(
  nodes: Seq[Node],
  edges: Seq[Edge],
  paths: Seq[List[Edge]] = Seq.empty,
  summary: String = ""
) {

  /**
   * Whether this result is empty (no nodes or edges found).
   */
  def isEmpty: Boolean = nodes.isEmpty && edges.isEmpty

  /**
   * Merge two query results together, deduplicating nodes by ID.
   */
  def merge(other: GraphQueryResult): GraphQueryResult =
    GraphQueryResult(
      nodes = (nodes ++ other.nodes).distinctBy(_.id),
      edges = (edges ++ other.edges).distinct,
      paths = paths ++ other.paths,
      summary =
        if (summary.isEmpty) other.summary
        else if (other.summary.isEmpty) summary
        else s"$summary\n${other.summary}"
    )
}

object GraphQueryResult {

  /**
   * An empty query result.
   */
  val empty: GraphQueryResult = GraphQueryResult(Seq.empty, Seq.empty)
}
