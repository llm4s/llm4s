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
 * The snippets of `docs/guide/agents/memory.md`, compiled and run.
 *
 * Each `// snippet:` region below is part of one of the guide's Scala blocks, word for word, and the last test reads
 * the page and fails unless every region is on it and every block of it is run (`GuideSnippets`, #2833): change the
 * guide and this spec together. The assertions pin what the prose around each block claims. Blocks that need a live
 * provider (an embedding client, an agent) take that as a parameter, so they are compiled but not run against one; the
 * SemanticMemoryMiddleware block is in another module and is not run here. The regions sit between `format: off`
 * lines, so scalafmt keeps the page's layout.
 */
@org.llm4s.tags.DocsSpec
class MemoryGuideSpec extends AnyWordSpec with Matchers with EitherValues {

  // format: off

  // ---- Quick Start

  private def quickStart(): Result[String] =
    for {
      // snippet: quick-start
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
    // end snippet

  // ---- Memory Manager

  private def managers(): (SimpleMemoryManager, SimpleMemoryManager) = {
    // snippet: managers
    // In-memory store, default configuration
    val manager = SimpleMemoryManager.empty

    // A store and a configuration of your own
    val configured = SimpleMemoryManager.withStore(
      store = InMemoryStore.empty,
      config = MemoryManagerConfig(defaultImportance = 0.7)
    )
    // end snippet
    (manager, configured)
  }

  // ---- Recording Memory

  private def recordUserFact(manager: MemoryManager): Result[MemoryManager] = {
    // snippet: user-fact
    // Record a user preference
    val result = manager.recordUserFact(
      fact = "Prefers functional programming",
      userId = Some("user-123"),
      importance = Some(0.9)
    )
    // end snippet
    result
  }

  private def recordEntityFact(manager: MemoryManager): Result[MemoryManager] = {
    // snippet: entity-fact
    // Record knowledge about an entity
    val result = manager.recordEntityFact(
      entityId = EntityId("scala-lang"),
      entityName = "Scala",
      fact = "Scala is a JVM language combining OOP and FP",
      entityType = "language",
      importance = Some(0.8)
    )
    // end snippet
    result
  }

  private def recordKnowledge(manager: MemoryManager): Result[MemoryManager] = {
    // snippet: knowledge
    // Record external knowledge: `source` says where it came from
    val result = manager.recordKnowledge(
      content = "Orders over 50 EUR ship free",
      source = "shipping-policy",
      metadata = Map("region" -> "eu")
    )
    // end snippet
    result
  }

  private def recordTask(manager: MemoryManager): Result[MemoryManager] = {
    // snippet: task
    // Record a task result
    val result = manager.recordTask(
      description = "Generate the quarterly report",
      outcome = "Report generated",
      success = true,
      importance = Some(0.6)
    )
    // end snippet
    result
  }

  private def recordEverything(manager: MemoryManager): Result[MemoryManager] =
    recordUserFact(manager).flatMap(recordEntityFact).flatMap(recordKnowledge).flatMap(recordTask)

  private def recordTurn(manager: MemoryManager): Result[MemoryManager] = {
    // snippet: message
    // Record a conversation turn
    val result = manager.recordMessage(
      message = UserMessage("What's the weather in Paris?"),
      conversationId = "conv-789"
    )
    // end snippet
    result
  }

  private def recordSeveral(manager: MemoryManager): Result[MemoryManager] = {
    // snippet: conversation
    // Record several messages at once
    val result = manager.recordConversation(
      messages = Seq(UserMessage("And in Rome?"), AssistantMessage("Sunny, 24 degrees.")),
      conversationId = "conv-789"
    )
    // end snippet
    result
  }

  private def recordMessages(manager: MemoryManager): Result[MemoryManager] =
    recordTurn(manager).flatMap(recordSeveral)

  // ---- Retrieving Memory

  private def relevantContext(manager: MemoryManager): Result[String] = {
    // snippet: relevant-context
    val context = manager.getRelevantContext(
      query = "Tell me about Scala programming",
      maxTokens = 1000,
      filter = MemoryFilter.ByTypes(Set(MemoryType.UserFact, MemoryType.Knowledge))
    )
    // end snippet
    context
  }

  private def conversationContext(manager: MemoryManager): Result[String] = {
    // snippet: conversation-context
    val history = manager.getConversationContext(
      conversationId = "conv-789",
      maxMessages = 10
    )
    // end snippet
    history
  }

  private def entityContext(manager: MemoryManager): Result[String] = {
    // snippet: entity-context
    val entityInfo = manager.getEntityContext(EntityId("anthropic"))
    // end snippet
    entityInfo
  }

  private def userContext(manager: MemoryManager): Result[String] = {
    // snippet: user-context
    val userInfo = manager.getUserContext(Some("user-123"))
    // end snippet
    userInfo
  }

  // ---- Memory Stores

  private def inMemoryStore(): SimpleMemoryManager = {
    // snippet: in-memory-store
    val store = InMemoryStore.empty
    val manager = SimpleMemoryManager.withStore(store)
    // end snippet
    manager
  }

  // The page opens "memory.db"; this opens a temporary file instead, so that line is not a region.
  private def sqliteStores(path: String): Result[(SQLiteMemoryStore, SQLiteMemoryStore)] =
    for {
      file <- SQLiteMemoryStore(path)

      // snippet: sqlite-stores
      // In-memory SQLite (fast, volatile)
      volatile <- SQLiteMemoryStore.inMemory()
    } yield (file, volatile)
    // end snippet

  // snippet: vector-store
  // Wrap an embedding client
  def embeddingServiceFor(embeddingClient: EmbeddingClient, modelConfig: EmbeddingModelConfig): EmbeddingService =
    LLMEmbeddingService(embeddingClient, modelConfig)

  // A vector store in a SQLite file (VectorMemoryStore.inMemory(embeddingService) keeps it in memory)
  def vectorManager(embeddingService: EmbeddingService, path: String): Result[SimpleMemoryManager] =
    VectorMemoryStore(path, embeddingService).map(store => SimpleMemoryManager.withStore(store))
  // end snippet

  // ---- Memory with Agents

  // snippet: answer-with-memory
  def answerWithMemory(
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

      // Run your agent with that prompt and keep what was said
      messages <- runAgent(systemPrompt)
      updated  <- manager.recordConversation(messages, conversationId)
    } yield updated
  // end snippet

  // ---- Semantic Search

  // snippet: semantic-search
  def semanticSearch(embeddingService: EmbeddingService): Result[Seq[ScoredMemory]] =
    VectorMemoryStore.inMemory(embeddingService).flatMap { store =>
      Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
        for {
          m1 <- SimpleMemoryManager.withStore(store).recordKnowledge("Paris is the capital of France", "geography")
          m2 <- m1.recordKnowledge("Berlin is the capital of Germany", "geography")
          m3 <- m2.recordKnowledge("Rome is the capital of Italy", "geography")
          results <- m3.store.search("European capitals", topK = 2)
        } yield results
      }
    }
  // end snippet

  private def searchWithFilter(store: MemoryStore): Result[Seq[ScoredMemory]] = {
    // snippet: search-filter
    val filter = MemoryFilter.ByType(MemoryType.Knowledge) &&
      MemoryFilter.MinImportance(0.7) &&
      MemoryFilter.ByMetadata("user_id", "user-123")

    val results = store.search(
      query = "programming languages",
      topK = 5,
      filter = filter
    )
    // end snippet
    results
  }

  // ---- Memory Consolidation and Entity Extraction

  // snippet: consolidation
  // An LLM client is needed to write the summaries
  def llmManager(client: LLMClient): LLMMemoryManager =
    LLMMemoryManager(
      config = MemoryManagerConfig.default,
      store = InMemoryStore.empty,
      client = client
    )

  // Consolidate memories older than a week, in groups of at least ten
  def consolidate(manager: MemoryManager): Result[MemoryManager] =
    manager.consolidateMemories(
      olderThan = Instant.now().minus(7, ChronoUnit.DAYS),
      minCount = 10
    )
  // end snippet

  // snippet: extract-entities
  def extractEntities(manager: MemoryManager): Result[String] =
    for {
      m1 <- manager.extractEntities(
        text = "Anthropic is an AI company that created Claude",
        conversationId = Some("conv-789")
      )
      // The extracted entities are ordinary entity memories
      info <- m1.getEntityContext(EntityId.fromName("Anthropic"))
    } yield info
  // end snippet

  // ---- Memory Statistics

  private def statistics(manager: MemoryManager): Result[String] = {
    // snippet: statistics
    val result = manager.stats.map { stats =>
      s"""Total memories: ${stats.totalMemories}
         |By type: ${stats.byType}
         |Oldest: ${stats.oldestMemory}
         |Newest: ${stats.newestMemory}""".stripMargin
    }
    // end snippet
    result
  }

  // ---- Persistence Patterns

  private def saveAndLoad(path: String): Result[String] = {
    // snippet: save-and-load
    // Both opened stores close even when a Result is Left.
    val result = SQLiteMemoryStore(path).flatMap { store =>
      Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
        SimpleMemoryManager.withStore(store).recordUserFact("Likes Scala", Some("user-1")).map(_ => ())
      }
    }.flatMap { _ =>
      SQLiteMemoryStore(path).flatMap { reopened =>
        Using.resource(new AutoCloseable { override def close(): Unit = reopened.close() }) { _ =>
          SimpleMemoryManager.withStore(reopened).getUserContext(Some("user-1"))
        }
      }
    }
    // Right("Known facts about the user:\n- Likes Scala")
    // end snippet
    result
  }

  // snippet: persistent-memory
  class PersistentMemory(dbPath: String) {

    // Open the store, run something against a manager on it, and close the store again
    def withManager[A](use: MemoryManager => Result[A]): Result[A] =
      SQLiteMemoryStore(dbPath).flatMap { store =>
        Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
          use(SimpleMemoryManager.withStore(store))
        }
      }
  }
  // end snippet

  // ---- Best Practices

  private def importance(manager: MemoryManager): Result[MemoryManager] =
    // snippet: importance
    for {
      // High importance - core user preferences
      m1 <- manager.recordUserFact("Primary programming language is Scala", importance = Some(0.9))

      // Medium importance - useful but not critical
      m2 <- m1.recordEntityFact(EntityId("scaladays"), "ScalaDays", "Attended in 2024", importance = Some(0.6))

      // Low importance - ephemeral information
      m3 <- m2.recordTask("Run the tests", "Passed", success = true, importance = Some(0.3))
    } yield m3
    // end snippet

  private def tokenBudget(manager: MemoryManager, query: String): Result[String] = {
    // snippet: token-budget
    // About four characters per token: the context is cut to fit
    val context = manager.getRelevantContext(query, maxTokens = 500)
    // end snippet
    context
  }

  private def cleanUp(store: MemoryStore, thirtyDaysAgo: Instant): Result[MemoryStore] =
    // snippet: clean-up
    // Delete old, low-importance memories (there is no "maximum importance" filter: use `Custom`)
    store.deleteMatching(
      MemoryFilter.Custom(_.importance.exists(_ <= 0.3)) &&
        MemoryFilter.ByTimeRange(before = Some(thirtyDaysAgo))
    )
    // end snippet

  // format: on

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
      val prompt = conversation.messages.collectFirst { case u: UserMessage => u.content }.getOrElse("")
      val reply  = if (prompt.contains("Return JSON array only")) entities else "A short summary of the older memories."
      Right(
        Completion(
          id = "canned",
          created = Instant.EPOCH,
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
      MemoryManagerConfig.default.autoExtractEntities shouldBe false
    }

    "give a memory the configured default importance when none is passed" in {
      val (_, configured) = managers()
      val importances = for {
        m1       <- configured.recordUserFact("Prefers dark mode", Some("user-1"))
        memories <- m1.store.recall(MemoryFilter.All)
      } yield memories.map(_.importance)
      importances.value shouldBe Seq(Some(0.7))
    }

    "build the configuration of the Configuration Options block" in {
      // format: off
      // snippet: config
      val config = MemoryManagerConfig(contextTokenBudget = 500, autoExtractEntities = true)
      val tighter = config.withContextTokenBudget(250)
      // end snippet
      // format: on
      SimpleMemoryManager(config).contextTokenBudget shouldBe 500
      config.autoExtractEntities shouldBe true
      SimpleMemoryManager(tighter).contextTokenBudget shouldBe 250
      tighter.autoExtractEntities shouldBe true
    }

    "turn on automatic entity extraction with the fullFeatured preset the warning names" in {
      MemoryManagerConfig.fullFeatured.autoExtractEntities shouldBe true
      MemoryManagerConfig.fullFeatured shouldBe MemoryManagerConfig.default.withAutoExtractEntities(true)
    }

    "keep the context within contextTokenBudget when the call passes no maxTokens" in {
      val longFact = "Scala " + ("word " * 400)
      def context(budget: Int) = for {
        m1  <- SimpleMemoryManager(MemoryManagerConfig(contextTokenBudget = budget)).recordKnowledge(longFact, "docs")
        ctx <- m1.getRelevantContext("Scala")
      } yield ctx
      context(10).value.length should be <= 40
      context(1000).value should include(longFact.trim)
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
      } yield k.map(memory => (memory.metadata.get("region"), memory.importance))
      knowledge.value shouldBe Seq((Some("eu"), None))
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

        // format: off
        // snippet: sessions
        // Session 1
        val result1 = memory.withManager(_.recordUserFact("Likes Scala", Some("user-1")))

        // Session 2 (later)
        val result2 = memory.withManager(_.getUserContext(Some("user-1")))
        // context includes "Likes Scala" from session 1
        // end snippet
        // format: on

        result1.isRight shouldBe true
        result2.value should include("Likes Scala")
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

    // #1580: a heading is written only with an entry under it, and nothing at all when no entry fits.
    "never leave a section heading without an entry when maxTokens is tiny" in {
      val manager = SimpleMemoryManager.empty.recordKnowledge("Scala 3 has opaque types", "docs")
      val context = manager.flatMap(_.getRelevantContext("Scala", maxTokens = 5)).value
      val lines   = context.linesIterator.toList
      lines.count(_.startsWith("## ")) shouldBe lines.count(_.startsWith("- "))
      context shouldBe "" // 5 tokens is about 20 characters: too few for the heading and its one entry

      val justEnough = manager.flatMap(_.getRelevantContext("Scala", maxTokens = 17)).value
      justEnough shouldBe "# Retrieved Context\n## Relevant Knowledge\n- Scala 3 has opaque types"
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

  "The guide" should {
    "show exactly the code this spec runs" in {
      org.llm4s.testutil.GuideSnippets.check(
        "docs/guide/agents/memory.md",
        Seq("modules/memory/src/test/scala/org/llm4s/agent/memory/MemoryGuideSpec.scala"),
        notRun = Set(
          // the sbt dependency lines
          "libraryDependencies += \"org.llm4s\" %% \"llm4s-memory\" % llm4sVersion",
          "libraryDependencies += \"org.llm4s\" %% \"llm4s-agent-memory\" % llm4sVersion // same version as llm4s-agent",
          // SemanticMemoryMiddleware is in llm4s-agent-memory, which this module does not depend on
          "import org.llm4s.agent.Agent"
        )
      ) shouldBe empty
    }
  }
}
