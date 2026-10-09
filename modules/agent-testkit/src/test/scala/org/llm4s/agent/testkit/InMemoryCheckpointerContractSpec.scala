package org.llm4s.agent.testkit

import org.llm4s.agent.graph.{ Checkpointer, InMemoryCheckpointer }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock

/** The reference store passes the contract every store must pass; it is the first to. */
class InMemoryCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract {
  protected def newCheckpointer(clock: Clock): Checkpointer = InMemoryCheckpointer(clock)
}
