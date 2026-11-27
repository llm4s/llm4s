package org.llm4s.agent.testkit

import org.llm4s.agent.graph.InMemoryCheckpointer
import org.llm4s.agent.service.{ EntryId, InMemoryRunQueue }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock

/** The reference store's queue passes the contract every run queue must pass; it is the first to. */
class InMemoryRunQueueContractSpec extends AnyFlatSpec with RunQueueContract {
  protected def newStores(clock: Clock): RunQueueContract.Stores = {
    val store = InMemoryCheckpointer(clock)
    RunQueueContract.Stores(store, store.runQueue)
  }

  override protected def spoilInput(stores: RunQueueContract.Stores, entryId: EntryId): Boolean =
    stores.queue match {
      case queue: InMemoryRunQueue => queue.corruptInput(entryId); true
      case _                       => false
    }
}
