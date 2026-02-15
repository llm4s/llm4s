package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{Graph, Node, Edge}
import org.llm4s.types.Result

trait GraphStore {

  def upsertNode(node: Node): Result[Unit]

  def upsertEdge(edge: Edge): Result[Unit]

  def getNode(id: String): Result[Option[Node]]

  def getNeighbors(
    nodeId: String,
    direction: Direction = Direction.Both
  ): Result[Seq[(Edge, Node)]]

  def query(filter: GraphFilter): Result[Graph]

  def traverse(
    startId: String,
    config: TraversalConfig
  ): Result[Seq[Node]]

  def deleteNode(id: String): Result[Unit]

  def deleteEdge(
    source: String,
    target: String,
    relationship: String
  ): Result[Unit]

  def loadAll(): Result[Graph]

  def stats(): Result[GraphStats]
}
