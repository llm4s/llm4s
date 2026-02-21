package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import org.llm4s.types.TryOps

import java.nio.file.{ Files, Path }
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * JSON-based implementation of GraphStore.
 *
 * Thread-safety note: This implementation is NOT thread-safe.
 * For concurrent access, wrap with synchronization or use a thread-safe alternative.
 *
 * @param path The file path to save/load the graph
 */
class JsonGraphStore(path: Path) extends GraphStore {
  // In-memory cache of the graph - mutable but document this limitation
  private var cachedGraph: Option[Graph] = None

  /** Load from file once */
  private def ensureLoaded(): Result[Graph] =
    cachedGraph match {
      case Some(g) => Right(g)
      case None =>
        load().map { g =>
          cachedGraph = Some(g)
          g
        }
    }

  override def upsertNode(node: Node): Result[Unit] =
    for {
      graph <- ensureLoaded()
      updated = graph.addNode(node)
      _ <- persistGraph(updated)
    } yield {
      cachedGraph = Some(updated)
      ()
    }

  override def upsertEdge(edge: Edge): Result[Unit] =
    for {
      graph <- ensureLoaded()
      // Validate both endpoints exist
      _ <-
        if (graph.hasNode(edge.source) && graph.hasNode(edge.target)) Right(())
        else
          Left(
            ProcessingError(
              "edge_validation",
              s"Edge(${edge.source}->${edge.target}): source or target node does not exist"
            )
          )
      // Enforce upsert semantics: remove any existing edge with the same
      // source, target, and relationship before adding the new edge
      updatedEdges = graph.edges.filterNot { e =>
        e.source == edge.source &&
        e.target == edge.target &&
        e.relationship == edge.relationship
      } :+ edge
      updated = graph.copy(edges = updatedEdges)
      _ <- persistGraph(updated)
    } yield {
      cachedGraph = Some(updated)
      ()
    }

  override def getNode(id: String): Result[Option[Node]] =
    for {
      graph <- ensureLoaded()
    } yield graph.nodes.get(id)

  override def getNeighbors(nodeId: String, direction: Direction = Direction.Both): Result[Seq[EdgeNodePair]] =
    for {
      graph <- ensureLoaded()
    } yield
      if (!graph.hasNode(nodeId)) {
        Seq.empty
      } else {
        val edges = direction match {
          case Direction.Outgoing => graph.getOutgoingEdges(nodeId)
          case Direction.Incoming => graph.getIncomingEdges(nodeId)
          case Direction.Both     => graph.getConnectedEdges(nodeId)
        }
        edges.flatMap { edge =>
          val neighborId = if (edge.source == nodeId) edge.target else edge.source
          graph.nodes.get(neighborId).map(node => EdgeNodePair(edge, node))
        }
      }

  override def query(filter: GraphFilter): Result[Graph] =
    for {
      graph <- ensureLoaded()
    } yield {
      val nodesByLabel =
        filter.nodeLabel.map(label => graph.findNodesByLabel(label).map(n => n.id -> n).toMap).getOrElse(graph.nodes)

      val nodesByProperty = (filter.propertyKey, filter.propertyValue) match {
        case (Some(key), Some(value)) =>
          graph
            .findNodesByProperty(key, value)
            .map(n => n.id -> n)
            .toMap
            .view
            .filterKeys(nodesByLabel.contains)
            .toMap
        case _ => nodesByLabel
      }

      val filteredEdges = filter.relationshipType match {
        case Some(rel) =>
          graph.edges.filter(e =>
            e.relationship == rel && nodesByProperty.contains(e.source) && nodesByProperty.contains(e.target)
          )
        case None =>
          graph.edges.filter(e => nodesByProperty.contains(e.source) && nodesByProperty.contains(e.target))
      }

      Graph(nodesByProperty.toMap, filteredEdges)
    }

  override def traverse(startId: String, config: TraversalConfig = TraversalConfig()): Result[Seq[Node]] =
    for {
      graph <- ensureLoaded()
      traversed <- GraphTraversal.bfs(startId, config)(
        nodeId => Right(graph.nodes.get(nodeId)),
        (currentNodeId, direction) => {
          val neighbors = direction match {
            case Direction.Outgoing => graph.getOutgoingEdges(currentNodeId)
            case Direction.Incoming => graph.getIncomingEdges(currentNodeId)
            case Direction.Both     => graph.getConnectedEdges(currentNodeId)
          }

          val nextIds = neighbors.flatMap { edge =>
            val targetId = direction match {
              case Direction.Outgoing => edge.target
              case Direction.Incoming => edge.source
              case Direction.Both =>
                if (edge.source == currentNodeId) edge.target else edge.source
            }
            graph.nodes.get(targetId).map(_.id)
          }

          Right(nextIds)
        }
      )
    } yield traversed

  override def deleteNode(id: String): Result[Unit] =
    for {
      graph <- ensureLoaded()
      updated = graph.copy(
        nodes = graph.nodes - id,
        edges = graph.edges.filter(e => e.source != id && e.target != id)
      )
      _ <- persistGraph(updated)
    } yield {
      cachedGraph = Some(updated)
      ()
    }

  override def deleteEdge(source: String, target: String, relationship: String): Result[Unit] =
    for {
      graph <- ensureLoaded()
      updated = graph.copy(
        edges = graph.edges.filter(e => !(e.source == source && e.target == target && e.relationship == relationship))
      )
      _ <- persistGraph(updated)
    } yield {
      cachedGraph = Some(updated)
      ()
    }

  override def loadAll(): Result[Graph] =
    ensureLoaded()

  override def stats(): Result[GraphStats] =
    for {
      graph <- ensureLoaded()
    } yield {
      val nodeCount = graph.nodes.size.toLong
      val edgeCount = graph.edges.size.toLong
      val avgDegree = if (nodeCount > 0) (edgeCount * 2.0) / nodeCount else 0.0

      val densestNodeId = graph.nodes.keys
        .maxByOption(nodeId => graph.getConnectedEdges(nodeId).size)

      GraphStats(nodeCount, edgeCount, avgDegree, densestNodeId)
    }

  /** Persist graph to file */
  private def persistGraph(graph: Graph): Result[Unit] = Try {
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

  def load(): Result[Graph] =
    if (!Files.exists(path)) {
      // Initialize with empty graph if file doesn't exist
      val emptyGraph = Graph(Map.empty, List.empty)
      persistGraph(emptyGraph).map(_ => emptyGraph)
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

  /** Legacy method for backward compatibility */
  def save(graph: Graph): Result[Unit] = persistGraph(graph)
}
