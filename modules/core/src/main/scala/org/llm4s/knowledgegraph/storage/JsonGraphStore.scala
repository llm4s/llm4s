package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }

import org.llm4s.types.Result
import org.llm4s.error.ConfigurationError
import org.llm4s.types.TryOps

import java.nio.file.{ Files, Path }
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * Interface for persisting Knowledge Graphs.
 */
trait GraphStore {
  def save(graph: Graph): Result[Unit]
  def load(): Result[Graph]
}

/**
 * JSON-based implementation of GraphStore.
 *
 * @param path The file path to save/load the graph
 */
class JsonGraphStore(path: Path) extends GraphStore {

  override def save(graph: Graph): Result[Unit] = Try {
    val nodesJson = graph.nodes.values.map { node =>
      ujson.Obj(
        "id"         -> node.id,
        "label"      -> node.label,
        "properties" -> ujson.Obj.from(node.properties)
      )
    }

    val edgesJson = graph.edges.map { edge =>
      ujson.Obj(
        "source"       -> edge.source,
        "target"       -> edge.target,
        "relationship" -> edge.relationship,
        "properties"   -> ujson.Obj.from(edge.properties)
      )
    }

    val json = ujson.Obj(
      "nodes" -> ujson.Arr.from(nodesJson),
      "edges" -> ujson.Arr.from(edgesJson)
    )

    Files.write(path, json.render(indent = 2).getBytes(StandardCharsets.UTF_8))
    ()
  }.toResult

  override def load(): Result[Graph] =
    if (!Files.exists(path)) {
      Left(ConfigurationError(s"Graph file not found: $path"))
    } else {
      Try {
        val content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        val json    = ujson.read(content)

        val nodes = json("nodes").arr
          .map { n =>
            val id    = n("id").str
            val label = n("label").str
            val props = if (n.obj.contains("properties")) {
              n("properties").obj.toMap
            } else {
              Map.empty[String, ujson.Value]
            }
            Node(id, label, props)
          }
          .map(n => n.id -> n)
          .toMap

        val edges = json("edges").arr.map { e =>
          val source = e("source").str
          val target = e("target").str
          val rel    = e("relationship").str
          val props = if (e.obj.contains("properties")) {
            e("properties").obj.toMap
          } else {
            Map.empty[String, ujson.Value]
          }
          Edge(source, target, rel, props)
        }.toList

        val graph = Graph(nodes, edges)

        // Validate graph integrity at load boundary
        graph.validate().map(_ => graph)
      }.toResult.flatten
    }
}
