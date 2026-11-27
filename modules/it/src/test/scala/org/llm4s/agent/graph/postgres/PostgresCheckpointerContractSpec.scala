package org.llm4s.agent.graph.postgres

import org.llm4s.agent.encryption.{ EncryptingCheckpointer, EncryptionKey, KeyId, StaticKeySource }
import org.llm4s.agent.graph.{ Checkpointer, ThreadId }
import org.llm4s.agent.testkit.{ CheckpointerContract, ManualClock }
import org.llm4s.it.tags.Docker
import org.scalatest.{ BeforeAndAfterAll, Outcome }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * `PostgresCheckpointer` passes the contract every store must pass (design 4.50), unchanged, against a real server.
 * Every case shares one schema of this suite's own, each with thread ids of its own; `reopen` closes the store - its
 * pool - and opens the schema again, `sibling` and `contenders` open further stores, each over a pool of its own, so
 * a race is decided by the database's row locks, not by one instance.
 *
 * '''The store's clock.''' The store judges claims and retention by the database's clock. Here each case's
 * `ManualClock` is mirrored into a row of the schema's `it_clocks` table, and the store reads that row where it would
 * read `clock_timestamp()`: time is still read in the database, inside the store's own statements, and
 * [[advanceStoreClock]] moves it by moving the clock and writing the row. So `exactExpiry` holds: a claim's expiry is
 * exactly the clock plus its `ttl`. `PostgresServerClockSpec` checks the same store on the server's real clock.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]] for the connection.
 */
@Docker
class PostgresCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract with BeforeAndAfterAll {

  /** This suite's state: a schema of its own, so a suite that extends this one shares nothing with it. */
  protected def state: PostgresContractState = PostgresCheckpointerContractSpec

  /** The store the contract is given for an open `store`: the store itself, or something over it. */
  protected def wrapped(store: PostgresCheckpointer): Checkpointer = store

  private def schema   = state.schema
  private def config   = state.config
  private def clocks   = state.clocks
  private def clockIds = state.clockIds
  private def opened   = state.opened
  private def schemas  = state.schemas

  private def clockId(clock: Clock): String = clockIds.synchronized {
    Option(clockIds.get(clock)).getOrElse {
      val id = clocks.create(clock.instant())
      clockIds.put(clock, id)
      id
    }
  }

  private def open(clock: Clock, in: PostgresCheckpointerConfig = config): PostgresCheckpointer = {
    PgCheckpointerDb.require()
    val store = PgCheckpointerDb.store(in, clocks.sql(clockId(clock)))
    opened.add(store)
    store
  }

  /** The configuration - the schema - `store` was opened with, so a reopen or a sibling opens the same storage. */
  private def configOf(store: Checkpointer): PostgresCheckpointerConfig = store match {
    case postgres: PostgresCheckpointer     => postgres.config
    case encrypting: EncryptingCheckpointer => configOf(encrypting.inner)
    case _                                  => config
  }

  protected def newCheckpointer(clock: Clock): Checkpointer = wrapped(open(clock))

  // the retention sweep's cases each get a schema of their own: they page through, and renumber, the whole store
  override protected def isolatedCheckpointer(clock: Clock): Checkpointer = {
    PgCheckpointerDb.require()
    val isolated = PgCheckpointerDb.freshSchema()
    schemas.add(isolated)
    wrapped(open(clock, PostgresCheckpointerConfig(schema = isolated)))
  }

  // a trigger refusing to delete any of the thread's checkpoints, or its events from a seq on, so its prune fails (#3086)
  override protected def refuseDeletes(
    store: Checkpointer,
    threadId: ThreadId,
    eventsFrom: Option[Long]
  ): Option[() => Unit] = {
    val in      = configOf(store).schema
    val id      = threadId.value.replace("'", "''")
    val trigger = s"refuse_prune_${java.util.UUID.randomUUID().toString.replace("-", "")}"
    val (table, condition) =
      eventsFrom.fold(("checkpoint_history", ""))(seq => ("checkpoint_events", s" AND OLD.seq >= $seq"))
    PgCheckpointerDb.execute(
      s"""CREATE OR REPLACE FUNCTION "$in".refuse_prune() RETURNS trigger LANGUAGE plpgsql AS
         |$$$$ BEGIN RAISE EXCEPTION 'pruning this thread is refused'; END $$$$""".stripMargin
    )
    PgCheckpointerDb.execute(
      s"""CREATE TRIGGER $trigger BEFORE DELETE ON "$in".$table
         |FOR EACH ROW WHEN (OLD.thread_id = '$id'$condition) EXECUTE FUNCTION "$in".refuse_prune()""".stripMargin
    )
    Some(() => PgCheckpointerDb.execute(s"""DROP TRIGGER $trigger ON "$in".$table"""))
  }

  override protected def shortenSweepBudget(store: Checkpointer): Boolean = store match {
    case postgres: PostgresCheckpointer =>
      postgres.sweepBudget = Duration.Zero
      true
    case encrypting: EncryptingCheckpointer => shortenSweepBudget(encrypting.inner)
    case _                                  => false
  }

  override protected def reopen(store: Checkpointer, clock: ManualClock): Checkpointer = {
    val in = configOf(store)
    closeExtra(store)
    wrapped(open(clock, in))
  }

  override protected def sibling(store: Checkpointer, clock: ManualClock): Checkpointer =
    wrapped(open(clock, configOf(store)))

  override protected def advanceStoreClock(store: Checkpointer, clock: ManualClock, by: FiniteDuration): Unit = {
    clock.advance(by)
    clocks.set(clockId(clock), clock.instant())
  }

  // the database's clock is the case's clock, to the microsecond, so expiries are exact
  override protected def exactExpiry: Boolean = true

  // LISTEN/NOTIFY (design 4.51): a commit through any store wakes a waiter within a second on a healthy database
  override protected def wakeBound: Option[FiniteDuration] = Some(1.second)

  /**
   * A store with a 300 ms call timeout over a pool of two with a 250 ms connection timeout: its documented bound is
   * their sum. The storage is made busy as another instance's long write would: a connection of its own holding an
   * `EXCLUSIVE` lock on the threads table, so every write waits for it and only the call timeout ends the wait.
   */
  override protected def boundedStore(clock: Clock): Option[CheckpointerContract.BoundedStore] = {
    PgCheckpointerDb.require()
    val bounded = PgCheckpointerDb.freshSchema()
    schemas.add(bounded)
    val boundConfig =
      PostgresCheckpointerConfig(schema = bounded, callTimeout = 300.millis, connectionTimeout = 250.millis)
    val store = PgCheckpointerDb.store(boundConfig, maxPoolSize = 2)
    Some(
      CheckpointerContract.BoundedStore(
        wrapped(store),
        boundConfig.bound,
        () => {
          val holder = PgCheckpointerDb.connect()
          holder.setAutoCommit(false)
          Try(holder.createStatement().execute(s"""LOCK TABLE "$bounded".checkpoint_threads IN EXCLUSIVE MODE""")).get
          () => {
            Try(holder.rollback())
            holder.close()
          }
        }
      )
    )
  }

  // every store a case opened is closed with it, so the pools never exceed the server's connection limit
  override def withFixture(test: NoArgTest): Outcome = {
    val outcome = super.withFixture(test)
    Iterator.continually(opened.poll()).takeWhile(_ != null).foreach(_.close())
    outcome
  }

  override def afterAll(): Unit = {
    if (PgCheckpointerDb.enabled) {
      (schema +: schemas.asScala.toVector).foreach(PgCheckpointerDb.dropSchema)
    }
    super.afterAll()
  }
}

/** The suite's state, kept out of the class: the contract's cases call the hooks while the suite is constructed. */
private object PostgresCheckpointerContractSpec extends PostgresContractState

/**
 * `EncryptingCheckpointer` over `PostgresCheckpointer` passes the same contract, against the same server, in a schema
 * of its own (#2449): what one pool encrypted, a store over another pool reads, and nothing a runtime can observe of
 * the store changes.
 */
@Docker
class PostgresEncryptingCheckpointerContractSpec extends PostgresCheckpointerContractSpec {
  import PostgresEncryptingCheckpointerContractSpec.keys

  override protected def state: PostgresContractState = PostgresEncryptingCheckpointerContractSpec

  override protected def wrapped(store: PostgresCheckpointer): Checkpointer = EncryptingCheckpointer(store, keys)

  override protected def closeExtra(store: Checkpointer): Unit = store match {
    case encrypting: EncryptingCheckpointer => super.closeExtra(encrypting.inner)
    case other                              => super.closeExtra(other)
  }
}

private object PostgresEncryptingCheckpointerContractSpec extends PostgresContractState {
  val keys: StaticKeySource = StaticKeySource(EncryptionKey.generate(KeyId("postgres-contract")))
}

/** One contract suite's schema, clocks and open stores. */
private class PostgresContractState {
  val schema: String                                      = PgCheckpointerDb.freshSchema()
  val config: PostgresCheckpointerConfig                  = PostgresCheckpointerConfig(schema = schema)
  lazy val clocks: PgCheckpointerDb.Clocks                = new PgCheckpointerDb.Clocks(schema)
  val clockIds: java.util.IdentityHashMap[Clock, String]  = new java.util.IdentityHashMap[Clock, String]()
  val opened: ConcurrentLinkedQueue[PostgresCheckpointer] = new ConcurrentLinkedQueue[PostgresCheckpointer]()
  val schemas: ConcurrentLinkedQueue[String]              = new ConcurrentLinkedQueue[String]()
}
