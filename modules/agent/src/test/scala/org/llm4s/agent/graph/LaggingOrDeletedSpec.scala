package org.llm4s.agent.graph

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ EitherValues, OptionValues }

import java.time.Instant
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A subscription that falls behind while its thread is deleted and created again by another runtime
 * ends with `ThreadDeleted`, not `Lagging`: the gap its runtime's next commit seems to leave is the
 * deleted thread's numbers. A subscription that really falls behind still ends `Lagging`. The same cases
 * run on SQLite in `llm4s-agent-checkpoint-sqlite` (`SqliteLaggingOrDeletedSpec`).
 */
abstract class LaggingOrDeletedCases
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with Eventually:
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  /** A new, empty store. */
  protected def newStore(): Checkpointer

  protected def storeName: String

  private val start = Instant.parse("2026-10-09T12:00:00Z")
  private val run   = RunId("r")

  private def draft(name: String): EventDraft =
    EventDraft(run.value, None, None, None, start, RunEvent.Custom(name, 1, ujson.Str(name)))

  /** Commits `names` as one run of another runtime: claim, commit, release. */
  private def commitElsewhere(store: Checkpointer, thread: ThreadId, names: String*): Vector[EventRecord] =
    val claim   = store.claim(thread, ClaimRequest(RunId("elsewhere"), None, 30.seconds)).value
    val records = store.commit(thread, Commit(claim.token, events = names.toVector.map(draft))).value
    store.release(thread, claim.token).value
    records

  /** Deletes the thread as another runtime would: under a claim of its own. */
  private def deleteElsewhere(store: Checkpointer, thread: ThreadId): Unit =
    val claim = store.claim(thread, ClaimRequest(RunId("deleter"), None, 30.seconds)).value
    store.deleteThread(thread, claim.token).value

  /** A listener that holds its first call until `release`, so what follows it stays queued. */
  final private class Held extends (StreamEvent => Unit):
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val seen    = new CopyOnWriteArrayList[StreamEvent]()
    def apply(event: StreamEvent): Unit =
      if entered.getCount > 0 then
        entered.countDown()
        release.await(10, TimeUnit.SECONDS): Unit
      seen.add(event): Unit
    def disconnected: Option[StreamEvent.Disconnected] =
      seen.asScala.collectFirst { case d: StreamEvent.Disconnected => d }

  private def probe(thread: ThreadId): StreamEvent.Live =
    StreamEvent.Live(thread.value, run.value, FencingToken(1L), "task", "node", "probe", 1, ujson.Null)

  /**
   * A live subscription with a disabled watch, from after `old`'s last event, capacity 2, whose
   * listener is held in its first call - a live event, so the subscription has switched to live - and
   * that has one durable event, `queued`, of this runtime queued behind it.
   */
  private def queuedBehind(store: Checkpointer, thread: ThreadId) =
    val old      = commitElsewhere(store, thread, "o1", "o2", "o3")
    val hub      = EventHub(store)
    val listener = Held()
    val sub = hub
      .subscribe(thread, afterSeq = old.last.seq, capacity = 2, listener, AbandonedWrites.none, TenantScope.Unscoped)
      .value
    // a live event is held while the subscription replays and queued once it is live: send until one arrives
    eventually {
      hub.live(thread, probe(thread))
      listener.entered.getCount shouldBe 0
    }
    val queued = commitElsewhere(store, thread, "q")
    hub.durable(thread, queued)
    (hub, listener, sub, queued.last.seq)

  s"A subscription on $storeName" should "end with ThreadDeleted, not Lagging, when another runtime deletes and recreates its thread while it is behind" in {
    val store                          = newStore()
    val thread                         = ThreadId(s"deleted-${java.util.UUID.randomUUID()}")
    val (hub, listener, sub, lastSeen) = queuedBehind(store, thread)

    // another runtime deletes the thread and creates it again; this runtime commits its first event
    deleteElsewhere(store, thread)
    val first = commitElsewhere(store, thread, "n1")
    first.map(_.seq) shouldBe Vector(lastSeen + 2)
    hub.durable(thread, first)

    listener.release.countDown()
    eventually(listener.disconnected shouldBe Some(StreamEvent.Disconnected(lastSeen, DisconnectReason.ThreadDeleted)))
    listener.seen.asScala.collect { case StreamEvent.Durable(r) => r.seq } shouldBe Vector(lastSeen)
    sub.cancel()
  }

  it should "still end Lagging when it really falls behind, with no delete" in {
    val store                          = newStore()
    val thread                         = ThreadId(s"behind-${java.util.UUID.randomUUID()}")
    val (hub, listener, sub, lastSeen) = queuedBehind(store, thread)

    // another runtime commits an event this runtime has not handed over, then this runtime commits: two
    // missing and one queued do not fit in 2
    commitElsewhere(store, thread, "elsewhere")
    hub.durable(thread, commitElsewhere(store, thread, "next"))

    listener.release.countDown()
    eventually(listener.disconnected shouldBe Some(StreamEvent.Disconnected(lastSeen, DisconnectReason.Lagging)))
    sub.cancel()
  }

class InMemoryLaggingOrDeletedSpec extends LaggingOrDeletedCases:
  protected def newStore(): Checkpointer = InMemoryCheckpointer()
  protected def storeName: String        = "InMemoryCheckpointer"
