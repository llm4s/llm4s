package org.llm4s.knowledgegraph.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import java.nio.file.Files
import java.nio.charset.StandardCharsets

class JsonGraphStoreTest extends AnyFunSuite with Matchers {

  test("JsonGraphStore should save and load graph correctly") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val node1 = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
      val node2 = Node("2", "Person", Map.empty[String, ujson.Value])
      val edge1 = Edge("1", "2", "KNOWS")
      val graph = Graph(Map("1" -> node1, "2" -> node2), List(edge1))

      val store = new JsonGraphStore(tempFile)

      store.save(graph) should be(Right(()))

      val loaded = store.load()
      loaded should be(Right(graph))
    } finally
      Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore should fail loading non-existent file") {
    val tempFile = Files.createTempFile("graph", ".json")
    Files.delete(tempFile) // Ensure it doesn't exist

    val store = new JsonGraphStore(tempFile)
    // JsonGraphStore initializes with empty graph if file doesn't exist
    val loaded = store.load()
    loaded should be(Right(Graph(Map.empty, List.empty)))
    Files.exists(tempFile) shouldBe true // File should be created
    Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore should handle missing properties field") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      // Write JSON without properties field
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

      loaded should be(a[Right[_, _]])
      val graph = loaded.toOption.get
      graph.nodes("1").properties shouldBe empty
      graph.edges.head.properties shouldBe empty
    } finally
      Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore should fail loading malformed JSON") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val malformedJson = """{"nodes": [{"id": "1", "label": "Person"}""" // Missing closing braces
      Files.write(tempFile, malformedJson.getBytes(StandardCharsets.UTF_8))

      val store  = new JsonGraphStore(tempFile)
      val loaded = store.load()

      loaded should be(a[Left[_, _]])
    } finally
      Files.deleteIfExists(tempFile)
  }

  test("JsonGraphStore should save and load empty graph") {
    val tempFile = Files.createTempFile("graph", ".json")
    try {
      val emptyGraph = Graph(Map.empty, List.empty)
      val store      = new JsonGraphStore(tempFile)

      store.save(emptyGraph) should be(Right(()))

      val loaded = store.load()
      loaded should be(Right(emptyGraph))
    } finally
      Files.deleteIfExists(tempFile)
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

      store.save(graph) should be(Right(()))

      val loaded = store.load()
      loaded should be(Right(graph))
    } finally
      Files.deleteIfExists(tempFile)
  }
}
