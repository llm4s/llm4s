package org.llm4s.knowledgegraph.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Node }
import java.nio.file.Files

/**
 * Contract consistency tests for all GraphStore implementations.
 *
 * These tests run against multiple implementations (InMemory, JSON) to ensure:
 * 1. BFS traversal produces identical results
 * 2. Error handling is consistent (especially for missing start nodes)
 * 3. Property filtering returns same nodes across implementations
 * 4. Statistics calculations are accurate and consistent
 * 5. Edge operations maintain referential integrity uniformly
 *
 * Addresses PR #666 review issue #8: "API contract must be identical across implementations"
 */
class GraphStoreConsistencySpec extends AnyFunSuite with Matchers {

  def withAllStores[T](testName: String)(fn: GraphStore => Unit): Unit = {
    test(s"$testName (InMemoryGraphStore)") {
      val store = new InMemoryGraphStore()
      fn(store)
    }

    test(s"$testName (JsonGraphStore)") {
      val jsonPath = Files.createTempDirectory("llm4s-test-").resolve("test.json")
      val store    = new JsonGraphStore(jsonPath)
      try
        fn(store)
      finally
        Files.deleteIfExists(jsonPath)
    }
  }

  withAllStores("CRUD: insert and retrieve node") { store =>
    val node = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    store.upsertNode(node) shouldBe Right(())

    val retrieved = store.getNode("1")
    retrieved shouldBe Right(Some(node))
  }

  withAllStores("CRUD: update existing node") { store =>
    val node1 = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    val node2 = Node("1", "Person", Map("name" -> ujson.Str("Alicia")))

    store.upsertNode(node1) shouldBe Right(())
    store.upsertNode(node2) shouldBe Right(())

    val updated = store.getNode("1")
    updated.toOption.get.flatMap(_.properties.get("name")) shouldBe Some(ujson.Str("Alicia"))
  }

  withAllStores("CRUD: return None for non-existent node") { store =>
    val result = store.getNode("non-existent")
    result shouldBe Right(None)
  }

  withAllStores("Edge: reject edge with missing source") { store =>
    val target = Node("2", "B")
    store.upsertNode(target)

    val edge   = Edge("1", "2", "EDGE")
    val result = store.upsertEdge(edge)

    result shouldBe a[Left[_, _]]
  }

  withAllStores("Edge: reject edge with missing target") { store =>
    val source = Node("1", "A")
    store.upsertNode(source)

    val edge   = Edge("1", "2", "EDGE")
    val result = store.upsertEdge(edge)

    result shouldBe a[Left[_, _]]
  }

  withAllStores("Edge: create edge between existing nodes") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))

    val result = store.upsertEdge(Edge("1", "2", "EDGE"))
    result shouldBe Right(())
  }

  withAllStores("Neighbors: Direction.Outgoing returns targets") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("1", "3", "EDGE"))

    val neighbors = store.getNeighbors("1", Direction.Outgoing)
    val ids       = neighbors.toOption.get.map(_.node.id).toSet

    ids shouldBe Set("2", "3")
  }

  withAllStores("Neighbors: Direction.Incoming returns sources") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("2", "1", "EDGE"))
    store.upsertEdge(Edge("3", "1", "EDGE"))

    val neighbors = store.getNeighbors("1", Direction.Incoming)
    val ids       = neighbors.toOption.get.map(_.node.id).toSet

    ids shouldBe Set("2", "3")
  }

  withAllStores("Neighbors: Direction.Both returns all adjacent") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertNode(Node("4", "D"))
    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("3", "1", "EDGE"))

    val neighbors = store.getNeighbors("1", Direction.Both)
    val ids       = neighbors.toOption.get.map(_.node.id).toSet

    ids shouldBe Set("2", "3")
  }

  withAllStores("Query: filter by node label") { store =>
    store.upsertNode(Node("1", "Person", Map("name" -> ujson.Str("Alice"))))
    store.upsertNode(Node("2", "Organization", Map("name" -> ujson.Str("ACME"))))

    val result = store.query(GraphFilter(nodeLabel = Some("Person")))
    result.toOption.get.nodes.keys.toSet shouldBe Set("1")
  }

  withAllStores("Query: filter by property value (string)") { store =>
    store.upsertNode(Node("1", "Person", Map("city" -> ujson.Str("NYC"))))
    store.upsertNode(Node("2", "Person", Map("city" -> ujson.Str("LA"))))

    val result = store.query(GraphFilter(propertyKey = Some("city"), propertyValue = Some("NYC")))
    result.toOption.get.nodes.keys.toSet shouldBe Set("1")
  }

  withAllStores("Query: filter by property value (number)") { store =>
    store.upsertNode(Node("1", "Person", Map("age" -> ujson.Num(30))))
    store.upsertNode(Node("2", "Person", Map("age" -> ujson.Num(25))))

    val result = store.query(GraphFilter(propertyKey = Some("age"), propertyValue = Some("30")))
    result.toOption.get.nodes.keys.toSet shouldBe Set("1")
  }

  withAllStores("Query: filter by relationship type") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "KNOWS"))
    store.upsertEdge(Edge("1", "3", "WORKS_FOR"))

    val result = store.query(GraphFilter(relationshipType = Some("KNOWS")))
    result.toOption.get.edges.size shouldBe 1
    result.toOption.get.edges.head.relationship shouldBe "KNOWS"
  }

  withAllStores("Traverse: handle non-existent start node") { store =>
    val result = store.traverse("non-existent")
    result shouldBe Right(Seq())
  }

  withAllStores("Traverse: single node graph") { store =>
    store.upsertNode(Node("1", "A"))

    val result = store.traverse("1")
    result.toOption.get.map(_.id) shouldBe Seq("1")
  }

  withAllStores("Traverse: BFS order in linear chain") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertNode(Node("4", "D"))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("2", "3", "E"))
    store.upsertEdge(Edge("3", "4", "E"))

    val result = store.traverse("1")
    result.toOption.get.map(_.id) shouldBe Seq("1", "2", "3", "4")
  }

  withAllStores("Traverse: BFS order in branching graph") { store =>
    //     1
    //    / \
    //   2   3
    //  /
    // 4
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertNode(Node("4", "D"))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("1", "3", "E"))
    store.upsertEdge(Edge("2", "4", "E"))

    val result = store.traverse("1")
    val ids    = result.toOption.get.map(_.id)

    // BFS: level 0 = [1], level 1 = [2,3], level 2 = [4]
    ids.head shouldBe "1"
    ids.indexOf("2") should be < ids.indexOf("4")
    ids.indexOf("3") should be < ids.indexOf("4")
  }

  withAllStores("Traverse: respects maxDepth") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertNode(Node("4", "D"))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("2", "3", "E"))
    store.upsertEdge(Edge("3", "4", "E"))

    val result = store.traverse("1", TraversalConfig(maxDepth = 2))
    val ids    = result.toOption.get.map(_.id).toSet

    // Depth 2: nodes at distance 0, 1, 2 from node 1
    ids shouldBe Set("1", "2", "3")
  }

  withAllStores("Traverse: Direction.Outgoing only follows outgoing edges") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("3", "1", "E"))

    val result = store.traverse("1", TraversalConfig(direction = Direction.Outgoing))
    val ids    = result.toOption.get.map(_.id).toSet

    ids shouldBe Set("1", "2")
  }

  withAllStores("Traverse: Direction.Incoming only follows incoming edges") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("2", "1", "E"))
    store.upsertEdge(Edge("1", "3", "E"))

    val result = store.traverse("1", TraversalConfig(direction = Direction.Incoming))
    val ids    = result.toOption.get.map(_.id).toSet

    ids shouldBe Set("1", "2")
  }

  withAllStores("Traverse: handles cycles correctly (no infinite loop)") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("2", "3", "E"))
    store.upsertEdge(Edge("3", "1", "E"))

    val result = store.traverse("1")
    val ids    = result.toOption.get.map(_.id)

    // Each node visited exactly once
    ids.distinct should have size 3
    ids should have size 3
  }

  withAllStores("Delete: remove node and cleanup edges") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("1", "3", "E"))

    store.deleteNode("1") shouldBe Right(())

    store.getNode("1") shouldBe Right(None)
    // Edges from deleted node should be gone
    val n2Incoming = store.getNeighbors("2", Direction.Incoming)
    n2Incoming.toOption.get.map(_.node.id) should not contain "1"
  }

  withAllStores("Delete: remove specific edge") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "KNOWS"))
    store.upsertEdge(Edge("1", "2", "WORKS_WITH"))

    store.deleteEdge("1", "2", "KNOWS") shouldBe Right(())

    val neighbors     = store.getNeighbors("1")
    val relationships = neighbors.toOption.get.map(_.edge.relationship)
    relationships should contain("WORKS_WITH")
    relationships should not contain "KNOWS"
  }

  withAllStores("Stats: accurate node count") { store =>
    (1 to 5).foreach(i => store.upsertNode(Node(i.toString, s"Node$i")))

    val stats = store.stats()
    stats.toOption.get.nodeCount shouldBe 5L
  }

  withAllStores("Stats: accurate edge count") { store =>
    (1 to 3).foreach(i => store.upsertNode(Node(i.toString, s"Node$i")))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("2", "3", "E"))
    store.upsertEdge(Edge("1", "3", "E"))

    val stats = store.stats()
    stats.toOption.get.edgeCount shouldBe 3L
  }

  withAllStores("Stats: correct average degree") { store =>
    // Create star: node 1 has degree 2, nodes 2,3 have degree 1 each
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("1", "3", "E"))

    val stats = store.stats()
    // Total degree = 2 + 1 + 1 = 4, avg = 4 / 3 ~ 1.33
    stats.toOption.get.averageDegree shouldBe (4.0 / 3.0)
  }

  withAllStores("LoadAll: return complete graph") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "E"))

    val graph = store.loadAll()
    graph.toOption.get.nodes.size shouldBe 2
    graph.toOption.get.edges.size shouldBe 1
  }

  withAllStores("Consistency: property values preserved") { store =>
    val props = Map(
      "str"  -> ujson.Str("hello"),
      "num"  -> ujson.Num(42),
      "bool" -> ujson.Bool(true)
    )
    val node = Node("1", "Test", props)
    store.upsertNode(node)

    val retrieved = store.getNode("1").toOption.get.get
    retrieved.properties("str") shouldBe ujson.Str("hello")
    retrieved.properties("num") shouldBe ujson.Num(42)
    retrieved.properties("bool") shouldBe ujson.Bool(true)
  }

  withAllStores("Error handling: undefined start node returns empty") { store =>
    store.upsertNode(Node("1", "A"))

    val result = store.traverse("undefined-node")
    result shouldBe Right(Seq())
  }

  withAllStores("Error handling: empty neighbors for isolated node") { store =>
    store.upsertNode(Node("1", "A"))

    val neighbors = store.getNeighbors("1")
    neighbors shouldBe Right(Seq())
  }
}
