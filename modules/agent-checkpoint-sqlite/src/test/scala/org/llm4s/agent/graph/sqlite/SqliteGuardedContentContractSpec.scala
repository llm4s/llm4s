package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.testkit.{ GuardedContentContract, RunQueueContract }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock

/**
 * A SQLite file holds nothing a guardrail refused, and no reason it gave (#2853, #2854): each case on a file of its
 * own.
 */
class SqliteGuardedContentContractSpec extends AnyFlatSpec with GuardedContentContract {

  protected def newStores(): RunQueueContract.Stores = {
    val store = SqliteFiles.opened(SqliteFiles.fresh(), Clock.systemUTC())
    RunQueueContract.Stores(store, store.runQueue)
  }
}
