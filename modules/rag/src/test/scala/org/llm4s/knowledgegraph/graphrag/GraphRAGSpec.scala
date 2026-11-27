package org.llm4s.knowledgegraph.graphrag

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.knowledgegraph.storage.InMemoryGraphStore
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.vectorstore.HybridSearchResult
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class GraphRAGSpec extends AnyFunSuite with Matchers {

  private class DeterministicLLM extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val system = conversation.messages.collectFirst { case s: SystemMessage => s.content }.getOrElse("")
      val content =
        if (system.contains("Summarize the graph community")) "finance themes and relationships"
        else if (system.contains("Answer the question using only the provided community summary.")) "partial answer"
        else if (system.contains("Synthesize a single coherent answer from partial answers.")) "global answer"
        else if (system.contains("local graph neighborhood")) "local answer"
        else if (system.contains("hybrid vector and graph evidence")) "hybrid answer"
        else "generic answer"

      Right(
        Completion(
          id = "test",
          created = Instant.ofEpochSecond(1L),
          model = ModelName("test-model"),
          message = AssistantMessage(text = Some(content)),
          usage = None
        )
      )
    }

    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def contextWindow: Int     = 8192
    override def reserveCompletion: Int = 1024
  }

  private class CountingLLM extends DeterministicLLM {
    var completeCalls: Int = 0
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      completeCalls += 1
      super.complete(conversation, options)
    }
  }

  private def seededStore(): InMemoryGraphStore = {
    val store = new InMemoryGraphStore()
    val nodes = Seq(
      Node("alice", "Person", Map("name" -> ujson.Str("Alice"), "source" -> ujson.Str("doc-a"))),
      Node("bob", "Person", Map("name" -> ujson.Str("Bob"), "source" -> ujson.Str("doc-a"))),
      Node("fintech", "Org", Map("name" -> ujson.Str("FinTech Labs"), "source" -> ujson.Str("doc-a"))),
      Node("carol", "Person", Map("name" -> ujson.Str("Carol"), "source" -> ujson.Str("doc-b"))),
      Node("dave", "Person", Map("name" -> ujson.Str("Dave"), "source" -> ujson.Str("doc-b"))),
      Node("bank", "Org", Map("name" -> ujson.Str("Bank Group"), "source" -> ujson.Str("doc-b")))
    )
    nodes.foreach(node => store.upsertNode(node).isRight shouldBe true)

    val edges = Seq(
      Edge("alice", "bob", "KNOWS"),
      Edge("alice", "fintech", "WORKS_AT"),
      Edge("bob", "fintech", "WORKS_AT"),
      Edge("carol", "dave", "KNOWS"),
      Edge("carol", "bank", "WORKS_AT"),
      Edge("dave", "bank", "WORKS_AT")
    )
    edges.foreach(edge => store.upsertEdge(edge).isRight shouldBe true)
    store
  }

  test("detectCommunities builds multi-level community hierarchy") {
    val rag = new GraphRAG(seededStore(), new DeterministicLLM())

    val hierarchy = rag.detectCommunities().toOption.get
    hierarchy.levels.nonEmpty shouldBe true
    hierarchy.levels.head.size should be >= 2
    hierarchy.levels.head.flatMap(_.nodeIds).toSet should contain allElementsOf Set(
      "alice",
      "bob",
      "fintech",
      "carol",
      "dave",
      "bank"
    )
  }

  test("summarizeCommunities generates summaries and writes nothing to the graph store") {
    val store  = seededStore()
    val before = store.loadAll().toOption.get
    val rag    = new GraphRAG(store, new DeterministicLLM())

    val hierarchy = rag.summarizeCommunities().toOption.get
    hierarchy.allCommunities.forall(_.summary.exists(_.nonEmpty)) shouldBe true

    store.loadAll().toOption.get shouldBe before
  }

  test("no query mode changes the graph store, so other readers of it see the same graph") {
    val store         = seededStore()
    val before        = store.loadAll().toOption.get
    val rag           = new GraphRAG(store, new DeterministicLLM())
    val vectorResults = Seq(HybridSearchResult("alice", "Alice works at FinTech Labs", 0.9, Some(0.9), None))

    rag.globalSearch("What are the themes?").isRight shouldBe true
    rag.localSearch("What does Alice do?").isRight shouldBe true
    rag.hybridSearch("Who is connected to Alice?", vectorResults).isRight shouldBe true
    rag.summarizeCommunities(forceRefresh = true).isRight shouldBe true

    store.loadAll().toOption.get shouldBe before
  }

  test("a user node labelled Community, or with a community: id, survives a query and is part of the graph") {
    val store = seededStore()
    store.upsertNode(Node("community:scala", "Community", Map("name" -> ujson.Str("Scala Community")))).isRight shouldBe
      true
    store.upsertEdge(Edge("alice", "community:scala", "MEMBER_OF")).isRight shouldBe true
    val before = store.loadAll().toOption.get
    val rag    = new GraphRAG(store, new DeterministicLLM())

    rag.globalSearch("What are the themes?").isRight shouldBe true
    store.loadAll().toOption.get shouldBe before

    val local = rag.localSearch("Tell me about the Scala Community").toOption.get
    local.seedNodeIds should contain("community:scala")
    local.visitedNodeIds should contain("alice")
    rag.detectCommunities().toOption.get.allCommunities.flatMap(_.nodeIds) should contain("community:scala")
  }

  test("concurrent first queries summarise the communities once") {
    val llm = new CountingLLM {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        synchronized(super.complete(conversation, options))
    }
    val single = new CountingLLM()
    new GraphRAG(seededStore(), single).summarizeCommunities().isRight shouldBe true

    val rag   = new GraphRAG(seededStore(), llm)
    val start = new java.util.concurrent.CountDownLatch(1)
    val threads = (1 to 8).map { _ =>
      val t = new Thread(() => { start.await(); rag.summarizeCommunities(): Unit })
      t.start()
      t
    }
    start.countDown()
    threads.foreach(_.join())
    llm.completeCalls shouldBe single.completeCalls
  }

  test("globalSearch uses community summaries map-reduce") {
    val rag = new GraphRAG(seededStore(), new DeterministicLLM())

    val result = rag.globalSearch("What are the finance themes?", topK = 2).toOption.get
    result.answer shouldBe "global answer"
    result.rankedCommunities.nonEmpty shouldBe true
    result.partialAnswers.nonEmpty shouldBe true
  }

  test("globalSearch falls back to top communities when lexical overlap is empty") {
    val rag = new GraphRAG(seededStore(), new DeterministicLLM())

    val result = rag.globalSearch("qwerty asdfgh zxcvbn", topK = 2).toOption.get
    result.answer shouldBe "global answer"
    result.rankedCommunities.nonEmpty shouldBe true
  }

  test("localSearch fans out from seed entities") {
    val rag = new GraphRAG(seededStore(), new DeterministicLLM())

    val result = rag.localSearch("What does Alice do?", maxDepth = 1).toOption.get
    result.answer shouldBe "local answer"
    result.seedNodeIds should contain("alice")
    result.visitedNodeIds should contain("bob")
    result.visitedNodeIds should contain("fintech")
  }

  test("localSearch fails when no seed entities are found") {
    val rag = new GraphRAG(seededStore(), new DeterministicLLM())
    val res = rag.localSearch("qwerty asdfgh zxcvbn")
    res.isLeft shouldBe true
  }

  test("hybridSearch combines vector signal with graph traversal") {
    val rag = new GraphRAG(seededStore(), new DeterministicLLM())
    val vectorResults = Seq(
      HybridSearchResult(
        id = "doc-a-chunk-0",
        content = "Alice works at FinTech Labs",
        score = 0.95,
        metadata = Map("entityId" -> "alice", "docId" -> "doc-a")
      )
    )

    val result = rag.hybridSearch("Who is connected to Alice?", vectorResults).toOption.get
    result.answer shouldBe "hybrid answer"
    result.seedNodeIds should contain("alice")
    result.hits.nonEmpty shouldBe true
    result.hits.head.node.id shouldBe "alice"
  }

  test("summarizeCommunities uses cache and avoids repeated LLM calls") {
    val llm = new CountingLLM()
    val rag = new GraphRAG(seededStore(), llm)

    rag.summarizeCommunities().isRight shouldBe true
    val firstCount = llm.completeCalls
    rag.summarizeCommunities().isRight shouldBe true
    llm.completeCalls shouldBe firstCount
  }

  test("detectCommunities uses cache unless forceRefresh is true") {
    val store = seededStore()
    val rag   = new GraphRAG(store, new DeterministicLLM())

    val before = rag.detectCommunities().toOption.get
    before.levels.head.flatMap(_.nodeIds).toSet should not contain "eve"

    store
      .upsertNode(Node("eve", "Person", Map("name" -> ujson.Str("Eve"), "source" -> ujson.Str("doc-c"))))
      .isRight shouldBe true

    val cached = rag.detectCommunities().toOption.get
    cached.levels.head.flatMap(_.nodeIds).toSet should not contain "eve"

    val refreshed = rag.detectCommunities(forceRefresh = true).toOption.get
    refreshed.levels.head.flatMap(_.nodeIds).toSet should contain("eve")
  }

  test("globalSearch returns error when no communities have summaries") {
    val emptyStore = new InMemoryGraphStore()
    val rag        = new GraphRAG(emptyStore, new DeterministicLLM())

    rag.globalSearch("What are the themes?").isLeft shouldBe true
  }

  test("route chooses local for entity queries and global for broad/fallback queries") {
    val rag = new GraphRAG(seededStore(), new DeterministicLLM())

    rag.route("What does Alice do?").toOption.get shouldBe GraphRAGMode.Local
    rag.route("Give an overall summary of themes across the corpus").toOption.get shouldBe GraphRAGMode.Global
    rag.route("qwerty asdfgh zxcvbn").toOption.get shouldBe GraphRAGMode.Global
  }

  test("answer routes to local/global when no vector hits and hybrid when vector hits exist") {
    val rag = new GraphRAG(seededStore(), new DeterministicLLM())
    val vectorResults = Seq(
      HybridSearchResult(
        id = "doc-a-chunk-0",
        content = "Alice works at FinTech Labs",
        score = 0.95,
        metadata = Map("entityId" -> "alice")
      )
    )

    rag.answer("What does Alice do?").toOption.get.mode shouldBe GraphRAGMode.Local
    rag.answer("Give an overall summary of themes").toOption.get.mode shouldBe GraphRAGMode.Global
    rag.answer("Who is connected to Alice?", vectorResults).toOption.get.mode shouldBe GraphRAGMode.Hybrid
  }

  test("resolveSeedsFromVector uses provenance and lexical fallbacks when direct entity ids are missing") {
    val graph = seededStore().loadAll().toOption.get

    val provenanceSeeds = GraphRAG.resolveSeedsFromVector(
      vectorResults = Seq(
        HybridSearchResult(
          id = "doc-b-hit",
          content = "Bank discussion",
          score = 0.7,
          metadata = Map("docId" -> "doc-b")
        )
      ),
      graph = graph
    )
    provenanceSeeds should not be empty
    provenanceSeeds.exists(id => Set("carol", "dave", "bank").contains(id)) shouldBe true

    val lexicalSeeds = GraphRAG.resolveSeedsFromVector(
      vectorResults = Seq(
        HybridSearchResult(
          id = "no-meta",
          content = "Alice worked with Bob at FinTech Labs",
          score = 0.6,
          metadata = Map.empty
        )
      ),
      graph = graph
    )
    lexicalSeeds should contain("alice")
  }
}
