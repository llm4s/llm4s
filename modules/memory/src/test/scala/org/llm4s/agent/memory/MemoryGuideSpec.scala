package org.llm4s.agent.memory

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.Using

/**
 * The snippets of `docs/guide/agents/memory.md`, compiled and run as written.
 *
 * If a snippet here stops compiling or an assertion fails, the guide is teaching something that no
 * longer works: change the guide and this spec together. Each `snippet` method below is the code of
 * one block in the guide, and the assertions pin what the prose around that block claims. Blocks
 * that need a live provider (an embedding client, an agent) take that as a parameter, so they are
 * compiled but not run against one; the Postgres store lives in another module and its snippets
 * are not here.
 */
class MemoryGuideSpec extends AnyWordSpec with Matchers with EitherValues {

  // ---- Quick Start

  private def quickStart(): Result[String] =
    for {
      // Record a user fact
      m1 <- SimpleMemoryManager.empty.recordUserFact(
        fact = "Prefers Scala over Java",
        userId = Some("user-123"),
        importance = Some(0.9)
      )

      // Record entity knowledge
      m2 <- m1.recordEntityFact(
        entityId = EntityId("anthropic"),
        entityName = "Anthropic",
        fact = "AI company that created Claude",
        importance = Some(0.8)
      )

      // Get relevant context for a query
      context <- m2.getRelevantContext("Tell me about Scala programming")
    } yield context

  // ---- Memory Manager

  private def managers(): (SimpleMemoryManager, SimpleMemoryManager) = {
    // In-memory store, default configuration
    val manager = SimpleMemoryManager.empty

    // A store and a configuration of your own
    val configured = SimpleMemoryManager.withStore(
      store = InMemoryStore.empty,
      config = MemoryManagerConfig(defaultImportance = 0.7)
    )
    (manager, configured)
  }

  // ---- Recording Memory

  private def recordEverything(manager: MemoryManager): Result[MemoryManager] =
    for {
      m1 <- manager.recordUserFact(
        fact = "Prefers functional programming",
        userId = Some("user-123"),
        importance = Some(0.9)
      )
      m2 <- m1.recordEntityFact(
        entityId = EntityId("scala-lang"),
        entityName = "Scala",
        fact = "Scala is a JVM language combining OOP and FP",
        entityType = "language",
        importance = Some(0.8)
      )
      m3 <- m2.recordKnowledge(
        content = "The latest LLM4S version is 0.5.0",
        source = "release-notes",
        metadata = Map("channel" -> "stable")
      )
      m4 <- m3.recordTask(
        description = "Generate the quarterly report",
        outcome = "Report generated",
        success = true,
        importance = Some(0.6)
      )
    } yield m4

  private def recordMessages(manager: MemoryManager): Result[MemoryManager] =
    for {
      // Record a conversation turn
      m1 <- manager.recordMessage(
        message = UserMessage("What's the weather in Paris?"),
        conversationId = "conv-789"
      )

      // Record several messages at once
      m2 <- m1.recordConversation(
        messages = Seq(UserMessage("And in Rome?"), AssistantMessage("Sunny, 24 degrees.")),
        conversationId = "conv-789"
      )
    } yield m2

  // ---- Retrieving Memory

  private def relevantContext(manager: MemoryManager): Result[String] =
    manager.getRelevantContext(
      query = "Tell me about Scala programming",
      maxTokens = 1000,
      filter = MemoryFilter.ByTypes(Set(MemoryType.UserFact, MemoryType.Knowledge))
    )

  private def conversationContext(manager: MemoryManager): Result[String] =
    manager.getConversationContext(conversationId = "conv-789", maxMessages = 10)

  private def entityContext(manager: MemoryManager): Result[String] =
    manager.getEntityContext(EntityId("anthropic"))

  private def userContext(manager: MemoryManager): Result[String] =
    manager.getUserContext(Some("user-123"))

  // ---- Memory Stores

  private def inMemoryStore(): SimpleMemoryManager = {
    val store = InMemoryStore.empty
    SimpleMemoryManager.withStore(store)
  }

  private def sqliteStores(path: String): Result[(SQLiteMemoryStore, SQLiteMemoryStore)] =
    for {
      // File-based (persistent)
      file <- SQLiteMemoryStore(path)

      // In-memory SQLite (fast, volatile)
      volatile <- SQLiteMemoryStore.inMemory()
    } yield (file, volatile)

  // Wrap an embedding client (needs a live client to run, so the spec only compiles this)
  private def embeddingServiceFor(
    embeddingClient: EmbeddingClient,
    modelConfig: EmbeddingModelConfig
  ): EmbeddingService =
    LLMEmbeddingService(embeddingClient, modelConfig)

  // A vector store in a SQLite file (VectorMemoryStore.inMemory(embeddingService) keeps it in memory)
  private def vectorManager(embeddingService: EmbeddingService, path: String): Result[SimpleMemoryManager] =
    VectorMemoryStore(path, embeddingService).map(store => SimpleMemoryManager.withStore(store))

  // ---- Memory with Agents

  private def answerWithMemory(
    manager: MemoryManager,
    userQuery: String,
    conversationId: String
  )(runAgent: String => Result[Seq[Message]]): Result[MemoryManager] =
    for {
      // Get relevant context for the query
      context <- manager.getRelevantContext(userQuery)

      // Build a system prompt that carries the context
      systemPrompt = s"""You are a helpful assistant.
        |
        |Relevant context from memory:
        |$context""".stripMargin

      // Run your agent with that prompt (see the Agents guide) and keep what was said
      messages <- runAgent(systemPrompt)
      updated  <- manager.recordConversation(messages, conversationId)
    } yield updated

  // ---- Semantic Search

  private def semanticSearch(embeddingService: EmbeddingService): Result[Seq[ScoredMemory]] =
    for {
      store <- VectorMemoryStore.inMemory(embeddingService)
      m1    <- SimpleMemoryManager.withStore(store).recordKnowledge("Paris is the capital of France", "geography")
      m2    <- m1.recordKnowledge("Berlin is the capital of Germany", "geography")
      m3    <- m2.recordKnowledge("Rome is the capital of Italy", "geography")

      // Search by meaning
      results <- m3.store.search("European capitals", topK = 2)
    } yield results

  private def searchWithFilter(store: MemoryStore): Result[Seq[ScoredMemory]] = {
    val filter = MemoryFilter.ByType(MemoryType.Knowledge) &&
      MemoryFilter.MinImportance(0.7) &&
      MemoryFilter.ByMetadata("user_id", "user-123")

    store.search(query = "programming languages", topK = 5, filter = filter)
  }

  // ---- Memory Consolidation and Entity Extraction

  private def consolidate(manager: MemoryManager): Result[MemoryManager] =
    manager.consolidateMemories(
      olderThan = Instant.now().minus(7, ChronoUnit.DAYS),
      minCount = 10
    )

  private def llmManager(client: LLMClient): LLMMemoryManager =
    LLMMemoryManager(
      config = MemoryManagerConfig.default,
      store = InMemoryStore.empty,
      client = client
    )

  private def extractEntities(manager: MemoryManager): Result[String] =
    for {
      m1 <- manager.extractEntities(
        text = "Anthropic is an AI company that created Claude",
        conversationId = Some("conv-789")
      )
      // The extracted entities are ordinary entity memories
      info <- m1.getEntityContext(EntityId.fromName("Anthropic"))
    } yield info

  // ---- Memory Statistics

  private def statistics(manager: MemoryManager): Result[String] =
    manager.stats.map { stats =>
      s"""Total memories: ${stats.totalMemories}
         |By type: ${stats.byType}
         |Oldest: ${stats.oldestMemory}
         |Newest: ${stats.newestMemory}""".stripMargin
    }

  // ---- Persistence Patterns

  private def saveAndLoad(path: String): Result[String] =
    for {
      // Create a persistent store
      store <- SQLiteMemoryStore(path)
      _     <- SimpleMemoryManager.withStore(store).recordUserFact("Likes Scala", Some("user-1"))
      _ = store.close()

      // On the next run, open the same file: the memories are still there
      reopened <- SQLiteMemoryStore(path)
      context  <- SimpleMemoryManager.withStore(reopened).getUserContext(Some("user-1"))
      _ = reopened.close()
    } yield context

  private class PersistentMemory(dbPath: String) {
    def withManager[A](use: MemoryManager => Result[A]): Result[A] =
      SQLiteMemoryStore(dbPath).flatMap { store =>
        val outcome = use(SimpleMemoryManager.withStore(store))
        store.close()
        outcome
      }
  }

  // ---- Best Practices

  private def importance(manager: MemoryManager): Result[MemoryManager] =
    for {
      // High importance - core user preferences
      m1 <- manager.recordUserFact("Primary programming language is Scala", importance = Some(0.9))

      // Medium importance - useful but not critical
      m2 <- m1.recordEntityFact(EntityId("scaladays"), "ScalaDays", "Attended in 2024", importance = Some(0.6))

      // Low importance - ephemeral information
      m3 <- m2.recordTask("Run the tests", "Passed", success = true, importance = Some(0.3))
    } yield m3

  private def tokenBudget(manager: MemoryManager, query: String): Result[String] =
    // About four characters per token: the context is cut to fit
    manager.getRelevantContext(query, maxTokens = 500)

  private def cleanUp(store: MemoryStore, thirtyDaysAgo: Instant): Result[MemoryStore] =
    // Delete old, low-importance memories (there is no "maximum importance" filter: use `Custom`)
    store.deleteMatching(
      MemoryFilter.Custom(_.importance.exists(_ <= 0.3)) &&
        MemoryFilter.ByTimeRange(before = Some(thirtyDaysAgo))
    )

  // ---- Test support

  /** A temporary SQLite file, deleted afterwards. */
  private def withTempDb[A](use: String => A): A = {
    val path = Files.createTempFile("memory-guide", ".db")
    Using.resource(new AutoCloseable { override def close(): Unit = { Files.deleteIfExists(path); () } }) { _ =>
      use(path.toString)
    }
  }

  /** An LLM that answers the entity-extraction and consolidation prompts with canned replies. */
  private object CannedLLM extends LLMClient {
    private val entities =
      """[{"entity_name": "Anthropic", "entity_type": "company", "fact": "Anthropic is an AI company that created Claude.", "importance": 0.9}]"""

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val prompt = conversation.messages.collectFirst { case UserMessage(content) => content }.getOrElse("")
      val reply  = if (prompt.contains("Return JSON array only")) entities else "A short summary of the older memories."
      Right(
        Completion(
          id = "canned",
          created = 0L,
          content = reply,
          model = "canned",
          message = AssistantMessage(reply),
          usage = None
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // ---- The tests

  "the Quick Start" should {
    "return the stored fact whose words appear in the query, under a section for its type" in {
      quickStart().value shouldBe "# Retrieved Context\n## User Preferences\n- Prefers Scala over Java"
    }

    "leave out a memory that shares no word with the query" in {
      // 'Anthropic' and 'Claude' do not appear in "Tell me about Scala programming"
      (quickStart().value should not).include("Claude")
    }
  }

  "the Memory Manager section" should {
    "build a manager with default and with explicit configuration" in {
      val (manager, configured) = managers()
      manager.stats.value.totalMemories shouldBe 0L
      configured.config.defaultImportance shouldBe 0.7
      MemoryManagerConfig.default.consolidationEnabled shouldBe false
    }

    "give a memory the configured default importance when none is passed" in {
      val (_, configured) = managers()
      val importances = for {
        m1       <- configured.recordUserFact("Prefers dark mode", Some("user-1"))
        memories <- m1.store.recall(MemoryFilter.All)
      } yield memories.map(_.importance)
      importances.value shouldBe Seq(Some(0.7))
    }

    // The guide says contextTokenBudget is not consulted yet. When a manager starts to honour it, this
    // test starts failing: promote it and update the guide.
    "keep the context within contextTokenBudget when a manager honours it" in pendingUntilFixed {
      val longFact = "Scala " + ("word " * 400)
      val manager  = SimpleMemoryManager(MemoryManagerConfig(contextTokenBudget = 10))
      val context = for {
        m1  <- manager.recordKnowledge(longFact, "docs")
        ctx <- m1.getRelevantContext("Scala")
      } yield ctx
      context.value.length should be <= 40
    }
  }

  "Recording Memory" should {
    "record each kind of memory" in {
      val recorded = recordEverything(SimpleMemoryManager.empty).flatMap(_.stats).value
      recorded.totalMemories shouldBe 4L
      recorded.byType shouldBe Map[MemoryType, Long](
        MemoryType.UserFact  -> 1L,
        MemoryType.Entity    -> 1L,
        MemoryType.Knowledge -> 1L,
        MemoryType.Task      -> 1L
      )
    }

    "record a message and a conversation, counted as one conversation" in {
      val stats = recordMessages(SimpleMemoryManager.empty).flatMap(_.stats).value
      stats.totalMemories shouldBe 3L
      stats.conversationCount shouldBe 1L
    }

    "keep the metadata passed to recordKnowledge, and give that memory no importance" in {
      val knowledge = for {
        m <- recordEverything(SimpleMemoryManager.empty)
        k <- m.store.recall(MemoryFilter.ByType(MemoryType.Knowledge))
      } yield k.map(memory => (memory.metadata.get("channel"), memory.importance))
      knowledge.value shouldBe Seq((Some("stable"), None))
    }
  }

  "Retrieving Memory" should {
    "return the relevant context, restricted by the filter" in {
      val withScala = for {
        m1  <- SimpleMemoryManager.empty.recordUserFact("Prefers Scala over Java", Some("user-123"))
        m2  <- m1.recordEntityFact(EntityId("scala"), "Scala", "Scala is a JVM language")
        ctx <- relevantContext(m2)
      } yield ctx
      withScala.value should include("Prefers Scala over Java")
      (withScala.value should not).include("JVM language")
    }

    "format the conversation, the entity and the user context as the guide shows" in {
      val contexts = for {
        m1   <- recordMessages(SimpleMemoryManager.empty)
        m2   <- m1.recordEntityFact(EntityId("anthropic"), "Anthropic", "AI company that created Claude")
        m3   <- m2.recordUserFact("Prefers functional programming", Some("user-123"))
        conv <- conversationContext(m3)
        ent  <- entityContext(m3)
        usr  <- userContext(m3)
      } yield (conv, ent, usr)
      val (conv, ent, usr) = contexts.value

      conv should startWith("Previous conversation:")
      conv should include("[user]: What's the weather in Paris?")
      conv should include("[assistant]: Sunny, 24 degrees.")
      ent shouldBe "Known facts about Anthropic:\n- AI company that created Claude"
      usr shouldBe "Known facts about the user:\n- Prefers functional programming"
    }

    "return an empty string when there is nothing to report" in {
      conversationContext(SimpleMemoryManager.empty).value shouldBe ""
      entityContext(SimpleMemoryManager.empty).value shouldBe ""
      userContext(SimpleMemoryManager.empty).value shouldBe ""
    }

    "limit the conversation context to the most recent maxMessages" in {
      val conv = for {
        m1 <- recordMessages(SimpleMemoryManager.empty)
        c  <- m1.getConversationContext("conv-789", maxMessages = 1)
      } yield c
      conv.value.linesIterator.count(_.startsWith("[")) shouldBe 1
    }

    "scope the user context to one user" in {
      val usr = for {
        m1 <- SimpleMemoryManager.empty.recordUserFact("Likes Scala", Some("user-1"))
        m2 <- m1.recordUserFact("Likes Rust", Some("user-2"))
        u1 <- m2.getUserContext(Some("user-1"))
      } yield u1
      usr.value should include("Likes Scala")
      (usr.value should not).include("Likes Rust")
    }

    "include the facts of every user when no user is given" in {
      val everyone = for {
        m1  <- SimpleMemoryManager.empty.recordUserFact("Likes Scala", Some("user-1"))
        m2  <- m1.recordUserFact("Likes Rust", Some("user-2"))
        all <- m2.getUserContext(None)
      } yield all
      everyone.value should include("Likes Scala")
      everyone.value should include("Likes Rust")
    }
  }

  "Memory Stores" should {
    "keep memories in an in-memory store that is private to its manager" in {
      val first = inMemoryStore().recordUserFact("Likes Scala", None).value
      first.stats.value.totalMemories shouldBe 1L
      inMemoryStore().stats.value.totalMemories shouldBe 0L
    }

    "open a SQLite file and an in-memory SQLite database" in {
      withTempDb { path =>
        val stores = sqliteStores(path).value
        stores._1.close()
        stores._2.close()
        succeed
      }
    }

    "store and search through a vector store kept in a SQLite file" in {
      withTempDb { path =>
        val hits = for {
          manager <- vectorManager(MockEmbeddingService(dimensions = 8), path)
          m1      <- manager.recordKnowledge("Paris is the capital of France", "geography")
          found   <- m1.store.search("capital of France", topK = 3)
          _ = m1.store match {
            case closeable: VectorMemoryStore => closeable.close()
            case _                            => ()
          }
        } yield found.map(_.memory.content)
        hits.value shouldBe Seq("Paris is the capital of France")
      }
    }

    "wrap an embedding client as an embedding service (compiled here, run only with a live client)" in {
      // The call needs a real EmbeddingClient, so the check is the type ascription below: the guide's
      // call must type-check, and a signature change stops this spec from compiling.
      val _: (EmbeddingClient, EmbeddingModelConfig) => EmbeddingService = embeddingServiceFor
      succeed
    }
  }

  "Memory with Agents" should {
    "put the memory in the prompt and record the turn afterwards" in {
      val seenPrompt = new java.util.concurrent.atomic.AtomicReference[String]("")
      val outcome = for {
        m1 <- SimpleMemoryManager.empty.recordUserFact("Prefers Scala over Java", Some("user-1"))
        m2 <- answerWithMemory(m1, "Scala", "conv-1") { prompt =>
          seenPrompt.set(prompt)
          Right(Seq(UserMessage("Scala"), AssistantMessage("A fine language.")))
        }
        c <- m2.getConversationContext("conv-1")
      } yield c
      seenPrompt.get() should include("Relevant context from memory:")
      seenPrompt.get() should include("Prefers Scala over Java")
      outcome.value should include("[assistant]: A fine language.")
    }
  }

  "Semantic Search" should {
    "return topK scored memories from a vector store, best first" in {
      val results = semanticSearch(MockEmbeddingService(dimensions = 8)).value
      results should have size 2
      results.map(_.score) shouldBe results.map(_.score).sorted(Ordering[Double].reverse)
      results
        .map(_.memory.content)
        .toSet
        .subsetOf(
          Set(
            "Paris is the capital of France",
            "Berlin is the capital of Germany",
            "Rome is the capital of Italy"
          )
        ) shouldBe true
    }

    "narrow a search with a filter built from the combinators" in {
      val store = for {
        s1 <- InMemoryStore.empty.store(
          Memory
            .fromKnowledge("Scala is a programming language", "docs")
            .withMetadata("user_id", "user-123")
            .withImportance(0.9)
        )
        s2 <- s1.store(
          Memory
            .fromKnowledge("Java is a programming language", "docs")
            .withMetadata("user_id", "user-123")
            .withImportance(0.5)
        )
        s3 <- s2.store(Memory.userFact("Prefers programming languages with types", Some("user-123")))
        s4 <- s3.store(
          Memory
            .fromKnowledge("Rust is a programming language", "docs")
            .withMetadata("user_id", "user-999")
            .withImportance(0.9)
        )
      } yield s4
      val hits = store.flatMap(searchWithFilter).value
      hits.map(_.memory.content) shouldBe Seq("Scala is a programming language")
    }
  }

  "Memory Consolidation and Entity Extraction" should {
    "leave a SimpleMemoryManager unchanged, as it does neither" in {
      val after = for {
        m1 <- SimpleMemoryManager.empty.recordUserFact("Likes Scala", None)
        m2 <- consolidate(m1)
        m3 <- extractEntities(m2).map(_ => m2)
        s  <- m3.stats
      } yield s.totalMemories
      after.value shouldBe 1L

      extractEntities(SimpleMemoryManager.empty).value shouldBe ""
    }

    "extract entities with an LLMMemoryManager and store them as entity memories" in {
      val info = extractEntities(llmManager(CannedLLM)).value
      info should startWith("Known facts about Anthropic:")
      info should include("Anthropic is an AI company that created Claude.")
    }

    "consolidate old memories with an LLMMemoryManager" in {
      val manager = llmManager(CannedLLM)
      val counts = for {
        m1     <- manager.recordMessage(UserMessage("one"), "conv-1")
        m2     <- m1.recordMessage(AssistantMessage("two"), "conv-1")
        m3     <- m2.recordMessage(UserMessage("three"), "conv-1")
        before <- m3.stats
        after <- m3
          .consolidateMemories(olderThan = Instant.now().plusSeconds(60), minCount = 2)
          .flatMap(_.stats)
      } yield (before.totalMemories, after.totalMemories)
      val (before, after) = counts.value
      before shouldBe 3L
      after should be < before
    }
  }

  "Memory Statistics" should {
    "report the counts, the types and the time span" in {
      val text = for {
        m1 <- recordEverything(SimpleMemoryManager.empty)
        t  <- statistics(m1)
      } yield t
      text.value should include("Total memories: 4")
      text.value should include("UserFact -> 1")
      text.value should include("Oldest: Some(")
    }

    "report an empty manager without a time span" in {
      statistics(SimpleMemoryManager.empty).value should include("Oldest: None")
    }
  }

  "Persistence Patterns" should {
    "find a saved memory after the store is closed and opened again" in {
      withTempDb(path => saveAndLoad(path).value shouldBe "Known facts about the user:\n- Likes Scala")
    }

    "carry memory from one session to the next" in {
      withTempDb { path =>
        val memory = new PersistentMemory(path)
        memory.withManager(_.recordUserFact("Likes Scala", Some("user-1"))).value
        val context = memory.withManager(_.getUserContext(Some("user-1")))
        context.value should include("Likes Scala")
      }
    }
  }

  "Best Practices" should {
    "record the importance given for each kind of memory" in {
      val recorded = for {
        m1       <- importance(SimpleMemoryManager.empty)
        memories <- m1.store.recall(MemoryFilter.All)
      } yield memories.flatMap(_.importance).sorted
      recorded.value shouldBe Seq(0.3, 0.6, 0.9)
    }

    "cut the context to fit maxTokens, at about four characters per token" in {
      val facts = (1 to 10).map(i => s"Scala fact number $i is about the language")
      val manager = facts.foldLeft[Result[MemoryManager]](Right(SimpleMemoryManager.empty)) { (acc, fact) =>
        acc.flatMap(_.recordKnowledge(fact, "docs"))
      }
      val roomy = manager.flatMap(_.getRelevantContext("Scala", maxTokens = 2000)).value
      val tight = manager.flatMap(tokenBudget(_, "Scala")).value
      roomy.linesIterator.count(_.startsWith("- ")) shouldBe 10
      tight.linesIterator.count(_.startsWith("- ")) shouldBe 10 // 500 tokens is still roomy for ten short facts

      val squeezed = manager.flatMap(_.getRelevantContext("Scala", maxTokens = 20)).value
      squeezed.linesIterator.count(_.startsWith("- ")) should be < 10
    }

    // The guide says a very small maxTokens can leave a heading with nothing under it. When that is
    // fixed this starts failing: promote it and drop the sentence from the guide.
    "never leave a section heading without an entry when maxTokens is tiny" in pendingUntilFixed {
      val manager = SimpleMemoryManager.empty.recordKnowledge("Scala 3 has opaque types", "docs")
      val context = manager.flatMap(_.getRelevantContext("Scala", maxTokens = 5)).value
      val lines   = context.linesIterator.toList
      lines.count(_.startsWith("## ")) shouldBe lines.count(_.startsWith("- "))
      lines.count(_.startsWith("- ")) should be >= 1
    }

    "delete old, low-importance memories and keep the rest" in {
      val now = Instant.now()
      val remaining = for {
        s1   <- InMemoryStore.empty.store(Memory.fromTask("old and minor", "ok", success = true).withImportance(0.2))
        s2   <- s1.store(Memory.fromTask("old and major", "ok", success = true).withImportance(0.9))
        s3   <- cleanUp(s2, thirtyDaysAgo = now.plusSeconds(60))
        n    <- s3.count()
        left <- s3.recall(MemoryFilter.All)
      } yield (n, left.flatMap(_.importance))
      remaining.value shouldBe ((1L, Seq(0.9)))
    }
  }
}
