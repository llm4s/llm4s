package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling, Text }
import org.llm4s.agent.graph.{ GraphError, IdempotencyKey, RunBudgets, RunConfig, StateKey, StateUpdate, ThreadId }
import org.llm4s.agent.graph.middleware.{ PermissionMiddleware, PermissionRule }
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, LockKey, Operation, ToolOutcome, ToolResource }
import org.llm4s.agent.graph.toolloop.ToolLoop
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.jdk.CollectionConverters._

/**
 * Batch locks (#1903): calls of one model message whose locks conflict run in successive supersteps, in
 * call order, each seeing the state the earlier one committed; the others run concurrently, as before.
 */
class ToolBatchLockSpec extends AnyFlatSpec with Matchers {

  private val counter: StateKey[Int, Int] = StateKey.replace[Int]("counter", 0)

  /** Locked on the call's `text`, when `locked`. */
  private def lockedSpec(name: String, locked: Boolean): AgentToolSpec[Text] =
    if (locked) SpecTools.spec(name).withLocks(t => Set(LockKey(t.text))) else SpecTools.spec(name)

  /** Reads the counter from its snapshot and writes it back incremented: a whole-value write. */
  private def increment(locked: Boolean): AgentTool[Text] =
    AgentTool(lockedSpec("increment", locked), writes = Set(counter)) { (_, context) =>
      val next = context.state.get(counter).fold(e => fail(e.message), identity) + 1
      ToolOutcome.Success(ujson.Str(next.toString), StateUpdate.update(counter, next))
    }

  private def toolResults(result: AgentResult): Vector[String] =
    result.messages.collect { case t: ToolMessage => t.content }.toVector

  "Two calls holding one lock" should "run in turn, the second seeing the first's write" in {
    val client = ScriptedLLMClient.of(
      calling(call("c1", "increment", "counter"), call("c2", "increment", "counter")),
      CompletionFixture.simple("done")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(increment(locked = true))))
    toolResults(agent.run("go").value) shouldBe Vector("1", "2")
  }

  "Two unlocked calls" should "share the batch's snapshot, as before: one whole-value write is lost" in {
    val client = ScriptedLLMClient.of(
      calling(call("c1", "increment", "counter"), call("c2", "increment", "counter")),
      CompletionFixture.simple("done")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(increment(locked = false))))
    toolResults(agent.run("go").value) shouldBe Vector("1", "1")
  }

  /** A tool logging its start and end; while `meet` is set, it waits for `meet` to reach zero. */
  private def logging(log: CopyOnWriteArrayList[String], meet: Option[CountDownLatch], locked: Boolean) =
    AgentTool(lockedSpec("work", locked)) { (_, context) =>
      log.add(s"start ${context.toolCallId.value}")
      val met = meet.forall { latch =>
        latch.countDown()
        latch.await(5, TimeUnit.SECONDS)
      }
      log.add(s"end ${context.toolCallId.value}")
      ToolOutcome.Success(ujson.Str(if (met) "met" else "alone"))
    }

  "Calls with non-conflicting locks" should "run concurrently; a conflicting later call waits for the earlier" in {
    val log  = new CopyOnWriteArrayList[String]()
    val meet = new CountDownLatch(2)
    val tool = AgentTool(lockedSpec("work", locked = true)) { (args, context) =>
      log.add(s"start ${context.toolCallId.value}")
      // a and b must be inside the tool at once; c runs alone, after a
      val met = if (context.toolCallId.value == "c") true else { meet.countDown(); meet.await(5, TimeUnit.SECONDS) }
      log.add(s"end ${context.toolCallId.value}")
      ToolOutcome.Success(ujson.Str(s"${args.text}:${if (met) "met" else "alone"}"))
    }
    val client = ScriptedLLMClient.of(
      calling(call("a", "work", "x"), call("b", "work", "y"), call("c", "work", "x")),
      CompletionFixture.simple("done")
    )
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(tool)))
    val result = agent.run("go").value

    toolResults(result) shouldBe Vector("x:met", "y:met", "x:met")
    val events = log.asScala.toVector
    events.indexOf("end a") should be < events.indexOf("start c")
    events.indexOf("end b") should be < events.indexOf("start c")
  }

  "Unlocked calls" should "still run concurrently" in {
    val log    = new CopyOnWriteArrayList[String]()
    val client = ScriptedLLMClient.of(calling(call("a", "work"), call("b", "work")), CompletionFixture.simple("done"))
    val agent =
      built(
        Agent
          .builder("assistant", client)
          .withTools(SpecTools.set(logging(log, Some(new CountDownLatch(2)), locked = false)))
      )
    toolResults(agent.run("go").value) shouldBe Vector("met", "met")
  }

  "A workspace lock" should "conflict with a file lock beneath it" in {
    val spec = SpecTools.spec("increment").withLocks(t => Set(LockKey(t.text)))
    val tool = AgentTool(spec, writes = Set(counter)) { (_, context) =>
      val next = context.state.get(counter).fold(e => fail(e.message), identity) + 1
      ToolOutcome.Success(ujson.Str(next.toString), StateUpdate.update(counter, next))
    }
    val client = ScriptedLLMClient.of(
      calling(
        call("c1", "increment", "workspace/w1/src/a.scala"),
        call("c2", "increment", "workspace/w1"),
        call("c3", "increment", "workspace/w2")
      ),
      CompletionFixture.simple("done")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(tool)))
    // c1 and c3 share the first snapshot; c2 waits for c1 and sees its write
    toolResults(agent.run("go").value) shouldBe Vector("1", "2", "1")
  }

  "A failure between two locked calls" should "leave the thread for recover, which runs only the second" in {
    val firstRuns = new AtomicInteger()
    val broken    = new AtomicBoolean(true)
    val keys      = new CopyOnWriteArrayList[IdempotencyKey]()
    val tool = AgentTool(SpecTools.spec("step").withLocks(_ => Set(LockKey("shared")))) { (args, context) =>
      if (args.text == "one") {
        firstRuns.incrementAndGet()
        ToolOutcome.Success(ujson.Str("one done"))
      } else {
        keys.add(context.idempotencyKey)
        if (broken.get) ToolOutcome.Fatal(ValidationError("step", "backend down"))
        else ToolOutcome.Success(ujson.Str("two done"))
      }
    }
    val client = ScriptedLLMClient.of(
      calling(call("c1", "step", "one"), call("c2", "step", "two")),
      CompletionFixture.simple("all done")
    )
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(tool)))
    val thread = ThreadId("locked-recover")

    cause(agent.run(thread, "go").error) shouldBe a[GraphError.ToolFailed]
    firstRuns.get shouldBe 1

    broken.set(false)
    val recovered = agent.recover(thread).value
    recovered.answer shouldBe Some("all done")
    toolResults(recovered) shouldBe Vector("one done", "two done")
    firstRuns.get shouldBe 1
    keys.asScala.toVector.distinct should have size 1
  }

  "A cancel during the second locked call" should "leave the first's result; recover runs only the second" in {
    val firstRuns = new AtomicInteger()
    val entered   = new CountDownLatch(1)
    val block     = new AtomicBoolean(true)
    val tool = AgentTool(SpecTools.spec("step").withLocks(_ => Set(LockKey("shared")))) { (args, _) =>
      if (args.text == "one") {
        firstRuns.incrementAndGet()
        ToolOutcome.Success(ujson.Str("one done"))
      } else {
        if (block.get) {
          entered.countDown()
          Thread.sleep(60_000) // interrupted by cancel
        }
        ToolOutcome.Success(ujson.Str("two done"))
      }
    }
    val client = ScriptedLLMClient.of(
      calling(call("c1", "step", "one"), call("c2", "step", "two")),
      CompletionFixture.simple("all done")
    )
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(tool)))
    val thread = ThreadId("locked-cancel")

    val run = agent.start(thread, "go").fold(e => fail(e.message), identity)
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    run.cancel()
    cause(run.await().error) shouldBe a[GraphError.Cancelled]

    block.set(false)
    val recovered = agent.recover(thread).value
    toolResults(recovered) shouldBe Vector("one done", "two done")
    firstRuns.get shouldBe 1
  }

  "Each further wave" should "cost two supersteps, counted by maxSupersteps" in {
    val tool = AgentTool(SpecTools.spec("step").withLocks(_ => Set(LockKey("shared"))))((_, _) =>
      ToolOutcome.Success(ujson.Str("ok"))
    )
    def run(maxSupersteps: Int) = {
      val client = ScriptedLLMClient.of(
        calling(call("c1", "step"), call("c2", "step"), call("c3", "step")),
        CompletionFixture.simple("done")
      )
      val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(tool)))
      agent.run(ThreadId(s"budget-$maxSupersteps"), "go", RunConfig().withBudgets(RunBudgets(maxSupersteps)))
    }
    // input, model, 3 waves of calls with a collect after each, model, finish
    run(10).value.answer shouldBe Some("done")
    run(9).isLeft shouldBe true
  }

  "A lock function that throws" should "order the call after every earlier locked call" in {
    val spec = SpecTools.spec("increment").withLocks { t =>
      if (t.text == "boom") throw new IllegalStateException("no locks") else Set(LockKey(t.text))
    }
    val tool = AgentTool(spec, writes = Set(counter)) { (_, context) =>
      val next = context.state.get(counter).fold(e => fail(e.message), identity) + 1
      ToolOutcome.Success(ujson.Str(next.toString), StateUpdate.update(counter, next))
    }
    val client = ScriptedLLMClient.of(
      calling(call("c1", "increment", "a"), call("c2", "increment", "boom"), call("c3", "increment", "b")),
      CompletionFixture.simple("done")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(tool)))
    toolResults(agent.run("go").value) shouldBe Vector("1", "2", "3")
  }

  "A locked call waiting for approval" should "hold back a later conflicting call until it is approved and run" in {
    val ran = new CopyOnWriteArrayList[String]()
    val spec = SpecTools
      .spec("increment")
      .withLocks(_ => Set(LockKey("counter")))
      .withResources(t => Vector(ToolResource(Operation.Write, t.text)))
    val tool = AgentTool(spec, writes = Set(counter)) { (t, context) =>
      ran.add(t.text)
      val next = context.state.get(counter).fold(e => fail(e.message), identity) + 1
      ToolOutcome.Success(ujson.Str(next.toString), StateUpdate.update(counter, next))
    }
    val client = ScriptedLLMClient.of(
      calling(call("c1", "increment", "/prod"), call("c2", "increment", "/tmp"), call("c3", "increment", "/prod")),
      CompletionFixture.simple("done")
    )
    val rules =
      Seq(PermissionRule.ask(Operation.Write, "/prod"), PermissionRule.allow(Operation.Write, "/tmp"))
    val agent = built(
      Agent
        .builder("assistant", client)
        .withTools(SpecTools.set(tool))
        .withMiddleware(new PermissionMiddleware(rules))
    )
    def approvals(result: AgentResult) = result.status match {
      case AgentStatus.Suspended(pending, _, _, _) => pending.map(_._1)
      case other                                   => fail(s"expected Suspended, got $other")
    }

    // c1 asks; c2 and c3 wait behind it in the queue
    val first = agent.run("go").value
    approvals(first) should have size 1
    ran.size shouldBe 0

    // c1 runs, then c2; c3 asks in its own round
    val second = agent.resume(first.threadId, Map(first.approve(approvals(first).head))).value
    approvals(second) should have size 1
    ran.asScala.toVector shouldBe Vector("/prod", "/tmp")

    val done = agent.resume(second.threadId, Map(second.approve(approvals(second).head))).value
    done.answer shouldBe Some("done")
    toolResults(done) shouldBe Vector("1", "2", "3")
  }

  "The tool queue" should "be a key the loop owns" in {
    val sneaky =
      AgentTool(SpecTools.spec("sneaky"), writes = Set(ToolLoop.queue))((_, _) => ToolOutcome.Success(ujson.Str("x")))
    val client = ScriptedLLMClient.of(CompletionFixture.simple("never"))
    Agent.builder("assistant", client).withTools(SpecTools.set(sneaky)).build().isLeft shouldBe true
  }
}
