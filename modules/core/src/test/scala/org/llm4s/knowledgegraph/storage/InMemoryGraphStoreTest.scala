package org.llm4s.knowledgegraph.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{Node, Edge}
import ujson.Str

class InMemoryGraphStoreTest extends AnyFunSuite with Matchers {

  test("upsertNode should add and retrieve node") {
    val store = new InMemoryGraphStore
    val node  = Node("1", "Person", Map("name" -> Str("Alice")))

    store.upsertNode(node).isRight shouldBe true
    store.getNode("1").toOption.flatten shouldBe Some(node)
  }

  test("upsertEdge should connect existing nodes") {
    val store = new InMemoryGraphStore
    val n1    = Node("1", "Person")
    val n2    = Node("2", "Person")
    val edge  = Edge("1", "2", "KNOWS")

    store.upsertNode(n1)
    store.upsertNode(n2)

    store.upsertEdge(edge).isRight shouldBe true

    val neighbors = store.getNeighbors("1", Direction.Outgoing).toOption.get
    neighbors.map(_._2.id) should contain("2")
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

    store.deleteNode("1").isRight shouldBe true
    store.getNode("1").toOption.flatten shouldBe None
  }

  test("stats should reflect node and edge count") {
    val store = new InMemoryGraphStore
    store.upsertNode(Node("1", "Person"))
    store.upsertNode(Node("2", "Person"))
    store.upsertEdge(Edge("1", "2", "KNOWS"))

    val stats = store.stats().toOption.get
    stats.nodeCount shouldBe 2
    stats.edgeCount shouldBe 1
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

    val depth1 = store.traverse("1", TraversalConfig(maxDepth = 1)).toOption.get
    depth1.map(_.id) should contain("2")
    depth1.map(_.id) should not contain "3"

    val depth2 = store.traverse("1", TraversalConfig(maxDepth = 2)).toOption.get
    depth2.map(_.id) should contain("3")
  }
}
