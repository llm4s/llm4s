package org.llm4s.agent.testkit

import org.llm4s.agent.graph.{ Checkpointer, InMemoryCheckpointer }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock
import scala.concurrent.duration.FiniteDuration

/** The reference store passes the contract every store must pass; it is the first to. */
class InMemoryCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract {
  protected def newCheckpointer(clock: Clock): Checkpointer = InMemoryCheckpointer(clock)
}

/**
 * The contract as a store that judges expiry by a clock of its own runs it: through
 * `advanceStoreClock`, with `expiresAt` checked by its effect only. The reference store stands in,
 * its own clock moved by the hook rather than by the suite.
 */
class OwnClockContractSpec extends AnyFlatSpec with CheckpointerContract {
  import OwnClockContractSpec.clocks

  protected def newCheckpointer(clock: Clock): Checkpointer = {
    val own   = ManualClock(clock.instant())
    val store = InMemoryCheckpointer(own)
    clocks.put(store, own)
    store
  }

  override protected def advanceStoreClock(store: Checkpointer, clock: ManualClock, by: FiniteDuration): Unit =
    clocks.get(store).advance(by)

  override protected def exactExpiry: Boolean = false
}

object OwnClockContractSpec {

  /** Each store's own clock; the cases' stores are discarded with the JVM. */
  private val clocks = new java.util.concurrent.ConcurrentHashMap[Checkpointer, ManualClock]()
}
