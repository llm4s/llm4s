package org.llm4s.knowledgegraph.storage

import org.llm4s.error.ProcessingError
import org.llm4s.knowledgegraph.Node
import org.llm4s.types.Result

/** Graph traversal algorithms for the knowledge-graph storage layer. */
private[knowledgegraph] object GraphTraversal {

  import scala.annotation.tailrec

  /**
   * Breadth-first traversal, one level at a time, in the order [[GraphStore.traverse]] promises: by depth, and by
   * node id within a depth (#2970). Nodes in `visitedNodeIds` are skipped rather than traversed through; the start
   * node, when it exists and is not in that set, comes first at depth 0.
   */
  def bfs(
    startId: String,
    config: TraversalConfig
  )(
    getNode: String => Result[Option[Node]],
    getNeighborIds: (String, Direction) => Result[Seq[String]]
  ): Result[Seq[Node]] =
    if (config.visitedNodeIds.contains(startId)) Right(Seq.empty)
    else
      getNode(startId).flatMap {
        case None => Right(Seq.empty)
        case Some(start) =>
          def neighbourIds(level: Vector[Node], seen: Set[String]): Result[Vector[String]] =
            level
              .foldLeft[Result[Set[String]]](Right(Set.empty)) { (acc, n) =>
                acc.flatMap(ids => getNeighborIds(n.id, config.direction).map(ids ++ _))
              }
              .map(ids => ids.filterNot(id => seen.contains(id) || config.visitedNodeIds.contains(id)).toVector.sorted)

          def nodes(ids: Vector[String]): Result[Vector[Node]] =
            ids.foldLeft[Result[Vector[Node]]](Right(Vector.empty)) { (acc, id) =>
              acc.flatMap(found => getNode(id).map(found ++ _))
            }

          @tailrec
          def loop(level: Vector[Node], depth: Int, seen: Set[String], acc: Vector[Node]): Result[Vector[Node]] =
            if (level.isEmpty || depth >= config.maxDepth) Right(acc)
            else
              neighbourIds(level, seen).flatMap(ids => nodes(ids).map(ids -> _)) match {
                case Left(err)          => Left(err)
                case Right((ids, next)) => loop(next, depth + 1, seen ++ ids, acc ++ next)
              }

          loop(Vector(start), 0, Set(startId), Vector(start)).map(_.toSeq)
      }

  /**
   * The half-specified property filter check every store applies: a `propertyKey` without a `propertyValue`, or the
   * reverse, is a `Left` rather than a filter that is silently ignored (#2970).
   */
  def checkPropertyFilter(filter: GraphFilter): Result[Unit] =
    (filter.propertyKey, filter.propertyValue) match {
      case (Some(_), None) | (None, Some(_)) =>
        Left(
          ProcessingError(
            "graph-filter",
            "GraphFilter.propertyKey and propertyValue must be given together: one without the other filters nothing"
          )
        )
      case _ => Right(())
    }

  /**
   * Node count, edge count, average degree `2E/V` and the node with the most incident edges (a self-loop counts
   * once), ties going to the smallest id: the figures [[GraphStore.stats]] promises (#2970).
   */
  def stats(graph: org.llm4s.knowledgegraph.Graph): GraphStats = {
    val nodeCount = graph.nodes.size.toLong
    val edgeCount = graph.edges.size.toLong
    val avgDegree = if (nodeCount > 0) (edgeCount * 2.0) / nodeCount else 0.0
    val degree = graph.edges.foldLeft(Map.empty[String, Int]) { (m, e) =>
      val withSource = m.updated(e.source, m.getOrElse(e.source, 0) + 1)
      if (e.target == e.source) withSource else withSource.updated(e.target, withSource.getOrElse(e.target, 0) + 1)
    }
    val densestNodeId = graph.nodes.keys.toVector.sorted.maxByOption(id => degree.getOrElse(id, 0))
    GraphStats(nodeCount, edgeCount, avgDegree, densestNodeId)
  }
}
