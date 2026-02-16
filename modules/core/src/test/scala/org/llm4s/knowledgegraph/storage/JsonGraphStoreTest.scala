package org.llm4s.knowledgegraph.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import java.nio.file.Files
import java.nio.charset.StandardCharsets

class JsonGraphStoreTest extends AnyFunSuite with Matchers {

  // --------------------------------------------------
  // Legacy save/load persistence tests
  // --------------------------------------------------

  test("JsonGraphStore should save and load graph correctly") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val node1 = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
      val node2 = Node("2", "Person", Map.empty[String, ujson.Value])
      val edge1 = Edge("1", "2", "KNOWS")
      val graph = Graph(Map("1" -> node1, "2" -> node2), List(edge1))

      val store = new JsonGraphStore(tempFile)

      store.save(graph) shouldBe Right(())
      store.load() shouldBe Right(graph)
    } finally Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore should fail loading non-existent file") {
    val tempFile = Files.createTempFile("graph", ".json")
    Files.delete(tempFile)

    val store = new JsonGraphStore(tempFile)
    store.load().isLeft shouldBe true
  }

  test("JsonGraphStore should handle missing properties field") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val jsonWithoutProps =
        """{
          |  "nodes": [
          |    {"id": "1", "label": "Person"},
          |    {"id": "2", "label": "Person"}
          |  ],
          |  "edges": [
          |    {"source": "1", "target": "2", "relationship": "KNOWS"}
          |  ]
          |}""".stripMargin

      Files.write(tempFile, jsonWithoutProps.getBytes(StandardCharsets.UTF_8))

      val store  = new JsonGraphStore(tempFile)
      val loaded = store.load()

      loaded match {
        case Right(graph) =>
          graph.nodes("1").properties shouldBe empty
          graph.edges.head.properties shouldBe empty
        case Left(err) =>
          fail(s"Expected success but got: $err")
      }
    } finally Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore should fail loading malformed JSON") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val malformedJson =
        """{"nodes": [{"id": "1", "label": "Person"}""" // broken JSON
      Files.write(tempFile, malformedJson.getBytes(StandardCharsets.UTF_8))

      val store = new JsonGraphStore(tempFile)
      store.load().isLeft shouldBe true
    } finally Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore should save and load empty graph") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val emptyGraph = Graph(Map.empty, List.empty)
      val store      = new JsonGraphStore(tempFile)

      store.save(emptyGraph) shouldBe Right(())
      store.load() shouldBe Right(emptyGraph)
    } finally Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore should handle properties with special characters") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val node1 = Node(
        "1",
        "Person",
        Map(
          "name"  -> ujson.Str("Alice \"Ace\" O'Brien"),
          "bio"   -> ujson.Str("Line1\nLine2\tTabbed"),
          "emoji" -> ujson.Str("👋🌍")
        )
      )
      val node2 = Node("2", "Person", Map.empty[String, ujson.Value])
      val edge1 = Edge(
        "1",
        "2",
        "KNOWS",
        Map(
          "since" -> ujson.Str("2020/01/01"),
          "note"  -> ujson.Str("Met @ café")
        )
      )
      val graph = Graph(Map("1" -> node1, "2" -> node2), List(edge1))

      val store = new JsonGraphStore(tempFile)

      store.save(graph) shouldBe Right(())
      store.load() shouldBe Right(graph)
    } finally Files.deleteIfExists(tempFile)
  }

  // --------------------------------------------------
  // GraphStore API coverage tests
  // --------------------------------------------------

  test("upsertNode should persist node and be retrievable") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)
      val node  = Node("1", "Person", Map.empty)

      store.upsertNode(node) shouldBe Right(())

      store.getNode("1").map(_.map(_.id)) shouldBe Right(Some("1"))
    } finally Files.deleteIfExists(tempFile)
  }

  test("upsertEdge should connect existing nodes") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      store.upsertNode(Node("1", "A", Map.empty))
      store.upsertNode(Node("2", "B", Map.empty))

      store.upsertEdge(Edge("1", "2", "REL")) shouldBe Right(())

      store.getNeighbors("1", Direction.Outgoing).map(_.size) shouldBe Right(1)
    } finally Files.deleteIfExists(tempFile)
  }

  test("getNeighbors should return empty for isolated node") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)
      store.upsertNode(Node("1", "A", Map.empty))

      store.getNeighbors("1", Direction.Both) shouldBe Right(Seq.empty)
    } finally Files.deleteIfExists(tempFile)
  }

  test("traverse should respect maxDepth") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      store.upsertNode(Node("1", "A", Map.empty))
      store.upsertNode(Node("2", "B", Map.empty))
      store.upsertNode(Node("3", "C", Map.empty))

      store.upsertEdge(Edge("1", "2", "REL"))
      store.upsertEdge(Edge("2", "3", "REL"))

      val result = store.traverse("1", TraversalConfig(maxDepth = 1))

      result.map(_.map(_.id).toSet) shouldBe Right(Set("1", "2"))
    } finally Files.deleteIfExists(tempFile)
  }

  test("query should filter by label") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      store.upsertNode(Node("1", "Person", Map.empty))
      store.upsertNode(Node("2", "Company", Map.empty))

      val result = store.query(GraphFilter(label = Some("Person")))

      result.map(_.nodes.size) shouldBe Right(1)
    } finally Files.deleteIfExists(tempFile)
  }

  test("stats should reflect node and edge count") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      store.upsertNode(Node("1", "A", Map.empty))
      store.upsertNode(Node("2", "B", Map.empty))
      store.upsertEdge(Edge("1", "2", "REL"))

      val stats = store.stats()

      stats.map(s => (s.nodeCount, s.edgeCount)) shouldBe Right((2, 1))
    } finally Files.deleteIfExists(tempFile)
  }

  test("traverse should fail if start node does not exist") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)
      store.traverse("missing", TraversalConfig(maxDepth = 1)).isLeft shouldBe true
    } finally Files.deleteIfExists(tempFile)
  }

  test("query should filter by property key and value") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      store.upsertNode(Node("1", "Person", Map("name" -> ujson.Str("Alice"))))
      store.upsertNode(Node("2", "Person", Map("name" -> ujson.Str("Bob"))))

      val result = store.query(
        GraphFilter(propertyKey = Some("name"), propertyValue = Some("Alice"))
      )

      result.map(_.nodes.keySet) shouldBe Right(Set("1"))
    } finally Files.deleteIfExists(tempFile)
  }

  test("deleteEdge should remove specific edge") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      store.upsertNode(Node("1", "A"))
      store.upsertNode(Node("2", "B"))
      store.upsertEdge(Edge("1", "2", "REL"))

      store.deleteEdge("1", "2", "REL") shouldBe Right(())

      store.getNeighbors("1", Direction.Outgoing).map(_.size) shouldBe Right(0)
    } finally Files.deleteIfExists(tempFile)
  }

  test("deleteNode should fail if node does not exist") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)
      store.deleteNode("missing").isLeft shouldBe true
    } finally Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore.upsertEdge should fail if nodes do not exist") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      val result = store.upsertEdge(Edge("1", "2", "REL"))

      result.isLeft shouldBe true
    } finally Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore.getNeighbors should handle Incoming direction") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      store.upsertNode(Node("1", "A", Map.empty))
      store.upsertNode(Node("2", "B", Map.empty))
      store.upsertEdge(Edge("1", "2", "REL"))

      val result = store.getNeighbors("2", Direction.Incoming)

      result.map(_.head._2.id) shouldBe Right("1")
    } finally Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore.query should filter boolean properties") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      store.upsertNode(Node("1", "Person", Map("active" -> ujson.Bool(true))))
      store.upsertNode(Node("2", "Person", Map("active" -> ujson.Bool(false))))

      val result = store.query(
        GraphFilter(propertyKey = Some("active"), propertyValue = Some("true"))
      )

      result.map(_.nodes.keySet) shouldBe Right(Set("1"))
    } finally Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore.query should filter by relationship") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val store = new JsonGraphStore(tempFile)

      store.upsertNode(Node("1", "A", Map.empty))
      store.upsertNode(Node("2", "B", Map.empty))

      store.upsertEdge(Edge("1", "2", "KNOWS"))
      store.upsertEdge(Edge("1", "2", "LIKES"))

      val result = store.query(GraphFilter(relationship = Some("KNOWS")))

      result.map(_.edges.size) shouldBe Right(1)
    } finally Files.deleteIfExists(tempFile)
  }
}
