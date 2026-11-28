package org.llm4s.knowledgegraph.query

import org.llm4s.knowledgegraph.engine.GraphEngine
import org.llm4s.error.ProcessingError
import org.llm4s.knowledgegraph.{ Edge, Node }
import org.llm4s.knowledgegraph.storage.{ Direction, GraphFilter, GraphStore }
import org.llm4s.types.Result

/**
 * Executes [[GraphQuery]] operations against a [[org.llm4s.knowledgegraph.storage.GraphStore GraphStore]].
 *
 * This component bridges the structured query ADT with the engine-agnostic
 * storage layer, translating each query variant into the appropriate
 * `GraphStore` and `GraphEngine` calls.
 *
 * @example
 * {{{
 * val executor = new GraphQueryExecutor(graphStore)
 * val query = GraphQuery.FindNodes(label = Some("Person"))
 * val result = executor.execute(query)
 * // result: Right(GraphQueryResult(nodes = Seq(...), edges = Seq(...)))
 * }}}
 *
 * A query usually comes from a model, so its numbers are bounded here (#2974): a depth or hop count is clamped to
 * `1..maxDepth`, a composite runs at most `maxCompositeSteps` steps, a path query returns at most `maxPaths` shortest
 * paths, and a `$N` step reference expands to at most `maxReferencedNodes` nodes. An id that is not in the graph is
 * listed in [[GraphQueryResult.missingNodeIds]] (#2973).
 *
 * @param graphStore The graph store to query against
 * @param maxDepth The largest depth or hop count a query may use
 * @param maxCompositeSteps The most steps a composite query runs; the rest are skipped, and the summary says so
 * @param maxPaths The most paths a path query returns
 * @param maxReferencedNodes The most nodes a `$N` step reference expands to
 */
class GraphQueryExecutor(
  graphStore: GraphStore,
  maxDepth: Int = GraphQueryExecutor.DefaultMaxDepth,
  maxCompositeSteps: Int = GraphQueryExecutor.DefaultMaxCompositeSteps,
  maxPaths: Int = GraphQueryExecutor.DefaultMaxPaths,
  maxReferencedNodes: Int = GraphQueryExecutor.DefaultMaxReferencedNodes
) {

  private def clampDepth(depth: Int): Int = math.max(1, math.min(depth, math.max(1, maxDepth)))

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
      val ordered = filteredNodes.sortBy(_.id)

      val nodeIds       = ordered.map(_.id).toSet
      val relevantEdges = subgraph.edges.filter(e => nodeIds.contains(e.source) && nodeIds.contains(e.target))

      GraphQueryResult(
        nodes = ordered,
        edges = relevantEdges,
        summary = s"Found ${ordered.size} node(s)${query.label.map(l => s" with label '$l'").getOrElse("")}"
      )
    }
  }

  /**
   * The nodes within `depth` hops of `seeds`, by breadth-first search over [[GraphStore.getNeighbors]], following
   * only edges of `relationshipType` when one is given, so every depth applies the filter the same way (#2974). The
   * seeds that exist come first, then each depth in id order; the edges are those the search followed. Depth 0 gives
   * the seeds alone.
   */
  private[query] def neighborhood(
    seeds: Seq[String],
    depth: Int,
    direction: Direction,
    relationshipType: Option[String]
  ): Result[(Seq[Node], Seq[Edge], Seq[String])] =
    seeds.distinct
      .foldLeft[Result[(Vector[Node], Vector[String])]](Right((Vector.empty, Vector.empty))) { case (acc, id) =>
        acc.flatMap { case (found, missing) =>
          graphStore.getNode(id).map {
            case Some(n) => (found :+ n, missing)
            case None    => (found, missing :+ id)
          }
        }
      }
      .flatMap { case (seedNodes, missing) =>
        def expand(
          frontier: Vector[String],
          level: Int,
          seen: Set[String],
          nodes: Vector[Node],
          edges: Vector[Edge]
        ): Result[(Vector[Node], Vector[Edge])] =
          if (frontier.isEmpty || level >= depth) Right((nodes, edges))
          else
            frontier
              .foldLeft[Result[Vector[EdgeHop]]](Right(Vector.empty)) { (acc, id) =>
                acc.flatMap(hops =>
                  graphStore.getNeighbors(id, direction).map { pairs =>
                    hops ++ pairs.filter(p => relationshipType.forall(_ == p.edge.relationship)).map(EdgeHop(_))
                  }
                )
              }
              .flatMap { hops =>
                val next = hops.filterNot(h => seen.contains(h.node.id)).map(_.node).distinctBy(_.id).sortBy(_.id)
                expand(next.map(_.id), level + 1, seen ++ next.map(_.id), nodes ++ next, edges ++ hops.map(_.edge))
              }

        expand(seedNodes.map(_.id), 0, seedNodes.map(_.id).toSet, seedNodes, Vector.empty).map { case (nodes, edges) =>
          (nodes, edges.distinctBy(e => (e.source, e.target, e.relationship)), missing)
        }
      }

  private case class EdgeHop(edge: Edge, node: Node)
  private object EdgeHop {
    def apply(pair: org.llm4s.knowledgegraph.storage.EdgeNodePair): EdgeHop = EdgeHop(pair.edge, pair.node)
  }

  private def notFound(id: String): GraphQueryResult =
    GraphQueryResult(Seq.empty, Seq.empty, summary = s"Node '$id' not found", missingNodeIds = Seq(id))

  private def executeFindNeighbors(query: GraphQuery.FindNeighbors): Result[GraphQueryResult] = {
    val depth = clampDepth(query.maxDepth)
    neighborhood(Seq(query.nodeId), depth, query.direction, query.relationshipType).map {
      case (_, _, missing) if missing.nonEmpty => notFound(query.nodeId)
      case (nodes, edges, _)                   =>
        // The start node comes first, so its edges keep both ends in the result (#2975)
        val within = if (depth == 1) "" else s" within $depth hop(s)"
        GraphQueryResult(
          nodes = nodes,
          edges = edges,
          summary = s"Found ${nodes.size - 1} neighbor(s) of node '${query.nodeId}'$within"
        )
    }
  }

  private def executeFindPath(query: GraphQuery.FindPath): Result[GraphQueryResult] =
    for {
      from <- graphStore.getNode(query.fromNodeId)
      to   <- graphStore.getNode(query.toNodeId)
      result <- (from, to) match {
        case (None, _) | (_, None) =>
          val missing = Seq(query.fromNodeId -> from, query.toNodeId -> to).collect { case (id, None) => id }.distinct
          Right(
            GraphQueryResult(
              Seq.empty,
              Seq.empty,
              summary = s"Node(s) ${missing.map(id => s"'$id'").mkString(", ")} not found",
              missingNodeIds = missing
            )
          )
        case (Some(node), Some(_)) if query.fromNodeId == query.toNodeId =>
          Right(GraphQueryResult(Seq(node), Seq.empty, summary = s"'${query.fromNodeId}' is the start and the end"))
        case _ => findPaths(query)
      }
    } yield result

  private def findPaths(query: GraphQuery.FindPath): Result[GraphQueryResult] =
    graphStore.loadAll().map { graph =>
      val hops = clampDepth(query.maxHops)
      val paths =
        new GraphEngine(graph).findShortestPaths(query.fromNodeId, query.toNodeId, hops, query.direction, maxPaths)

      if (paths.isEmpty) {
        GraphQueryResult(
          nodes = Seq.empty,
          edges = Seq.empty,
          paths = Seq.empty,
          summary = s"No path found between '${query.fromNodeId}' and '${query.toNodeId}' within $hops hop(s)"
        )
      } else {
        // Collect all nodes and edges across all paths
        val allEdges   = paths.flatten.distinct
        val allNodeIds = allEdges.flatMap(e => Seq(e.source, e.target)).distinct
        val allNodes   = allNodeIds.flatMap(graph.nodes.get)

        GraphQueryResult(
          nodes = allNodes,
          edges = allEdges,
          paths = paths,
          summary = s"Found ${paths.size} shortest path(s) between '${query.fromNodeId}' and '${query.toNodeId}'"
        )
      }
    }

  private def executeDescribeNode(query: GraphQuery.DescribeNode): Result[GraphQueryResult] =
    graphStore.getNode(query.nodeId).flatMap {
      case None => Right(notFound(query.nodeId))
      case Some(node) =>
        if (query.includeNeighbors) {
          graphStore.getNeighbors(query.nodeId).map { pairs =>
            GraphQueryResult(
              nodes = (node +: pairs.map(_.node)).distinctBy(_.id),
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

  /**
   * Runs the steps in order. A `$N` node id stands for each node step `N` found (at most `maxReferencedNodes`); a
   * reference to a step that has not run yet is a `Left` (#2973).
   */
  private def executeComposite(query: GraphQuery.CompositeQuery): Result[GraphQueryResult] = {
    val run     = stepsRun(query)
    val skipped = query.steps.size - run.size
    run.zipWithIndex
      .foldLeft[Result[Vector[GraphQueryResult]]](Right(Vector.empty)) { case (acc, (step, index)) =>
        acc.flatMap { done =>
          resolveReferences(step, index, done).flatMap {
            case Left(message) => Right(done :+ GraphQueryResult(Seq.empty, Seq.empty, summary = message))
            case Right(queries) =>
              queries
                .foldLeft[Result[GraphQueryResult]](Right(GraphQueryResult.empty)) { (r, q) =>
                  r.flatMap(prev => execute(q).map(prev.merge))
                }
                .map(done :+ _)
          }
        }
      }
      .map { results =>
        val merged = results.foldLeft(GraphQueryResult.empty)(_.merge(_))
        if (skipped > 0)
          merged.copy(summary =
            s"${merged.summary}\n($skipped step(s) beyond the limit of $maxCompositeSteps skipped)".trim
          )
        else merged
      }
  }

  /** The steps of a composite query that run: the first `maxCompositeSteps`. */
  private[query] def stepsRun(query: GraphQuery.CompositeQuery): Seq[GraphQuery] =
    query.steps.take(math.max(0, maxCompositeSteps))

  /**
   * The step with each `$N` reference replaced by the ids step `N` found: `Right(Right(queries))`, or
   * `Right(Left(message))` when the referenced step found no nodes, or a `Left` for a reference to a later step.
   */
  private def resolveReferences(
    step: GraphQuery,
    index: Int,
    done: Vector[GraphQueryResult]
  ): Result[Either[String, Seq[GraphQuery]]] = {
    def ids(nodeId: String): Result[Either[String, Seq[String]]] =
      GraphQuery.stepReference(nodeId) match {
        case None => Right(Right(Seq(nodeId)))
        case Some(n) if n > index =>
          Left(ProcessingError("query_execution", s"Step ${index + 1} refers to step $n, which has not run yet"))
        case Some(n) =>
          val found = done(n - 1).nodes.map(_.id).take(math.max(1, maxReferencedNodes))
          Right(if (found.isEmpty) Left(s"Step ${index + 1}: step $n found no nodes for '$nodeId'") else Right(found))
      }
    step match {
      case q: GraphQuery.FindNeighbors => ids(q.nodeId).map(_.map(_.map(id => q.copy(nodeId = id))))
      case q: GraphQuery.DescribeNode  => ids(q.nodeId).map(_.map(_.map(id => q.copy(nodeId = id))))
      case q: GraphQuery.FindPath =>
        for {
          froms <- ids(q.fromNodeId)
          tos   <- ids(q.toNodeId)
        } yield for {
          f <- froms
          t <- tos
        } yield (for {
          a <- f
          b <- t
        } yield q.copy(fromNodeId = a, toNodeId = b)).take(math.max(1, maxReferencedNodes))
      case other => Right(Right(Seq(other)))
    }
  }
}

object GraphQueryExecutor {

  /** The default ceiling on a query's depth or hop count. */
  val DefaultMaxDepth: Int = 5

  /** The default number of steps a composite query runs. */
  val DefaultMaxCompositeSteps: Int = 8

  /** The default number of paths a path query returns. */
  val DefaultMaxPaths: Int = 10

  /** The default number of nodes a `$N` step reference expands to. */
  val DefaultMaxReferencedNodes: Int = 10
}
