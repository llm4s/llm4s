package org.llm4s.agent.graph.postgres

import org.llm4s.agent.harness.{ LongTermStore, StoreLimits }
import org.llm4s.agent.testkit.LongTermStoreContract
import org.llm4s.it.tags.Docker
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

/**
 * `PostgresCheckpointer.longTermStore` passes the contract every long-term store must pass (#2453), unchanged,
 * against a real server - the suite `InMemoryLongTermStore` passes in `llm4s-agent-testkit`. Every case's store has a
 * schema of its own; its `sibling` is another `PostgresCheckpointer` over that schema, so the concurrency cases are
 * decided by the database's locks, not by one instance.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]] for the connection.
 */
@Docker
class PostgresLongTermStoreContractSpec extends AnyFlatSpec with BeforeAndAfterAll with LongTermStoreContract {

  import PostgresLongTermStoreContractSpec.stores

  protected def newStore(limits: StoreLimits): LongTermStore          = stores.fresh(limits)
  protected def appliedKeys(store: LongTermStore): Int                = stores.appliedKeys(store)
  override protected def sibling(store: LongTermStore): LongTermStore = stores.sibling(store)

  override def afterAll(): Unit = {
    stores.close()
    super.afterAll()
  }
}

/** The suite's stores, kept out of the class: the contract's cases are registered while the suite is constructed. */
private object PostgresLongTermStoreContractSpec {
  val stores: PgLongTermStores = new PgLongTermStores
}
