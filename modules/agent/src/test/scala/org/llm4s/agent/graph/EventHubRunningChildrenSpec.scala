package org.llm4s.agent.graph

import org.llm4s.agent.AgentId
import org.llm4s.agent.delegation.ChildRef
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit }

/**
 * The event hub's running children (design 4.47, #2074): what a nested subscription attaching mid-child is told, and
 * that a stale run's late `childEnded` never forgets a successor run's announcement of the same child.
 */
class EventHubRunningChildrenSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val parent = ThreadId("hub-parent")
  private val child  = ThreadId("hub-parent/~task/worker/1")

  private def ref(run: String): ChildRef = ChildRef(child, RunId(run), AgentId.unsafe("worker"), None, 1)

  private def started(run: String, token: Long): StreamEvent.Live =
    StreamEvent.Live(parent.value, run, FencingToken(token), "1.0", "tools", "agent.task_started", 1, ujson.Obj())

  /** A nested subscription attached now, and the events it gets within a moment. */
  private def attached(hub: EventHub): Vector[StreamEvent] =
    val seen = new LinkedBlockingQueue[StreamEvent]()
    val sub  = hub.subscribe(parent, 0L, 16, e => seen.put(e), _ => false, TenantScope.Unscoped, nested = true).value
    val got  = Iterator.continually(Option(seen.poll(300, TimeUnit.MILLISECONDS))).takeWhile(_.isDefined).flatten
    val all  = got.toVector
    sub.cancel()
    all

  "a nested subscription" should "get each running child's TaskStarted, then one gap for it" in {
    val hub = new EventHub(InMemoryCheckpointer())
    hub.childStarted(parent, ref("r1"), started("r1", 1L), () => true)
    attached(hub) shouldBe Vector(started("r1", 1L), StreamEvent.Nested(ref("r1"), StreamEvent.LiveGap(0)))
    hub.childEnded(parent, child, FencingToken(1L))
    attached(hub) shouldBe empty
  }

  "a stale run's childEnded" should "leave a successor run's announcement of the same child" in {
    val hub = new EventHub(InMemoryCheckpointer())
    hub.childStarted(parent, ref("r1"), started("r1", 1L), () => true)
    hub.childStarted(parent, ref("r2"), started("r2", 2L), () => true)
    hub.childEnded(parent, child, FencingToken(1L))
    attached(hub) shouldBe Vector(started("r2", 2L), StreamEvent.Nested(ref("r2"), StreamEvent.LiveGap(0)))
  }

  "a run's release" should "forget the thread's running children" in {
    val hub = new EventHub(InMemoryCheckpointer())
    hub.childStarted(parent, ref("r1"), started("r1", 1L), () => true)
    hub.release(parent)
    attached(hub) shouldBe empty
  }
}
