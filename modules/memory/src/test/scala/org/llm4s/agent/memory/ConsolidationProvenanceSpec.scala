package org.llm4s.agent.memory

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.types.Result
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A consolidation's summary keeps its sources' scope, so an erasure finds it, and replaces them only when it is whole
 * (#3035).
 */
class ConsolidationProvenanceSpec extends AnyFlatSpec with Matchers with LoneElement {

  private def quietly[A](body: => A): A = {
    val logger   = LoggerFactory.getLogger(classOf[LLMMemoryManager]).asInstanceOf[LBLogger]
    val previous = logger.getLevel
    logger.setLevel(Level.OFF)
    try body
    finally logger.setLevel(previous)
  }

  /** Answers with every source line of the prompt, so a summary holds the content of each memory it merged. */
  final private class EchoClient(reason: FinishReason = FinishReason.Stop, reply: Option[String] = None)
      extends LLMClient {
    val calls = new java.util.concurrent.atomic.AtomicInteger()
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.incrementAndGet()
      val prompt = conversation.messages.collectFirst { case u: UserMessage => u.content }.getOrElse("")
      val echoed = prompt.linesIterator.filter(_.contains("secret-")).mkString(" ")
      Right(
        Completion(
          id = "c",
          created = Instant.now(),
          model = ModelName("echo"),
          message = AssistantMessage(reply.getOrElse(echoed)),
          finishReason = reason
        )
      )
    }
    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def contextWindow: Int     = 100000
    override def reserveCompletion: Int = 1000
  }

  private val later = Instant.now().plus(1, ChronoUnit.DAYS)

  private def fact(entity: String, conversation: Option[String], text: String): Memory = {
    val base = Memory.forEntity(EntityId(entity), entity, text)
    conversation.fold(base)(c => base.withMetadata("conversation_id", c))
  }

  private def stored(store: MemoryStore, memories: Seq[Memory]): MemoryStore =
    store.storeAll(memories).fold(e => fail(e.message), identity)

  private def everything(store: MemoryStore): Seq[Memory] =
    store.recall(MemoryFilter.All, Int.MaxValue).fold(e => fail(e.message), identity)

  private def consolidated(
    store: MemoryStore,
    client: LLMClient,
    config: MemoryManagerConfig = MemoryManagerConfig.default
  ): Result[MemoryStore] =
    quietly(LLMMemoryManager(config, store, client).consolidateMemories(later, 2).map(_.store))

  private val stores: Seq[(String, () => MemoryStore)] = Seq(
    "InMemoryStore"     -> (() => InMemoryStore.empty),
    "SQLiteMemoryStore" -> (() => SQLiteMemoryStore.inMemory().fold(e => fail(e.message), identity))
  )

  stores.foreach { case (name, fresh) =>
    s"Consolidation on $name" should "never merge one entity's facts across conversations, so erasing one leaves none of it" in {
      val start = stored(
        fresh(),
        Seq(
          fact("scala", Some("c1"), "secret-c1-a"),
          fact("scala", Some("c1"), "secret-c1-b"),
          fact("scala", Some("c2"), "secret-c2-a"),
          fact("scala", Some("c2"), "secret-c2-b")
        )
      )
      val after     = consolidated(start, new EchoClient()).fold(e => fail(e.message), identity)
      val summaries = everything(after)
      summaries should have size 2
      summaries.map(_.conversationId).toSet shouldBe Set(Some("c1"), Some("c2"))

      val erased = after.deleteMatching(MemoryFilter.ByConversation("c1")).fold(e => fail(e.message), identity)
      val left   = everything(erased)
      (left.map(_.content).mkString should not).include("secret-c1")
      left.map(_.content).mkString should include("secret-c2-a")
    }

    it should "never merge user facts of different users, nor facts of no user" in {
      val start = stored(
        fresh(),
        Seq(
          Memory.userFact("secret-u1-a", Some("u1")),
          Memory.userFact("secret-u1-b", Some("u1")),
          Memory.userFact("secret-u2-a", Some("u2")),
          Memory.userFact("secret-none-a", None),
          Memory.userFact("secret-none-b", None)
        )
      )
      val after = consolidated(start, new EchoClient()).fold(e => fail(e.message), identity)
      val erased =
        after.deleteMatching(MemoryFilter.ByMetadata("user_id", "u1")).fold(e => fail(e.message), identity)
      val left = everything(erased)
      (left.map(_.content).mkString should not).include("secret-u1")
      left.map(_.content).sorted shouldBe Seq("secret-none-a", "secret-none-b", "secret-u2-a")
    }

    it should "keep the sources when the summary stopped at its token limit" in {
      val sources = Seq(fact("scala", Some("c1"), "secret-a"), fact("scala", Some("c1"), "secret-b"))
      val start   = stored(fresh(), sources)
      val after   = consolidated(start, new EchoClient(FinishReason.Length)).fold(e => fail(e.message), identity)
      everything(after).map(_.id).toSet shouldBe sources.map(_.id).toSet

      val strict = consolidated(
        start,
        new EchoClient(FinishReason.Length),
        MemoryManagerConfig.default.withConsolidationConfig(ConsolidationConfig.strict)
      )
      strict.isLeft shouldBe true
      everything(start).map(_.id).toSet shouldBe sources.map(_.id).toSet
    }
  }

  "Consolidation" should "keep the sources for an over-long, empty or otherwise unfinished summary, never cut it" in {
    val sources = Seq(fact("scala", None, "secret-a"), fact("scala", None, "secret-b"))
    val clients = Seq(
      new EchoClient(reply = Some("x" * (LLMMemoryManager.MaxSummaryChars + 1))),
      new EchoClient(reply = Some("   ")),
      new EchoClient(FinishReason.Other("content_filter")),
      new EchoClient(FinishReason.ToolCalls)
    )
    clients.foreach { client =>
      val after = consolidated(stored(InMemoryStore.empty, sources), client).fold(e => fail(e.message), identity)
      everything(after).map(_.id).toSet shouldBe sources.map(_.id).toSet
      client.calls.get shouldBe 1
    }
  }

  it should "pin the provenance keys a summary is stored with, listing every source id" in {
    val sources = (1 to 12).map(i => fact("scala", Some("c1"), s"secret-$i").withMetadata("tenant_ref", "t1"))
    val after =
      consolidated(stored(InMemoryStore.empty, sources), new EchoClient()).fold(e => fail(e.message), identity)
    val summary = everything(after).loneElement
    // the stored format: these keys and values are what an older reader and an erasure see
    summary.metadata.keySet shouldBe Set(
      "entity_id",
      "entity_name",
      "entity_type",
      "conversation_id",
      "tenant_ref",
      "consolidated_from",
      "consolidated_at",
      "consolidation_method",
      "original_ids",
      "consolidation_version"
    )
    summary.metadata("consolidated_from") shouldBe "12"
    summary.metadata("consolidation_method") shouldBe "llm_summary"
    summary.metadata("consolidation_version") shouldBe "2"
    summary.metadata("conversation_id") shouldBe "c1"
    summary.metadata("tenant_ref") shouldBe "t1"
    summary.metadata("original_ids").split(',').toSet shouldBe sources.map(_.id.value).toSet
    ConsolidationProvenance.sourceIds(summary).toSet shouldBe sources.map(_.id).toSet
  }

  it should "never merge memories of different writers, and carry the writer onto the summary" in {
    val writer = "llm4s.writer"
    val ours   = Seq("secret-a", "secret-b").map(t => fact("scala", None, t).withMetadata(writer, "semantic-memory"))
    val theirs = Seq("secret-c", "secret-d").map(t => fact("scala", None, t))
    val after =
      consolidated(stored(InMemoryStore.empty, ours ++ theirs), new EchoClient()).fold(e => fail(e.message), identity)
    val written = everything(after)
    written should have size 2
    written.map(_.getMetadata(writer)).toSet shouldBe Set(Some("semantic-memory"), None)
    written.find(_.getMetadata(writer).isDefined).get.content should (include("secret-a").and(not.include("secret-c")))
  }

  it should "list the sources of an earlier summary among a later summary's" in {
    val first = Seq("secret-a", "secret-b").map(t => fact("scala", Some("c1"), t))
    val once  = consolidated(stored(InMemoryStore.empty, first), new EchoClient()).fold(e => fail(e.message), identity)
    val more  = Seq("secret-c").map(t => fact("scala", Some("c1"), t))
    val twice = consolidated(stored(once, more), new EchoClient()).fold(e => fail(e.message), identity)
    ConsolidationProvenance.sourceIds(everything(twice).loneElement).toSet shouldBe (first ++ more).map(_.id).toSet
  }

  it should "read a summary written before #3035 back unchanged, and never consolidate it again" in {
    val legacy = fact("scala", Some("c1"), "secret-old").withMetadata(
      Map("consolidated_from" -> "60", "consolidation_method" -> "llm_summary", "original_ids" -> "a,b")
    )
    val fresh  = fact("scala", Some("c1"), "secret-new")
    val start  = stored(InMemoryStore.empty, Seq(legacy, fresh))
    val client = new EchoClient()
    val after  = consolidated(start, client).fold(e => fail(e.message), identity)
    client.calls.get shouldBe 0
    everything(after).map(_.id).toSet shouldBe Set(legacy.id, fresh.id)
    ConsolidationProvenance.isLegacySummary(legacy) shouldBe true
    ConsolidationProvenance.sourceIds(legacy) shouldBe Seq(legacy.id)
    after.recall(ConsolidationProvenance.legacySummaries, 10).map(_.map(_.id)) shouldBe Right(Seq(legacy.id))
  }
}
