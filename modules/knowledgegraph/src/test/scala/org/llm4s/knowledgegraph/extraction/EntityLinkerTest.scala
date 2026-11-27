package org.llm4s.knowledgegraph.extraction

import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Completion
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import java.time.Instant

class EntityLinkerTest extends AnyFunSuite with Matchers with MockFactory {

  private def makeCompletion(content: String): Completion = Completion(
    id = "test-id",
    model = ModelName("test-model"),
    created = Instant.ofEpochSecond(1234567890L),
    message = org.llm4s.llmconnect.model.AssistantMessage(Some(content), Nil),
    usage = None
  )

  test("EntityLinker should merge nodes with same label and name (deterministic)") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Alice"))),
        "3" -> Node("3", "Organization", Map("name" -> ujson.Str("Acme")))
      ),
      List(
        Edge("1", "3", "WORKS_FOR"),
        Edge("2", "3", "WORKS_FOR")
      )
    )

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    val linked = result.toOption.get
    linked.nodes.values.count(_.label == "Person") shouldBe 1
    linked.nodes.values.count(_.label == "Organization") shouldBe 1
    linked.edges.count(_.relationship == "WORKS_FOR") shouldBe 1 // Deduplicated
  }

  test("EntityLinker should be case-insensitive when matching names") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("alice"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Alice")))
      ),
      List.empty
    )

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    result.toOption.get.nodes should have size 1
  }

  test("EntityLinker should normalize whitespace when matching names") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Steve  Jobs"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str(" Steve Jobs ")))
      ),
      List.empty
    )

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    result.toOption.get.nodes should have size 1
  }

  test("EntityLinker should not merge nodes with different labels") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Apple"))),
        "2" -> Node("2", "Organization", Map("name" -> ujson.Str("Apple")))
      ),
      List.empty
    )

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    result.toOption.get.nodes should have size 2
  }

  test("EntityLinker should rewrite edges after merging") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Alice"))),
        "3" -> Node("3", "Person", Map("name" -> ujson.Str("Bob")))
      ),
      List(
        Edge("2", "3", "KNOWS") // Should be rewritten to canonical Alice ID
      )
    )

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    val linked = result.toOption.get
    linked.edges should have size 1
    // The edge should reference the canonical Alice node
    (linked.nodes should contain).key(linked.edges.head.source)
    linked.edges.head.target shouldBe "3"
  }

  test("EntityLinker should remove self-loops created by merging") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Alice")))
      ),
      List(
        Edge("1", "2", "KNOWS") // Would become self-loop after merge
      )
    )

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    result.toOption.get.edges shouldBe empty
  }

  test("EntityLinker should merge properties from duplicate nodes") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"), "role" -> ujson.Str("CEO"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Alice"), "department" -> ujson.Str("Engineering")))
      ),
      List.empty
    )

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    val merged = result.toOption.get.nodes.values.head
    (merged.properties should contain).key("role")
    (merged.properties should contain).key("department")
    (merged.properties should contain).key("name")
  }

  test("EntityLinker should not merge nodes without name property by ID") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "alice" -> Node("alice", "Person"),
        "ALICE" -> Node("ALICE", "Person")
      ),
      List.empty
    )

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    // A node without a name is keyed by its exact id, so it merges with nothing, even an id differing only in case (#2967)
    result.toOption.get.nodes should have size 2
  }

  test("EntityLinker should handle empty graph") {
    val linker = new EntityLinker()
    val result = linker.link(Graph.empty)

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe Graph.empty
  }

  test("EntityLinker should find disambiguation candidates for substring matches") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Steve Jobs"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Jobs")))
      ),
      List.empty
    )

    val candidates = linker.findDisambiguationCandidates(graph)
    candidates should have size 1
  }

  test("EntityLinker should not flag exact-match names as disambiguation candidates") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Alice")))
      ),
      List.empty
    )

    // Exact matches are handled by deterministic merge, not disambiguation
    val afterMerge = linker.deterministicMerge(graph)
    val candidates = linker.findDisambiguationCandidates(afterMerge)
    candidates shouldBe empty
  }

  test("EntityLinker with LLM should merge confirmed ambiguous pairs") {
    val llmClient = mock[LLMClient]
    val linker    = new EntityLinker(Some(llmClient))

    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Steve Jobs"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Jobs"))),
        "3" -> Node("3", "Organization", Map("name" -> ujson.Str("Apple")))
      ),
      List(
        Edge("1", "3", "FOUNDED"),
        Edge("2", "3", "WORKS_FOR")
      )
    )

    val disambiguationResponse = """{"merges": [{"pair": 1, "merge": true}]}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(disambiguationResponse)))

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    val linked = result.toOption.get
    // "Jobs" and "Steve Jobs" should be merged; "Steve Jobs" kept (longer name)
    linked.nodes.values.count(_.label == "Person") shouldBe 1
    val person = linked.nodes.values.find(_.label == "Person").get
    person.properties("name").str shouldBe "Steve Jobs"
  }

  test("EntityLinker with LLM should keep rejected pairs separate") {
    val llmClient = mock[LLMClient]
    val linker    = new EntityLinker(Some(llmClient))

    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Steve Jobs"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Jobs")))
      ),
      List.empty
    )

    val disambiguationResponse = """{"merges": [{"pair": 1, "merge": false}]}"""

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(disambiguationResponse)))

    val result = linker.link(graph)

    result should be(a[Right[_, _]])
    result.toOption.get.nodes should have size 2
  }

  test("EntityLinker linkTracked should preserve and union source provenance for merged nodes") {
    val linker = new EntityLinker()

    val doc1 = DocumentSource("doc1", "Document 1")
    val doc2 = DocumentSource("doc2", "Document 2")

    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Alice"))),
        "3" -> Node("3", "Organization", Map("name" -> ujson.Str("Acme")))
      ),
      List(Edge("1", "3", "WORKS_FOR"), Edge("2", "3", "WORKS_FOR"))
    )

    val tracked = SourceTrackedGraph(
      graph = graph,
      sources = Seq(doc1, doc2),
      nodeSources = Map("1" -> Set("doc1"), "2" -> Set("doc2"), "3" -> Set("doc1", "doc2")),
      edgeSources = Map(
        ("1", "3", "WORKS_FOR") -> Set("doc1"),
        ("2", "3", "WORKS_FOR") -> Set("doc2")
      )
    )

    val result = linker.linkTracked(tracked)

    result should be(a[Right[_, _]])
    val linked = result.toOption.get

    // After merging, only one Person node should remain
    linked.graph.nodes.values.count(_.label == "Person") shouldBe 1
    linked.sources should have size 2 // Both sources preserved

    // The canonical Person node (lowest ID = "1") should have sources from BOTH doc1 and doc2
    val canonicalPersonId = linked.graph.nodes.values.find(_.label == "Person").get.id
    linked.nodeSources(canonicalPersonId) shouldBe Set("doc1", "doc2")

    // The surviving WORKS_FOR edge should be attributed to doc1 (from the canonical node "1")
    // The duplicate edge ("2"->"3") was rewritten to ("1"->"3") and deduplicated;
    // edgeSources for ("1","3","WORKS_FOR") should contain doc1
    linked.edgeSources.get((canonicalPersonId, "3", "WORKS_FOR")) should not be empty
  }

  test("EntityLinker with LLM should fall back gracefully on disambiguation parse failure") {
    val llmClient = mock[LLMClient]
    val linker    = new EntityLinker(Some(llmClient))

    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Steve Jobs"))),
        "2" -> Node("2", "Person", Map("name" -> ujson.Str("Jobs")))
      ),
      List.empty
    )

    // LLM returns malformed JSON
    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion("not valid json at all")))

    val result = linker.link(graph)

    // Should succeed (Right) with the graph intact — not fail with an error
    result should be(a[Right[_, _]])
    // Both nodes still present (disambiguation skipped, deterministic merge didn't merge them
    // since names differ after normalization)
    result.toOption.get.nodes should have size 2
  }

  // ─── #2967, #2962 ─────────────────────────────────────────────────────────────────────────

  private def person(id: String, name: String, props: (String, ujson.Value)*): Node =
    Node(id, "Person", Map[String, ujson.Value]("name" -> ujson.Str(name)) ++ props)

  private def llmReplying(reply: String): LLMClient = {
    val client = mock[LLMClient]
    (client.complete _).expects(*, *).returning(Right(makeCompletion(reply)))
    client
  }

  test("linkTracked moves provenance through a model-driven merge (#2967)") {
    val linker = new EntityLinker(Some(llmReplying("""{"merges": [{"pair": 1, "merge": true}]}""")))
    val graph = Graph(
      Map(
        "d1__jobs"  -> person("d1__jobs", "Jobs"),
        "d2__steve" -> person("d2__steve", "Steve Jobs"),
        "d1__apple" -> Node("d1__apple", "Org", Map("name" -> ujson.Str("Apple")))
      ),
      List(Edge("d1__jobs", "d1__apple", "WORKS_FOR"))
    )
    val tracked = SourceTrackedGraph(
      graph,
      Seq(DocumentSource("d1", "One"), DocumentSource("d2", "Two")),
      Map("d1__jobs"                             -> Set("d1"), "d2__steve" -> Set("d2"), "d1__apple" -> Set("d1")),
      Map(("d1__jobs", "d1__apple", "WORKS_FOR") -> Set("d1"))
    )
    val linked = linker.linkTracked(tracked).toOption.get
    linked.graph.nodes.keySet shouldBe Set("d2__steve", "d1__apple")
    linked.nodeSources shouldBe Map("d2__steve" -> Set("d1", "d2"), "d1__apple" -> Set("d1"))
    linked.getEdgeSources("d2__steve", "d1__apple", "WORKS_FOR").map(_.id) shouldBe Set("d1")
    linked.graph.edges shouldBe List(Edge("d2__steve", "d1__apple", "WORKS_FOR"))
  }

  test("linkTracked unions the sources of edges that merge onto one key (#2962)") {
    val graph = Graph(
      Map(
        "d1__a"    -> person("d1__a", "Alice"),
        "d2__a"    -> person("d2__a", "Alice"),
        "d1__acme" -> Node("d1__acme", "Org", Map("name" -> ujson.Str("Acme"))),
        "d2__acme" -> Node("d2__acme", "Org", Map("name" -> ujson.Str("Acme")))
      ),
      List(Edge("d1__a", "d1__acme", "WORKS_AT"), Edge("d2__a", "d2__acme", "WORKS_AT"))
    )
    val tracked = SourceTrackedGraph(
      graph,
      Seq(DocumentSource("d1", "One"), DocumentSource("d2", "Two")),
      graph.nodes.keys.map(id => id -> Set(id.take(2))).toMap,
      Map(("d1__a", "d1__acme", "WORKS_AT") -> Set("d1"), ("d2__a", "d2__acme", "WORKS_AT") -> Set("d2"))
    )
    val linked = new EntityLinker().linkTracked(tracked).toOption.get
    linked.graph.edges shouldBe List(Edge("d1__a", "d1__acme", "WORKS_AT"))
    linked.edgeSources shouldBe Map(("d1__a", "d1__acme", "WORKS_AT") -> Set("d1", "d2"))
  }

  test("three names for one entity, all pairs confirmed, merge into one node with every edge (#2967)") {
    // Pairs, in candidate order: (j, sj), (j, sjr), (sj, sjr)
    val linker = new EntityLinker(
      Some(
        llmReplying(
          """{"merges": [{"pair": 1, "merge": true}, {"pair": 2, "merge": true}, {"pair": 3, "merge": true}]}"""
        )
      )
    )
    val graph = Graph(
      Map(
        "j"   -> person("j", "Jobs", "a" -> ujson.Num(1)),
        "sj"  -> person("sj", "Steve Jobs", "b" -> ujson.Num(2)),
        "sjr" -> person("sjr", "Steve Jobs Sr", "c" -> ujson.Num(3)),
        "x"   -> Node("x", "Org", Map("name" -> ujson.Str("Apple")))
      ),
      List(Edge("j", "x", "E1"), Edge("sj", "x", "E2"), Edge("sjr", "x", "E3"))
    )
    linker.findDisambiguationCandidates(graph).map { case (a, b) => (a.id, b.id) } shouldBe
      List(("j", "sj"), ("j", "sjr"), ("sj", "sjr"))
    val linked = linker.link(graph).toOption.get
    linked.nodes.keySet shouldBe Set("sjr", "x")
    linked.nodes("sjr").properties shouldBe Map(
      "name" -> ujson.Str("Steve Jobs Sr"),
      "a"    -> ujson.Num(1),
      "b"    -> ujson.Num(2),
      "c"    -> ujson.Num(3)
    )
    linked.edges.map(e => (e.source, e.relationship)).toSet shouldBe Set(("sjr", "E1"), ("sjr", "E2"), ("sjr", "E3"))
  }

  test("a malformed merge element is skipped and the others still apply (#2967)") {
    val linker = new EntityLinker(
      Some(llmReplying("""{"merges": [{"pair": "one", "merge": true}, {"pair": 1, "merge": true}]}"""))
    )
    val graph = Graph(Map("1" -> person("1", "Steve Jobs"), "2" -> person("2", "Jobs")), Nil)
    linker.link(graph).toOption.get.nodes.keySet shouldBe Set("1")
  }

  test("blank names are no name: such nodes neither merge nor pair with every node (#2967)") {
    val linker = new EntityLinker()
    val graph = Graph(
      Map("a" -> person("a", ""), "b" -> person("b", "  "), "c" -> person("c", "Carol")),
      Nil
    )
    linker.link(graph).toOption.get.nodes.keySet shouldBe Set("a", "b", "c")
    linker.findDisambiguationCandidates(graph) shouldBe empty
  }

  test("a blank-name node never merges with a node whose name equals its id (#2967)") {
    val graph = Graph(
      Map(
        "alice"    -> person("alice", ""),
        "other"    -> person("other", "Alice"),
        "nameless" -> Node("nameless", "Person")
      ),
      Nil
    )
    new EntityLinker().link(graph).toOption.get.nodes.keySet shouldBe Set("alice", "other", "nameless")
  }

  test("a self-loop in the graph survives linking; only one the merge creates is dropped (#2967)") {
    val graph = Graph(
      Map(
        "acme" -> Node("acme", "Org", Map("name" -> ujson.Str("Acme"))),
        "1"    -> person("1", "Alice"),
        "2"    -> person("2", "Alice")
      ),
      List(Edge("acme", "acme", "SUBSIDIARY_OF"), Edge("1", "2", "KNOWS"))
    )
    new EntityLinker().link(graph).toOption.get.edges shouldBe List(Edge("acme", "acme", "SUBSIDIARY_OF"))
  }

  test("edges that differ only in properties become one, its properties merged (#2967)") {
    val graph = Graph(
      Map("1" -> person("1", "Alice"), "2" -> person("2", "Alice"), "3" -> person("3", "Bob")),
      List(
        Edge("1", "3", "KNOWS", Map("since" -> ujson.Num(2020))),
        Edge("2", "3", "KNOWS", Map("since" -> ujson.Num(2021), "how" -> ujson.Str("work"))),
        Edge("3", "1", "LIKES", Map("w" -> ujson.Num(1))),
        Edge("3", "1", "LIKES", Map("w" -> ujson.Num(2)))
      )
    )
    new EntityLinker().link(graph).toOption.get.edges shouldBe List(
      Edge("1", "3", "KNOWS", Map("since" -> ujson.Num(2020), "how" -> ujson.Str("work"))),
      Edge("3", "1", "LIKES", Map("w" -> ujson.Num(1)))
    )
  }

  test("a preferred id stays canonical, so an incremental build never renames an existing node (#2967)") {
    val graph = Graph(Map("z_existing" -> person("z_existing", "Alice"), "a_new" -> person("a_new", "Alice")), Nil)
    val tracked = SourceTrackedGraph(
      graph,
      Seq(DocumentSource("old", "Old"), DocumentSource("new", "New")),
      Map("z_existing" -> Set("old"), "a_new" -> Set("new")),
      Map.empty
    )
    val linked = new EntityLinker().linkTracked(tracked, preferredIds = Set("z_existing")).toOption.get
    linked.graph.nodes.keySet shouldBe Set("z_existing")
    linked.nodeSources shouldBe Map("z_existing" -> Set("old", "new"))
    new EntityLinker().link(graph).toOption.get.nodes.keySet shouldBe Set("a_new")
  }
}
