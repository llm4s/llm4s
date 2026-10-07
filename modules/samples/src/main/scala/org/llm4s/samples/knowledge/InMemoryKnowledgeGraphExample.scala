package org.llm4s.samples.knowledge

import org.llm4s.knowledgegraph.storage.{
  Direction,
  EdgeNodePair,
  GraphFilter,
  GraphStats,
  GraphStore,
  InMemoryGraphStore,
  JsonGraphStore,
  TraversalConfig
}
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.types.{ Result, TryOps }
import org.slf4j.LoggerFactory

import java.nio.file.Files
import scala.util.Try

/**
 * A knowledge graph in memory: build it by hand, look around it, query it, and save it as JSON.
 *
 * Nothing here calls a model or needs an API key, a database or a container (the other knowledge-graph sample,
 * [[Neo4jKnowledgeGraphExample]], needs a running Neo4j). It uses [[org.llm4s.knowledgegraph.storage.InMemoryGraphStore]]
 * from the `llm4s-knowledgegraph` module and shows, in order:
 *
 *  1. building a small company graph with `upsertNode` and `upsertEdge` (an edge needs both of its nodes to exist),
 *  2. neighbours in a direction, with the edge that connects them,
 *  3. a breadth-first traversal limited to a number of hops (the start node is part of the result),
 *  4. a query that returns the sub-graph matching a label and a property,
 *  5. the graph's statistics, and
 *  6. saving the graph with [[org.llm4s.knowledgegraph.storage.JsonGraphStore]] and reading it back from the file.
 *
 * Run it with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.knowledge.InMemoryKnowledgeGraphExample"
 * }}}
 *
 * The graph, for reference (arrows point from source to target):
 * {{{
 * bob   --REPORTS_TO--> alice      bob   --WORKS_AT--> acme     acme --LOCATED_IN--> london
 * carol --REPORTS_TO--> alice      carol --WORKS_AT--> acme     bob  --USES--------> scala
 *                                  alice --WORKS_AT--> acme
 * }}}
 */
object InMemoryKnowledgeGraphExample {

  private val logger = LoggerFactory.getLogger(getClass)

  /** What the sample found: one field per step, so a test can check each. */
  final private[samples] case class Report(
    stats: GraphStats,
    aliceDirectReports: Seq[String],
    aliceEmployer: Seq[String],
    bobWithinOneHop: Seq[String],
    bobWithinTwoHops: Seq[String],
    people: Graph,
    engineers: Graph,
    reloaded: GraphStats
  )

  def main(args: Array[String]): Unit =
    run().fold(
      error => logger.error("[InMemoryKnowledgeGraphExample] Failed: {}", error.formatted),
      report => render(report).foreach(println)
    )

  /** Builds the graph, asks it the questions above and returns the answers. */
  private[samples] def run(): Result[Report] =
    for {
      store         <- buildStore()
      directReports <- store.getNeighbors("alice", Direction.Incoming).map(related(_, "REPORTS_TO"))
      employer      <- store.getNeighbors("alice", Direction.Outgoing).map(related(_, "WORKS_AT"))
      oneHop        <- reachable(store, "bob", hops = 1)
      twoHops       <- reachable(store, "bob", hops = 2)
      people        <- store.query(GraphFilter(nodeLabel = Some("Person")))
      engineers <- store.query(
        GraphFilter(nodeLabel = Some("Person"), propertyKey = Some("role"), propertyValue = Some("Engineer"))
      )
      stats    <- store.stats()
      graph    <- store.loadAll()
      reloaded <- saveAndReload(graph)
    } yield Report(stats, directReports, employer, oneHop, twoHops, people, engineers, reloaded)

  /** Step 1: the graph. Nodes first, because an edge is refused unless both of its ends exist. */
  private[samples] def buildStore(): Result[GraphStore] = {
    val store = new InMemoryGraphStore()

    val nodes = Seq(
      person("alice", "Alice", "Manager"),
      person("bob", "Bob", "Engineer"),
      person("carol", "Carol", "Engineer"),
      Node("acme", "Organization", Map("name" -> ujson.Str("Acme Corporation"))),
      Node("london", "Location", Map("name" -> ujson.Str("London"))),
      Node("scala", "Technology", Map("name" -> ujson.Str("Scala")))
    )

    val edges = Seq(
      Edge("bob", "alice", "REPORTS_TO"),
      Edge("carol", "alice", "REPORTS_TO"),
      Edge("alice", "acme", "WORKS_AT"),
      Edge("bob", "acme", "WORKS_AT"),
      Edge("carol", "acme", "WORKS_AT"),
      Edge("acme", "london", "LOCATED_IN"),
      Edge("bob", "scala", "USES")
    )

    for {
      _ <- inOrder(nodes)(store.upsertNode)
      _ <- inOrder(edges)(store.upsertEdge)
    } yield store
  }

  /** Step 3: the ids of the nodes within `hops` edges of `start`, following edges forwards, nearest first. */
  private def reachable(store: GraphStore, start: String, hops: Int): Result[Seq[String]] =
    store
      .traverse(start, TraversalConfig(maxDepth = hops, direction = Direction.Outgoing))
      .map(_.map(_.id))

  /** Step 2: the neighbours reached over one kind of relationship. */
  private def related(neighbours: Seq[EdgeNodePair], relationship: String): Seq[String] =
    neighbours.filter(_.edge.relationship == relationship).map(_.node.id)

  /**
   * Step 6: writes the graph to a JSON file and reads it back with a second store, which only knows the file.
   *
   * The file lives in a directory made for this call, because [[JsonGraphStore]] creates a missing file but cannot read
   * an empty one, which is what a freshly made temporary file is. Both are removed afterwards.
   */
  private def saveAndReload(graph: Graph): Result[GraphStats] =
    Try(Files.createTempDirectory("llm4s-kg-example")).toResult.flatMap { directory =>
      val file = directory.resolve("graph.json")
      val outcome = for {
        _     <- new JsonGraphStore(file).save(graph)
        stats <- new JsonGraphStore(file).stats()
      } yield stats
      List(file, directory).foreach(path => Files.deleteIfExists(path))
      outcome
    }

  private def person(id: String, name: String, role: String): Node =
    Node(id, "Person", Map("name" -> ujson.Str(name), "role" -> ujson.Str(role)))

  /** Runs `step` over `items` in order and stops at the first failure. */
  private def inOrder[A](items: Seq[A])(step: A => Result[Unit]): Result[Unit] =
    items.foldLeft[Result[Unit]](Right(()))((outcome, item) => outcome.flatMap(_ => step(item)))

  /** The lines the sample prints. */
  private[samples] def render(report: Report): Seq[String] = {
    def names(graph: Graph): String = graph.nodes.keys.toSeq.sorted.mkString(", ")

    Seq(
      "In-memory knowledge graph",
      "",
      s"1. Built ${report.stats.nodeCount} nodes and ${report.stats.edgeCount} edges; the most connected node is " +
        s"${report.stats.densestNodeId.getOrElse("(none)")}",
      s"2. alice's direct reports: ${report.aliceDirectReports.mkString(", ")}; alice works at: ${report.aliceEmployer
          .mkString(", ")}",
      s"3. Within 1 hop of bob: ${report.bobWithinOneHop.mkString(", ")}",
      s"   Within 2 hops of bob: ${report.bobWithinTwoHops.mkString(", ")}",
      s"4. People: ${names(report.people)} (${report.people.edges.size} edges between them)",
      s"   Engineers: ${names(report.engineers)} (${report.engineers.edges.size} edges between them)",
      f"5. Average degree: ${report.stats.averageDegree}%.2f",
      s"6. Saved to JSON and read back: ${report.reloaded.nodeCount} nodes and ${report.reloaded.edgeCount} edges"
    )
  }
}
