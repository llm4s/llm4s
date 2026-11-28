package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A plain cancel and a settling stop on one handle (#3243, the root cause of #2943): the run reads the stop's cause
 * and its settlement as one pair ([[StopSignal.recorded]]), and a pair with a plain cancel as its cause never holds a
 * settlement, however the two stops interleave. A settlement read with a plain cancel's cause made the run close its
 * thread `Failed` where a cancelled run leaves it `Running`.
 *
 * The handles are never launched: no run thread exists, so a stop records its cause and interrupts nothing.
 */
class RunHandleStopRaceSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private val settlement = StopSettlement(RunOutcome.Answered, _ => Right(StateUpdate.empty))

  private def handle(signal: StopSignal) =
    new DefaultRunHandle[String](
      ThreadId("t"),
      RunId("r"),
      1L,
      signal,
      (_, _, _, _) => Left(ValidationError("subscribe", "not used")),
      None
    )

  "A settling stop after a plain cancel" should "install no settlement (#3243)" in {
    val signal = StopSignal()
    val h      = handle(signal)
    h.cancel()
    h.stopSettling(settlement, None)
    signal.recorded() shouldBe (Some(StopCause.Cancelled), None)
  }

  "A plain cancel after a boundary stop" should "withdraw the boundary stop's settlement (#3243)" in {
    val signal = StopSignal()
    val h      = handle(signal)
    h.stopSettling(settlement, Some(_ => false))
    signal.recorded() shouldBe (None, Some(settlement))
    h.cancel()
    signal.recorded() shouldBe (Some(StopCause.Cancelled), None)
    signal.boundary.get shouldBe false
  }

  "A settling stop that wins" should "record its cause and its settlement together (#3243)" in {
    val signal = StopSignal()
    val h      = handle(signal)
    h.stopSettling(settlement, None)
    h.cancel()
    signal.recorded() shouldBe (Some(StopCause.Cancelled), Some(settlement))
  }

  // Each round races a plain cancel against a settling stop - immediate, or at a boundary - while a reader samples
  // the pair as the run does. Whichever stop wins, the reader may see a settlement only where a settling stop won.
  "A plain cancel racing a settling stop" should "never be read with a settlement it did not win with (#3243)" in {
    val rounds = 2000
    (1 to rounds).foreach { round =>
      val signal    = StopSignal()
      val h         = handle(signal)
      val boundary  = round % 2 == 0
      val start     = new CyclicBarrier(3)
      val done      = new AtomicBoolean(false)
      val seen      = new java.util.concurrent.ConcurrentLinkedQueue[(Option[StopCause], Option[StopSettlement])]()
      val canceller = new Thread(() => { start.await(): Unit; h.cancel() })
      val settler = new Thread(() => {
        start.await(): Unit
        h.stopSettling(settlement, if (boundary) Some(_ => false) else None)
      })
      val reader = new Thread(() => {
        start.await(): Unit
        while (!done.get) seen.add(signal.recorded()): Unit
        seen.add(signal.recorded()): Unit
      })
      Seq(canceller, settler, reader).foreach(_.start())
      canceller.join()
      settler.join()
      done.set(true)
      reader.join()
      // the settling stop won only when it is the one whose settlement is in the final pair
      val settledWon = signal.recorded()._2.isDefined
      seen.forEach { case (cause, settled) =>
        if (cause.isDefined && settled.isDefined) settledWon shouldBe true
      }
      if (!settledWon) signal.recorded() shouldBe (Some(StopCause.Cancelled), None)
    }
  }
}
