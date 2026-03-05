package org.llm4s.knowledgegraph.query

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Node }
import org.llm4s.knowledgegraph.storage.{ Direction, InMemoryGraphStore }

class GraphQueryExecutorTest extends AnyFunSuite with Matchers {

  // Build a test graph: Alice (Person) -[WORKS_FOR]-> Acme (Organization)
  //                     Bob (Person) -[WORKS_FOR]-> Acme
  //                     Alice -[KNOWS]-> Bob
  //                     Acme -[LOCATED_IN]-> NYC (Location)
  private def buildTestStore(): InMemoryGraphStore = {
    val store = new InMemoryGraphStore()
    store.upsertNode(Node("alice", "Person", Map("name" -> ujson.Str("Alice"), "age" -> ujson.Num(30))))
    store.upsertNode(Node("bob", "Person", Map("name" -> ujson.Str("Bob"), "age" -> ujson.Num(25))))
    store.upsertNode(
      Node("acme", "Organization", Map("name" -> ujson.Str("Acme Corp"), "industry" -> ujson.Str("Tech")))
    )
    store.upsertNode(Node("nyc", "Location", Map("name" -> ujson.Str("New York City"))))
    store.upsertEdge(Edge("alice", "acme", "WORKS_FOR"))
    store.upsertEdge(Edge("bob", "acme", "WORKS_FOR"))
    store.upsertEdge(Edge("alice", "bob", "KNOWS"))
    store.upsertEdge(Edge("acme", "nyc", "LOCATED_IN"))
    store
  }

  test("FindNodes should find nodes by label") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(GraphQuery.FindNodes(label = Some("Person")))

    result should be(a[Right[_, _]])
    val qr = result.toOption.get
    qr.nodes should have size 2
    qr.nodes.map(_.id) should contain theSameElementsAs Seq("alice", "bob")
    qr.summary should include("2 node(s)")
  }

  test("FindNodes should find nodes by property") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(
      GraphQuery.FindNodes(label = Some("Person"), properties = Map("name" -> "Alice"))
    )

    result should be(a[Right[_, _]])
    val qr = result.toOption.get
    qr.nodes should have size 1
    qr.nodes.head.id shouldBe "alice"
  }

  test("FindNodes with no matching label should return empty") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(GraphQuery.FindNodes(label = Some("Animal")))

    result should be(a[Right[_, _]])
    result.toOption.get.isEmpty shouldBe true
  }

  test("FindNeighbors should find direct neighbors") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(
      GraphQuery.FindNeighbors(nodeId = "alice", direction = Direction.Outgoing)
    )

    result should be(a[Right[_, _]])
    val qr = result.toOption.get
    qr.nodes.map(_.id) should contain theSameElementsAs Seq("acme", "bob")
  }

  test("FindNeighbors should filter by relationship type") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(
      GraphQuery.FindNeighbors(
        nodeId = "alice",
        direction = Direction.Outgoing,
        relationshipType = Some("WORKS_FOR")
      )
    )

    result should be(a[Right[_, _]])
    val qr = result.toOption.get
    qr.nodes should have size 1
    qr.nodes.head.id shouldBe "acme"
  }

  test("FindNeighbors with multi-hop should traverse deeper") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(
      GraphQuery.FindNeighbors(nodeId = "alice", maxDepth = 2)
    )

    result should be(a[Right[_, _]])
    val qr = result.toOption.get
    // Alice -> Bob, Acme at depth 1; NYC at depth 2
    qr.nodes.map(_.id) should contain("nyc")
  }

  test("FindPath should find path between connected nodes") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(
      GraphQuery.FindPath(fromNodeId = "alice", toNodeId = "nyc", maxHops = 3)
    )

    result should be(a[Right[_, _]])
    val qr = result.toOption.get
    qr.paths should not be empty
    qr.summary should include("path(s)")
  }

  test("FindPath should return empty for disconnected nodes") {
    val store = new InMemoryGraphStore()
    store.upsertNode(Node("a", "A"))
    store.upsertNode(Node("b", "B"))

    val executor = new GraphQueryExecutor(store)
    val result   = executor.execute(GraphQuery.FindPath("a", "b", maxHops = 5))

    result should be(a[Right[_, _]])
    result.toOption.get.paths shouldBe empty
  }

  test("DescribeNode should return node with neighbors") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(GraphQuery.DescribeNode(nodeId = "alice"))

    result should be(a[Right[_, _]])
    val qr = result.toOption.get
    qr.nodes.map(_.id) should contain("alice")
    qr.nodes.size should be > 1 // alice + neighbors
    qr.edges should not be empty
  }

  test("DescribeNode without neighbors should return only the node") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(
      GraphQuery.DescribeNode(nodeId = "alice", includeNeighbors = false)
    )

    result should be(a[Right[_, _]])
    val qr = result.toOption.get
    qr.nodes should have size 1
    qr.nodes.head.id shouldBe "alice"
    qr.edges shouldBe empty
  }

  test("DescribeNode for missing node should return empty result") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(GraphQuery.DescribeNode(nodeId = "nonexistent"))

    result should be(a[Right[_, _]])
    result.toOption.get.nodes shouldBe empty
    result.toOption.get.summary should include("not found")
  }

  test("CompositeQuery should execute steps in sequence and merge results") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(
      GraphQuery.CompositeQuery(
        steps = Seq(
          GraphQuery.FindNodes(label = Some("Person")),
          GraphQuery.FindNodes(label = Some("Organization"))
        )
      )
    )

    result should be(a[Right[_, _]])
    val qr = result.toOption.get
    qr.nodes.map(_.id) should contain theSameElementsAs Seq("alice", "bob", "acme")
  }

  test("CompositeQuery with empty steps should return empty result") {
    val store    = buildTestStore()
    val executor = new GraphQueryExecutor(store)

    val result = executor.execute(GraphQuery.CompositeQuery(steps = Seq.empty))

    result should be(a[Right[_, _]])
    result.toOption.get.isEmpty shouldBe true
  }

  test("GraphQueryResult merge should deduplicate nodes by ID") {
    val r1 = GraphQueryResult(
      nodes = Seq(Node("a", "A"), Node("b", "B")),
      edges = Seq(Edge("a", "b", "REL"))
    )
    val r2 = GraphQueryResult(
      nodes = Seq(Node("b", "B"), Node("c", "C")),
      edges = Seq(Edge("b", "c", "REL"))
    )

    val merged = r1.merge(r2)
    merged.nodes should have size 3
    merged.edges should have size 2
  }
}
