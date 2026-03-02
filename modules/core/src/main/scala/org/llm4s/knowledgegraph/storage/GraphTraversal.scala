package org.llm4s.knowledgegraph.storage

import org.llm4s.error.LLMError
import org.llm4s.knowledgegraph.Node
import org.llm4s.types.Result

private[storage] object GraphTraversal {

  import scala.annotation.tailrec

  def bfs(
    startId: String,
    config: TraversalConfig
  )(
    getNode: String => Result[Option[Node]],
    getNeighborIds: (String, Direction) => Result[Seq[String]]
  ): Result[Seq[Node]] =
    getNode(startId).flatMap {
      case None => Right(Seq.empty)
      case Some(_) =>
        @tailrec
        def loop(
          queue: scala.collection.immutable.Queue[(String, Int)],
          visited: Set[String],
          acc: Vector[Node]
        ): Either[LLMError, Vector[Node]] =
          if (queue.isEmpty) Right(acc)
          else {
            val ((currentNodeId, depth), newQueue) = queue.dequeue
            if (visited.contains(currentNodeId) || config.visitedNodeIds.contains(currentNodeId)) {
              loop(newQueue, visited, acc)
            } else {
              getNode(currentNodeId) match {
                case Left(err)   => Left(err)
                case Right(None) => loop(newQueue, visited, acc)
                case Right(Some(currentNode)) =>
                  val newVisited = visited + currentNodeId
                  val newAcc     = acc :+ currentNode
                  if (depth < config.maxDepth) {
                    getNeighborIds(currentNodeId, config.direction) match {
                      case Left(err) => Left(err)
                      case Right(nextIds) =>
                        val nextQueue = nextIds.foldLeft(newQueue) { (q, nextId) =>
                          if (!newVisited.contains(nextId) && !config.visitedNodeIds.contains(nextId))
                            q.enqueue((nextId, depth + 1))
                          else q
                        }
                        loop(nextQueue, newVisited, newAcc)
                    }
                  } else {
                    loop(newQueue, newVisited, newAcc)
                  }
              }
            }
          }
        loop(scala.collection.immutable.Queue((startId, 0)), Set.empty, Vector.empty).map(_.toSeq)
    }
}
