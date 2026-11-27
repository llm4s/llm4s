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
 *
 * @example
 * {{{
 * // Find all Person nodes
 * val findPeople = GraphQuery.FindNodes(label = Some("Person"))
 *
 * // Find neighbors of a specific node
 * val neighbors = GraphQuery.FindNeighbors(nodeId = "alice", maxDepth = 2)
 *
 * // Find path between two nodes
 * val path = GraphQuery.FindPath(fromNodeId = "alice", toNodeId = "bob")
 *
 * // Compose multiple queries
 * val composite = GraphQuery.CompositeQuery(Seq(findPeople, neighbors))
 * }}}
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
   * Find the shortest paths between two nodes.
   *
   * @param fromNodeId The starting node ID
   * @param toNodeId The target node ID
   * @param maxHops Maximum number of hops to search
   * @param direction Which edges a path may follow: `Both` (the default) also walks an edge against its direction, so
   *                  `alice -WORKS_FOR-> acme <-WORKS_FOR- bob` connects Alice and Bob (#2974)
   */
  case class FindPath(
    fromNodeId: String,
    toNodeId: String,
    maxHops: Int = 5,
    direction: Direction = Direction.Both
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
   * A node id of the form `$N` (see [[GraphQuery.stepReference]]) in a later step stands for each node step `N`
   * (1-based) found, so `find_nodes{name: Alice}` then `find_neighbors{node_id: "$1"}` runs on Alice's real id (#2973).
   *
   * @param steps The ordered list of queries to execute
   */
  case class CompositeQuery(
    steps: Seq[GraphQuery]
  ) extends GraphQuery

  /** The step a `$N` node id refers to (1-based), if it is one. */
  def stepReference(nodeId: String): Option[Int] =
    if (nodeId.length > 1 && nodeId.head == '$') nodeId.tail.toIntOption.filter(_ > 0) else None
}

/**
 * Result of executing a graph query.
 *
 * @param nodes The nodes returned by the query
 * @param edges The edges returned by the query (relationships between result nodes)
 * @param paths Ordered paths found (for path queries)
 * @param summary A human-readable summary of the result
 * @param missingNodeIds Node ids the query named that are not in the graph: an unknown id, rather than a node with no
 *                       neighbours (#2973)
 */
case class GraphQueryResult(
  nodes: Seq[Node],
  edges: Seq[Edge],
  paths: Seq[List[Edge]] = Seq.empty,
  summary: String = "",
  missingNodeIds: Seq[String] = Seq.empty
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
      edges = (edges ++ other.edges).distinctBy(e => (e.source, e.target, e.relationship)),
      paths = paths ++ other.paths,
      summary =
        if (summary.isEmpty) other.summary
        else if (other.summary.isEmpty) summary
        else s"$summary\n${other.summary}",
      missingNodeIds = (missingNodeIds ++ other.missingNodeIds).distinct
    )
}

object GraphQueryResult {

  /**
   * An empty query result.
   */
  val empty: GraphQueryResult = GraphQueryResult(Seq.empty, Seq.empty)
}
