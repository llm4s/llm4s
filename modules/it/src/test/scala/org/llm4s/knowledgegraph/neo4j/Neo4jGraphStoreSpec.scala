package org.llm4s.knowledgegraph.neo4j

import org.llm4s.knowledgegraph.{ Edge, Node }
import org.llm4s.knowledgegraph.storage.{ Direction, GraphFilter, TraversalConfig }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Integration tests for Neo4jGraphStore.
 *
 * Requires a running Neo4j instance on bolt://localhost:7687 with credentials
 * neo4j / neo4j (the default). All tests are automatically skipped (cancelled)
 * if no server is reachable, so the regular CI build is not affected.
 *
 * To run locally:
 *   docker run -p 7687:7687 -e NEO4J_AUTH=neo4j/neo4j neo4j:5
 *   sbt "it/test"
 *
 * Note: The neo4j-harness embedded approach was attempted but is unusable on
 * Java 17+ with neo4j-harness 5.26.x — the harness's LocalNettyConnector uses
 * Netty 4.1.115.Final's NioIoHandler in a way that does not support
 * LocalServerChannel, causing it to crash on startup regardless of the JVM version.
 */
class Neo4jGraphStoreSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  private var store: Neo4jGraphStore = _
  private var neo4jAvailable         = false

  private val neo4jUri  = sys.env.getOrElse("NEO4J_URI", "bolt://localhost:7687")
  private val neo4jUser = sys.env.getOrElse("NEO4J_USER", "neo4j")
  private val neo4jPass = sys.env.getOrElse("NEO4J_PASSWORD", "neo4j")

  override def beforeAll(): Unit =
    Neo4jGraphStore(neo4jUri, neo4jUser, neo4jPass) match {
      case Right(s) =>
        store = s
        // Probe with a quick query to confirm the server is actually reachable
        neo4jAvailable = store.stats().isRight
        if (!neo4jAvailable && store != null) {
          store.close()
          store = null
        }
      case Left(_) =>
        neo4jAvailable = false
    }

  override def afterAll(): Unit =
    if (neo4jAvailable && store != null) store.close()

  /** Clear all LLM4S nodes before each test for isolation. */
  override def beforeEach(): Unit =
    if (neo4jAvailable)
      store
        .executeWrite("MATCH (n:LLM4S) DETACH DELETE n")
        .fold(
          e => throw new RuntimeException(s"Failed to clear graph: $e"),
          identity
        )

  /** Skip the current test if Neo4j is not reachable. */
  private def requireNeo4j(): Unit =
    assume(neo4jAvailable, "No Neo4j at bolt://localhost:7687 — start one to run this test")

  // ─── Node CRUD ────────────────────────────────────────────────────────────

  test("upsertNode / getNode: round-trip stores and retrieves a node") {
    requireNeo4j()
    val node = Node("1", "Person", Map("name" -> ujson.Str("Alice"), "age" -> ujson.Num(30)))
    store.upsertNode(node) shouldBe Right(())
    store.getNode("1") shouldBe Right(Some(node))
  }

  test("upsertNode: second upsert updates the existing node") {
    requireNeo4j()
    store.upsertNode(Node("1", "Person", Map("name" -> ujson.Str("Alice"))))
    store.upsertNode(Node("1", "Person", Map("name" -> ujson.Str("Alicia"))))
    store.getNode("1").toOption.get.flatMap(_.properties.get("name")) shouldBe Some(ujson.Str("Alicia"))
  }

  test("upsertNode: replacement semantics remove old properties") {
    requireNeo4j()
    store.upsertNode(Node("1", "Person", Map("name" -> ujson.Str("Alice"), "city" -> ujson.Str("NYC")))) shouldBe Right(
      ()
    )
    store.upsertNode(Node("1", "Person", Map("name" -> ujson.Str("Alicia")))) shouldBe Right(())
    val props = store.getNode("1").toOption.flatten.map(_.properties).getOrElse(Map.empty)
    props.get("name") shouldBe Some(ujson.Str("Alicia"))
    props.contains("city") shouldBe false
  }

  test("upsertNode: ujson.Null properties are skipped explicitly") {
    requireNeo4j()
    store.upsertNode(Node("1", "Person", Map("nickname" -> ujson.Null, "name" -> ujson.Str("Alice")))) shouldBe Right(
      ()
    )
    val props = store.getNode("1").toOption.flatten.map(_.properties).getOrElse(Map.empty)
    props.contains("nickname") shouldBe false
    props.get("name") shouldBe Some(ujson.Str("Alice"))
  }

  test("getNode: returns Right(None) for a missing node") {
    requireNeo4j()
    store.getNode("does-not-exist") shouldBe Right(None)
  }

  // ─── Edge CRUD ────────────────────────────────────────────────────────────

  test("upsertEdge: rejects edge when source node is missing") {
    requireNeo4j()
    store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "EDGE")) shouldBe a[Left[_, _]]
  }

  test("upsertEdge: rejects edge when target node is missing") {
    requireNeo4j()
    store.upsertNode(Node("1", "A"))
    store.upsertEdge(Edge("1", "2", "EDGE")) shouldBe a[Left[_, _]]
  }

  test("upsertEdge: creates edge between two existing nodes") {
    requireNeo4j()
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "KNOWS")) shouldBe Right(())
  }

  test("upsertEdge: rejects invalid relationship type characters") {
    requireNeo4j()
    store.upsertNode(Node("1", "A"))
    store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "WORKS-FOR")) shouldBe a[Left[_, _]]
  }

  test("upsertEdge: replacement semantics remove old edge properties") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "KNOWS", Map("since" -> ujson.Num(2020), "weight" -> ujson.Num(1)))) shouldBe Right(
      ()
    )
    store.upsertEdge(Edge("1", "2", "KNOWS", Map("weight" -> ujson.Num(2)))) shouldBe Right(())
    val edge = store.getNeighbors("1", Direction.Outgoing).toOption.get.find(_.node.id == "2").map(_.edge).get
    edge.properties.contains("since") shouldBe false
    edge.properties.get("weight") shouldBe Some(ujson.Num(2))
  }

  // ─── Neighbors ────────────────────────────────────────────────────────────

  test("getNeighbors: Outgoing returns target nodes") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B")); store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "E")); store.upsertEdge(Edge("1", "3", "E"))
    store.getNeighbors("1", Direction.Outgoing).toOption.get.map(_.node.id).toSet shouldBe Set("2", "3")
  }

  test("getNeighbors: Incoming returns source nodes") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B")); store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("2", "1", "E")); store.upsertEdge(Edge("3", "1", "E"))
    store.getNeighbors("1", Direction.Incoming).toOption.get.map(_.node.id).toSet shouldBe Set("2", "3")
  }

  test("getNeighbors: Both returns all adjacent nodes") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B")); store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "E")); store.upsertEdge(Edge("3", "1", "E"))
    store.getNeighbors("1", Direction.Both).toOption.get.map(_.node.id).toSet shouldBe Set("2", "3")
  }

  // ─── Query ────────────────────────────────────────────────────────────────

  test("query: filters nodes by label") {
    requireNeo4j()
    store.upsertNode(Node("1", "Person", Map("name" -> ujson.Str("Alice"))))
    store.upsertNode(Node("2", "Organization", Map("name" -> ujson.Str("ACME"))))
    store.query(GraphFilter(nodeLabel = Some("Person"))).toOption.get.nodes.keys.toSet shouldBe Set("1")
  }

  test("query: filters nodes by property value") {
    requireNeo4j()
    store.upsertNode(Node("1", "Person", Map("city" -> ujson.Str("NYC"))))
    store.upsertNode(Node("2", "Person", Map("city" -> ujson.Str("LA"))))
    store
      .query(GraphFilter(propertyKey = Some("city"), propertyValue = Some("NYC")))
      .toOption
      .get
      .nodes
      .keys
      .toSet shouldBe Set("1")
  }

  test("query: filters numeric property values using string semantics") {
    requireNeo4j()
    store.upsertNode(Node("1", "Person", Map("age" -> ujson.Num(42))))
    store.upsertNode(Node("2", "Person", Map("age" -> ujson.Num(7))))
    store
      .query(GraphFilter(propertyKey = Some("age"), propertyValue = Some("42.0")))
      .toOption
      .get
      .nodes
      .keys
      .toSet shouldBe Set("1")
  }

  test("query: rejects unsafe propertyKey input") {
    requireNeo4j()
    store.upsertNode(Node("1", "Person", Map("city" -> ujson.Str("NYC"))))
    val result = store.query(GraphFilter(propertyKey = Some("city` OR 1=1 OR n.`x"), propertyValue = Some("NYC")))
    result shouldBe a[Left[_, _]]
  }

  test("query: filters edges by relationship type") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B")); store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "KNOWS")); store.upsertEdge(Edge("1", "3", "WORKS_FOR"))
    val result = store.query(GraphFilter(relationshipType = Some("KNOWS"))).toOption.get
    result.edges.size shouldBe 1
    result.edges.head.relationship shouldBe "KNOWS"
  }

  test("query: relationshipType is parameterized and does not allow query-shaping input") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B")); store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "KNOWS")); store.upsertEdge(Edge("1", "3", "WORKS_FOR"))
    val injectedType = "KNOWS' OR 1=1 OR type(r) = 'X"
    val result       = store.query(GraphFilter(relationshipType = Some(injectedType))).toOption.get
    result.edges shouldBe empty
  }

  // ─── Traversal ────────────────────────────────────────────────────────────

  test("traverse: returns empty for non-existent start node") {
    requireNeo4j()
    store.traverse("non-existent") shouldBe Right(Seq.empty)
  }

  test("traverse: BFS linear chain visits nodes in order") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B")); store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "E")); store.upsertEdge(Edge("2", "3", "E"))
    store.traverse("1", TraversalConfig(direction = Direction.Outgoing)).toOption.get.map(_.id) shouldBe Seq(
      "1",
      "2",
      "3"
    )
  }

  test("traverse: respects maxDepth") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B"))
    store.upsertNode(Node("3", "C")); store.upsertNode(Node("4", "D"))
    store.upsertEdge(Edge("1", "2", "E")); store.upsertEdge(Edge("2", "3", "E")); store.upsertEdge(Edge("3", "4", "E"))
    store
      .traverse("1", TraversalConfig(maxDepth = 2, direction = Direction.Outgoing))
      .toOption
      .get
      .map(_.id)
      .toSet shouldBe Set("1", "2", "3")
  }

  test("traverse: handles cycles without infinite loop") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B")); store.upsertNode(Node("3", "C"))
    store.upsertEdge(Edge("1", "2", "E")); store.upsertEdge(Edge("2", "3", "E")); store.upsertEdge(Edge("3", "1", "E"))
    val ids = store.traverse("1").toOption.get.map(_.id)
    ids.distinct should have size 3
    ids should have size 3
  }

  // ─── Delete ───────────────────────────────────────────────────────────────

  test("deleteNode: removes node and its connected edges") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "E"))
    store.deleteNode("1") shouldBe Right(())
    store.getNode("1") shouldBe Right(None)
    store.getNeighbors("2", Direction.Incoming).toOption.get.map(_.node.id) should not contain "1"
  }

  test("deleteEdge: removes only the specified edge") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "KNOWS")); store.upsertEdge(Edge("1", "2", "WORKS_WITH"))
    store.deleteEdge("1", "2", "KNOWS") shouldBe Right(())
    val rels = store.getNeighbors("1", Direction.Outgoing).toOption.get.map(_.edge.relationship)
    rels should contain("WORKS_WITH")
    rels should not contain "KNOWS"
  }

  // ─── loadAll / stats ──────────────────────────────────────────────────────

  test("loadAll: returns complete graph") {
    requireNeo4j()
    store.upsertNode(Node("1", "A")); store.upsertNode(Node("2", "B"))
    store.upsertEdge(Edge("1", "2", "E"))
    val graph = store.loadAll().toOption.get
    graph.nodes.size shouldBe 2
    graph.edges.size shouldBe 1
  }

  test("stats: accurate node and edge counts") {
    requireNeo4j()
    (1 to 3).foreach(i => store.upsertNode(Node(i.toString, s"Node$i")))
    store.upsertEdge(Edge("1", "2", "E")); store.upsertEdge(Edge("2", "3", "E"))
    val s = store.stats().toOption.get
    s.nodeCount shouldBe 3L
    s.edgeCount shouldBe 2L
  }

  // ─── Native Cypher pass-through ───────────────────────────────────────────

  test("executeRead: returns results for arbitrary Cypher") {
    requireNeo4j()
    store.upsertNode(Node("1", "Person", Map("name" -> ujson.Str("Alice"))))
    val rows = store.executeRead("MATCH (n:LLM4S) RETURN n.llm4s_id AS id").toOption.get
    rows should not be empty
    rows.head("id") should include("1")
  }

  test("executeWrite: executes arbitrary write Cypher") {
    requireNeo4j()
    store.upsertNode(Node("1", "A"))
    store.executeWrite("MATCH (n:LLM4S {llm4s_id: '1'}) SET n.custom = 'hello'") shouldBe Right(())
    store.executeRead("MATCH (n:LLM4S {llm4s_id: '1'}) RETURN n.custom AS v").toOption.get.head("v") should include(
      "hello"
    )
  }

  // ─── Property type round-trips ────────────────────────────────────────────

  test("property values: string, number, boolean are preserved") {
    requireNeo4j()
    val props = Map("str" -> ujson.Str("hello"), "num" -> ujson.Num(42), "bool" -> ujson.Bool(true))
    store.upsertNode(Node("1", "Test", props))
    val retrieved = store.getNode("1").toOption.get.get
    retrieved.properties("str") shouldBe ujson.Str("hello")
    retrieved.properties("num") shouldBe ujson.Num(42)
    retrieved.properties("bool") shouldBe ujson.Bool(true)
  }
}
