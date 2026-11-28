package org.llm4s.agent.testkit

import org.llm4s.agent.encryption.{
  EncryptingCheckpointer,
  EncryptingLongTermStore,
  EncryptingRunQueue,
  EncryptionKey,
  KeyId,
  StaticKeySource
}
import org.llm4s.agent.graph.{ Checkpointer, InMemoryCheckpointer, TenantId }
import org.llm4s.agent.service.{ EntryId, InMemoryRunQueue, QueuedInput }
import org.llm4s.agent.harness.{ FileBackend, InMemoryLongTermStore, LongTermStore, StoreLimits }
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
  // the reference store keeps nothing beyond its instance: runtimes share it by sharing the instance
  override protected def durable: Boolean = false

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

  // a ciphertext that does not open: the wrapper's own unreadable input
  override protected def spoilInput(stores: RunQueueContract.Stores, entryId: EntryId): Boolean =
    stores.queue match {
      case encrypting: EncryptingRunQueue =>
        encrypting.inner match {
          case inner: InMemoryRunQueue =>
            inner.corruptInput(entryId, QueuedInput.toJson(QueuedInput.Start(ujson.Str("not a ciphertext"))))
            true
          case _ => false
        }
      case _ => false
    }
}

/** The encrypted long-term stores the contract runs use, over the reference store. */
object EncryptedStores {

  /** An encrypting store offering exactly `limits`: the store under it has the room their sealed items need. */
  def over(limits: StoreLimits = StoreLimits.default): EncryptingLongTermStore =
    EncryptingLongTermStore(InMemoryLongTermStore(EncryptingLongTermStore.storageLimits(limits)), ContractKeys.keys)

  /** The applied keys of the reference store under `store`. */
  def appliedKeys(store: LongTermStore): Int = store match {
    case encrypting: EncryptingLongTermStore =>
      encrypting.inner match {
        case inMemory: InMemoryLongTermStore => inMemory.appliedKeys
        case other                           => throw new IllegalStateException(s"not the in-memory store: $other")
      }
    case other => throw new IllegalStateException(s"not an encrypting store: $other")
  }
}

/**
 * [[EncryptingLongTermStore]] over the reference store passes the contract every long-term store must pass (#2449,
 * #2453), bounds and search included: encryption above a store changes nothing a caller can observe of it.
 */
class EncryptingLongTermStoreContractSpec extends AnyFlatSpec with LongTermStoreContract {
  protected def newStore(limits: StoreLimits): LongTermStore = EncryptedStores.over(limits)
  protected def appliedKeys(store: LongTermStore): Int       = EncryptedStores.appliedKeys(store)
}

/** A replay across a crash is answered from the applied key, its sealed outcome opened (#2449). */
class EncryptingLongTermStoreReplaySpec extends AnyFlatSpec with ReplayContract {
  protected def newSubject(): ReplayContract.Subject = {
    val store = EncryptedStores.over()
    LongTermStoreContract.CounterSubject(
      "an encrypting long-term store's transactional modify",
      store,
      () => EncryptedStores.appliedKeys(store)
    )
  }
}

/** A `StoreFileBackend` over an [[EncryptingLongTermStore]] passes the file contract: its files need no more wiring. */
class EncryptedStoreFileBackendContractSpec extends AnyFlatSpec with FileBackendContract {
  protected def newBackend(): FileBackend =
    HarnessBackends.storeFiles(EncryptedStores.over(StoreLimits(maxBytes = 200)))
  override protected def tenantScoped: Boolean = true
  // each item is its key, plus {"content":"..."}: 94 bytes; two fit, the third is over
  override protected def boundedBatch: Option[Vector[(String, String)]] =
    Some(Vector("/a" -> "x" * 78, "/b" -> "x" * 78, "/c" -> "x" * 78))
}
