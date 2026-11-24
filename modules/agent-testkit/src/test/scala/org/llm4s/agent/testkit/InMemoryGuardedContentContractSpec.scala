package org.llm4s.agent.testkit

import org.llm4s.agent.graph.InMemoryCheckpointer
import org.scalatest.flatspec.AnyFlatSpec

/** `InMemoryCheckpointer` holds nothing a guardrail refused, and no reason it gave (#2853, #2854). */
class InMemoryGuardedContentContractSpec extends AnyFlatSpec with GuardedContentContract:
  protected def newStores(): RunQueueContract.Stores =
    val store = new InMemoryCheckpointer()
    RunQueueContract.Stores(store, store.runQueue)
