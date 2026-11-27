package org.llm4s.knowledgegraph.neo4j

import java.util.concurrent.locks.ReentrantLock

/**
 * Serialises the Neo4j suites' tests. `Neo4jGraphStoreSpec` and `Neo4jGraphStoreContractSpec` share one database and
 * the `:LLM4S` label, and each clears every `:LLM4S` node before a test; `it` runs suites in parallel, so without the
 * lock one suite's clear could delete another's nodes mid-test.
 */
private[neo4j] object Neo4jSuiteLock {
  private val lock = new ReentrantLock()

  def exclusively[A](body: => A): A = {
    lock.lock()
    try body
    finally lock.unlock()
  }
}
