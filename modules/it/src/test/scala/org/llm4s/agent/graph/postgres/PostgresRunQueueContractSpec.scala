package org.llm4s.agent.graph.postgres

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.service.{ QueuedInput, Submission, TakeRequest }
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
