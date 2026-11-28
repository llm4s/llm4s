package org.llm4s.agent.graph.postgres

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.service.{ EntryId, EntryOutcome, QueuedInput, Submission, TakeRequest }
import org.llm4s.agent.testkit.{ ManualClock, RunQueueContract }
import org.llm4s.it.tags.Docker
import org.scalatest.{ BeforeAndAfterAll, Outcome }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.util.Using

/**
 * `PostgresCheckpointer.runQueue` passes the contract every run queue must pass (design 4.55), against a real server.
 * Every case shares one schema of this suite's own, with thread and agent ids of its own; `reopen` closes the store's
 * pool and opens the schema again, and `sibling` opens another store over a pool of its own, so the race of many takes
 * is decided by the database's row locks (`FOR UPDATE SKIP LOCKED`) and advisory locks, not by one instance.
 *
 * As for `PostgresCheckpointerContractSpec`, each case's `ManualClock` is mirrored into a row the store reads where it
 * would read `clock_timestamp()`, so lease expiry is still judged in the database and exactly.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]] for the connection.
 */
@Docker
class PostgresRunQueueContractSpec extends AnyFlatSpec with RunQueueContract with BeforeAndAfterAll {

  import PostgresRunQueueContractSpec.*

  private def clockId(clock: Clock): String = clockIds.synchronized {
    Option(clockIds.get(clock)).getOrElse {
      val id = clocks.create(clock.instant())
      clockIds.put(clock, id)
      id
    }
  }

  private def open(clock: Clock): RunQueueContract.Stores = {
    PgCheckpointerDb.require()
    val store = PgCheckpointerDb.store(config, clocks.sql(clockId(clock)))
    opened.add(store)
    RunQueueContract.Stores(store, store.runQueue)
  }

  protected def newStores(clock: Clock): RunQueueContract.Stores = open(clock)

  override protected def reopen(stores: RunQueueContract.Stores, clock: ManualClock): RunQueueContract.Stores = {
    closeExtra(stores)
    open(clock)
  }

  override protected def sibling(stores: RunQueueContract.Stores, clock: ManualClock): RunQueueContract.Stores =
    open(clock)

  override protected def advanceStoreClock(
    stores: RunQueueContract.Stores,
    clock: ManualClock,
    by: FiniteDuration
  ): Unit = {
    clock.advance(by)
    clocks.set(clockId(clock), clock.instant())
  }

  override protected def spoilInput(stores: RunQueueContract.Stores, entryId: EntryId): Boolean =
    stores.checkpointer match {
      case store: PostgresCheckpointer =>
        store
          .writing("spoil") { c =>
            PostgresCheckpointer.update(
              c,
              s"UPDATE ${store.t.queueEntries} SET input_json = ? WHERE entry_id = ?",
              """{"kind":"from-a-newer-build"}""",
              entryId.value
            )
            Right(())
          }
          .isRight
      case _ => false
    }

  // the database's clock is the case's clock, to the microsecond, so expiries are exact
  override protected def exactExpiry: Boolean = true

  override def withFixture(test: NoArgTest): Outcome = {
    val outcome = super.withFixture(test)
    Iterator.continually(opened.poll()).takeWhile(_ != null).foreach(_.close())
    outcome
  }

  "PostgresCheckpointer.runQueue" should "take a free head behind a full batch of heads whose threads are locked" in {
    val stores  = open(Clock.systemUTC())
    val agent   = AgentId.of(s"scan-${UUID.randomUUID().toString.take(8)}").toOption.get
    val threads = (0 to PostgresRunQueue.Candidates).map(i => ThreadId(s"scan-$i-${UUID.randomUUID()}"))
    val ids = threads.map(t => stores.queue.submit(Submission(t, agent, QueuedInput.query("q"))).toOption.get.entryId)
    // another transaction holds the advisory lock of every thread of the first batch, as submissions deciding their
    // thread's order would
    PgCheckpointerDb.withConnection { c =>
      c.setAutoCommit(false)
      threads.take(PostgresRunQueue.Candidates).foreach { t =>
        Using.resource(c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) { st =>
          st.setString(1, s"""llm4s.agent.queue:"$schema":thread:${t.value}""")
          st.executeQuery().close()
        }
      }
      val taken = stores.queue.take(TakeRequest("worker", Set(agent), 30.seconds)).toOption.get
      c.rollback()
      taken.map(_.entry.id) shouldBe Some(ids.last)
    }
  }

  it should "take a free head while another take's candidate read is in flight, which locks no row (#3096)" in {
    val stores  = open(Clock.systemUTC())
    val agent   = AgentId.of(s"cand-${UUID.randomUUID().toString.take(8)}").toOption.get
    val threads = (0 until 3).map(i => ThreadId(s"cand-$i-${UUID.randomUUID()}"))
    val ids = threads.map(t => stores.queue.submit(Submission(t, agent, QueuedInput.query("q"))).toOption.get.entryId)
    // another worker's take, between reading its candidates and leasing the first: it holds that thread's lock, and
    // only that one row may be locked
    PgCheckpointerDb.withConnection { c =>
      c.setAutoCommit(false)
      Using.resource(c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) { st =>
        st.setString(1, s"""llm4s.agent.queue:"$schema":thread:${threads.head.value}""")
        st.executeQuery().close()
      }
      Using.resource(c.prepareStatement(Sql.queueCandidates(PostgresSchema.Tables(schema)))) { st =>
        val agents = c.createArrayOf("text", Array[AnyRef](agent.value))
        val now    = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        st.setArray(1, agents)
        st.setArray(2, agents)
        st.setObject(3, now)
        st.setObject(4, now)
        st.setLong(5, 0L)
        st.setObject(6, now)
        st.executeQuery().close()
      }
      val taken = stores.queue.take(TakeRequest("worker", Set(agent), 30.seconds)).toOption.get
      c.rollback()
      taken.map(_.entry.id) shouldBe Some(ids(1))
    }
  }

  it should "wake a waiter in one store when another store closes its entry (#3092)" in {
    val closer = open(Clock.systemUTC())
    val waiter = open(Clock.systemUTC())
    val agent  = AgentId.of(s"wake-${UUID.randomUUID().toString.take(8)}").toOption.get
    val id = closer.queue
      .submit(Submission(ThreadId(s"wake-${UUID.randomUUID()}"), agent, QueuedInput.query("q")))
      .toOption
      .get
      .entryId
    val waited = new java.util.concurrent.atomic.AtomicReference[Option[Long]](None)
    val thread = new Thread(() => {
      val began = System.nanoTime()
      waiter.queue.awaitClosed(None, id, 60.seconds).toOption.get.isOpen shouldBe false
      waited.set(Some((System.nanoTime() - began) / 1_000_000L))
    })
    thread.start()
    // the waiter has registered and its store listens: a close now reaches it only by notification
    val store = waiter.checkpointer.asInstanceOf[PostgresCheckpointer]
    val until = System.nanoTime() + 10.seconds.toNanos
    while (!store.listening && System.nanoTime() < until) Thread.sleep(20)
    store.listening shouldBe true
    Thread.sleep(200)
    val leased   = closer.queue.take(TakeRequest("worker", Set(agent), 30.seconds)).toOption.get.get
    val closedAt = System.nanoTime()
    closer.queue.close(leased.lease, EntryOutcome.Done(None, None)).isRight shouldBe true
    thread.join(10_000)
    waited.get.isDefined shouldBe true
    (System.nanoTime() - closedAt) should be < 5.seconds.toNanos
  }

  override def afterAll(): Unit = {
    if (PgCheckpointerDb.enabled) PgCheckpointerDb.dropSchema(schema)
    super.afterAll()
  }
}

/** The suite's state, kept out of the class: the contract's cases call the hooks while the suite is constructed. */
private object PostgresRunQueueContractSpec {
  val schema: String                                      = PgCheckpointerDb.freshSchema()
  val config: PostgresCheckpointerConfig                  = PostgresCheckpointerConfig(schema = schema)
  lazy val clocks: PgCheckpointerDb.Clocks                = new PgCheckpointerDb.Clocks(schema)
  val clockIds: java.util.IdentityHashMap[Clock, String]  = new java.util.IdentityHashMap[Clock, String]()
  val opened: ConcurrentLinkedQueue[PostgresCheckpointer] = new ConcurrentLinkedQueue[PostgresCheckpointer]()
}
