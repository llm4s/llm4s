package org.llm4s.agent.graph

import org.llm4s.agent.AgentId
import org.llm4s.agent.delegation.ChildRef
import org.llm4s.agent.events.{ SubagentEvents, TaskEnded }
import org.llm4s.llmconnect.model.UsageSummary
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.Duration

/**
 * The event hub's running children (design 4.47, #2074): what a nested subscription attaching mid-child is told, that
 * a child is forgotten when its call's `TaskEnded` is handed over, and that a stale run's `TaskEnded` never forgets a
 * successor run's announcement of the same child.
 */
class EventHubRunningChildrenSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val parent = ThreadId("hub-parent")
  private val child  = ThreadId("hub-parent/~task/worker/1")

  private def ref(run: String): ChildRef = ChildRef(child, RunId(run), AgentId.unsafe("worker"), None, 1)

  private def started(run: String, token: Long): StreamEvent.Live =
    StreamEvent.Live(parent.value, run, FencingToken(token), "1.0", "tools", "agent.task_started", 1, ujson.Obj())

  /**
   * Run `run`'s commit of the call's `TaskEnded` to `store` under a new claim - the store's first is token 1 - handed
   * over to `hub`.
   */
  private def taskEnded(store: Checkpointer, hub: EventHub, run: String): Unit =
    val ended = SubagentEvents.TaskEnded.encode(TaskEnded(ref(run), "completed", UsageSummary(), Duration.Zero))
    val event = RunEvent.Custom(SubagentEvents.TaskEnded.name, SubagentEvents.TaskEnded.version, ended)
    val draft = EventDraft(run, None, Some("1.0"), Some("tools"), java.time.Instant.now(), event)
    val claim =
      store.claim(parent, ClaimRequest(RunId(run), None, scala.concurrent.duration.DurationInt(1).minute)).value
    val records = store.commit(parent, Commit(claim.token, events = Vector(draft))).value
    store.release(parent, claim.token).value
    hub.durable(parent, records)

  /** A nested subscription attached now, and the live events it gets within a moment. */
  private def attached(hub: EventHub, capacity: Int = 16): Vector[StreamEvent] =
    val seen = new LinkedBlockingQueue[StreamEvent]()
    val sub =
      hub.subscribe(parent, 0L, capacity, e => seen.put(e), _ => false, TenantScope.Unscoped, nested = true).value
    val got = Iterator.continually(Option(seen.poll(300, TimeUnit.MILLISECONDS))).takeWhile(_.isDefined).flatten
    val all = got.toVector
    sub.cancel()
    all.filterNot(_.isInstanceOf[StreamEvent.Durable])

  private def startAndGap(run: String, token: Long): Vector[StreamEvent] =
    Vector(started(run, token), StreamEvent.Nested(ref(run), StreamEvent.LiveGap(0)))

  "a nested subscription" should "get each announced child's TaskStarted, then one gap for it, until its TaskEnded" in {
    val store = InMemoryCheckpointer()
    val hub   = new EventHub(store)
    hub.childStarted(parent, ref("r1"), started("r1", 1L), () => true)
    attached(hub) shouldBe startAndGap("r1", 1L)
    taskEnded(store, hub, "r1")
    attached(hub) shouldBe empty
  }

  it should "get the start and its gap together at the minimum capacity" in {
    val hub = new EventHub(InMemoryCheckpointer())
    hub.childStarted(parent, ref("r1"), started("r1", 1L), () => true)
    attached(hub, capacity = 2) shouldBe startAndGap("r1", 1L)
  }

  "a stale run's TaskEnded" should "leave a successor run's announcement of the same child" in {
    val store = InMemoryCheckpointer()
    val hub   = new EventHub(store)
    hub.childStarted(parent, ref("r1"), started("r1", 1L), () => true)
    hub.childStarted(parent, ref("r2"), started("r2", 2L), () => true)
    // the store's first claim, token 1: the stale run's
    taskEnded(store, hub, "r1")
    attached(hub) shouldBe startAndGap("r2", 2L)
  }

  "a child start sent once its run is no longer current" should "be neither delivered nor remembered" in {
    val hub = new EventHub(InMemoryCheckpointer())
    hub.childStarted(parent, ref("r1"), started("r1", 1L), () => false)
    attached(hub) shouldBe empty
  }

  "forgetChildren" should "forget the thread's running children, as a release with an orphan in flight does" in {
    val hub = new EventHub(InMemoryCheckpointer())
    hub.childStarted(parent, ref("r1"), started("r1", 1L), () => true)
    hub.forgetChildren(parent)
    attached(hub) shouldBe empty
  }

  "a run's release" should "forget the thread's running children" in {
    val hub = new EventHub(InMemoryCheckpointer())
    hub.childStarted(parent, ref("r1"), started("r1", 1L), () => true)
    hub.release(parent)
    attached(hub) shouldBe empty
  }
}
