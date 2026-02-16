package org.llm4s.knowledgegraph.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Node, Edge }
import ujson.Str

class InMemoryGraphStoreTest extends AnyFunSuite with Matchers {

  test("upsertNode should add and retrieve node") {
    val store = new InMemoryGraphStore
    val node  = Node("1", "Person", Map("name" -> Str("Alice")))

    store.upsertNode(node) shouldBe Right(())

    store.getNode("1") match {
      case Right(Some(found)) =>
        found shouldBe node
      case other =>
        fail(s"Unexpected result: $other")
    }
  }

  test("upsertEdge should connect existing nodes") {
    val store = new InMemoryGraphStore
    val n1    = Node("1", "Person")
    val n2    = Node("2", "Person")
    val edge  = Edge("1", "2", "KNOWS")

    store.upsertNode(n1) shouldBe Right(())
    store.upsertNode(n2) shouldBe Right(())

    store.upsertEdge(edge) shouldBe Right(())

    store.getNeighbors("1", Direction.Outgoing) match {
      case Right(neighbors) =>
        neighbors.map(_._2.id) should contain("2")
      case other =>
        fail(s"Unexpected result: $other")
    }
  }

  test("upsertEdge should fail if node missing") {
    val store = new InMemoryGraphStore
    val edge  = Edge("1", "2", "KNOWS")

    store.upsertEdge(edge).isLeft shouldBe true
  }

  test("deleteNode should remove node and connected edges") {
    val store = new InMemoryGraphStore
    val n1    = Node("1", "Person")
    val n2    = Node("2", "Person")
    val edge  = Edge("1", "2", "KNOWS")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertEdge(edge)

    store.deleteNode("1") shouldBe Right(())

    store.getNode("1") match {
      case Right(None) =>
        succeed
      case other =>
        fail(s"Expected node to be deleted but got: $other")
    }
  }

  test("stats should reflect node and edge count") {
    val store = new InMemoryGraphStore

    store.upsertNode(Node("1", "Person"))
    store.upsertNode(Node("2", "Person"))
    store.upsertEdge(Edge("1", "2", "KNOWS"))

    store.stats() match {
      case Right(stats) =>
        stats.nodeCount shouldBe 2
        stats.edgeCount shouldBe 1
      case other =>
        fail(s"Unexpected result: $other")
    }
  }

  test("traverse should respect maxDepth") {
    val store = new InMemoryGraphStore

    val n1 = Node("1", "Person")
    val n2 = Node("2", "Person")
    val n3 = Node("3", "Person")

    store.upsertNode(n1)
    store.upsertNode(n2)
    store.upsertNode(n3)

    store.upsertEdge(Edge("1", "2", "KNOWS"))
    store.upsertEdge(Edge("2", "3", "KNOWS"))

    store.traverse("1", TraversalConfig(maxDepth = 1)) match {
      case Right(depth1) =>
        depth1.map(_.id) should contain("2")
        depth1.map(_.id) should not contain "3"
      case other =>
        fail(s"Unexpected result: $other")
    }

    store.traverse("1", TraversalConfig(maxDepth = 2)) match {
      case Right(depth2) =>
        depth2.map(_.id) should contain("3")
      case other =>
        fail(s"Unexpected result: $other")
    }
  }

  test("traverse should fail if start node does not exist") {
    val store = new InMemoryGraphStore
    store.traverse("missing", TraversalConfig(maxDepth = 1)).isLeft shouldBe true
  }

  test("query should filter by property key and value") {
    val store = new InMemoryGraphStore

    store.upsertNode(Node("1", "Person", Map("name" -> Str("Alice"))))
    store.upsertNode(Node("2", "Person", Map("name" -> Str("Bob"))))

    val result = store.query(
      GraphFilter(propertyKey = Some("name"), propertyValue = Some("Alice"))
    )

    result.map(_.nodes.keySet) shouldBe Right(Set("1"))
  }

  test("deleteEdge should remove specific edge") {
    val store = new InMemoryGraphStore

    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "REL"))

    store.deleteEdge("1", "2", "REL") shouldBe Right(())

    store.getNeighbors("1", Direction.Outgoing) match {
      case Right(neighbors) =>
        neighbors shouldBe empty
      case other =>
        fail(s"Unexpected result: $other")
    }
  }
}
