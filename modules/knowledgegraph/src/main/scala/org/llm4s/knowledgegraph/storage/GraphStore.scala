package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.types.Result

/**
 * Represents the direction of graph traversal.
 */
sealed trait Direction
object Direction {
  case object Outgoing extends Direction
  case object Incoming extends Direction
  case object Both     extends Direction
}

/**
 * Configuration for graph traversal operations.
 *
 * @param maxDepth Maximum number of hops/edges to traverse from the start node (inclusive)
 * @param direction Direction of traversal (Outgoing, Incoming, Both). Controls whether traversal follows outgoing, incoming, or all edges from each node.
 * @param visitedNodeIds Optional set of already-visited nodes to exclude
 */
case class TraversalConfig(
  maxDepth: Int = Int.MaxValue,
  direction: Direction = Direction.Both,
  visitedNodeIds: Set[String] = Set.empty
)

/**
 * Filter criteria for graph queries.
 *
 * @param nodeLabel Optional filter by node label
 * @param relationshipType Optional filter by relationship type
 * @param propertyKey Optional property name to filter by; given with `propertyValue` or not at all
 * @param propertyValue Optional property value to filter by (compared as string); given with `propertyKey` or not at all
 */
case class GraphFilter(
  nodeLabel: Option[String] = None,
  relationshipType: Option[String] = None,
  propertyKey: Option[String] = None,
  propertyValue: Option[String] = None
)

/**
 * Statistics about a graph.
 *
 * @param nodeCount Total number of nodes
 * @param edgeCount Total number of edges
 * @param averageDegree Average number of connections per node
 * @param densestNodeId Node with the most connections (if any)
 */
case class GraphStats(
  nodeCount: Long,
  edgeCount: Long,
  averageDegree: Double,
  densestNodeId: Option[String] = None
)

/**
 * Represents a pair of an edge with its target node during traversal.
 *
 * @param edge The edge traversed
 * @param node The target node
 */
case class EdgeNodePair(edge: Edge, node: Node)

/**
 * Abstract interface for persisting and querying Knowledge Graphs.
 *
 * Every implementation behaves the same way on the points below; `GraphStoreContract` (the knowledgegraph tests,
 * run against the in-memory, JSON and Neo4j stores) checks them (#2970):
 * - A relationship type is any non-empty string, spaces, hyphens, digits first and non-ASCII letters included
 *   (`"WORKS FOR"`, `"works-for"`, `"3D_PRINTS"`), and so is a property key.
 * - Between two nodes there is at most one edge of each relationship type; edges of different types between the same
 *   nodes are kept apart. A self-loop (an edge from a node to itself) is an ordinary edge: [[getNeighbors]] returns
 *   it, once, in every direction.
 * - [[traverse]] is breadth-first: nodes come in order of depth, and by node id within one depth.
 * - [[stats]] counts each edge once; `averageDegree` is `2 * edgeCount / nodeCount`, and `densestNodeId` is the node
 *   with the most incident edges (a self-loop counts once), the smallest id on a tie.
 * - A [[GraphFilter]] with a `propertyKey` but no `propertyValue`, or the reverse, is a `Left`.
 * - A missing node is `Right(None)` from [[getNode]], `Right(Seq.empty)` from [[getNeighbors]] and [[traverse]], and
 *   a `Left` from [[upsertEdge]]; deleting a missing node or edge is `Right(())`.
 * - Implementations are thread-safe or document that they are not.
 *
 * This trait is designed to be engine-agnostic, allowing implementations
 * for various graph databases (Neo4j, TinkerPop, SPARQL, etc.) while
 * maintaining a consistent interface.
 *
 * @example
 * {{{
 * val store: GraphStore = new InMemoryGraphStore()
 * for {
 *   _ <- store.upsertNode(Node("alice", "Person", Map("name" -> ujson.Str("Alice"))))
 *   _ <- store.upsertNode(Node("acme", "Organization"))
 *   _ <- store.upsertEdge(Edge("alice", "acme", "WORKS_FOR"))
 *   neighbors <- store.getNeighbors("alice")
 * } yield neighbors
 * }}}
 */
trait GraphStore {

  /**
   * Inserts or updates a node in the graph.
   * If the node ID already exists, the existing node is replaced.
   *
   * @param node The node to upsert
   * @return Right(()) on success, Left(error) on failure
   */
  def upsertNode(node: Node): Result[Unit]

  /**
   * Inserts or updates an edge in the graph.
   * Both source and target nodes must exist.
   * If an edge with the same source, target, and relationship already exists, it is replaced.
   *
   * @param edge The edge to upsert
   * @return Right(()) on success, Left(error) if source or target nodes don't exist
   */
  def upsertEdge(edge: Edge): Result[Unit]

  /**
   * Retrieves a node by ID.
   *
   * @param id The node ID
   * @return Right(Some(node)) if found, Right(None) if not found, Left(error) on failure
   */
  def getNode(id: String): Result[Option[Node]]

  /**
   * Retrieves neighboring nodes and their connecting edges.
   *
   * @param nodeId The ID of the node
   * @param direction The direction of traversal (Outgoing, Incoming, Both)
   * @return Right(Seq of (edge, neighbor node)) on success, Left(error) on failure
   */
  def getNeighbors(nodeId: String, direction: Direction = Direction.Both): Result[Seq[EdgeNodePair]]

  /**
   * Queries the graph based on filter criteria.
   * Returns a subgraph matching the filter conditions.
   *
   * A property filter needs both `propertyKey` and `propertyValue`: one without the other is a `Left`.
   *
   * @param filter The filter criteria
   * @return Right(subgraph) on success, Left(error) on failure
   */
  def query(filter: GraphFilter): Result[Graph]

  /**
   * Traverses the graph starting from a node using BFS.
   * Nodes come in order of depth, and by node id within one depth, so every implementation returns the same sequence.
   *
   * Missing start node: should return Right(Seq.empty) not an error,
   * since traversal of a non-existent node yields no nodes.
   *
   * @param startId The ID of the starting node
   * @param config Traversal configuration (depth, direction, visited set). The direction parameter controls whether traversal follows outgoing, incoming, or all edges from each node.
   * @return Right(Seq of traversed nodes) on success, Left(error) on critical failure
   */
  def traverse(startId: String, config: TraversalConfig = TraversalConfig()): Result[Seq[Node]]

  /**
   * Deletes a node and all its connected edges.
   *
   * @param id The node ID to delete
   * @return Right(()) on success or if node doesn't exist, Left(error) on failure
   */
  def deleteNode(id: String): Result[Unit]

  /**
   * Deletes a specific edge.
   *
   * @param source The source node ID
   * @param target The target node ID
   * @param relationship The relationship type
   * @return Right(()) on success or if edge doesn't exist, Left(error) on failure
   */
  def deleteEdge(source: String, target: String, relationship: String): Result[Unit]

  /**
   * Loads the entire graph.
   *
   * For implementations backed by external databases, consider pagination
   * or size limits to avoid memory issues with large graphs.
   *
   * @return Right(graph) on success, Left(error) on failure
   */
  def loadAll(): Result[Graph]

  /**
   * Computes statistics about the graph.
   *
   * @return Right(stats) on success, Left(error) on failure
   */
  def stats(): Result[GraphStats]
}
