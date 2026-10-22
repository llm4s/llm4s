package org.llm4s.agent.memory

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.MockLLMClients
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Each [[MemoryManagerConfig]] option changes what a manager does (#1579).
 *
 * `defaultImportance` and `consolidationConfig` are covered where they are used
 * (`SimpleMemoryManagerSpec`, `LLMMemoryManagerSpec`); this spec covers `contextTokenBudget` and
 * `autoExtractEntities`, which no manager read before #1579.
 */
class MemoryManagerConfigBehaviourSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val longFact = "Scala " + ("word " * 400) // about 2000 characters, about 500 tokens

  private def contextFor(manager: MemoryManager, maxTokens: Option[Int] = None): String = {
    val result = for {
      m1  <- manager.recordKnowledge(longFact, "docs")
      ctx <- maxTokens.fold(m1.getRelevantContext("Scala"))(t => m1.getRelevantContext("Scala", maxTokens = t))
    } yield ctx
    result.value
  }

  // ---------------------------------------------------------------- contextTokenBudget

  "contextTokenBudget" should "bound getRelevantContext when the call passes no maxTokens" in {
    contextFor(SimpleMemoryManager(MemoryManagerConfig(contextTokenBudget = 10))) shouldBe ""
  }

  it should "let the memory in when the configured budget has room for it" in {
    val generous = contextFor(SimpleMemoryManager(MemoryManagerConfig(contextTokenBudget = 1000)))
    generous should include(longFact.trim)
  }

  it should "default to 2000 tokens, which fits the same memory" in {
    SimpleMemoryManager.empty.contextTokenBudget shouldBe 2000
    contextFor(SimpleMemoryManager.empty) should include(longFact.trim)
  }

  it should "give way to an explicit maxTokens, in either direction" in {
    val tight = SimpleMemoryManager(MemoryManagerConfig(contextTokenBudget = 10))
    contextFor(tight, maxTokens = Some(1000)) should include(longFact.trim)

    val roomy = SimpleMemoryManager(MemoryManagerConfig(contextTokenBudget = 1000))
    contextFor(roomy, maxTokens = Some(10)) shouldBe ""
  }

  it should "be kept by the manager returned from a record call" in {
    val manager = SimpleMemoryManager(MemoryManagerConfig(contextTokenBudget = 123))
    manager.recordUserFact("Prefers Scala").value.contextTokenBudget shouldBe 123
  }

  it should "be honoured by LLMMemoryManager" in {
    val client = new MockLLMClients.SimpleMock("[]")
    val tight  = LLMMemoryManager(MemoryManagerConfig(contextTokenBudget = 10), InMemoryStore.empty, client)
    contextFor(tight) shouldBe ""
  }

  /** A MemoryManager written outside llm4s, which does not override `contextTokenBudget`. */
  final private class ProbeManager extends MemoryManager {
    private val delegate           = SimpleMemoryManager.empty
    var seenMaxTokens: Option[Int] = None

    def store: MemoryStore = delegate.store
    def recordMessage(message: Message, conversationId: String, importance: Option[Double]): Result[MemoryManager] =
      delegate.recordMessage(message, conversationId, importance)
    def recordConversation(messages: Seq[Message], conversationId: String): Result[MemoryManager] =
      delegate.recordConversation(messages, conversationId)
    def recordEntityFact(
      entityId: EntityId,
      entityName: String,
      fact: String,
      entityType: String,
      importance: Option[Double]
    ): Result[MemoryManager] = delegate.recordEntityFact(entityId, entityName, fact, entityType, importance)
    def recordUserFact(fact: String, userId: Option[String], importance: Option[Double]): Result[MemoryManager] =
      delegate.recordUserFact(fact, userId, importance)
    def recordKnowledge(content: String, source: String, metadata: Map[String, String]): Result[MemoryManager] =
      delegate.recordKnowledge(content, source, metadata)
    def recordTask(
      description: String,
      outcome: String,
      success: Boolean,
      importance: Option[Double]
    ): Result[MemoryManager] = delegate.recordTask(description, outcome, success, importance)
    def getRelevantContext(query: String, maxTokens: Int, filter: MemoryFilter): Result[String] = {
      seenMaxTokens = Some(maxTokens)
      Right("")
    }
    def getConversationContext(conversationId: String, maxMessages: Int): Result[String] =
      delegate.getConversationContext(conversationId, maxMessages)
    def getEntityContext(entityId: EntityId): Result[String]   = delegate.getEntityContext(entityId)
    def getUserContext(userId: Option[String]): Result[String] = delegate.getUserContext(userId)
    def consolidateMemories(olderThan: java.time.Instant, minCount: Int): Result[MemoryManager] =
      delegate.consolidateMemories(olderThan, minCount)
    def extractEntities(text: String, conversationId: Option[String]): Result[MemoryManager] =
      delegate.extractEntities(text, conversationId)
    def stats: Result[MemoryStats] = delegate.stats
  }

  "MemoryManager.contextTokenBudget" should "default to 2000 for an implementation that does not override it" in {
    val probe = new ProbeManager
    probe.getRelevantContext("anything").value shouldBe ""
    probe.seenMaxTokens shouldBe Some(MemoryManager.DefaultContextTokenBudget)
  }

  // ---------------------------------------------------------------- autoExtractEntities

  /** Answers every entity-extraction prompt with one entity, and counts the calls. */
  final private class ExtractingClient extends LLMClient {
    var prompts: Vector[String] = Vector.empty

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val prompt = conversation.messages.collectFirst { case u: UserMessage => u.content }.getOrElse("")
      prompts = prompts :+ prompt
      val response =
        """[{"entity_name": "Martin Odersky", "entity_type": "person", "fact": "Martin Odersky created Scala.", "importance": 0.9}]"""
      Right(
        Completion(
          id = "extract",
          created = Instant.EPOCH,
          content = response,
          model = "mock-model",
          message = AssistantMessage(response),
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

  private def llmManager(autoExtract: Boolean, client: LLMClient): LLMMemoryManager =
    LLMMemoryManager(MemoryManagerConfig(autoExtractEntities = autoExtract), InMemoryStore.empty, client)

  private def entityMemories(manager: MemoryManager): Seq[Memory] =
    manager.store.recall(MemoryFilter.ByType(MemoryType.Entity)).value

  "autoExtractEntities" should "extract entities from a recorded user message when true" in {
    val client = new ExtractingClient
    val recorded =
      llmManager(autoExtract = true, client).recordMessage(UserMessage("Martin Odersky created Scala"), "conv-1").value

    client.prompts should have size 1
    client.prompts.head should include("Martin Odersky created Scala")
    val entities = entityMemories(recorded)
    entities.map(_.content) shouldBe Seq("Martin Odersky created Scala.")
    entities.head.conversationId shouldBe Some("conv-1")
    recorded.store.recall(MemoryFilter.ByType(MemoryType.Conversation)).value should have size 1
  }

  it should "make no LLM call and store no entity when false" in {
    val client = new ExtractingClient
    val recorded =
      llmManager(autoExtract = false, client).recordMessage(UserMessage("Martin Odersky created Scala"), "conv-1").value

    client.prompts shouldBe empty
    entityMemories(recorded) shouldBe empty
    recorded.store.recall(MemoryFilter.All).value should have size 1
  }

  it should "analyse user and assistant messages of a recorded conversation, but not system or tool messages" in {
    val client = new ExtractingClient
    val conversation = Seq(
      SystemMessage("You are a helpful assistant"),
      UserMessage("Who created Scala?"),
      AssistantMessage("Martin Odersky created Scala."),
      ToolMessage("{\"result\": 42}", "call-1")
    )
    val recorded = llmManager(autoExtract = true, client).recordConversation(conversation, "conv-2").value

    client.prompts should have size 2
    client.prompts.exists(_.contains("Who created Scala?")) shouldBe true
    client.prompts.exists(_.contains("Martin Odersky created Scala.")) shouldBe true
    client.prompts.exists(_.contains("You are a helpful assistant")) shouldBe false
    client.prompts.exists(_.contains("\"result\"")) shouldBe false
    recorded.store.recall(MemoryFilter.ByType(MemoryType.Conversation)).value should have size 4
    entityMemories(recorded) should not be empty
  }

  it should "keep the recorded message when extraction fails" in {
    val logger   = LoggerFactory.getLogger(classOf[BaseMemoryManagerOps]).asInstanceOf[LBLogger]
    val previous = logger.getLevel
    logger.setLevel(Level.OFF)
    val result =
      try llmManager(autoExtract = true, new MockLLMClients.FailingMock()).recordMessage(UserMessage("Hello"), "conv-3")
      finally logger.setLevel(previous)

    val recorded = result.value
    recorded.store.recall(MemoryFilter.All).value.map(_.content) shouldBe Seq("Hello")
  }

  it should "have no effect on SimpleMemoryManager, which extracts nothing" in {
    val recorded = SimpleMemoryManager(MemoryManagerConfig(autoExtractEntities = true))
      .recordMessage(UserMessage("Martin Odersky created Scala"), "conv-4")
      .value
    entityMemories(recorded) shouldBe empty
    recorded.store.recall(MemoryFilter.All).value should have size 1
  }

  // ---------------------------------------------------------------- autoExtractEntities: cancellation (#1847)

  /** Runs `answer` for every completion request, and counts the calls. */
  final private class ScriptedClient(answer: () => Result[Completion]) extends LLMClient {
    var calls: Int = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls += 1
      answer()
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /** Runs `body` with the warnings of the shared record operations captured. */
  private def capturingWarnings[A](body: => A): (A, Seq[String]) = {
    val logger   = LoggerFactory.getLogger(classOf[BaseMemoryManagerOps]).asInstanceOf[LBLogger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]()
    appender.start()
    val previousLevel    = logger.getLevel
    val previousAdditive = logger.isAdditive
    logger.addAppender(appender)
    logger.setLevel(Level.WARN)
    logger.setAdditive(false)
    val result =
      try body
      finally {
        logger.detachAppender(appender)
        logger.setLevel(previousLevel)
        logger.setAdditive(previousAdditive)
        appender.stop()
      }
    import scala.jdk.CollectionConverters._
    val warnings = appender.list.asScala.toList.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage)
    (result, warnings)
  }

  /** Runs `body` and reports whether it left the interrupt flag set, clearing it afterwards. */
  private def withInterruptFlagReport[A](body: => A): (A, Boolean) = {
    var interrupted = false
    val result =
      try body
      finally interrupted = Thread.interrupted()
    (result, interrupted)
  }

  "autoExtractEntities" should "return a cancelled extraction as a CancelledError, logging no warning" in {
    val client = new ScriptedClient(() => Left(org.llm4s.error.CancelledError("model")))
    val (result, warnings) =
      capturingWarnings(llmManager(autoExtract = true, client).recordMessage(UserMessage("Ada wrote programs"), "c"))

    result.left.value shouldBe a[org.llm4s.error.CancelledError]
    warnings shouldBe empty
    client.calls shouldBe 1
  }

  it should "keep the interrupt flag when the extraction returns a cancellation" in {
    val client = new ScriptedClient(() => {
      Thread.currentThread().interrupt()
      Left(org.llm4s.error.CancelledError("model"))
    })
    val (result, interrupted) =
      withInterruptFlagReport(llmManager(autoExtract = true, client).recordMessage(UserMessage("Ada"), "c"))

    result.left.value shouldBe a[org.llm4s.error.CancelledError]
    interrupted shouldBe true
  }

  it should "treat a failure that ends while the thread is interrupted as a cancellation, keeping the flag" in {
    val client = new ScriptedClient(() => {
      Thread.currentThread().interrupt()
      Left(org.llm4s.error.NetworkError("connection closed", None, "https://example.invalid"))
    })
    val ((result, warnings), interrupted) = withInterruptFlagReport(
      capturingWarnings(llmManager(autoExtract = true, client).recordMessage(UserMessage("Ada"), "c"))
    )

    result.left.value shouldBe a[org.llm4s.error.CancelledError]
    warnings shouldBe empty
    interrupted shouldBe true
  }

  it should "return an InterruptedException thrown by the extraction as a CancelledError and restore the flag" in {
    val client = new ScriptedClient(() => throw new InterruptedException("stopped"))
    val (result, interrupted) =
      withInterruptFlagReport(llmManager(autoExtract = true, client).recordMessage(UserMessage("Ada"), "c"))

    result.left.value shouldBe a[org.llm4s.error.CancelledError]
    interrupted shouldBe true
  }

  it should "stop recording a conversation at a cancelled extraction" in {
    val client = new ScriptedClient(() => Left(org.llm4s.error.CancelledError("model")))
    val result = llmManager(autoExtract = true, client)
      .recordConversation(Seq(UserMessage("one"), AssistantMessage("two"), UserMessage("three")), "c")

    result.left.value shouldBe a[org.llm4s.error.CancelledError]
    client.calls shouldBe 1
  }

  it should "log an ordinary extraction failure as a warning and keep the recorded message" in {
    val client =
      new ScriptedClient(() => Left(org.llm4s.error.NetworkError("connection refused", None, "https://x.invalid")))
    val ((result, warnings), interrupted) = withInterruptFlagReport(
      capturingWarnings(llmManager(autoExtract = true, client).recordMessage(UserMessage("Hello"), "conv-5"))
    )

    val recorded = result.value
    recorded.store.recall(MemoryFilter.All).value.map(_.content) shouldBe Seq("Hello")
    entityMemories(recorded) shouldBe empty
    warnings should have size 1
    warnings.head should include("entity extraction failed for conversation conv-5")
    warnings.head should include("connection refused")
    interrupted shouldBe false
  }

  it should "keep recording a conversation after an ordinary extraction failure" in {
    val client =
      new ScriptedClient(() => Left(org.llm4s.error.NetworkError("connection refused", None, "https://x.invalid")))
    val (result, warnings) = capturingWarnings(
      llmManager(autoExtract = true, client)
        .recordConversation(Seq(UserMessage("one"), AssistantMessage("two")), "c")
    )

    result.value.store.recall(MemoryFilter.All).value should have size 2
    client.calls shouldBe 2
    warnings should have size 2
  }
}
