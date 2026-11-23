package org.llm4s.agent.graph.postgres

import org.llm4s.agent.encryption.{ EncryptingLongTermStore, EncryptionKey, KeyId, StaticKeySource }
import org.llm4s.agent.harness.{ LongTermStore, StoreLimits }
import org.llm4s.agent.testkit.LongTermStoreContract
import org.llm4s.it.tags.Docker
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

/**
 * `EncryptingLongTermStore` over `PostgresCheckpointer.longTermStore` passes the contract every long-term store must
 * pass (#2449, #2453), against a real server: its bounds are counted, and its outcomes recorded, inside the
 * database's transaction, under the namespace's lock. A case's store has a schema of its own; its `sibling` is
 * another wrapper, with the same keys, over another `PostgresCheckpointer` on that schema, as a second machine has.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]] for the connection.
 */
@Docker
class EncryptedPostgresLongTermStoreContractSpec extends AnyFlatSpec with BeforeAndAfterAll with LongTermStoreContract {

  import EncryptedPostgresLongTermStoreContractSpec.{ keys, stores }

  private def under(store: LongTermStore): LongTermStore = store match {
    case encrypting: EncryptingLongTermStore => encrypting.inner
    case other                               => fail(s"not an encrypting store: $other")
  }

  // the store under it has the room the sealed items of `limits` need, so the wrapper offers exactly `limits`
  protected def newStore(limits: StoreLimits): LongTermStore =
    EncryptingLongTermStore(stores.fresh(EncryptingLongTermStore.storageLimits(limits)), keys)
  protected def appliedKeys(store: LongTermStore): Int = stores.appliedKeys(under(store))
  override protected def sibling(store: LongTermStore): LongTermStore =
    EncryptingLongTermStore(stores.sibling(under(store)), keys)

  override def afterAll(): Unit = {
    stores.close()
    super.afterAll()
  }
}

/** The suite's stores and keys, kept out of the class: the contract's cases are registered while it is constructed. */
private object EncryptedPostgresLongTermStoreContractSpec {
  val stores: PgLongTermStores = new PgLongTermStores
  val keys: StaticKeySource    = StaticKeySource(EncryptionKey.generate(KeyId("contract")))
}
