package org.llm4s.knowledgegraph.storage

import org.llm4s.knowledgegraph.{ Edge, Node }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * The behaviour every [[GraphStore]] shares, as its Scaladoc states it (#2970). A store's spec extends this and
 * supplies a fresh, empty store per test; `InMemoryGraphStoreContractSpec` and `JsonGraphStoreContractSpec` run it
 * here, and `Neo4jGraphStoreContractSpec` (`modules/it`, `@Docker`) runs it against a live Neo4j.
 *
 * Uses only main classes and ScalaTest: `modules/it` compiles this file into its own tests (`Test / unmanagedSources`
 * in `build.sbt`) rather than depending on this module's tests.
 */
abstract class GraphStoreContract extends AnyFunSuite with Matchers {

  /** The store's name, appended to each test's name. */
  protected def storeName: String

  /** Runs `body` against a fresh, empty store. */
  protected def withStore(body: GraphStore => Unit): Unit

  protected def contract(testName: String)(body: GraphStore => Unit): Unit =
    test(s"$testName ($storeName)")(withStore(body))

  private def ok[A](r: org.llm4s.types.Result[A]): A = r.fold(e => fail(s"expected Right, got Left($e)"), identity)

  contract("CRUD: insert and retrieve node") { store =>
    val node = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    store.upsertNode(node) shouldBe Right(())

    val retrieved = store.getNode("1")
    retrieved shouldBe Right(Some(node))
  }

  contract("CRUD: update existing node") { store =>
    val node1 = Node("1", "Person", Map("name" -> ujson.Str("Alice")))
    val node2 = Node("1", "Person", Map("name" -> ujson.Str("Alicia")))

    store.upsertNode(node1) shouldBe Right(())
    store.upsertNode(node2) shouldBe Right(())

    val updated = store.getNode("1")
    updated.toOption.get.flatMap(_.properties.get("name")) shouldBe Some(ujson.Str("Alicia"))
  }

  contract("CRUD: return None for non-existent node") { store =>
    val result = store.getNode("non-existent")
    result shouldBe Right(None)
  }

  contract("Edge: reject edge with missing source") { store =>
    val target = Node("2", "B")
    store.upsertNode(target)

    val edge   = Edge("1", "2", "EDGE")
    val result = store.upsertEdge(edge)

    result shouldBe a[Left[_, _]]
  }

  contract("Edge: reject edge with missing target") { store =>
    val source = Node("1", "A")
    store.upsertNode(source)

    val edge   = Edge("1", "2", "EDGE")
    val result = store.upsertEdge(edge)

    result shouldBe a[Left[_, _]]
  }

  contract("Edge: create edge between existing nodes") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))

    val result = store.upsertEdge(Edge("1", "2", "EDGE"))
    result shouldBe Right(())
  }

  contract("Neighbors: Direction.Outgoing returns targets") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "EDGE"))
    store.upsertEdge(Edge("1", "3", "EDGE"))

    val neighbors = store.getNeighbors("1", Direction.Outgoing)
    val ids       = neighbors.toOption.get.map(_.node.id).toSet

    ids shouldBe Set("2", "3")
  }

  contract("Neighbors: Direction.Incoming returns sources") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("2", "1", "EDGE"))
    store.upsertEdge(Edge("3", "1", "EDGE"))

    val neighbors = store.getNeighbors("1", Direction.Incoming)
    val ids       = neighbors.toOption.get.map(_.node.id).toSet

    ids shouldBe Set("2", "3")
  }

  contract("Neighbors: Direction.Both returns all adjacent") { store =>
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

  contract("Query: filter by node label") { store =>
    store.upsertNode(Node("1", "Person", Map("name" -> ujson.Str("Alice"))))
    store.upsertNode(Node("2", "Organization", Map("name" -> ujson.Str("ACME"))))

    val result = store.query(GraphFilter(nodeLabel = Some("Person")))
    result.toOption.get.nodes.keys.toSet shouldBe Set("1")
  }

  contract("Query: filter by property value (string)") { store =>
    store.upsertNode(Node("1", "Person", Map("city" -> ujson.Str("NYC"))))
    store.upsertNode(Node("2", "Person", Map("city" -> ujson.Str("LA"))))

    val result = store.query(GraphFilter(propertyKey = Some("city"), propertyValue = Some("NYC")))
    result.toOption.get.nodes.keys.toSet shouldBe Set("1")
  }

  contract("Query: filter by property value (number)") { store =>
    store.upsertNode(Node("1", "Person", Map("age" -> ujson.Num(30))))
    store.upsertNode(Node("2", "Person", Map("age" -> ujson.Num(25))))

    val result = store.query(GraphFilter(propertyKey = Some("age"), propertyValue = Some("30")))
    result.toOption.get.nodes.keys.toSet shouldBe Set("1")
  }

  contract("Query: filter by relationship type") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "KNOWS"))
    store.upsertEdge(Edge("1", "3", "WORKS_FOR"))

    val result = store.query(GraphFilter(relationshipType = Some("KNOWS")))
    result.toOption.get.edges.size shouldBe 1
    result.toOption.get.edges.head.relationship shouldBe "KNOWS"
  }

  contract("Traverse: handle non-existent start node") { store =>
    val result = store.traverse("non-existent")
    result shouldBe Right(Seq())
  }

  contract("Traverse: single node graph") { store =>
    store.upsertNode(Node("1", "A"))

    val result = store.traverse("1")
    result.toOption.get.map(_.id) shouldBe Seq("1")
  }

  contract("Traverse: BFS order in linear chain") { store =>
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

  contract("Traverse: BFS order in branching graph") { store =>
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

  contract("Traverse: respects maxDepth") { store =>
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

  contract("Traverse: Direction.Outgoing only follows outgoing edges") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("3", "1", "E"))

    val result = store.traverse("1", TraversalConfig(direction = Direction.Outgoing))
    val ids    = result.toOption.get.map(_.id).toSet

    ids shouldBe Set("1", "2")
  }

  contract("Traverse: Direction.Incoming only follows incoming edges") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("2", "1", "E"))
    store.upsertEdge(Edge("1", "3", "E"))

    val result = store.traverse("1", TraversalConfig(direction = Direction.Incoming))
    val ids    = result.toOption.get.map(_.id).toSet

    ids shouldBe Set("1", "2")
  }

  contract("Traverse: handles cycles correctly (no infinite loop)") { store =>
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

  contract("Delete: remove node and cleanup edges") { store =>
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

  contract("Delete: remove specific edge") { store =>
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

  contract("Stats: accurate node count") { store =>
    (1 to 5).foreach(i => store.upsertNode(Node(i.toString, s"Node$i")))

    val stats = store.stats()
    stats.toOption.get.nodeCount shouldBe 5L
  }

  contract("Stats: accurate edge count") { store =>
    (1 to 3).foreach(i => store.upsertNode(Node(i.toString, s"Node$i")))
    store.upsertEdge(Edge("1", "2", "E"))
    store.upsertEdge(Edge("2", "3", "E"))
    store.upsertEdge(Edge("1", "3", "E"))

    val stats = store.stats()
    stats.toOption.get.edgeCount shouldBe 3L
  }

  contract("Stats: correct average degree") { store =>
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

  contract("LoadAll: return complete graph") { store =>
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "E"))

    val graph = store.loadAll()
    graph.toOption.get.nodes.size shouldBe 2
    graph.toOption.get.edges.size shouldBe 1
  }

  contract("Consistency: property values preserved") { store =>
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

  contract("Error handling: undefined start node returns empty") { store =>
    store.upsertNode(Node("1", "A"))

    val result = store.traverse("undefined-node")
    result shouldBe Right(Seq())
  }

  contract("Error handling: empty neighbors for isolated node") { store =>
    store.upsertNode(Node("1", "A"))

    val neighbors = store.getNeighbors("1")
    neighbors shouldBe Right(Seq())
  }

  // ─── #2970: the points on which Neo4jGraphStore used to differ ─────────────────────────────

  private val awkwardTypes =
    Seq("WORKS FOR", "works-for", "3D_PRINTS", "emploie\u00e0", "\u52b4\u50cd", "a`b", "x]->(y)")

  contract("Edge: any non-empty relationship type is written, read, filtered on and deleted") { store =>
    ok(store.upsertNode(Node("1", "A")))
    ok(store.upsertNode(Node("2", "B")))
    awkwardTypes.foreach(t => store.upsertEdge(Edge("1", "2", t)) shouldBe Right(()))
    ok(store.getNeighbors("1", Direction.Outgoing)).map(_.edge.relationship).toSet shouldBe awkwardTypes.toSet
    ok(store.loadAll()).edges.map(_.relationship).toSet shouldBe awkwardTypes.toSet
    awkwardTypes.foreach { t =>
      ok(store.query(GraphFilter(relationshipType = Some(t)))).edges.map(_.relationship) shouldBe List(t)
    }
    awkwardTypes.foreach(t => store.deleteEdge("1", "2", t) shouldBe Right(()))
    ok(store.loadAll()).edges shouldBe empty
  }

  contract("Edge: deleting an edge that does not exist is a Right, whatever its type") { store =>
    ok(store.upsertNode(Node("1", "A")))
    store.deleteEdge("1", "2", "WORKS FOR") shouldBe Right(())
    store.deleteEdge("missing", "also-missing", "x-y") shouldBe Right(())
    store.deleteNode("missing") shouldBe Right(())
  }

  contract("Edge: one edge per type between two nodes, re-upserted in place; different types are kept apart") { store =>
    ok(store.upsertNode(Node("1", "A")))
    ok(store.upsertNode(Node("2", "B")))
    ok(store.upsertEdge(Edge("1", "2", "KNOWS", Map("w" -> ujson.Num(1)))))
    ok(store.upsertEdge(Edge("1", "2", "KNOWS", Map("w" -> ujson.Num(2)))))
    ok(store.upsertEdge(Edge("1", "2", "LIKES")))
    ok(store.upsertEdge(Edge("2", "1", "KNOWS")))
    val edges = ok(store.loadAll()).edges
    edges should have size 3
    edges.filter(e => e.source == "1" && e.relationship == "KNOWS").map(_.properties) shouldBe List(
      Map("w" -> ujson.Num(2))
    )
    ok(store.stats()).edgeCount shouldBe 3L
  }

  contract("Neighbors: a self-loop is returned once in every direction") { store =>
    ok(store.upsertNode(Node("1", "A")))
    ok(store.upsertNode(Node("2", "B")))
    ok(store.upsertEdge(Edge("1", "1", "SELF")))
    ok(store.upsertEdge(Edge("1", "2", "E")))
    Seq(Direction.Outgoing, Direction.Incoming, Direction.Both).foreach { d =>
      val selfLoops = ok(store.getNeighbors("1", d)).filter(_.node.id == "1")
      selfLoops.map(_.edge) shouldBe Seq(Edge("1", "1", "SELF"))
    }
    ok(store.getNeighbors("1", Direction.Both)).map(_.node.id).toSet shouldBe Set("1", "2")
  }

  contract("Traverse: a self-loop does not repeat the node") { store =>
    ok(store.upsertNode(Node("1", "A")))
    ok(store.upsertNode(Node("2", "B")))
    ok(store.upsertEdge(Edge("1", "1", "SELF")))
    ok(store.upsertEdge(Edge("1", "2", "E")))
    ok(store.traverse("1")).map(_.id) shouldBe Seq("1", "2")
  }

  contract("Traverse: by depth, and by id within one depth, whatever the insertion order") { store =>
    Seq("root", "c", "a", "b", "z", "y").foreach(id => ok(store.upsertNode(Node(id, "N"))))
    ok(store.upsertEdge(Edge("root", "c", "E")))
    ok(store.upsertEdge(Edge("root", "a", "E")))
    ok(store.upsertEdge(Edge("b", "root", "E")))
    ok(store.upsertEdge(Edge("c", "y", "E")))
    ok(store.upsertEdge(Edge("a", "z", "E")))
    ok(store.traverse("root")).map(_.id) shouldBe Seq("root", "a", "b", "c", "y", "z")
    ok(store.traverse("root", TraversalConfig(direction = Direction.Outgoing))).map(_.id) shouldBe
      Seq("root", "a", "c", "y", "z")
  }

  contract("Traverse: a start node in visitedNodeIds yields nothing, and visited nodes are not traversed through") {
    store =>
      Seq("1", "2", "3").foreach(id => ok(store.upsertNode(Node(id, "N"))))
      ok(store.upsertEdge(Edge("1", "2", "E")))
      ok(store.upsertEdge(Edge("2", "3", "E")))
      ok(store.traverse("1", TraversalConfig(visitedNodeIds = Set("1")))) shouldBe empty
      ok(store.traverse("1", TraversalConfig(visitedNodeIds = Set("2")))).map(_.id) shouldBe Seq("1")
  }

  contract("Stats: averageDegree is 2E/V and a self-loop counts once towards the densest node") { store =>
    Seq("a", "b", "c").foreach(id => ok(store.upsertNode(Node(id, "N"))))
    ok(store.upsertEdge(Edge("b", "b", "SELF")))
    ok(store.upsertEdge(Edge("a", "c", "E")))
    val stats = ok(store.stats())
    stats.edgeCount shouldBe 2L
    stats.averageDegree shouldBe (4.0 / 3.0)
    // a, b and c each have one incident edge: the tie goes to the smallest id
    stats.densestNodeId shouldBe Some("a")
  }

  contract("Stats: the densest node is the one with the most incident edges") { store =>
    Seq("a", "b", "c").foreach(id => ok(store.upsertNode(Node(id, "N"))))
    ok(store.upsertEdge(Edge("a", "c", "E")))
    ok(store.upsertEdge(Edge("b", "c", "E")))
    ok(store.stats()).densestNodeId shouldBe Some("c")
  }

  contract("Stats: an empty store")(store => ok(store.stats()) shouldBe GraphStats(0L, 0L, 0.0, None))

  contract("Query: any property key a node is written with can be filtered on") { store =>
    val keys = Seq("first-name", "first name", "na`me", "\u540d\u524d", "x) OR true //")
    keys.zipWithIndex.foreach { case (k, i) => ok(store.upsertNode(Node(s"n$i", "P", Map(k -> ujson.Str("v"))))) }
    keys.zipWithIndex.foreach { case (k, i) =>
      ok(store.query(GraphFilter(propertyKey = Some(k), propertyValue = Some("v")))).nodes.keySet shouldBe Set(s"n$i")
    }
  }

  contract("Query: a property key without a value, or a value without a key, is a Left") { store =>
    ok(store.upsertNode(Node("1", "P", Map("city" -> ujson.Str("NYC")))))
    store.query(GraphFilter(propertyKey = Some("city"))) shouldBe a[Left[_, _]]
    store.query(GraphFilter(propertyValue = Some("NYC"))) shouldBe a[Left[_, _]]
  }

  contract("Query: label and property filters combine, and edges need both ends in the result") { store =>
    ok(store.upsertNode(Node("1", "Person", Map("city" -> ujson.Str("NYC")))))
    ok(store.upsertNode(Node("2", "Person", Map("city" -> ujson.Str("NYC")))))
    ok(store.upsertNode(Node("3", "Org", Map("city" -> ujson.Str("NYC")))))
    ok(store.upsertEdge(Edge("1", "2", "KNOWS")))
    ok(store.upsertEdge(Edge("1", "3", "WORKS_FOR")))
    val g = ok(store.query(GraphFilter(Some("Person"), None, Some("city"), Some("NYC"))))
    g.nodes.keySet shouldBe Set("1", "2")
    g.edges shouldBe List(Edge("1", "2", "KNOWS"))
  }
}
