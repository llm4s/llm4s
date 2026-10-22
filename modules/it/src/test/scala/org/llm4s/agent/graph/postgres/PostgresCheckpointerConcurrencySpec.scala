package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.*
import org.llm4s.it.tags.Docker
import org.llm4s.types.Result
import org.scalatest.{ BeforeAndAfterAll, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{ CyclicBarrier, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Real concurrent transactions against one schema, from many stores - many pools, as many machines would have -
 * beyond what the contract's sixteen claimers ask (design 4.50): many more contenders, over many rounds; commits
 * racing on one thread; fenced commits on two threads that fence each other, which deadlock unless rows are locked
 * in one order; tokens racing across stores; and a parent's children, placed as their commits race, each listed once.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]].
 */
@Docker
class PostgresCheckpointerConcurrencySpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterAll {

  private val schema = PgCheckpointerDb.freshSchema()
  private val config = PostgresCheckpointerConfig(schema = schema)
  private val ttl    = 30.seconds
  private val wait   = 60.seconds

  private def stores(n: Int): Vector[PostgresCheckpointer] = {
    PgCheckpointerDb.require()
    Vector.fill(n)(PgCheckpointerDb.store(config, maxPoolSize = 4))
  }

  private def thread(): ThreadId = ThreadId(s"concurrency-${java.util.UUID.randomUUID()}")

  /** Runs `n` calls at once, released together by a barrier, each on a virtual thread; their results, by index. */
  private def race[A](n: Int)(call: Int => A): Vector[A] = {
    val barrier = new CyclicBarrier(n)
    val results = new LinkedBlockingQueue[(Int, A)]()
    val threads = (0 until n).map { i =>
      Thread.ofVirtual().start { () =>
        barrier.await(wait.toMillis, TimeUnit.MILLISECONDS): Unit
        results.put(i -> call(i))
      }
    }
    threads.foreach(_.join(wait.toMillis))
    val all = results.asScala.toVector.sortBy(_._1).map(_._2)
    all should have size n.toLong
    all
  }

  private def checkpoint(id: String, parent: Option[String], on: ThreadId, origin: Option[RunOrigin] = None) =
    Checkpoint(
      Checkpoint.CurrentFormat,
      id,
      parent,
      on.value,
      "run",
      CheckpointStatus.Running,
      Instant.parse("2026-10-21T12:00:00Z"),
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false),
      None,
      None
    ).withOrigin(origin)

  private def event(name: String): EventDraft =
    EventDraft("run", None, None, None, Instant.now(), RunEvent.Custom(name, 1, ujson.Obj("name" -> name)))

  "Many stores claiming one thread at once" should "grant exactly one claim, round after round, with 64 contenders" in {
    val all = stores(16)
    (1 to 10).foreach { round =>
      val t       = thread()
      val results = race(64)(i => all(i % all.size).claim(t, ClaimRequest(RunId(s"run-$i"), None, ttl)))
      val granted = results.collect { case Right(claim) => claim }
      withClue(s"round $round: ")(granted should have size 1)
      results.collect { case Left(e) => e }.foreach {
        _ shouldBe GraphError.ThreadBusy(t.value, None, GraphError.BusyHolder.Run(granted.head.holder))
      }
    }
    all.foreach(_.close())
  }

  "Commits racing on one thread" should "number every event once, contiguously, and let one of many checkpoints win" in {
    val all   = stores(8)
    val t     = thread()
    val token = all.head.claim(t, ClaimRequest(RunId("run"), None, ttl)).value.token
    all.head.commit(t, Commit(token, Some(checkpoint("root", None, t)))).value
    // 64 commits of events alone, all accepted: one row lock orders them
    val appended =
      race(64)(i => all(i % all.size).commit(t, Commit(token, events = Vector(event(s"a$i"), event(s"b$i")))))
    val seqs = appended.flatMap(_.value.map(_.seq))
    seqs.sorted shouldBe (1L to 128L).toVector
    all.head.eventsAfter(t, 0L, 1000).value.map(_.seq) shouldBe (1L to 128L).toVector
    // 32 checkpoints over the same parent: exactly one is accepted, the rest conflict
    val forks = race(32)(i => all(i % all.size).commit(t, Commit(token, Some(checkpoint(s"c$i", Some("root"), t)))))
    forks.count(_.isRight) shouldBe 1
    forks.collect { case Left(e) => e }.foreach(_ shouldBe a[GraphError.CheckpointConflict])
    all.head.history(t, None, 100).value should have size 2
    all.foreach(_.close())
  }

  "Fenced commits on two threads that fence each other" should "never deadlock" in {
    val all = stores(8)
    val a   = thread()
    val b   = thread()
    val ta  = all.head.claim(a, ClaimRequest(RunId("a"), None, ttl)).value.token
    val tb  = all.head.claim(b, ClaimRequest(RunId("b"), None, ttl)).value.token
    // half commit to `a` fenced by `b`, half to `b` fenced by `a`: each locks both rows, in one order
    val results: Vector[Result[Vector[EventRecord]]] = race(64) { i =>
      val store = all(i % all.size)
      if (i % 2 == 0) store.commit(a, Commit(ta, events = Vector(event(s"a$i"))).withFence(b -> tb))
      else store.commit(b, Commit(tb, events = Vector(event(s"b$i"))).withFence(a -> ta))
    }
    results.collect { case Left(e) => e } shouldBe empty
    all.head.eventsAfter(a, 0L, 1000).value.map(_.seq) shouldBe (1L to 32L).toVector
    all.head.eventsAfter(b, 0L, 1000).value.map(_.seq) shouldBe (1L to 32L).toVector
    all.foreach(_.close())
  }

  "Claims, releases and takeovers racing across stores" should "issue tokens that only increase, never twice" in {
    val all     = stores(8)
    val threads = Vector.fill(8)(thread())
    val tokens = race(64) { i =>
      val store = all(i % all.size)
      val t     = threads(i % threads.size)
      // claim until granted, then release, so each thread's claims pass from store to store
      Iterator
        .continually(store.claim(t, ClaimRequest(RunId(s"run-$i"), None, ttl)))
        .collectFirst { case Right(claim) => claim }
        .map { claim =>
          store.release(t, claim.token).value
          t -> claim.token.value
        }
        .get
    }
    tokens.map(_._2).distinct should have size tokens.size.toLong
    // each thread's tokens were issued in the order its claims committed: one after the other, never equal
    tokens
      .groupBy(_._1)
      .values
      .foreach(perThread => perThread.map(_._2).distinct should have size perThread.size.toLong)
    all.foreach(_.close())
  }

  "Children whose first commits race" should "each be listed exactly once when paged" in {
    val all    = stores(8)
    val parent = thread()
    val claim  = all.head.claim(parent, ClaimRequest(RunId("parent"), None, ttl)).value
    all.head.commit(parent, Commit(claim.token, Some(checkpoint("p", None, parent)))).value
    val worker = org.llm4s.agent.AgentId.unsafe("worker")
    val kids   = Vector.tabulate(48)(i => ThreadId.child(parent, worker, IdempotencyKey(s"call-$i")))
    val placed = race(kids.size) { i =>
      val store  = all(i % all.size)
      val kid    = kids(i)
      val token  = store.claim(kid, ClaimRequest(RunId(s"kid-$i"), None, ttl)).value.token
      val origin = RunOrigin(parent, IdempotencyKey(s"call-$i"), worker, Instant.now())
      store
        .commit(kid, Commit(token, Some(checkpoint(s"k$i", None, kid, Some(origin)))).withFence(parent -> claim.token))
        .map(_ => kid)
    }
    placed.collect { case Left(e) => e } shouldBe empty
    // paging two at a time sees every child exactly once
    val paged = Iterator
      .iterate((Vector.empty[ThreadId], Option.empty[ThreadId], false)) { case (seen, after, _) =>
        val page = all.head.children(parent, after, 2).value.map(_.threadId)
        (seen ++ page, page.lastOption.orElse(after), page.isEmpty)
      }
      .dropWhile(!_._3)
      .next()
      ._1
    paged.toSet shouldBe kids.toSet
    paged.distinct should have size kids.size.toLong
    all.foreach(_.close())
  }

  override def afterAll(): Unit = {
    if (PgCheckpointerDb.enabled) PgCheckpointerDb.dropSchema(schema)
    super.afterAll()
  }
}
