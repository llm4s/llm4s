package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{Graph, Node, Edge}
import org.llm4s.types.Result
import org.llm4s.error.{ConfigurationError, ProcessingError}
import org.llm4s.types.TryOps

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import scala.util.Try

class JsonGraphStore(path: Path) extends GraphStore {

  // =========================================================
  // Legacy public API (kept for backward compatibility)
  // =========================================================

  def save(graph: Graph): Result[Unit] =
    saveToFile(graph)

  def load(): Result[Graph] =
    loadFromFile()

  // =========================================================
  // Internal Persistence Helpers
  // =========================================================

  private def saveToFile(graph: Graph): Result[Unit] =
    Try {
      val nodesJson = graph.nodes.values.map { node =>
        ujson.Obj(
          "id" -> node.id,
          "label" -> node.label,
          "properties" -> ujson.Obj.from(node.properties)
        )
      }

      val edgesJson = graph.edges.map { edge =>
        ujson.Obj(
          "source" -> edge.source,
          "target" -> edge.target,
          "relationship" -> edge.relationship,
          "properties" -> ujson.Obj.from(edge.properties)
        )
      }

      val json = ujson.Obj(
        "nodes" -> ujson.Arr.from(nodesJson),
        "edges" -> ujson.Arr.from(edgesJson)
      )

      Files.write(path, json.render(indent = 2).getBytes(StandardCharsets.UTF_8))
      ()
    }.toResult

  private def loadFromFile(): Result[Graph] =
    if (!Files.exists(path)) {
      Left(ConfigurationError(s"Graph file not found: $path"))
    } else {
      Try {
        val content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        val json = ujson.read(content)

        val nodes = json("nodes").arr
          .map { n =>
            val id = n("id").str
            val label = n("label").str
            val props =
              if (n.obj.contains("properties"))
                n("properties").obj.toMap
              else Map.empty[String, ujson.Value]

            Node(id, label, props)
          }
          .map(n => n.id -> n)
          .toMap

        val edges = json("edges").arr.map { e =>
          val source = e("source").str
          val target = e("target").str
          val rel = e("relationship").str
          val props =
            if (e.obj.contains("properties"))
              e("properties").obj.toMap
            else Map.empty[String, ujson.Value]

          Edge(source, target, rel, props)
        }.toList

        val graph = Graph(nodes, edges)
        graph.validate().map(_ => graph)
      }.toResult.flatten
    }

  // =========================================================
  // New GraphStore Trait Implementation
  // =========================================================

  override def upsertNode(node: Node): Result[Unit] =
    loadFromFile().orElse(Right(Graph.empty)).flatMap { g =>
      saveToFile(g.addNode(node))
    }

  override def upsertEdge(edge: Edge): Result[Unit] =
    loadFromFile().orElse(Right(Graph.empty)).flatMap { g =>
      if (!g.hasNode(edge.source) || !g.hasNode(edge.target))
        Left(ProcessingError("missing_node", "Edge endpoints must exist"))
      else
        saveToFile(g.addEdge(edge))
    }

  override def getNode(id: String): Result[Option[Node]] =
    loadFromFile().map(_.nodes.get(id))

  override def getNeighbors(
      nodeId: String,
      direction: Direction
  ): Result[Seq[(Edge, Node)]] =
    loadFromFile().map { g =>
      g.edges.flatMap { e =>
        direction match {
          case Direction.Outgoing if e.source == nodeId =>
            g.nodes.get(e.target).map(n => (e, n))
          case Direction.Incoming if e.target == nodeId =>
            g.nodes.get(e.source).map(n => (e, n))
          case Direction.Both if e.source == nodeId =>
            g.nodes.get(e.target).map(n => (e, n))
          case Direction.Both if e.target == nodeId =>
            g.nodes.get(e.source).map(n => (e, n))
          case _ => None
        }
      }
    }

  override def query(filter: GraphFilter): Result[Graph] =
    loadFromFile().map { g =>
      val filteredNodes =
        filter.label match {
          case Some(label) =>
            g.nodes.filter(_._2.label == label)
          case None => g.nodes
        }

      val filteredEdges =
        filter.relationship match {
          case Some(rel) =>
            g.edges.filter(_.relationship == rel)
          case None => g.edges
        }

      Graph(filteredNodes, filteredEdges)
    }

  override def traverse(
      startId: String,
      config: TraversalConfig
  ): Result[Seq[Node]] =
    loadFromFile().map { g =>
      if (!g.hasNode(startId)) Seq.empty
      else {
        var visited = Set(startId)
        var frontier = Set(startId)

        for (_ <- 1 to config.maxDepth) {
          val next = frontier.flatMap { id =>
            g.getNeighbors(id).map(_.id)
          } -- visited

          visited ++= next
          frontier = next
        }

        visited.flatMap(g.nodes.get).toSeq
      }
    }

  override def deleteNode(id: String): Result[Unit] =
    loadFromFile().flatMap { g =>
      val newGraph =
        Graph(
          g.nodes - id,
          g.edges.filterNot(e => e.source == id || e.target == id)
        )
      saveToFile(newGraph)
    }

  override def deleteEdge(
      source: String,
      target: String,
      relationship: String
  ): Result[Unit] =
    loadFromFile().flatMap { g =>
      val newGraph =
        Graph(
          g.nodes,
          g.edges.filterNot(e =>
            e.source == source &&
            e.target == target &&
            e.relationship == relationship
          )
        )
      saveToFile(newGraph)
    }

  override def loadAll(): Result[Graph] =
    loadFromFile()

  override def stats(): Result[GraphStats] =
    loadAll().map(g => GraphStats(g.nodes.size, g.edges.size))
}
