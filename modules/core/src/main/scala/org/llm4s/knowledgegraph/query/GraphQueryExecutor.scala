package org.llm4s.knowledgegraph.query

import org.llm4s.knowledgegraph.engine.GraphEngine
import org.llm4s.knowledgegraph.storage.{ GraphFilter, GraphStore, TraversalConfig }
import org.llm4s.types.Result

/**
 * Executes [[GraphQuery]] operations against a [[GraphStore]].
 *
 * This component bridges the structured query ADT with the engine-agnostic
 * storage layer, translating each query variant into the appropriate
 * `GraphStore` and `GraphEngine` calls.
 *
 * @param graphStore The graph store to query against
 */
class GraphQueryExecutor(graphStore: GraphStore) {

  /**
   * Executes a graph query and returns the result.
   *
   * @param query The structured graph query to execute
   * @return Right(result) on success, Left(error) on failure
   */
  def execute(query: GraphQuery): Result[GraphQueryResult] = query match {
    case q: GraphQuery.FindNodes      => executeFindNodes(q)
    case q: GraphQuery.FindNeighbors  => executeFindNeighbors(q)
    case q: GraphQuery.FindPath       => executeFindPath(q)
    case q: GraphQuery.DescribeNode   => executeDescribeNode(q)
    case q: GraphQuery.CompositeQuery => executeComposite(q)
  }

  private def executeFindNodes(query: GraphQuery.FindNodes): Result[GraphQueryResult] = {
    val filter = GraphFilter(
      nodeLabel = query.label,
      propertyKey = query.properties.headOption.map(_._1),
      propertyValue = query.properties.headOption.map(_._2)
    )

    graphStore.query(filter).map { subgraph =>
      // Apply additional property filters beyond the first one
      val filteredNodes = if (query.properties.size > 1) {
        subgraph.nodes.values.filter { node =>
          query.properties.forall { case (key, value) =>
            node.properties.get(key).exists { v =>
              v match {
                case ujson.Str(s) => s == value
                case other        => other.toString == value
              }
            }
          }
        }.toSeq
      } else {
        subgraph.nodes.values.toSeq
      }

      val nodeIds       = filteredNodes.map(_.id).toSet
      val relevantEdges = subgraph.edges.filter(e => nodeIds.contains(e.source) && nodeIds.contains(e.target))

      GraphQueryResult(
        nodes = filteredNodes,
        edges = relevantEdges,
        summary = s"Found ${filteredNodes.size} node(s)${query.label.map(l => s" with label '$l'").getOrElse("")}"
      )
    }
  }

  private def executeFindNeighbors(query: GraphQuery.FindNeighbors): Result[GraphQueryResult] =
    if (query.maxDepth <= 1) {
      // Direct neighbors via GraphStore API
      graphStore.getNeighbors(query.nodeId, query.direction).map { pairs =>
        val filtered = query.relationshipType match {
          case Some(relType) => pairs.filter(_.edge.relationship == relType)
          case None          => pairs
        }

        GraphQueryResult(
          nodes = filtered.map(_.node),
          edges = filtered.map(_.edge),
          summary = s"Found ${filtered.size} neighbor(s) of node '${query.nodeId}'"
        )
      }
    } else {
      // Multi-hop traversal via GraphStore traverse
      val config = TraversalConfig(
        maxDepth = query.maxDepth,
        direction = query.direction
      )

      graphStore.traverse(query.nodeId, config).flatMap { traversedNodes =>
        // Load the full graph to get edges between traversed nodes
        graphStore.loadAll().map { fullGraph =>
          val nodeIds       = traversedNodes.map(_.id).toSet
          val relevantEdges = fullGraph.edges.filter(e => nodeIds.contains(e.source) && nodeIds.contains(e.target))

          val filteredEdges = query.relationshipType match {
            case Some(relType) => relevantEdges.filter(_.relationship == relType)
            case None          => relevantEdges
          }

          // Exclude the start node from the result nodes
          val neighborNodes = traversedNodes.filterNot(_.id == query.nodeId)

          GraphQueryResult(
            nodes = neighborNodes,
            edges = filteredEdges,
            summary = s"Found ${neighborNodes.size} node(s) within ${query.maxDepth} hop(s) of '${query.nodeId}'"
          )
        }
      }
    }

  private def executeFindPath(query: GraphQuery.FindPath): Result[GraphQueryResult] =
    graphStore.loadAll().map { graph =>
      val engine = new GraphEngine(graph)
      val paths  = engine.findAllPaths(query.fromNodeId, query.toNodeId, query.maxHops)

      if (paths.isEmpty) {
        GraphQueryResult(
          nodes = Seq.empty,
          edges = Seq.empty,
          paths = Seq.empty,
          summary =
            s"No path found between '${query.fromNodeId}' and '${query.toNodeId}' within ${query.maxHops} hop(s)"
        )
      } else {
        // Collect all nodes and edges across all paths
        val allEdges   = paths.flatten.distinct
        val allNodeIds = allEdges.flatMap(e => Seq(e.source, e.target)).toSet
        val allNodes   = allNodeIds.flatMap(graph.nodes.get).toSeq

        GraphQueryResult(
          nodes = allNodes,
          edges = allEdges,
          paths = paths,
          summary = s"Found ${paths.size} path(s) between '${query.fromNodeId}' and '${query.toNodeId}'"
        )
      }
    }

  private def executeDescribeNode(query: GraphQuery.DescribeNode): Result[GraphQueryResult] =
    graphStore.getNode(query.nodeId).flatMap {
      case None =>
        Right(
          GraphQueryResult(
            nodes = Seq.empty,
            edges = Seq.empty,
            summary = s"Node '${query.nodeId}' not found"
          )
        )
      case Some(node) =>
        if (query.includeNeighbors) {
          graphStore.getNeighbors(query.nodeId).map { pairs =>
            GraphQueryResult(
              nodes = node +: pairs.map(_.node),
              edges = pairs.map(_.edge),
              summary = s"Node '${query.nodeId}' (${node.label}) with ${pairs.size} neighbor(s)"
            )
          }
        } else {
          Right(
            GraphQueryResult(
              nodes = Seq(node),
              edges = Seq.empty,
              summary = s"Node '${query.nodeId}' (${node.label})"
            )
          )
        }
    }

  private def executeComposite(query: GraphQuery.CompositeQuery): Result[GraphQueryResult] =
    if (query.steps.isEmpty) {
      Right(GraphQueryResult.empty)
    } else {
      query.steps.foldLeft(Right(GraphQueryResult.empty): Result[GraphQueryResult]) { (acc, step) =>
        acc.flatMap(prev => execute(step).map(prev.merge))
      }
    }
}
