package org.llm4s.agent.graph.postgres

import com.zaxxer.hikari.HikariDataSource
import org.llm4s.agent.harness.{ LongTermStore, StoreLimits }

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The long-term stores a suite opens against [[PgCheckpointerDb]]'s server: each `fresh` store in a schema of its
 * own, every store over one small pool, all closed and dropped by `close`. A `sibling` is another
 * `PostgresCheckpointer` over the same schema, as a second machine opens it: it shares nothing with the first but the
 * database.
 */
final class PgLongTermStores {

  final private case class Opened(checkpointer: PostgresCheckpointer, limits: StoreLimits)

  private var pool: Option[HikariDataSource] = None
  private val schemas                        = new ConcurrentLinkedQueue[String]()
  private val checkpointers                  = new ConcurrentLinkedQueue[PostgresCheckpointer]()
  private val opened                         = new java.util.IdentityHashMap[LongTermStore, Opened]()

  private def shared(): HikariDataSource = synchronized {
    pool.getOrElse {
      val created = PgCheckpointerDb.pool(maxSize = 12)
      pool = Some(created)
      created
    }
  }

  /** A checkpointer over `schema`, which it creates or upgrades; cancels the test without a server. */
  def checkpointer(schema: String, clockSql: String = PostgresCheckpointer.ServerClock): PostgresCheckpointer = {
    PgCheckpointerDb.require()
    val store = PostgresCheckpointer
      .createWith(shared(), ownsDataSource = false, PostgresCheckpointerConfig(schema = schema), clockSql)
      .fold(e => throw new IllegalStateException(e.message), identity)
    checkpointers.add(store)
    store
  }

  /** A schema of this suite's own, dropped by `close`. */
  def freshSchema(): String = {
    val schema = PgCheckpointerDb.freshSchema()
    schemas.add(schema)
    schema
  }

  private def register(checkpointer: PostgresCheckpointer, limits: StoreLimits): LongTermStore = synchronized {
    val store = checkpointer.longTermStore(limits)
    opened.put(store, Opened(checkpointer, limits))
    store
  }

  /** An empty store, in a schema of its own. */
  def fresh(limits: StoreLimits = StoreLimits.default): LongTermStore =
    register(checkpointer(freshSchema()), limits)

  /** Another instance over `store`'s schema, with its limits. */
  def sibling(store: LongTermStore): LongTermStore = {
    val first = synchronized(opened.get(store))
    register(checkpointer(first.checkpointer.schema), first.limits)
  }

  def schemaOf(store: LongTermStore): String = synchronized(opened.get(store)).checkpointer.schema

  /** How many applied keys `store`'s schema holds, read from its table. */
  def appliedKeys(store: LongTermStore): Int =
    PgCheckpointerDb.scalar(s"""SELECT COUNT(*) FROM "${schemaOf(store)}".checkpoint_store_applied""")(_.getInt(1))

  def close(): Unit = {
    Iterator.continually(checkpointers.poll()).takeWhile(_ != null).foreach(_.close())
    if (PgCheckpointerDb.enabled)
      Iterator.continually(schemas.poll()).takeWhile(_ != null).foreach(PgCheckpointerDb.dropSchema)
    synchronized(pool.foreach(_.close()))
  }
}
