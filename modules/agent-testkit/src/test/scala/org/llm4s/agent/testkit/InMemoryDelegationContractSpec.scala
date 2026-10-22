package org.llm4s.agent.testkit

import org.llm4s.agent.graph.{ Checkpointer, InMemoryCheckpointer }
import org.scalatest.flatspec.AnyFlatSpec

/** `InMemoryCheckpointer` passes the delegation contract (design 4.44). */
class InMemoryDelegationContractSpec extends AnyFlatSpec with DelegationContract:
  protected def newCheckpointer(): Checkpointer = new InMemoryCheckpointer()
