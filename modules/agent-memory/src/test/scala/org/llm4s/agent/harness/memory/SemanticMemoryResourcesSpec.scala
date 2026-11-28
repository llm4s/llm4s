package org.llm4s.agent.harness.memory

import org.llm4s.agent.{ Agent, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.AgentFixture.built
import org.llm4s.agent.graph.*
import org.llm4s.agent.memory.*
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{ ConcurrentHashMap, CopyOnWriteArrayList }
import scala.concurrent.duration.*

/** `SemanticMemoryMiddleware`'s resources and recall scope (#3040): bounded tenant cells, `close`, scoped recall. */
class SemanticMemoryResourcesSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val t1 = TenantId("t1")
  private val t2 = TenantId("t2")

  private def perTenant(): (Option[TenantId] => Result[MemoryStore], ConcurrentHashMap[Option[TenantId], Int]) =
    val resolved = new ConcurrentHashMap[Option[TenantId], Int]()
    val resolver = (tenant: Option[TenantId]) =>
      resolved.merge(tenant, 1, _ + _)
      Right(InMemoryStore.empty)
    (resolver, resolved)

  private val simple: (MemoryStore, LLMClient) => MemoryManager = (store, _) => SimpleMemoryManager.withStore(store)

  private def answering(texts: String*): ScriptedLLMClient = ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  private def tenant(t: TenantId): RunConfig = RunConfig().withTenantId(t)

  private def systemPrompt(sent: Vector[Message]): String =
    sent.collectFirst { case s: SystemMessage => s.content }.getOrElse("")

  private def held(m: SemanticMemoryMiddleware, tenant: Option[TenantId]): MemoryStore =
    m.withStore(tenant)(Right(_)).value

  private def agentWith(client: LLMClient, m: SemanticMemoryMiddleware): Agent =
    built(Agent.builder("assistant", client).withMiddleware(m))

  // ===== #3040 =====

  "A middleware with maxTenants" should "release the least recently used idle store, and resolve it again" in {
    val released = new CopyOnWriteArrayList[MemoryStore]()
    val resolved = new ConcurrentHashMap[Option[TenantId], Int]()
    val dir      = java.nio.file.Files.createTempDirectory("llm4s-evict")
    // a store in a file: evicting it loses nothing, its tenant's next turn opens the file again
    val stores = (tenant: Option[TenantId]) =>
      resolved.merge(tenant, 1, _ + _)
      SQLiteMemoryStore(dir.resolve(s"${tenant.fold("none")(_.value)}.db").toString)
    val m = new SemanticMemoryMiddleware(
      stores,
      simple,
      answering(),
      maxTenants = 2,
      release = s => { released.add(s); SemanticMemoryMiddleware.closeStore(s) }
    )
    val agent = agentWith(answering("a", "b", "c", "d"), m)
    agent.run(ThreadId("1"), "one", tenant(t1)).value
    agent.run(ThreadId("2"), "two", tenant(t2)).value
    agent.run(ThreadId("3"), "three", RunConfig().withTenantId(TenantId("t3"))).value
    m.openTenants shouldBe 2
    released.size shouldBe 1
    agent.run(ThreadId("4"), "four", tenant(t1)).value
    resolved.get(Some(t1)) shouldBe 2
    MemoryTestSupport.contents(held(m, Some(t1))) should contain("one")
    m.close()
    java.nio.file.Files.list(dir).forEach(p => java.nio.file.Files.delete(p))
    java.nio.file.Files.delete(dir)
  }

  it should "release every store on close, and refuse later turns" in {
    val released    = new CopyOnWriteArrayList[MemoryStore]()
    val (stores, _) = perTenant()
    val m           = new SemanticMemoryMiddleware(stores, simple, answering(), release = s => released.add(s): Unit)
    val agent       = agentWith(answering("a", "b"), m)
    agent.run(ThreadId("1"), "one", tenant(t1)).value
    agent.run(ThreadId("2"), "two", tenant(t2)).value
    m.close()
    m.close()
    released.size shouldBe 2
    m.openTenants shouldBe 0
    m.withStore(Some(t1))(Right(_)).isLeft shouldBe true
  }

  it should "close an AutoCloseable store by default" in {
    val file  = java.nio.file.Files.createTempFile("llm4s-release", ".db")
    val store = SQLiteMemoryStore(file.toString).value
    val m     = new SemanticMemoryMiddleware(_ => Right(store), simple, answering())
    agentWith(answering("ok"), m).run(ThreadId("1"), "hello").value
    m.close()
    store.count().isLeft shouldBe true
    java.nio.file.Files.deleteIfExists(file)
  }

  it should "still refuse a resolver giving a new tenant an evicted tenant's storage" in {
    val file = java.nio.file.Files.createTempFile("llm4s-evicted", ".db")
    val m = new SemanticMemoryMiddleware(
      _ => SQLiteMemoryStore(file.toString),
      simple,
      answering(),
      record = None,
      recall = None,
      maxTenants = 1
    )
    m.withStore(Some(t1))(_.count()).isRight shouldBe true
    m.withStore(Some(t2))(_.count()).isLeft shouldBe true
    m.close()
    java.nio.file.Files.deleteIfExists(file)
  }

  "Recall" should "leave out the run's own thread by default, and recall by principal when scoped" in {
    val store  = InMemoryStore.empty
    val m      = new SemanticMemoryMiddleware(_ => Right(store), simple, answering())
    val client = answering("Noted.", "Noted again.", "You said teal.", "I do not know.")
    val agent  = agentWith(client, m)
    val alice  = RunConfig().withPrincipal(Principal("alice"))
    agent.run(ThreadId("same"), "teal is my colour", alice).value
    agent.run(ThreadId("same"), "and what else", alice).value
    (systemPrompt(client.sent(1)) should not).include("teal is my colour")
    held(m, None)
      .recall(MemoryFilter.ByMetadata(SemanticMemoryMiddleware.PrincipalKey, "alice"), 10)
      .value should have size 4

    val scoped = new SemanticMemoryMiddleware(
      _ => Right(held(m, None)),
      simple,
      answering(),
      recall = Some(MemoryRecall().withScope(MemoryRecall.byPrincipal)),
      record = None
    )
    val c2 = answering("You said teal.", "I do not know.")
    agentWith(c2, scoped).run(ThreadId("other"), "teal?", alice).value
    agentWith(c2, scoped).run(ThreadId("bob"), "teal?", RunConfig().withPrincipal(Principal("bob"))).value
    systemPrompt(c2.sent(0)) should include("teal is my colour")
    (systemPrompt(c2.sent(1)) should not).include("teal is my colour")
  }

  "MemoryConsolidation" should "run at most once per interval for a tenant" in {
    val cell = new TenantMemory(InMemoryStore.empty)
    val now  = Instant.parse("2026-11-28T00:00:00Z")
    cell.consolidationDue(now, 15.minutes) shouldBe true
    cell.consolidationDue(now.plusSeconds(60), 15.minutes) shouldBe false
    cell.consolidationDue(now.plusSeconds(15 * 60), 15.minutes) shouldBe true
    cell.consolidationDue(now.plusSeconds(15 * 60), Duration.Zero) shouldBe true
    MemoryConsolidation().interval shouldBe 15.minutes
    an[IllegalArgumentException] should be thrownBy MemoryConsolidation(1.day, 1, -1.second)
  }

  // ===== Codex on #3295 =====

  "Eviction" should "never drop a store the resolver cannot give back, such as an InMemoryStore" in {
    val m     = new SemanticMemoryMiddleware(_ => Right(InMemoryStore.empty), simple, answering(), maxTenants = 1)
    val agent = agentWith(answering("a", "b", "c"), m)
    agent.run(ThreadId("1"), "alice was here", tenant(t1)).value
    agent.run(ThreadId("2"), "bob was here", tenant(t2)).value
    agent.run(ThreadId("3"), "alice again", tenant(t1)).value
    MemoryTestSupport.contents(held(m, Some(t1))) should contain("alice was here")
    m.openTenants shouldBe 2
  }

  it should "bring the open stores back within maxTenants once a burst of more tenants ends" in {
    val dir      = java.nio.file.Files.createTempDirectory("llm4s-burst")
    val released = new CopyOnWriteArrayList[MemoryStore]()
    val m = new SemanticMemoryMiddleware(
      tenant => SQLiteMemoryStore(dir.resolve(s"${tenant.fold("none")(_.value)}.db").toString),
      simple,
      answering(),
      maxTenants = 1,
      release = s => { released.add(s); SemanticMemoryMiddleware.closeStore(s) }
    )
    val tenants = Vector("a", "b", "c").map(TenantId(_))
    val inside  = new java.util.concurrent.CountDownLatch(tenants.size)
    val leave   = new java.util.concurrent.CountDownLatch(1)
    val threads = tenants.map { t =>
      val thread = new Thread(() =>
        m.withStore(Some(t)) { store =>
          inside.countDown()
          leave.await()
          store.count()
        }: Unit
      )
      thread.start()
      thread
    }
    inside.await()
    m.openTenants shouldBe 3 // all in use: none can go
    leave.countDown()
    threads.foreach(_.join())
    m.openTenants shouldBe 1
    released.size shouldBe 2
    m.close()
    java.nio.file.Files.list(dir).forEach(p => java.nio.file.Files.delete(p))
    java.nio.file.Files.delete(dir)
  }

  "Consolidation" should "reach an older group behind a full page of memories that form none" in {
    val now   = Instant.parse("2026-11-28T12:00:00Z")
    val clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)
    def turn(id: String, conversation: String, at: Instant) =
      Memory(MemoryId(id), s"turn $id", MemoryType.Conversation, Map("conversation_id" -> conversation), at, None)
    val unmergeable = (1 to 30).map(i => turn(s"n$i", s"c$i", now.minusSeconds(3600L + i)))
    val mergeable   = (1 to 3).map(i => turn(s"o$i", "old", now.minusSeconds(86400L + i)))
    val store       = InMemoryStore.withMemories(unmergeable ++ mergeable).value
    val config      = MemoryManagerConfig.default.withConsolidationConfig(ConsolidationConfig(maxMemoriesScanned = 10))
    val m = new SemanticMemoryMiddleware(
      _ => Right(store),
      (s, c) => LLMMemoryManager(config, s, c),
      new MemoryTestSupport.FunctionClient(_ => "Summary of the old conversation."),
      recall = None,
      record = None,
      consolidate = Some(MemoryConsolidation(olderThan = 1.minute, minCount = 3).withInterval(Duration.Zero)),
      clock = clock
    )
    val agent = agentWith(answering(Seq.fill(8)("ok")*), m)
    // 33 old memories, 10 a page: the old group is in the fourth page
    (1 to 5).foreach(i => agent.run(ThreadId(s"t$i"), "hello").value)
    val left = MemoryTestSupport.contents(held(m, None))
    left should contain("Summary of the old conversation.")
    (left should not).contain("turn o1")
  }

  "The consolidation cursor" should "move past a page, and start again once a page comes back short" in {
    val t           = Instant.parse("2026-11-28T00:00:00Z")
    def at(s: Long) = Memory(MemoryId(s"m$s"), "x", MemoryType.Task, Map.empty, t.plusSeconds(s), None)
    SemanticMemoryMiddleware.nextCursor(Seq(at(5), at(3), at(4)), 3) shouldBe Some(t.plusSeconds(3))
    SemanticMemoryMiddleware.nextCursor(Seq(at(5), at(5)), 2) shouldBe Some(t.plusSeconds(5).minusMillis(1))
    SemanticMemoryMiddleware.nextCursor(Seq(at(5)), 3) shouldBe None
  }
}
