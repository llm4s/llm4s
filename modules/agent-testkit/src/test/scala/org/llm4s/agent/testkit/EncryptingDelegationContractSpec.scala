package org.llm4s.agent.testkit

import org.llm4s.agent.encryption.EncryptingCheckpointer
import org.llm4s.agent.graph.{ Checkpointer, InMemoryCheckpointer }
import org.scalatest.flatspec.AnyFlatSpec

/**
 * The delegation contract over [[EncryptingCheckpointer]] (#2853): a parent rebuilds its child's report or refusal
 * from the child thread's settled outcome, which the store holds sealed, exactly as it does over a plain store.
 */
class EncryptingDelegationContractSpec extends AnyFlatSpec with DelegationContract:
  protected def newCheckpointer(): Checkpointer =
    EncryptingCheckpointer(new InMemoryCheckpointer(), ContractKeys.keys)
