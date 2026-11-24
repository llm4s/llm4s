package org.llm4s.agent.graph.postgres

import org.llm4s.agent.testkit.{ GuardedContentContract, RunQueueContract }
import org.llm4s.it.tags.Docker
import org.scalatest.{ BeforeAndAfterAll, Outcome }
import org.scalatest.flatspec.AnyFlatSpec

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A PostgreSQL store holds nothing a guardrail refused, and no reason it gave (#2853, #2854), against a real server.
 * Every case shares one schema of this suite's own, on threads of its own.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]] for the connection.
 */
@Docker
class PostgresGuardedContentContractSpec extends AnyFlatSpec with BeforeAndAfterAll with GuardedContentContract {

  import PostgresGuardedContentContractSpec.*

  protected def newStores(): RunQueueContract.Stores = {
    PgCheckpointerDb.require()
    val store = PgCheckpointerDb.store(config)
    opened.add(store)
    RunQueueContract.Stores(store, store.runQueue)
  }

  override def withFixture(test: NoArgTest): Outcome = {
    val outcome = super.withFixture(test)
    Iterator.continually(opened.poll()).takeWhile(_ != null).foreach(_.close())
    outcome
  }

  override def afterAll(): Unit = {
    if (PgCheckpointerDb.enabled) PgCheckpointerDb.dropSchema(schema)
    super.afterAll()
  }
}

/** The suite's state, kept out of the class: the contract's cases call `newStores` while they run. */
private object PostgresGuardedContentContractSpec {
  val schema: String                                      = PgCheckpointerDb.freshSchema()
  val config: PostgresCheckpointerConfig                  = PostgresCheckpointerConfig(schema = schema)
  val opened: ConcurrentLinkedQueue[PostgresCheckpointer] = new ConcurrentLinkedQueue[PostgresCheckpointer]()
}
