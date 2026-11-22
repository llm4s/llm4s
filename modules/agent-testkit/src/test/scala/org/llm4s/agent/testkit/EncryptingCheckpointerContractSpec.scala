package org.llm4s.agent.testkit

import org.llm4s.agent.encryption.{ EncryptingCheckpointer, EncryptingRunQueue, EncryptionKey, KeyId, StaticKeySource }
import org.llm4s.agent.graph.{ Checkpointer, InMemoryCheckpointer, TenantId }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock
import scala.concurrent.duration.*

/** The keys the encrypted contract runs use: a default, and a key of its own for the contract's tenants. */
object ContractKeys {
  val keys: StaticKeySource =
    Vector("tenant-a", "tenant-b", "acme", "globex").foldLeft(
      StaticKeySource(EncryptionKey.generate(KeyId("default")))
    )((source, tenant) => source.withTenantKey(TenantId(tenant), EncryptionKey.generate(KeyId(s"key-$tenant"))))
}

/**
 * [[EncryptingCheckpointer]] over the reference store passes the contract every store must pass (#2449): encryption
 * above a store changes nothing a runtime can observe of it.
 */
class EncryptingCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract {

  protected def newCheckpointer(clock: Clock): Checkpointer =
    EncryptingCheckpointer(InMemoryCheckpointer(clock), ContractKeys.keys)

  override protected def boundedStore(clock: Clock): Option[CheckpointerContract.BoundedStore] =
    Some(CheckpointerContract.BoundedStore(newCheckpointer(clock), 1.second, () => () => ()))

  // the reference store's own commits wake its waiters at once, through the wrapper as without it
  override protected def wakeBound: Option[FiniteDuration] = Some(1.second)
}

/** [[EncryptingRunQueue]] over the reference store's queue passes the run queue's contract (#2449). */
class EncryptingRunQueueContractSpec extends AnyFlatSpec with RunQueueContract {

  protected def newStores(clock: Clock): RunQueueContract.Stores = {
    val store = InMemoryCheckpointer(clock)
    RunQueueContract.Stores(
      EncryptingCheckpointer(store, ContractKeys.keys),
      EncryptingRunQueue(store.runQueue, ContractKeys.keys)
    )
  }
}
