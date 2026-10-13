package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.CancelledError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration.*

/**
 * Whether a run keeps, or knows it has lost, its claim on a thread (#1700 follow-up). So far: a
 * `recover` that takes over another run's expired claim records it on `RunRecovered`.
 *
 * Time is manual: [[ManualTime]] is the store's clock, and moves only when a test moves it.
 */
class ClaimLivenessSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("t")
  private val t0     = Instant.parse("2026-10-13T09:00:00Z")

  /**
   * The store's clock and the runtime's monotonic one, moved together by [[advance]]; [[skewStore]]
   * moves only the store's, as a store clock running ahead does. `broken` makes the store's throw.
   */
  final private class ManualTime extends Clock {
    private val offset                         = new AtomicLong(0L)
    private val storeAhead                     = new AtomicLong(0L)
    @volatile var broken                       = false
    def getZone: ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant =
      if broken then throw new IllegalStateException("clock unavailable")
      else t0.plusNanos(offset.get + storeAhead.get)
    def nanos(): Long                       = 1_000_000_000_000L + offset.get
    def advance(by: FiniteDuration): Unit   = offset.addAndGet(by.toNanos): Unit
    def skewStore(by: FiniteDuration): Unit = storeAhead.addAndGet(by.toNanos): Unit
  }

  /**
   * `first` opens `entered` and waits for `gate`, opening `interrupted` if it is interrupted instead;
   * then `second` counts its executions in `later`, standing in for a tool's side effect.
   */
  final private class TwoStep {
    val gate        = new CountDownLatch(1)
    val entered     = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    val later       = new AtomicInteger()
    val log         = StateKey.appending[String]("log")
    val graph: CompiledGraph[String, Vector[String]] = {
      val b = GraphBuilder("two", "v1")
      val second = b.node[Unit]("second", writes = Set(log)) { (_, _, _) =>
        later.incrementAndGet()
        continue(Command.empty.update(log, "second"))
      }
      val first = b.node[String]("first", writes = Set(log)) { (input, _, _) =>
        entered.countDown()
        if CancelledError.catchInterrupt(gate.await(60, TimeUnit.SECONDS)).isLeft then {
          interrupted.countDown()
          Thread.currentThread().interrupt()
        }
        continue(Command.empty.update(log, input).goto(second))
      }
      b.compile(first)(_.get(log)).value
    }
    def awaitEntered(): Unit = entered.await(5, TimeUnit.SECONDS) shouldBe true
  }

  /** A claim that is never renewed during a test. */
  private val slow = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)

  private def run(id: String) = RunConfig().withRunId(RunId(id))

  private def events(store: Checkpointer): Vector[EventRecord] = store.eventsAfter(thread, 0L, 10_000).value

  // ---- 6: a takeover is recorded on RunRecovered ----

  "recover" should "record the run whose expired claim it took over" in {
    val time  = ManualTime()
    val store = InMemoryCheckpointer(time)
    val stale = TwoStep()
    val first = GraphRuntime(store, claims = slow).start(thread, stale.graph, "x", run("first")).value
    stale.awaitEntered()
    // the claim expires in the store; the run itself does not notice before the takeover
    time.skewStore(3.hours)
    val successor = TwoStep()
    successor.gate.countDown()
    await(GraphRuntime(store, claims = slow).recover(thread, successor.graph, run("second")).value).completed
    events(store).map(_.event).collect { case r: RunEvent.RunRecovered => r.takenOverFrom } shouldBe
      Vector(Some("first"))

    stale.gate.countDown()
    await(first).failed._2 should matchPattern {
      case GraphError.CheckpointWriteFailed("t", _: GraphError.StaleClaim, None) =>
    }
  }

  it should "record no takeover when the thread was free" in {
    val time  = ManualTime()
    val store = InMemoryCheckpointer(time)
    val two   = TwoStep()
    val first = GraphRuntime(store, claims = slow).start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    first.cancel()
    await(first)
    two.gate.countDown()
    await(GraphRuntime(store, claims = slow).recover(thread, two.graph, run("second")).value).completed
    events(store).map(_.event).collect { case r: RunEvent.RunRecovered => r.takenOverFrom } shouldBe Vector(None)
  }

  "RunRecovered" should "read a log written before takenOverFrom, and round-trip it" in {
    val json =
      """{"threadId":"t","seq":1,"runId":"r","checkpointId":null,"taskId":null,"nodeId":null,""" +
        """"timestamp":"2026-10-02T12:00:00Z","event":{"$type":"RunRecovered","fromCheckpoint":"c1","tenantId":null,"principal":null}}"""
    upickle.default.read[EventRecord](json).event shouldBe RunEvent.RunRecovered("c1", None, None, None)
    val taken = RunEvent.RunRecovered("c1", Some("a"), None, Some("r-1"))
    upickle.default.read[RunEvent](upickle.default.write(taken)) shouldBe taken
  }

  "InMemoryCheckpointer" should "name the holder of the expired claim a claim replaced" in {
    val time  = ManualTime()
    val store = InMemoryCheckpointer(time)
    store.claim(thread, ClaimRequest(RunId("first"), 1.second)).value.takenOverFrom shouldBe None
    time.advance(1.second)
    val taken = store.claim(thread, ClaimRequest(RunId("second"), 1.second)).value
    taken.takenOverFrom shouldBe Some(RunId("first"))
    store.release(thread, taken.token).value
    store.claim(thread, ClaimRequest(RunId("third"), 1.second)).value.takenOverFrom shouldBe None
  }
}
