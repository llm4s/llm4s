package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.*
import org.llm4s.it.tags.Docker
import org.llm4s.types.Result
import org.postgresql.PGConnection
import org.scalatest.{ BeforeAndAfterAll, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{ ConcurrentLinkedQueue, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * `PostgresCheckpointer`'s change notification (design 4.51), over two stores - two pools, two listeners - on one
 * database and schema, as two machines would open it: a waiter on store A wakes promptly on a commit through store B;
 * one listening connection serves every waiter of a store; the notification is sent only when its transaction commits
 * and carries nothing of the thread; and a listening connection killed under the store is survived - its waiters keep
 * their bound and poll while it is down, and it comes back.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]].
 */
@Docker
class PostgresChangeNotificationSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterAll {

  private val schemas = new ConcurrentLinkedQueue[String]()
  private val opened  = new ConcurrentLinkedQueue[PostgresCheckpointer]()

  /** Two stores over one fresh schema, each with a pool and a listener of its own. */
  private def pair(): (PostgresCheckpointer, PostgresCheckpointer) = {
    PgCheckpointerDb.require()
    val schema = PgCheckpointerDb.freshSchema()
    schemas.add(schema)
    val config = PostgresCheckpointerConfig(schema = schema, callTimeout = 2.seconds, connectionTimeout = 2.seconds)
    val a      = PgCheckpointerDb.store(config)
    val b      = PgCheckpointerDb.store(config)
    opened.add(a)
    opened.add(b)
    (a, b)
  }

  private def thread(): ThreadId = ThreadId(s"notify-${java.util.UUID.randomUUID()}")

  private def event(name: String): EventDraft =
    EventDraft("run", None, None, None, Instant.now(), RunEvent.Custom(name, 1, ujson.Obj("secret" -> name)))

  private def claimed(store: PostgresCheckpointer, t: ThreadId): FencingToken =
    store.claim(t, ClaimRequest(RunId("run"), None, 1.minute)).value.token

  private def commitEvents(store: PostgresCheckpointer, t: ThreadId, token: FencingToken, names: String*): Unit =
    store.commit(t, Commit(token, None, Vector.empty, names.toVector.map(event))).value: Unit

  /** `body` true within `within`, checked every 20 ms. */
  private def eventually(within: FiniteDuration)(body: => Boolean): Boolean = {
    val deadline = System.nanoTime() + within.toNanos
    @scala.annotation.tailrec
    def check(): Boolean =
      if (body) true
      else if (System.nanoTime() >= deadline) false
      else {
        Thread.sleep(20)
        check()
      }
    check()
  }

  /** One call on a virtual thread of its own: its result, and when it returned. */
  private def waiting(
    store: PostgresCheckpointer,
    t: ThreadId,
    after: Long,
    timeout: FiniteDuration
  ): (Thread, LinkedBlockingQueue[(Result[Vector[EventRecord]], Long)]) = {
    val returned = new LinkedBlockingQueue[(Result[Vector[EventRecord]], Long)]()
    val waiter = Thread.ofVirtual().start { () =>
      val result = store.awaitEventsAfter(t, after, 10, timeout)
      returned.offer(result -> System.nanoTime()): Unit
    }
    (waiter, returned)
  }

  private def taken[A](queue: LinkedBlockingQueue[A], within: FiniteDuration): A =
    Option(queue.poll(within.toMillis, TimeUnit.MILLISECONDS)).getOrElse(fail(s"nothing returned within $within"))

  /** Waits as a subscription's watch does - one call after another, `pollInterval` each - until one finds events. */
  private def watch(
    store: PostgresCheckpointer,
    t: ThreadId,
    after: Long,
    pollInterval: FiniteDuration,
    within: FiniteDuration
  ) = {
    val deadline = System.nanoTime() + within.toNanos
    Iterator
      .continually(store.awaitEventsAfter(t, after, 10, pollInterval))
      .dropWhile(r => r.exists(_.isEmpty) && System.nanoTime() < deadline)
      .next()
  }

  "A waiter on one store" should "wake promptly on a commit through another store over the same database" in {
    val (a, b) = pair()
    val t      = thread()
    val token  = claimed(b, t)
    commitEvents(b, t, token, "first")
    // the first wait starts store A's listener; once it listens, a commit wakes a waiter long before its timeout
    a.awaitEventsAfter(t, 1L, 10, 50.millis).value shouldBe empty
    eventually(10.seconds)(a.listening) shouldBe true
    (2 to 6).foreach { seq =>
      val (_, returned) = waiting(a, t, seq - 1L, 1.hour)
      Thread.sleep(100)
      returned.peek() shouldBe null
      commitEvents(b, t, token, s"e$seq")
      val committed    = System.nanoTime()
      val (result, at) = taken(returned, 10.seconds)
      result.value.map(_.seq) shouldBe Vector(seq.toLong)
      withClue(s"woken ${(at - committed).nanos.toMillis} ms after the commit: ")(
        (at - committed).nanos should be < 1.second
      )
    }
  }

  it should "share one listening connection with every other waiter of its store" in {
    val (a, b)  = pair()
    val threads = Vector.fill(8)(thread())
    val tokens  = threads.map(t => t -> claimed(b, t)).toMap
    val waiters = threads.flatMap(t => Vector.fill(4)(t -> waiting(a, t, 0L, 1.hour)._2))
    eventually(10.seconds)(a.listening) shouldBe true
    Thread.sleep(100)
    threads.foreach(t => commitEvents(b, t, tokens(t), "one"))
    waiters.foreach((_, returned) => taken(returned, 5.seconds)._1.value.map(_.seq) shouldBe Vector(1L))
    a.listenerConnections shouldBe 1
    // and nothing but its one listener: the waiters read through the pool, which holds no other LISTEN
    PgCheckpointerDb.scalar(
      s"SELECT count(*) FROM pg_stat_activity WHERE application_name = '${PostgresCheckpointer.ListenerApplicationName}' " +
        s"AND pid = ${a.listenerPid.getOrElse(-1)}"
    )(_.getLong(1)) shouldBe 1L
  }

  it should "keep waiting, and return nothing, when a commit lands on another thread" in {
    val (a, b) = pair()
    val t      = thread()
    val other  = thread()
    val token  = claimed(b, other)
    a.awaitEventsAfter(t, 0L, 10, 50.millis).value shouldBe empty
    eventually(10.seconds)(a.listening) shouldBe true
    val started       = System.nanoTime()
    val (_, returned) = waiting(a, t, 0L, 700.millis)
    Thread.sleep(100)
    commitEvents(b, other, token, "elsewhere")
    val (result, at) = taken(returned, 5.seconds)
    result.value shouldBe empty
    (at - started).nanos should be >= 650.millis
  }

  "A commit" should "notify only when it commits, naming the thread by a hash and carrying nothing of its content" in {
    val (a, _) = pair()
    val t      = thread()
    Using.resource(PgCheckpointerDb.connect()) { listener =>
      listener.setAutoCommit(true)
      Using.resource(listener.createStatement())(_.execute(s"""LISTEN "${a.channel}""""): Unit)
      val pg = listener.unwrap(classOf[PGConnection])
      def received(): Vector[(String, String)] =
        Option(pg.getNotifications(500)).toVector.flatten.map(n => n.getName -> n.getParameter)

      val token = claimed(a, t)
      received() shouldBe empty // a claim tells nobody
      // refused: a stale token, so the transaction rolls back and its notification is never sent
      a.commit(t, Commit(FencingToken(token.value + 1000), None, Vector.empty, Vector(event("refused"))))
        .isLeft shouldBe true
      received() shouldBe empty

      commitEvents(a, t, token, "landed")
      val notes = received()
      notes shouldBe Vector(a.channel -> ChangeNotifier.payload(t))
      notes.foreach { (_, payload) =>
        (payload should not).include(t.value)
        (payload should not).include("landed")
        payload.length shouldBe 16
      }

      a.deleteThread(t, token, None).value
      received() shouldBe Vector(a.channel -> ChangeNotifier.payload(t))
    }
  }

  "A store whose listening connection is killed" should "keep every wait bounded, poll while it is down, and listen again" in {
    val (a, b) = pair()
    val t      = thread()
    val token  = claimed(b, t)
    commitEvents(b, t, token, "first")
    a.awaitEventsAfter(t, 1L, 10, 50.millis).value shouldBe empty
    eventually(10.seconds)(a.listening) shouldBe true
    val killed = a.listenerPid.getOrElse(fail("no listener"))

    PgCheckpointerDb.scalar(s"SELECT pg_terminate_backend($killed)")(_.getBoolean(1)) shouldBe true
    // a wait made now keeps its bound, listener or none
    val started = System.nanoTime()
    a.awaitEventsAfter(t, 1L, 10, 300.millis).value shouldBe empty
    (System.nanoTime() - started).nanos should be < (300.millis + a.config.bound)

    // a commit made while it may be down still reaches a watch, by polling if not by notification
    commitEvents(b, t, token, "second")
    watch(a, t, 1L, 200.millis, 10.seconds).value.map(_.seq) shouldBe Vector(2L)

    // the store connects again, and a commit wakes a long wait once more
    eventually(10.seconds)(a.listenerPid.exists(_ != killed)) shouldBe true
    a.listenerConnections should be >= 2
    val (_, returned) = waiting(a, t, 2L, 1.hour)
    Thread.sleep(100)
    commitEvents(b, t, token, "third")
    val committed    = System.nanoTime()
    val (result, at) = taken(returned, 10.seconds)
    result.value.map(_.seq) shouldBe Vector(3L)
    (at - committed).nanos should be < 1.second
  }

  it should "end a waiting call, and close its listening connection, when the store is closed" in {
    val (a, _)        = pair()
    val t             = thread()
    val (_, returned) = waiting(a, t, 0L, 1.hour)
    eventually(10.seconds)(a.listening) shouldBe true
    val pid = a.listenerPid.getOrElse(fail("no listener"))
    a.close()
    taken(returned, 5.seconds)._1.isLeft shouldBe true
    eventually(5.seconds)(
      PgCheckpointerDb.scalar(s"SELECT count(*) FROM pg_stat_activity WHERE pid = $pid")(_.getLong(1)) == 0L
    ) shouldBe true
  }

  override def afterAll(): Unit = {
    opened.asScala.foreach(_.close())
    if (PgCheckpointerDb.enabled) schemas.asScala.foreach(PgCheckpointerDb.dropSchema)
    super.afterAll()
  }
}
