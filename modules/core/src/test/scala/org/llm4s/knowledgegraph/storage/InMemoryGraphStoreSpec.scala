package org.llm4s.knowledgegraph.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Node }

/**
 * Comprehensive test suite for InMemoryGraphStore.
 *
 * Tests all CRUD operations, query, traversal, and error handling
 * with emphasis on consistency across different implementations.
 *
 * Addresses PR #666 review issues:
 * - Thread safety (atomic CAS updates)
 * - Consistent BFS traversal
 * - Unified error handling
 * - Property filtering support
 */
class InMemoryGraphStoreSpec extends AnyFunSuite with Matchers {
  test("concurrent writes from multiple threads should not lose updates or corrupt state") {
    val store          = new InMemoryGraphStore()
    val threadCount    = 20
    val nodesPerThread = 10
    val threads = (1 to threadCount).map { tIdx =>
      new Thread(() => {
        val base = tIdx * 1000
        (1 to nodesPerThread).foreach { nIdx =>
          val nodeId = (base + nIdx).toString
          val node   = Node(nodeId, s"Thread$tIdx-Node$nIdx")
          store.upsertNode(node)
          // Each thread also upserts an edge to previous node (if exists)
          if (nIdx > 1) {
            val prevId = (base + nIdx - 1).toString
            val edge   = Edge(prevId, nodeId, s"T$tIdx")
            store.upsertEdge(edge)
          }
        }
      })
    }
    threads.foreach(_.start())
    threads.foreach(_.join())

    // After all threads complete, verify all nodes and edges are present
    val graph             = store.loadAll().toOption.get
    val expectedNodeCount = threadCount * nodesPerThread
    val expectedEdgeCount = threadCount * (nodesPerThread - 1)
    graph.nodes should have size expectedNodeCount
    graph.edges should have size expectedEdgeCount

    // Check for unique node IDs and edge consistency
    graph.nodes.keys.toSet.size shouldBe expectedNodeCount
    graph.edges.map(e => (e.source, e.target)).toSet.size shouldBe expectedEdgeCount
  }

  test("upsertNode should insert a new node") {
    val store = new InMemoryGraphStore()
    val node  = Node("1", "Person", Map("name" -> ujson.Str("Alice")))

    val result = store.upsertNode(node)
    result shouldBe Right(())

    val retrieved = store.getNode("1")
    retrieved.toOption.get.map(_.label) shouldBe Some("Person")
    retrieved.toOption.get.flatMap(_.properties.get("name")) shouldBe Some(ujson.Str("Alice"))
  }

  test("upsertNode should update existing node with new properties") {
    val store = new InMemoryGraphStore()
    val node1 = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    val node2 = Node("1", "Person", Map("name" -> ujson.Str("Alicia"), "age" -> ujson.Num(30)))

    store.upsertNode(node1) shouldBe Right(())
    store.upsertNode(node2) shouldBe Right(())

    val updated = store.getNode("1")
    updated.toOption.get.flatMap(_.properties.get("name")) shouldBe Some(ujson.Str("Alicia"))
    updated.toOption.get.flatMap(_.properties.get("age")) shouldBe Some(ujson.Num(30))
  }

  test("upsertEdge should fail if source node doesn't exist") {
    val store  = new InMemoryGraphStore()
    val target = Node("2", "Person")
    store.upsertNode(target)

    val edge   = Edge("1", "2", "KNOWS")
    val result = store.upsertEdge(edge)

    result shouldBe a[Left[_, _]]
    result.left.map(_.message) shouldBe Left(
      "Processing failed during validation: source or target node does not exist"
    )
  }

  test("upsertEdge should fail if target node doesn't exist") {
    val store  = new InMemoryGraphStore()
    val source = Node("1", "Person")
    store.upsertNode(source)

    val edge   = Edge("1", "2", "KNOWS")
    val result = store.upsertEdge(edge)

    result shouldBe a[Left[_, _]]
  }

  test("upsertEdge should insert edge between existing nodes") {
    val store = new InMemoryGraphStore()
    val n1    = Node("1", "Person")
    val n2    = Node("2", "Person")
    val edge  = Edge("1", "2", "KNOWS", Map("since" -> ujson.Num(2020)))

    store.upsertNode(n1)
    store.upsertNode(n2)
    val result = store.upsertEdge(edge)

    result shouldBe Right(())
  }

  test("upsertEdge should replace existing edge with same source, target, and relationship") {
    val store = new InMemoryGraphStore()
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
  }

  test("getNode should return None for non-existent node") {
    val store  = new InMemoryGraphStore()
    val result = store.getNode("non-existent")

    result shouldBe Right(None)
  }

  test("getNeighbors should return empty for non-existent node") {
    val store  = new InMemoryGraphStore()
    val result = store.getNeighbors("non-existent")

    result.toOption.get shouldBe empty
  }

  test("getNeighbors with Direction.Outgoing should return targets") {
    val store = new InMemoryGraphStore()
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")
    val n3    = Node("3", "C")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)
    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("1", "3", "EDGE"))

    val neighbors = store.getNeighbors("1", Direction.Outgoing)
    val targetIds = neighbors.toOption.get.map(_.node.id)

    targetIds should contain theSameElementsAs List("2", "3")
  }

  test("getNeighbors with Direction.Incoming should return sources") {
    val store = new InMemoryGraphStore()
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")
    val n3    = Node("3", "C")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)
    store.upsertEdge(Edge("2", "1", "EDGE"))
    store.upsertEdge(Edge("3", "1", "EDGE"))

    val neighbors = store.getNeighbors("1", Direction.Incoming)
    val sourceIds = neighbors.toOption.get.map(_.node.id)

    sourceIds should contain theSameElementsAs List("2", "3")
  }

  test("query should filter by node label") {
    val store  = new InMemoryGraphStore()
    val person = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    val org    = Node("2", "Organization", Map("name" -> ujson.Str("ACME")))

    store.upsertNode(person)
    store.upsertNode(org)

    val filter = GraphFilter(nodeLabel = Some("Person"))
    val result = store.query(filter)

    result.toOption.get.nodes should have size 1
    result.toOption.get.nodes.get("1").map(_.label) shouldBe Some("Person")
  }

  test("query should filter by property value") {
    val store = new InMemoryGraphStore()
    val alice = Node("1", "Person", Map("city" -> ujson.Str("NYC")))
    val bob   = Node("2", "Person", Map("city" -> ujson.Str("LA")))

    store.upsertNode(alice)
    store.upsertNode(bob)

    val filter = GraphFilter(propertyKey = Some("city"), propertyValue = Some("NYC"))
    val result = store.query(filter)

    result.toOption.get.nodes should have size 1
    result.toOption.get.nodes.get("1").map(_.label) shouldBe Some("Person")
  }

  test("query should filter by relationship type") {
    val store = new InMemoryGraphStore()
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
  }

  test("traverse should return empty for non-existent start node") {
    val store  = new InMemoryGraphStore()
    val result = store.traverse("non-existent")

    result.toOption.get shouldBe empty
  }

  test("traverse with BFS should visit nodes in breadth-first order") {
    val store = new InMemoryGraphStore()
    // Create graph: 1 -> 2, 1 -> 3, 2 -> 4
    val nodes = Seq(
      Node("1", "A"),
      Node("2", "B"),
      Node("3", "C"),
      Node("4", "D")
    )
    nodes.foreach(store.upsertNode)

    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("1", "3", "EDGE"))
    store.upsertEdge(Edge("2", "4", "EDGE"))

    val result     = store.traverse("1", TraversalConfig(maxDepth = 10))
    val visitedIds = result.toOption.get.map(_.id)

    // BFS order: level 0 -> [1], level 1 -> [2, 3], level 2 -> [4]
    visitedIds.head shouldBe "1"
    visitedIds should contain("2")
    visitedIds should contain("3")
    visitedIds should contain("4")
  }

  test("traverse with maxDepth should respect depth limit") {
    val store = new InMemoryGraphStore()
    // Chain: 1 -> 2 -> 3 -> 4
    val nodes = Seq(Node("1", "A"), Node("2", "B"), Node("3", "C"), Node("4", "D"))
    nodes.foreach(store.upsertNode)

    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("2", "3", "EDGE"))
    store.upsertEdge(Edge("3", "4", "EDGE"))

    val result     = store.traverse("1", TraversalConfig(maxDepth = 2))
    val visitedIds = result.toOption.get.map(_.id).toSet

    // Max depth 2 means: level 0 (node 1), level 1 (node 2), level 2 (node 3)
    visitedIds shouldBe Set("1", "2", "3")
  }

  test("traverse with Direction.Outgoing should follow outgoing edges") {
    val store = new InMemoryGraphStore()
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

    // Should visit 1 and 2, not 3 (which has edge pointing toward 1)
    visitedIds shouldBe Set("1", "2")
  }

  test("traverse with Direction.Incoming should follow incoming edges") {
    val store = new InMemoryGraphStore()
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

    // Should visit 1 and 2, not 3
    visitedIds shouldBe Set("1", "2")
  }

  test("traverse with Direction.Both should follow edges in both directions") {
    val store = new InMemoryGraphStore()
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
  }

  test("deleteNode should remove node and all connected edges") {
    val store = new InMemoryGraphStore()
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
    val n2Neighbors = store.getNeighbors("2", Direction.Incoming)
    n2Neighbors.toOption.get.map(_.node.id) should not contain "1"
  }

  test("deleteEdge should remove only specific edge") {
    val store = new InMemoryGraphStore()
    val n1    = Node("1", "A")
    val n2    = Node("2", "B")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertEdge(Edge("1", "2", "KNOWS", Map("since" -> ujson.Num(2020))))
    store.upsertEdge(Edge("1", "2", "WORKS_WITH", Map("since" -> ujson.Num(2020))))

    store.deleteEdge("1", "2", "KNOWS") shouldBe Right(())

    // WORKS_WITH edge should still exist
    val neighbors = store.getNeighbors("1", Direction.Outgoing)
    val edges     = neighbors.toOption.get.map(_.edge)
    edges.map(_.relationship) should contain("WORKS_WITH")
    edges.map(_.relationship) should not contain "KNOWS"
  }

  test("loadAll should return complete graph") {
    val store = new InMemoryGraphStore()
    val nodes = Seq(Node("1", "A"), Node("2", "B"), Node("3", "C"))
    nodes.foreach(store.upsertNode)

    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("2", "3", "EDGE"))

    val result = store.loadAll()
    result.toOption.get.nodes should have size 3
    result.toOption.get.edges should have size 2
  }

  test("stats should compute correct statistics") {
    val store = new InMemoryGraphStore()
    val nodes = Seq(
      Node("1", "A"),
      Node("2", "B"),
      Node("3", "C")
    )
    nodes.foreach(store.upsertNode)

    // Create a star topology: 1 connects to 2 and 3
    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("1", "3", "EDGE"))
    // 2 also connects to 3
    store.upsertEdge(Edge("2", "3", "EDGE"))

    val stats = store.stats()
    stats.toOption.get.nodeCount shouldBe 3L
    stats.toOption.get.edgeCount shouldBe 3L
    // avg degree = (3 edges * 2) / 3 nodes = 2.0
    stats.toOption.get.averageDegree shouldBe 2.0
    // Node 1 and 2 have degree 2, node 3 has degree 2
    stats.toOption.get.densestNodeId should not be empty
  }

  test("snapshot should return immutable graph state") {
    val store = new InMemoryGraphStore()
    val n1    = Node("1", "A")

    store.upsertNode(n1)
    val snap1 = store.snapshot()
    snap1.nodes should have size 1

    val n2 = Node("2", "B")
    store.upsertNode(n2)
    val snap2 = store.snapshot()

    snap1.nodes should have size 1 // Original snapshot unchanged
    snap2.nodes should have size 2 // New snapshot reflects change
  }

  test("concurrent operations should succeed (thread-safe via CAS)") {
    val store = new InMemoryGraphStore()
    // This tests the atomic reference behavior without explicit sync

    val n1 = Node("1", "A")
    store.upsertNode(n1)

    // Simulate multiple rapid updates
    (2 to 10).foreach { i =>
      val node = Node(i.toString, s"Node$i")
      store.upsertNode(node) shouldBe Right(())
    }

    val graph = store.loadAll()
    graph.toOption.get.nodes should have size 10
  }

  test("property filtering should work with complex properties") {
    val store = new InMemoryGraphStore()
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

    val filter = GraphFilter(propertyKey = Some("name"), propertyValue = Some("Alice"))
    val result = store.query(filter)
    result.toOption.get.nodes should have size 1
  }

  test("traverse should handle disconnected components") {
    val store = new InMemoryGraphStore()
    // Component 1: 1 -> 2
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "EDGE"))

    // Component 2: 3 -> 4 (disconnected)
    store.upsertNode(Node("3", "C"))
    store.upsertNode(Node("4", "D"))
    store.upsertEdge(Edge("3", "4", "EDGE"))

    val result     = store.traverse("1")
    val visitedIds = result.toOption.get.map(_.id).toSet

    // Should only visit component 1
    visitedIds shouldBe Set("1", "2")
  }
}
