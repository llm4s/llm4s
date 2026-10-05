package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }
import scala.concurrent.duration.*

/**
 * A node's [[CachePolicy]]: what is answered from memory, what is never stored, when an entry
 * expires or is evicted, and that a hit leaves checkpoints and events as a run would.
 */
class NodeCacheSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val out = StateKey.replace[Int]("out", 0)

  /** `start` sends its input to `compute`, which doubles it and counts its calls. */
  final private class Doubler(policy: Option[CachePolicy] = Some(CachePolicy.default)) {
    val calls = new AtomicInteger()
    val clock = new AtomicLong(0L)

    val graph: CompiledGraph[Int, Int] = {
      val b = GraphBuilder("doubler", "v1")
      val compute = b.node[Int]("compute", writes = Set(out), cache = policy) { (n, _, _) =>
        calls.incrementAndGet()
        continue(Command.empty.update(out, n * 2))
      }
      val start = b.node[Int]("start")((n, _, _) => continue(Command.empty.send(compute, n)))
      b.compile(start)(_.get(out)).value.withTicker(() => clock.get)
    }

    def run(n: Int): Int = runInMemory(graph, n).completed._2
  }

  "A node with a cache policy" should "answer a repeated input from memory" in {
    val doubler = new Doubler()

    doubler.run(21) shouldBe 42
    doubler.run(21) shouldBe 42
    doubler.calls.get shouldBe 1

    doubler.run(5) shouldBe 10
    doubler.calls.get shouldBe 2
  }

  it should "keep two nodes' results apart, even for one input" in {
    val first  = new AtomicInteger()
    val second = new AtomicInteger()
    val total  = StateKey.appending[Int]("total")
    val b      = GraphBuilder("two", "v1")
    val a = b.node[Int]("a", writes = Set(total), cache = Some(CachePolicy.default)) { (n, _, _) =>
      first.incrementAndGet()
      continue(Command.empty.update(total, n + 1))
    }
    val c = b.node[Int]("c", writes = Set(total), cache = Some(CachePolicy.default)) { (n, _, _) =>
      second.incrementAndGet()
      continue(Command.empty.update(total, n + 100))
    }
    val start = b.node[Int]("start")((n, _, _) => continue(Command.empty.send(a, n).send(c, n)))
    val graph = b.compile(start)(_.get(total)).value

    runInMemory(graph, 1).completed._2 shouldBe Vector(2, 101)
    runInMemory(graph, 1).completed._2 shouldBe Vector(2, 101)

    (first.get, second.get) shouldBe ((1, 1))
  }

  it should "give a hit the same update and routes as the run it was stored from" in {
    val calls = new AtomicInteger()
    val log   = StateKey.appending[String]("log")
    val b     = GraphBuilder("routes", "v1")
    val after = b.node[Unit]("after", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "after")))
    val cached = b.node[String]("cached", writes = Set(log), cache = Some(CachePolicy.default)) { (item, _, _) =>
      calls.incrementAndGet()
      continue(Command.empty.update(log, s"cached($item)").goto(after))
    }
    val start = b.node[String]("start")((item, _, _) => continue(Command.empty.send(cached, item)))
    val graph = b.compile(start)(_.get(log)).value

    val first  = runInMemory(graph, "x").completed._2
    val second = runInMemory(graph, "x").completed._2

    first shouldBe Vector("cached(x)", "after")
    second shouldBe first
    calls.get shouldBe 1
  }

  it should "run every time without a policy" in {
    val doubler = new Doubler(policy = None)

    doubler.run(21)
    doubler.run(21)

    doubler.calls.get shouldBe 2
  }

  it should "not store a failure" in {
    val calls = new AtomicInteger()
    val b     = GraphBuilder("fails-once", "v1")
    val work = b.node[Int]("work", writes = Set(out), cache = Some(CachePolicy.default)) { (n, _, _) =>
      if calls.incrementAndGet() == 1 then NodeResult.Fail(ValidationError("work", "first call fails"))
      else continue(Command.empty.update(out, n))
    }
    val graph = b.compile(work)(_.get(out)).value

    runInMemory(graph, 7).failed
    runInMemory(graph, 7).completed._2 shouldBe 7
    calls.get shouldBe 2

    runInMemory(graph, 7).completed._2 shouldBe 7
    calls.get shouldBe 2
  }

  it should "not store a suspension" in {
    val calls = new AtomicInteger()
    val b     = GraphBuilder("suspends", "v1")
    val ask   = b.declareResume[String, String]("ask")
    b.implement(ask.node)((_, _, _) => continue(Command.empty))
    val work = b.node[Int]("work", cache = Some(CachePolicy.default)) { (_, _, _) =>
      calls.incrementAndGet()
      NodeResult.Suspend(StateUpdate.empty, "proceed?", ask)
    }
    val graph = b.compile(work)(_ => Right(())).value

    runInMemory(graph, 1).suspended
    runInMemory(graph, 1).suspended

    calls.get shouldBe 2
  }

  it should "keep a separate cache for each compiled graph" in {
    val first  = new Doubler()
    val second = new Doubler()

    first.run(3)
    second.run(3)

    (first.calls.get, second.calls.get) shouldBe ((1, 1))
  }

  it should "leave the checkpoint and the events of a hit as an ordinary run leaves them" in {
    val store   = new InMemoryCheckpointer
    val runtime = GraphRuntime(store)
    val doubler = new Doubler()

    runtime.start(ThreadId("first"), doubler.graph, 4, RunConfig()).awaited.value.completed._2 shouldBe 8
    runtime.start(ThreadId("second"), doubler.graph, 4, RunConfig()).awaited.value.completed._2 shouldBe 8

    doubler.calls.get shouldBe 1
    def computed(id: String): Int = store
      .eventsAfter(ThreadId(id), 0L, 100)
      .value
      .count(r => r.nodeId.contains("compute") && r.event == RunEvent.TaskCompleted)
    (computed("first"), computed("second")) shouldBe ((1, 1))
  }

  "NodeCache" should "serve an entry until its time to live has passed" in {
    val clock = new AtomicLong(0L)
    val cache = new NodeCache(CachePolicy(ttl = Some(10.seconds)), () => clock.get)
    cache.put("k", Command.empty)

    clock.set(10.seconds.toNanos - 1)
    cache.get("k") shouldBe defined

    clock.set(10.seconds.toNanos)
    cache.get("k") shouldBe None
    cache.size shouldBe 0
  }

  it should "keep an entry for ever without a time to live" in {
    val clock = new AtomicLong(0L)
    val cache = new NodeCache(CachePolicy(ttl = None), () => clock.get)
    cache.put("k", Command.empty)

    clock.set(Long.MaxValue / 2)

    cache.get("k") shouldBe defined
  }

  it should "evict the least recently used entry beyond its capacity" in {
    val cache = new NodeCache(CachePolicy(maxEntries = 2), () => 0L)
    cache.put("a", Command.empty)
    cache.put("b", Command.empty)
    cache.get("a") shouldBe defined // a is now more recent than b

    cache.put("c", Command.empty)

    (cache.get("a").isDefined, cache.get("b").isDefined, cache.get("c").isDefined) shouldBe ((true, false, true))
    cache.size shouldBe 2
  }

  it should "return the command that was stored" in {
    val cache   = new NodeCache(CachePolicy.default, () => 0L)
    val command = Command.empty.update(out, 9)
    cache.put("k", command)

    (cache.get("k").value should be).theSameInstanceAs(command)
  }

  "NodeCache.key" should "depend on the node and the input, and on nothing else" in {
    val b      = GraphBuilder("keys", "v1")
    val first  = NodeDef(b.declare[Int]("first"), Set.empty, (_, _, _) => NodeResult.Continue(Command.empty))
    val second = NodeDef(b.declare[Int]("second"), Set.empty, (_, _, _) => NodeResult.Continue(Command.empty))

    NodeCache.key(first, 1) shouldBe NodeCache.key(first, 1)
    NodeCache.key(first, 1) should not be NodeCache.key(first, 2)
    NodeCache.key(first, 1) should not be NodeCache.key(second, 1)
    NodeCache.key(first, 1) shouldBe defined
  }

  it should "include the input's schema version" in {
    val b  = GraphBuilder("versions", "v1")
    val v1 = NodeDef(b.declare[Int]("n"), Set.empty, (_, _, _) => NodeResult.Continue(Command.empty))
    val v2 = NodeDef(
      b.declare[Int]("n2", SchemaVersion(2)(1 -> (json => Right(json)))),
      Set.empty,
      (_, _, _) => NodeResult.Continue(Command.empty)
    )

    // the node ids differ too, so compare only the version part of the key
    NodeCache.key(v1, 1).value.split('\u0000')(1) shouldBe "1"
    NodeCache.key(v2, 1).value.split('\u0000')(1) shouldBe "2"
  }

  "CachePolicy.apply" should "default to no expiry and 1024 entries" in {
    val policy = CachePolicy()
    (policy.ttl, policy.maxEntries) shouldBe ((None, 1024))
  }

  it should "reject a value that cannot work" in {
    an[IllegalArgumentException] should be thrownBy CachePolicy(ttl = Some(Duration.Zero))
    an[IllegalArgumentException] should be thrownBy CachePolicy(maxEntries = 0)
  }

  "CachePolicy.of" should "return every problem at once as a ValidationError" in {
    val error = CachePolicy.of(ttl = Some(-1.second), maxEntries = 0).left.value

    error shouldBe a[ValidationError]
    error.message should include("ttl")
    error.message should include("maxEntries")
  }

  "The CachePolicy setters" should "change one field and validate it" in {
    val changed = CachePolicy.default.withTtl(5.seconds).withMaxEntries(3)
    (changed.ttl, changed.maxEntries) shouldBe ((Some(5.seconds), 3))
    changed.withTtl(None).ttl shouldBe None
    CachePolicy.default.maxEntries shouldBe 1024
    an[IllegalArgumentException] should be thrownBy CachePolicy.default.withMaxEntries(0)
  }
}
