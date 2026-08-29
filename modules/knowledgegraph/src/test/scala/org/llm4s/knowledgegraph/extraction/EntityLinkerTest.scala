package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Completion
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }

class EntityLinkerTest extends AnyFunSuite with Matchers with MockFactory {

  private def makeCompletion(content: String): Completion = Completion(
    id = "test-id",
    content = content,
    model = "test-model",
    toolCalls = Nil,
    created = 1234567890L,
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
    // Nodes without name property use ID as name; "alice" and "ALICE" normalize to the same
    result.toOption.get.nodes should have size 1
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
}
