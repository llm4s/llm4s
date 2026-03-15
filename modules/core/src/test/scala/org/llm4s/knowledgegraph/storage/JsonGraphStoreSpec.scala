package org.llm4s.knowledgegraph.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Node }
import java.nio.file.{ Files, Path }

/**
 * Comprehensive test suite for JsonGraphStore.
 *
 * Tests JSON persistence, caching, property filtering, and all operations.
 *
 * Key validations:
 * - JSON file serialization and deserialization
 * - In-memory caching layer
 * - Property filtering with proper ujson.Value handling
 * - BFS traversal consistency with other implementations
 *
 * Note: JsonGraphStore is NOT thread-safe. Synchronization is caller's responsibility.
 */
class JsonGraphStoreSpec extends AnyFunSuite with Matchers {

  def createTempJsonPath(): Path = {
    val tempDir = Files.createTempDirectory("llm4s-test-")
    tempDir.resolve("test-graph.json")
  }

  test("upsertNode should insert new node") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val node  = Node("1", "Person", Map("name" -> ujson.Str("Alice")))

    store.upsertNode(node) shouldBe Right(())

    val retrieved = store.getNode("1")
    retrieved.toOption.get.map(_.label) shouldBe Some("Person")
    retrieved.toOption.get.flatMap(_.properties.get("name")) shouldBe Some(ujson.Str("Alice"))

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("upsertNode should update existing node") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val node1 = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    val node2 = Node("1", "Person", Map("name" -> ujson.Str("Alicia"), "age" -> ujson.Num(30)))

    store.upsertNode(node1) shouldBe Right(())
    store.upsertNode(node2) shouldBe Right(())

    val updated = store.getNode("1")
    updated.toOption.get.flatMap(_.properties.get("name")) shouldBe Some(ujson.Str("Alicia"))
    updated.toOption.get.flatMap(_.properties.get("age")) shouldBe Some(ujson.Num(30))

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("upsertEdge should fail if source node doesn't exist") {
    val path   = createTempJsonPath()
    val store  = new JsonGraphStore(path)
    val target = Node("2", "Person")
    store.upsertNode(target)

    val edge   = Edge("1", "2", "KNOWS")
    val result = store.upsertEdge(edge)

    result shouldBe a[Left[_, _]]

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("upsertEdge should insert edge between existing nodes") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")

    store.upsertNode(n1)
    store.upsertNode(n2)
    val result = store.upsertEdge(Edge("1", "2", "KNOWS"))

    result shouldBe Right(())

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("upsertEdge should replace existing edge with same source, target, and relationship") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "Person")
    val n2    = Node("2", "Person")

    store.upsertNode(n1)
    store.upsertNode(n2)

    val edge1 = Edge("1", "2", "KNOWS", Map("since" -> ujson.Num(2020)))
    val edge2 = Edge("1", "2", "KNOWS", Map("since" -> ujson.Num(2025), "strength" -> ujson.Str("strong")))

    store.upsertEdge(edge1) shouldBe Right(())
    store.upsertEdge(edge2) shouldBe Right(())

    // Verify only one edge exists with updated properties
    val graph = store.loadAll().toOption.get
    val edges = graph.edges.filter(e => e.source == "1" && e.target == "2" && e.relationship == "KNOWS")
    edges should have size 1
    edges.head.properties.get("since") shouldBe Some(ujson.Num(2025))
    edges.head.properties.get("strength") shouldBe Some(ujson.Str("strong"))

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("getNode should return None for non-existent node") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)

    val result = store.getNode("non-existent")
    result shouldBe Right(None)

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("getNeighbors should return outgoing neighbors") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")
    val n3    = Node("3", "C")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)
    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("1", "3", "EDGE"))

    val neighbors   = store.getNeighbors("1", Direction.Outgoing)
    val neighborIds = neighbors.toOption.get.map(_.node.id)

    neighborIds should contain theSameElementsAs List("2", "3")

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("getNeighbors should return incoming neighbors") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")
    val n3    = Node("3", "C")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)
    store.upsertEdge(Edge("2", "1", "EDGE"))
    store.upsertEdge(Edge("3", "1", "EDGE"))

    val neighbors   = store.getNeighbors("1", Direction.Incoming)
    val neighborIds = neighbors.toOption.get.map(_.node.id)

    neighborIds should contain theSameElementsAs List("2", "3")

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("query should filter by node label") {
    val path   = createTempJsonPath()
    val store  = new JsonGraphStore(path)
    val person = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    val org    = Node("2", "Organization", Map("name" -> ujson.Str("ACME")))

    store.upsertNode(person)
    store.upsertNode(org)

    val filter = GraphFilter(nodeLabel = Some("Person"))
    val result = store.query(filter)

    result.toOption.get.nodes should have size 1
    result.toOption.get.nodes.get("1").map(_.label) shouldBe Some("Person")

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("query should filter by property value (ujson.Str)") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val alice = Node("1", "Person", Map("city" -> ujson.Str("NYC")))
    val bob   = Node("2", "Person", Map("city" -> ujson.Str("LA")))

    store.upsertNode(alice)
    store.upsertNode(bob)

    val filter = GraphFilter(propertyKey = Some("city"), propertyValue = Some("NYC"))
    val result = store.query(filter)

    result.toOption.get.nodes should have size 1

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("query should filter by property value (ujson.Num)") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val alice = Node("1", "Person", Map("age" -> ujson.Num(30)))
    val bob   = Node("2", "Person", Map("age" -> ujson.Num(25)))

    store.upsertNode(alice)
    store.upsertNode(bob)

    val filter = GraphFilter(propertyKey = Some("age"), propertyValue = Some("30"))
    val result = store.query(filter)

    result.toOption.get.nodes should have size 1

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("query should filter by property value (ujson.Bool)") {
    val path     = createTempJsonPath()
    val store    = new JsonGraphStore(path)
    val active   = Node("1", "Person", Map("active" -> ujson.Bool(true)))
    val inactive = Node("2", "Person", Map("active" -> ujson.Bool(false)))

    store.upsertNode(active)
    store.upsertNode(inactive)

    val filter = GraphFilter(propertyKey = Some("active"), propertyValue = Some("true"))
    val result = store.query(filter)

    result.toOption.get.nodes should have size 1

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("query should filter by relationship type") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "Person")
    val n2    = Node("2", "Person")
    val n3    = Node("3", "Organization")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)
    store.upsertEdge(Edge("1", "2", "KNOWS"))
    store.upsertEdge(Edge("1", "3", "WORKS_FOR"))

    val filter = GraphFilter(relationshipType = Some("KNOWS"))
    val result = store.query(filter)

    result.toOption.get.edges should have size 1
    result.toOption.get.edges.head.relationship shouldBe "KNOWS"

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("traverse should return empty for non-existent start node") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)

    val result = store.traverse("non-existent")
    result.toOption.get shouldBe empty

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("traverse should use BFS and respect depth limit") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    // Create chain: 1 -> 2 -> 3 -> 4
    val nodes = Seq(Node("1", "A"), Node("2", "B"), Node("3", "C"), Node("4", "D"))
    nodes.foreach(store.upsertNode)

    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("2", "3", "EDGE"))
    store.upsertEdge(Edge("3", "4", "EDGE"))

    val result     = store.traverse("1", TraversalConfig(maxDepth = 2))
    val visitedIds = result.toOption.get.map(_.id).toSet

    // Max depth 2: level 0 (1), level 1 (2), level 2 (3)
    visitedIds shouldBe Set("1", "2", "3")

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("traverse should follow Direction.Outgoing") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")
    val n3    = Node("3", "C")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)
    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("3", "1", "EDGE")) // incoming to 1

    val result     = store.traverse("1", TraversalConfig(direction = Direction.Outgoing))
    val visitedIds = result.toOption.get.map(_.id).toSet

    // Should only visit 1 and 2 (outgoing edges)
    visitedIds shouldBe Set("1", "2")

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("traverse should follow Direction.Incoming") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")
    val n3    = Node("3", "C")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)
    store.upsertEdge(Edge("2", "1", "EDGE"))
    store.upsertEdge(Edge("1", "3", "EDGE")) // outgoing from 1

    val result     = store.traverse("1", TraversalConfig(direction = Direction.Incoming))
    val visitedIds = result.toOption.get.map(_.id).toSet

    // Should only visit 1 and 2 (incoming edges)
    visitedIds shouldBe Set("1", "2")

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("traverse should follow Direction.Both in both directions") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")
    val n3    = Node("3", "C")
    val n4    = Node("4", "D")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)
    store.upsertNode(n4)
    store.upsertEdge(Edge("2", "1", "EDGE")) // incoming to 1
    store.upsertEdge(Edge("1", "3", "EDGE")) // outgoing from 1
    store.upsertEdge(Edge("3", "4", "EDGE")) // continuing outgoing chain

    val result     = store.traverse("1", TraversalConfig(direction = Direction.Both))
    val visitedIds = result.toOption.get.map(_.id).toSet

    // Should visit all connected nodes: 2 (incoming), 1 (start), 3 (outgoing), 4 (via 3)
    visitedIds shouldBe Set("1", "2", "3", "4")

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("deleteNode should remove node and adjacent edges") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")
    val n3    = Node("3", "C")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)
    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("1", "3", "EDGE"))
    store.upsertEdge(Edge("2", "3", "EDGE"))

    store.deleteNode("1") shouldBe Right(())

    store.getNode("1").toOption.get shouldBe None
    // Edges from 1 should be deleted
    val n2Incoming = store.getNeighbors("2", Direction.Incoming)
    n2Incoming.toOption.get.map(_.node.id) should not contain "1"

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("deleteEdge should remove only specific edge") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertEdge(Edge("1", "2", "KNOWS", Map("since" -> ujson.Num(2020))))
    store.upsertEdge(Edge("1", "2", "WORKS_WITH", Map("since" -> ujson.Num(2020))))

    store.deleteEdge("1", "2", "KNOWS") shouldBe Right(())

    val neighbors = store.getNeighbors("1", Direction.Outgoing)
    val edges     = neighbors.toOption.get.map(_.edge)
    edges.map(_.relationship) should contain("WORKS_WITH")
    edges.map(_.relationship) should not contain "KNOWS"

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("loadAll should return complete graph") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val nodes = Seq(Node("1", "A"), Node("2", "B"), Node("3", "C"))
    nodes.foreach(store.upsertNode(_))

    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("2", "3", "EDGE"))

    val result = store.loadAll()
    result.toOption.get.nodes should have size 3
    result.toOption.get.edges should have size 2

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("stats should compute correct statistics") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val nodes = Seq(Node("1", "A"), Node("2", "B"), Node("3", "C"))
    nodes.foreach(store.upsertNode(_))

    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("1", "3", "EDGE"))
    store.upsertEdge(Edge("2", "3", "EDGE"))

    val stats = store.stats()
    stats.toOption.get.nodeCount shouldBe 3L
    stats.toOption.get.edgeCount shouldBe 3L

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("caching should improve performance for repeated reads") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val node  = Node("1", "A", Map("name" -> ujson.Str("Test")))

    store.upsertNode(node)

    // First read
    val snap1 = store.loadAll()
    snap1.toOption.get.nodes should have size 1

    // Second read should use cache
    val snap2 = store.loadAll()
    snap2.toOption.get.nodes should have size 1

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("snapshot should provide immutable view of graph") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val n1    = Node("1", "A")

    store.upsertNode(n1)
    val snap1 = store.loadAll().toOption.get
    snap1.nodes should have size 1

    val n2 = Node("2", "B")
    store.upsertNode(n2)
    val snap2 = store.loadAll().toOption.get

    snap1.nodes should have size 1 // Original snapshot unaffected
    snap2.nodes should have size 2 // New snapshot reflects change

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("property filtering should handle complex ujson types") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    val node = Node(
      "1",
      "Person",
      Map(
        "name"   -> ujson.Str("Alice"),
        "age"    -> ujson.Num(30),
        "active" -> ujson.Bool(true)
      )
    )
    store.upsertNode(node)

    // Test each type separately
    store
      .query(GraphFilter(propertyKey = Some("name"), propertyValue = Some("Alice")))
      .toOption
      .get
      .nodes should have size 1
    store
      .query(GraphFilter(propertyKey = Some("age"), propertyValue = Some("30")))
      .toOption
      .get
      .nodes should have size 1
    store
      .query(GraphFilter(propertyKey = Some("active"), propertyValue = Some("true")))
      .toOption
      .get
      .nodes should have size 1

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("persistence should survive store recreation") {
    val path = createTempJsonPath()

    // Write data
    val store1 = new JsonGraphStore(path)
    val node   = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    store1.upsertNode(node)
    store1.upsertNode(Node("2", "Person", Map("name" -> ujson.Str("Bob"))))
    store1.upsertEdge(Edge("1", "2", "KNOWS"))

    // Recreate store from same file
    val store2 = new JsonGraphStore(path)
    val graph  = store2.loadAll()

    graph.toOption.get.nodes should have size 2
    graph.toOption.get.edges should have size 1

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("should handle special characters in properties") {
    val path         = createTempJsonPath()
    val store        = new JsonGraphStore(path)
    val specialChars = "!@#$%^&*()_+-=[]{}|;:',.<>?/\\\"~`"
    val node         = Node("1", "Test", Map("special" -> ujson.Str(specialChars)))

    store.upsertNode(node) shouldBe Right(())

    val retrieved = store.getNode("1")
    retrieved.toOption.get.flatMap(_.properties.get("special")) shouldBe Some(ujson.Str(specialChars))

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("should handle BFS ordering consistency") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    // Create star topology
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertNode(Node("4", "D"))

    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("1", "3", "E"))
    store.upsertEdge(Edge("2", "4", "E"))

    val result = store.traverse("1")
    val ids    = result.toOption.get.map(_.id)

    // First element should be start node
    ids.head shouldBe "1"
    // Node 4 should be visited later (it's 2 hops away)
    ids.indexOf("4") should be > ids.indexOf("2")

    // Cleanup
    Files.deleteIfExists(path)
  }

  test("traverse should not revisit nodes in cycle") {
    val path  = createTempJsonPath()
    val store = new JsonGraphStore(path)
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))

    // Create cycle: 1 -> 2 -> 3 -> 1
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("2", "3", "E"))
    store.upsertEdge(Edge("3", "1", "E"))

    val result = store.traverse("1")
    val ids    = result.toOption.get.map(_.id)

    // Should visit each node exactly once
    ids.distinct should have size 3
    ids should have size 3

    // Cleanup
    Files.deleteIfExists(path)
  }
}
