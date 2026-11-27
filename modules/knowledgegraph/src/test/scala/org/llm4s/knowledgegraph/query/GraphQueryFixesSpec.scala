package org.llm4s.knowledgegraph.query

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.knowledgegraph.storage.{ Direction, InMemoryGraphStore }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The query fixes of epic #2983: #2973 (translation), #2974 (execution), #2975 (QA context and ranking). */
class GraphQueryFixesSpec extends AnyFunSuite with Matchers {

  private def person(id: String, name: String, props: (String, ujson.Value)*): Node =
    Node(id, "Person", Map[String, ujson.Value]("name" -> ujson.Str(name)) ++ props)

  private def store(nodes: Seq[Node], edges: Seq[Edge]): InMemoryGraphStore = {
    val s = new InMemoryGraphStore()
    nodes.foreach(s.upsertNode)
    edges.foreach(s.upsertEdge)
    s
  }

  // alice -WORKS_FOR-> acme <-WORKS_FOR- bob ; alice -KNOWS-> carol -KNOWS-> dave ; acme -LOCATED_IN-> nyc
  private def company(): InMemoryGraphStore = store(
    Seq(
      person("person_17", "Alice"),
      person("bob", "Bob"),
      person("carol", "Carol"),
      person("dave", "Dave"),
      Node("acme", "Org", Map("name" -> ujson.Str("Acme"))),
      Node("nyc", "City", Map("name" -> ujson.Str("New York")))
    ),
    Seq(
      Edge("person_17", "acme", "WORKS_FOR"),
      Edge("bob", "acme", "WORKS_FOR"),
      Edge("person_17", "carol", "KNOWS"),
      Edge("carol", "dave", "KNOWS"),
      Edge("acme", "nyc", "LOCATED_IN")
    )
  )

  private def translator = new GraphQueryTranslator(new ScriptedLLM(), new InMemoryGraphStore())

  // ─── #2973 ────────────────────────────────────────────────────────────────────────────────

  test("a reply of the wrong shape is a Left, never an exception (#2973)") {
    Seq(
      """{"query": "find_nodes"}""",
      """{"type": "find_neighbors", "node_id": {"id": "a"}}""",
      """{"type": "find_neighbors", "node_id": "a", "max_depth": "two"}""",
      """{"type": "find_neighbors", "node_id": "a", "direction": 3}""",
      """{"type": "describe_node", "node_id": "a", "include_neighbors": 5}""",
      """{"type": "composite", "steps": [{"no_type": true}]}""",
      """{"type": "composite", "steps": [{"type": "composite", "steps": []}]}""",
      """{"type": "find_nodes", "properties": [1, 2]}"""
    ).foreach(reply => translator.parseQueryResponse(reply) shouldBe a[Left[_, _]])
    // A top-level array no longer throws: the object inside it is read
    translator.parseQueryResponse("""[{"type": "find_nodes"}]""") shouldBe Right(GraphQuery.FindNodes())
  }

  test("a numeric id and a numeric-string depth are accepted (#2973)") {
    translator.parseQueryResponse("""{"type": "find_neighbors", "node_id": 42, "max_depth": "2"}""") shouldBe
      Right(GraphQuery.FindNeighbors("42", maxDepth = 2))
  }

  test("the JSON object is found inside prose and a code fence (#2973)") {
    val reply =
      """Sure! Here is the query:
        |```json
        |{"type": "describe_node", "node_id": "alice"}
        |```
        |Let me know if you need more.""".stripMargin
    translator.parseQueryResponse(reply) shouldBe Right(GraphQuery.DescribeNode("alice"))
  }

  test("a null explanation from the native query generator is an empty one (#2973)") {
    val llm = new ScriptedLLM("""Here: {"query": "MATCH (n) RETURN n", "explanation": null}""")
    new NativeQueryGenerator(llm).generate("all nodes", QueryLanguage.Cypher).map(_.explanation) shouldBe Right("")
  }

  test("the translator is told the resolved entities' ids (#2973)") {
    val llm = new ScriptedLLM("""{"type": "find_neighbors", "node_id": "person_17"}""")
    val t   = new GraphQueryTranslator(llm, company())
    t.translate("Who does Alice know?", Seq(IdentifiedEntity("Alice", "person_17", "Person"))) shouldBe
      Right(GraphQuery.FindNeighbors("person_17"))
    llm.prompts.head should include("id: person_17, label: Person")
  }

  test("a composite step refers to an earlier step's nodes with $N (#2973)") {
    val executor = new GraphQueryExecutor(company())
    val query = GraphQuery.CompositeQuery(
      Seq(
        GraphQuery.FindNodes(properties = Map("name" -> "Alice")),
        GraphQuery.FindNeighbors("$1", relationshipType = Some("WORKS_FOR"))
      )
    )
    val result = executor.execute(query).toOption.get
    (result.nodes.map(_.id) should contain).allOf("person_17", "acme")
    result.edges should contain(Edge("person_17", "acme", "WORKS_FOR"))
    result.missingNodeIds shouldBe empty
  }

  test("a reference to a step that has not run is a Left; to an empty step, a summary (#2973)") {
    val executor = new GraphQueryExecutor(company())
    executor.execute(GraphQuery.CompositeQuery(Seq(GraphQuery.FindNeighbors("$2")))) shouldBe a[Left[_, _]]
    val empty = executor
      .execute(
        GraphQuery.CompositeQuery(
          Seq(GraphQuery.FindNodes(properties = Map("name" -> "Nobody")), GraphQuery.DescribeNode("$1"))
        )
      )
      .toOption
      .get
    empty.summary should include("step 1 found no nodes")
  }

  test("an unknown id is reported as missing, not as a node with no neighbours (#2973)") {
    val executor = new GraphQueryExecutor(company())
    Seq(
      GraphQuery.FindNeighbors("alice"),
      GraphQuery.DescribeNode("alice"),
      GraphQuery.FindPath("alice", "bob")
    ).foreach { q =>
      val r = executor.execute(q).toOption.get
      r.missingNodeIds shouldBe Seq("alice")
      r.summary should include("not found")
    }
  }

  // ─── #2974 ────────────────────────────────────────────────────────────────────────────────

  test("a multi-hop FindNeighbors follows only the relationship type, as depth 1 does (#2974)") {
    val executor = new GraphQueryExecutor(company())
    val r = executor
      .execute(GraphQuery.FindNeighbors("person_17", relationshipType = Some("KNOWS"), maxDepth = 2))
      .toOption
      .get
    r.nodes.map(_.id) shouldBe Seq("person_17", "carol", "dave")
    r.edges.map(_.relationship).toSet shouldBe Set("KNOWS")
  }

  test("FindPath connects two nodes through an edge walked against its direction (#2974)") {
    val executor = new GraphQueryExecutor(company())
    val r        = executor.execute(GraphQuery.FindPath("person_17", "bob")).toOption.get
    r.paths shouldBe Seq(List(Edge("person_17", "acme", "WORKS_FOR"), Edge("bob", "acme", "WORKS_FOR")))
    executor
      .execute(GraphQuery.FindPath("person_17", "bob", direction = Direction.Outgoing))
      .toOption
      .get
      .paths shouldBe
      empty
  }

  test("FindPath from a node to itself is not reported as a path (#2974)") {
    val r = new GraphQueryExecutor(company()).execute(GraphQuery.FindPath("bob", "bob")).toOption.get
    r.paths shouldBe empty
    r.nodes.map(_.id) shouldBe Seq("bob")
  }

  test("depth and hop counts are clamped, and composite steps capped (#2974)") {
    val executor = new GraphQueryExecutor(company(), maxDepth = 1, maxCompositeSteps = 2)
    executor.execute(GraphQuery.FindNeighbors("person_17", maxDepth = 1000000)).toOption.get.nodes.map(_.id) shouldBe
      Seq("person_17", "acme", "carol")
    executor.execute(GraphQuery.FindNeighbors("person_17", maxDepth = -3)).toOption.get.nodes.map(_.id) shouldBe
      Seq("person_17", "acme", "carol")
    executor.execute(GraphQuery.FindPath("person_17", "bob", maxHops = 99)).toOption.get.paths shouldBe empty
    val many = GraphQuery.CompositeQuery(Seq.fill(5)(GraphQuery.DescribeNode("bob")))
    executor.execute(many).toOption.get.summary should include("3 step(s) beyond the limit of 2 skipped")
  }

  test("a path query returns at most maxPaths shortest paths (#2974)") {
    // a reaches z through each of five middles
    val middles = (1 to 5).map(i => s"m$i")
    val s = store(
      (Seq("a", "z") ++ middles).map(id => Node(id, "N")),
      middles.flatMap(m => Seq(Edge("a", m, "E"), Edge(m, "z", "E")))
    )
    new GraphQueryExecutor(s, maxPaths = 3).execute(GraphQuery.FindPath("a", "z")).toOption.get.paths should have size 3
  }

  // ─── #2975 ────────────────────────────────────────────────────────────────────────────────

  test("entities match by name, the label only narrowing, best first and capped (#2975)") {
    val people = (1 to 30).map(i => person(s"p$i", s"Person $i"))
    val s = store(
      people ++ Seq(
        person("alice", "Alice"),
        person("alice2", "Alice Smith"),
        Node("global", "Org", Map("name" -> ujson.Str("Global Corp"))),
        Node("alice-org", "Org", Map("name" -> ujson.Str("Alice")))
      ),
      Nil
    )
    val llm      = new ScriptedLLM("""[{"mention": "Alice", "label": "Person"}, {"mention": "", "label": "Person"}]""")
    val entities = new GraphQAPipeline(llm, s, GraphQAConfig(maxEntities = 5)).identifyEntities("Alice?").toOption.get
    entities.map(e => (e.nodeId, e.confidence)) shouldBe Seq("alice" -> 1.0, "alice2" -> 0.7)
  }

  test("an empty graph is answered without a model call (#2975)") {
    val llm = new ScriptedLLM("unused")
    val r   = new GraphQAPipeline(llm, new InMemoryGraphStore()).ask("Anything?").toOption.get
    r.answer shouldBe GraphQAPipeline.EmptyGraphAnswer
    llm.conversations shouldBe empty
  }

  test("a hub the question names keeps its edges when the context is trimmed, under every ranking (#2975)") {
    val leaves = (1 to 60).map(i => Node(f"leaf$i%02d", "Thing", Map("name" -> ujson.Str(s"Leaf $i"))))
    val s      = store(person("hub", "Hub") +: leaves, leaves.map(l => Edge("hub", l.id, "HAS")))
    Seq(
      RankingAlgorithm.PageRank,
      RankingAlgorithm.BetweennessCentrality,
      RankingAlgorithm.ClosenessCentrality,
      RankingAlgorithm.DegreeCentrality
    ).foreach { algorithm =>
      val llm = new ScriptedLLM("""[{"mention": "Hub"}]""", "answer")
      val config =
        GraphQAConfig(maxContextNodes = 10, rankingAlgorithm = algorithm, includeCitations = false, maxHops = 1)
      val r = new GraphQAPipeline(llm, s, config).askWithQuery("What does Hub have?", GraphQuery.FindNeighbors("hub"))
      val result = r.fold(e => fail(s"$algorithm: $e"), identity)
      result.queryResult.nodes should have size 10
      result.queryResult.nodes.head.id shouldBe "hub"
      result.queryResult.edges should have size 9
    }
  }

  test("without ranking the context is still trimmed, and long values and paths are cut (#2975)") {
    val long = "x" * 2000
    val s    = store((1 to 20).map(i => person(s"p$i", s"P$i", "bio" -> ujson.Str(long))), Nil)
    val llm  = new ScriptedLLM("[]", "answer")
    val config =
      GraphQAConfig(useRanking = false, maxContextNodes = 5, maxPropertyChars = 100, includeCitations = false)
    val r = new GraphQAPipeline(llm, s, config).askWithQuery("?", GraphQuery.FindNodes(label = Some("Person")))
    r.toOption.get.queryResult.nodes should have size 5
    val answerPrompt = llm.prompts.last
    answerPrompt should include("[1900 more chars]")
    (answerPrompt should not).include("x" * 101)
  }

  test("maxContextTokens bounds the context (#2975)") {
    val s   = store((1 to 50).map(i => person(s"p$i", s"Person number $i")), Nil)
    val llm = new ScriptedLLM("[]", "answer")
    val config =
      GraphQAConfig(useRanking = false, maxContextNodes = 50, maxContextTokens = Some(60), includeCitations = false)
    new GraphQAPipeline(llm, s, config).askWithQuery("?", GraphQuery.FindNodes(label = Some("Person")))
    val context = llm.prompts.last
    context.linesIterator.count(_.startsWith("- [Person]")) should ((be > 0).and(be < 50))
  }

  test("betweenness: exact values on a chain, and no exception for an edge to a node outside the graph (#2975)") {
    val chain = Graph(
      Map("a" -> Node("a", "N"), "b" -> Node("b", "N"), "c" -> Node("c", "N")),
      List(Edge("a", "b", "E"), Edge("b", "c", "E"))
    )
    GraphRanking.betweennessCentrality(chain) shouldBe Map("a" -> 0.0, "b" -> 1.0, "c" -> 0.0)
    val star = Graph(
      Map("h" -> Node("h", "N"), "x" -> Node("x", "N"), "y" -> Node("y", "N"), "z" -> Node("z", "N")),
      List(Edge("h", "x", "E"), Edge("h", "y", "E"), Edge("h", "z", "E"), Edge("h", "outside", "E"))
    )
    GraphRanking.betweennessCentrality(star) shouldBe Map("h" -> 1.0, "x" -> 0.0, "y" -> 0.0, "z" -> 0.0)
  }

  test("degree centrality counts distinct neighbours, and ignores edges to unknown nodes (#2975)") {
    val g = Graph(
      Map("a" -> Node("a", "N"), "b" -> Node("b", "N"), "c" -> Node("c", "N")),
      List(
        Edge("a", "b", "E1"),
        Edge("a", "b", "E2"),
        Edge("b", "a", "E3"),
        Edge("a", "a", "SELF"),
        Edge("a", "q", "E")
      )
    )
    GraphRanking.degreeCentrality(g) shouldBe Map("a" -> 0.5, "b" -> 0.5, "c" -> 0.0)
  }

  test("PageRank still sums to 1 with an edge to a node outside the graph (#2975)") {
    val g = Graph(
      Map("a" -> Node("a", "N"), "b" -> Node("b", "N")),
      List(Edge("a", "b", "E"), Edge("a", "outside", "E"))
    )
    GraphRanking.pageRank(g).values.sum shouldBe (1.0 +- 1e-6)
  }
}
