package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }

/**
 * Represents a source document from which knowledge graph entities were extracted.
 *
 * @param id Unique identifier for the document
 * @param title Human-readable title or filename
 * @param metadata Additional metadata about the document (e.g., author, date, URL)
 */
case class DocumentSource(
  id: String,
  title: String = "",
  metadata: Map[String, String] = Map.empty
)

/**
 * Represents a violation found during schema validation.
 *
 * @param description Human-readable description of the violation
 * @param entityId Optional ID of the entity involved
 * @param violationType Category of violation (e.g., "out_of_schema_entity", "invalid_relationship_endpoint")
 */
case class SchemaViolation(
  description: String,
  entityId: Option[String] = None,
  violationType: String = "unknown"
)

/**
 * Result of validating an extracted graph against an ExtractionSchema.
 *
 * @param validGraph Graph containing only nodes/edges that conform to the schema
 * @param outOfSchemaNodes Nodes whose label does not match any schema entity type
 * @param outOfSchemaEdges Edges whose relationship does not match any schema relationship type
 * @param violations Specific constraint violations found during validation
 */
case class ValidationResult(
  validGraph: Graph,
  outOfSchemaNodes: List[Node],
  outOfSchemaEdges: List[Edge],
  violations: List[SchemaViolation]
) {

  /** True if the graph fully conforms to the schema with no violations. */
  def isFullyValid: Boolean = outOfSchemaNodes.isEmpty && outOfSchemaEdges.isEmpty && violations.isEmpty
}

/**
 * A graph with source provenance tracking.
 *
 * Wraps a standard `Graph` with metadata about which documents contributed
 * each node and edge. The underlying `Graph` remains a pure data structure;
 * provenance is tracked externally.
 *
 * @param graph The underlying knowledge graph
 * @param sources All document sources that contributed to this graph
 * @param nodeSources Mapping of node ID to the set of source document IDs that contributed it
 * @param edgeSources Mapping of (source, target, relationship) to the set of source document IDs
 */
case class SourceTrackedGraph(
  graph: Graph,
  sources: Seq[DocumentSource],
  nodeSources: Map[String, Set[String]],
  edgeSources: Map[(String, String, String), Set[String]]
) {

  /**
   * Remaps all node IDs in a graph by prefixing them with a namespace string.
   *
   * LLM-generated node IDs are arbitrary (often "1", "2", etc.) and will collide
   * across documents. This ensures each document's nodes occupy a distinct ID space
   * before being merged into the shared graph.
   *
   * @param g         The graph whose IDs should be remapped
   * @param namespace The prefix to apply (typically the document source ID)
   * @return A new graph with all node IDs and edge source/target references updated
   */
  private def remapIds(g: Graph, namespace: String): Graph = {
    def remap(id: String): String = s"${namespace}__$id"

    val remappedNodes = g.nodes.map { case (id, node) =>
      remap(id) -> node.copy(id = remap(id))
    }

    val remappedEdges = g.edges.map(edge => edge.copy(source = remap(edge.source), target = remap(edge.target)))

    Graph(remappedNodes, remappedEdges)
  }

  /**
   * Adds a document's extracted graph to this tracked graph, merging nodes/edges
   * and recording provenance.
   *
   * Node IDs are namespaced with the document source ID before merging to prevent
   * silent overwrites when two documents produce the same LLM-generated IDs.
   * Entity linking (run afterwards by [[MultiDocumentGraphBuilder]]) is responsible
   * for collapsing semantically equivalent nodes across documents.
   *
   * @param docGraph The graph extracted from the document
   * @param source   The document source metadata
   * @return A new SourceTrackedGraph with the document incorporated
   */
  def addDocument(docGraph: Graph, source: DocumentSource): SourceTrackedGraph = {
    val remapped    = remapIds(docGraph, source.id)
    val mergedGraph = graph.merge(remapped)

    val updatedNodeSources = remapped.nodes.keys.foldLeft(nodeSources) { (acc, nodeId) =>
      val existing = acc.getOrElse(nodeId, Set.empty)
      acc + (nodeId -> (existing + source.id))
    }

    val updatedEdgeSources = remapped.edges.foldLeft(edgeSources) { (acc, edge) =>
      val key      = (edge.source, edge.target, edge.relationship)
      val existing = acc.getOrElse(key, Set.empty)
      acc + (key -> (existing + source.id))
    }

    val updatedSources = if (sources.exists(_.id == source.id)) sources else sources :+ source

    SourceTrackedGraph(mergedGraph, updatedSources, updatedNodeSources, updatedEdgeSources)
  }

  /**
   * Returns the document sources that contributed a given node.
   *
   * @param nodeId The node ID to look up
   * @return Set of DocumentSource objects that contributed this node
   */
  def getNodeSources(nodeId: String): Set[DocumentSource] = {
    val sourceIds = nodeSources.getOrElse(nodeId, Set.empty)
    sources.filter(s => sourceIds.contains(s.id)).toSet
  }

  /**
   * Returns the document sources that contributed a given edge.
   *
   * @param source The source node ID
   * @param target The target node ID
   * @param relationship The relationship type
   * @return Set of DocumentSource objects that contributed this edge
   */
  def getEdgeSources(source: String, target: String, relationship: String): Set[DocumentSource] = {
    val key       = (source, target, relationship)
    val sourceIds = edgeSources.getOrElse(key, Set.empty)
    sources.filter(s => sourceIds.contains(s.id)).toSet
  }

  /**
   * Replaces the underlying graph (e.g., after entity linking) while preserving source tracking.
   * Node/edge source mappings are preserved as-is; callers performing node merges should
   * update the mappings via `withUpdatedNodeSources` / `withUpdatedEdgeSources`.
   *
   * @param newGraph The replacement graph
   * @return A new SourceTrackedGraph with the updated graph
   */
  def withGraph(newGraph: Graph): SourceTrackedGraph = copy(graph = newGraph)

  /**
   * Returns a new SourceTrackedGraph with updated node source mappings.
   *
   * @param newNodeSources The replacement node source mappings
   * @return A new SourceTrackedGraph with updated mappings
   */
  def withUpdatedNodeSources(newNodeSources: Map[String, Set[String]]): SourceTrackedGraph =
    copy(nodeSources = newNodeSources)

  /**
   * Returns a new SourceTrackedGraph with updated edge source mappings.
   *
   * @param newEdgeSources The replacement edge source mappings
   * @return A new SourceTrackedGraph with updated mappings
   */
  def withUpdatedEdgeSources(newEdgeSources: Map[(String, String, String), Set[String]]): SourceTrackedGraph =
    copy(edgeSources = newEdgeSources)
}

object SourceTrackedGraph {

  /** Creates an empty source-tracked graph. */
  def empty: SourceTrackedGraph = SourceTrackedGraph(Graph.empty, Seq.empty, Map.empty, Map.empty)

  /**
   * Wraps an existing graph with provenance from a single document source.
   *
   * @param graph The graph to wrap
   * @param source The document source that produced the graph
   * @return A SourceTrackedGraph with all nodes/edges attributed to the given source
   */
  def fromGraph(graph: Graph, source: DocumentSource): SourceTrackedGraph = {
    val nodeSrcs = graph.nodes.keys.map(id => id -> Set(source.id)).toMap
    val edgeSrcs = graph.edges.map(e => (e.source, e.target, e.relationship) -> Set(source.id)).toMap
    SourceTrackedGraph(graph, Seq(source), nodeSrcs, edgeSrcs)
  }
}
