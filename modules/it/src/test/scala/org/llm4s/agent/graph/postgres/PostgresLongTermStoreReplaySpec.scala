package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.{ ReplaySupport, RunContext }
import org.llm4s.agent.testkit.{ LongTermStoreContract, ReplayContract }
import org.llm4s.it.tags.Docker
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

/**
 * `PostgresCheckpointer.longTermStore` is a `Transactional` target of the replay guard (design 4.24): a run crashed
 * at every point of the guard's table, and a stale claimant beside its successor, apply each effect once, and leave
 * no applied key in `checkpoint_store_applied`. The runs' threads are kept by the contract's default checkpointer; the
 * store under test is the long-term one.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]] for the connection.
 */
@Docker
class PostgresLongTermStoreReplaySpec extends AnyFlatSpec with ReplayContract with BeforeAndAfterAll {

  import PostgresLongTermStoreReplaySpec.stores

  // the contract names its subject while the suite is constructed, so the store is opened only once a case uses it
  protected def newSubject(): ReplayContract.Subject = new ReplayContract.Subject {
    private lazy val subject = {
      val store = stores.fresh()
      new LongTermStoreContract.CounterSubject(name, store, () => stores.appliedKeys(store))
    }
    def name: String                                                 = "PostgresCheckpointer.longTermStore"
    def support: ReplaySupport                                       = ReplaySupport.Transactional
    def perform(context: RunContext, slot: Int): Result[ujson.Value] = subject.perform(context, slot)
    def observe(slot: Int): Result[String]                           = subject.observe(slot)
    def initial(slot: Int): String                                   = subject.initial(slot)
    def once(slot: Int): String                                      = subject.once(slot)
    def interfere(slot: Int): Unit                                   = subject.interfere(slot)
    override def appliedKeys: Int                                    = subject.appliedKeys
  }

  override def afterAll(): Unit = {
    stores.close()
    super.afterAll()
  }
}

/** The suite's stores, kept out of the class: the contract's cases are registered while the suite is constructed. */
private object PostgresLongTermStoreReplaySpec {
  val stores: PgLongTermStores = new PgLongTermStores
}
