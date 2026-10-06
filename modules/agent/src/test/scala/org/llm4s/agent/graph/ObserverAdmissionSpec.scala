package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters.*

class ObserverAdmissionSpec extends AnyFlatSpec with Matchers:

  private val Tick = EventType[Int]("test.tick", 1)

  /** One node that sends a live tick as its very first action, then completes. */
  private def eager =
    val b = GraphBuilder("eager", "1")
    val entry = b.node[Unit]("only") { (_, _, ctx) =>
      Tick.progress(ctx, 1)
      NodeResult.Continue(Command.empty)
    }
    b.compile(entry)(_ => Right(())).fold(e => fail(e.message), identity)

  final private class Recorder:
    val events = new CopyOnWriteArrayList[StreamEvent]()
    val ended  = new CountDownLatch(1)
    val listener: StreamEvent => Unit = e =>
      events.add(e)
      e match
        case StreamEvent.Durable(r) if r.event == RunEvent.RunCompleted => ended.countDown()
        case _                                                          => ()

  "An observer" should "see the claim event and the first live event of the run" in {
    val runtime = GraphRuntime.inMemory()
    val rec     = Recorder()
    val handle = runtime
      .start(ThreadId("o1"), eager, (), observer = Some(Observer(64, rec.listener)))
      .fold(e => fail(e.message), identity)
    handle.await().isRight shouldBe true
    rec.ended.await(5, TimeUnit.SECONDS) shouldBe true
    val seen = rec.events.asScala.toVector
    seen.collectFirst { case StreamEvent.Durable(r) => r.event } shouldBe Some(RunEvent.RunStarted(None, None))
    seen.collect { case Tick(n) => n } shouldBe Vector(1)
    handle.observation.isDefined shouldBe true
    handle.observation.foreach(_.cancel())
    runtime.liveSubscriptions(ThreadId("o1")) shouldBe 0
  }

  it should "see every run's first live event, repeatedly (no race with the run thread)" in {
    val runtime = GraphRuntime.inMemory()
    (1 to 50).foreach { i =>
      val rec = Recorder()
      val handle = runtime
        .start(ThreadId(s"race-$i"), eager, (), observer = Some(Observer(64, rec.listener)))
        .fold(e => fail(e.message), identity)
      handle.await()
      rec.ended.await(5, TimeUnit.SECONDS) shouldBe true
      rec.events.asScala.collect { case Tick(n) => n }.toVector shouldBe Vector(1)
      handle.observation.foreach(_.cancel())
    }
  }

  it should "not replay earlier runs on the thread" in {
    val runtime = GraphRuntime.inMemory()
    runtime.start(ThreadId("again"), eager, ()).flatMap(_.await()).isRight shouldBe true
    val rec = Recorder()
    val handle = runtime
      .start(ThreadId("again"), eager, (), observer = Some(Observer(64, rec.listener)))
      .fold(e => fail(e.message), identity)
    handle.await().isRight shouldBe true
    rec.ended.await(5, TimeUnit.SECONDS) shouldBe true
    val durable = rec.events.asScala.collect { case StreamEvent.Durable(r) => r }.toVector
    durable.map(_.runId).distinct shouldBe Vector(handle.runId.value)
    durable.head.event shouldBe RunEvent.RunStarted(None, None)
    rec.events.asScala.collect { case Tick(n) => n }.toVector shouldBe Vector(1)
    handle.observation.foreach(_.cancel())
  }

  it should "leave no subscription when admission is refused" in {
    val runtime = GraphRuntime.inMemory()
    val first   = runtime.start(ThreadId("busy"), eager, ()).fold(e => fail(e.message), identity)
    first.await()
    val rec = Recorder()
    // a completed thread accepts a new start, so refuse with an invalid capacity instead
    runtime.start(ThreadId("busy"), eager, (), observer = Some(Observer(1, rec.listener))) match
      case Left(_: ValidationError) => ()
      case other                    => fail(s"expected a ValidationError, got $other")
    runtime.liveSubscriptions(ThreadId("busy")) shouldBe 0
    // the thread is free: a later run admits, and the refused observer never hears of it
    runtime.start(ThreadId("busy"), eager, ()).flatMap(_.await()).isRight shouldBe true
    Thread.sleep(100)
    rec.events.isEmpty shouldBe true
  }

  it should "be abandoned, hearing nothing, when admission is refused after it joined" in {
    val runtime = GraphRuntime.inMemory()
    runtime.start(ThreadId("done"), eager, ()).flatMap(_.await()).isRight shouldBe true
    val rec = Recorder()
    // the thread is not suspended: refused inside admission, after the observer joined the hub
    runtime.resume(ThreadId("done"), eager, Map.empty, observer = Some(Observer(64, rec.listener))) match
      case Left(_: GraphError.NotSuspended) => ()
      case other                            => fail(s"expected NotSuspended, got $other")
    runtime.liveSubscriptions(ThreadId("done")) shouldBe 0
    // the thread was released, and the abandoned observer hears nothing of the next run
    runtime.start(ThreadId("done"), eager, ()).flatMap(_.await()).isRight shouldBe true
    Thread.sleep(100)
    rec.events.isEmpty shouldBe true
  }

  it should "work for recover and resume" in {
    // recover: a node that fails on its first run only, so recover completes the thread
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val b     = GraphBuilder("flaky", "1")
    val entry = b.node[Unit]("only") { (_, _, ctx) =>
      Tick.progress(ctx, calls.incrementAndGet())
      if calls.get == 1 then NodeResult.Fail(ValidationError("x", "first run fails"))
      else NodeResult.Continue(Command.empty)
    }
    val graph   = b.compile(entry)(_ => Right(())).fold(e => fail(e.message), identity)
    val runtime = GraphRuntime.inMemory()
    runtime.start(ThreadId("rec"), graph, ()).flatMap(_.await())
    val rec = Recorder()
    val handle = runtime
      .recover(ThreadId("rec"), graph, observer = Some(Observer(64, rec.listener)))
      .fold(e => fail(e.message), identity)
    handle.await().isRight shouldBe true
    rec.ended.await(5, TimeUnit.SECONDS) shouldBe true
    rec.events.asScala.collect { case Tick(n) => n }.toVector shouldBe Vector(2)
    handle.observation.foreach(_.cancel())
  }
