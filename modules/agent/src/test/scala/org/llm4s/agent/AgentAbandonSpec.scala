package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.agent.graph.toolloop.ToolResultRule
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CountDownLatch, TimeUnit }

/**
 * `Agent.abandon` (#1750): a thread whose run stopped mid-turn - cancelled, failed or crashed - is closed rather than
 * recovered. Every tool call of the turn gets one result, none runs again, and the thread takes a new turn; a thread
 * that is not in that state is refused with a typed error and left as it is. `AgentToolResultContractSpec` checks the
 * requests the OpenAI and Anthropic clients then send.
 */
class AgentAbandonSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private def ok(text: String) = ToolOutcome.success(ToolContent.Json(ujson.Str(text)))

  private def results(messages: Seq[Message]): Vector[(String, String)] =
    messages.collect { case t: ToolMessage => t.toolCallId.asString -> ContentPart.text(t.parts) }.toVector

  /** An agent whose `flaky` tool fails `Fatal` and whose `good` tool counts its runs, over `store`. */
  private def failing(
    client: ScriptedLLMClient,
    store: Checkpointer,
    goodRan: AtomicInteger,
    flakyRan: AtomicInteger
  ) = {
    val good = SpecTools.tool("good") { (_, _) =>
      goodRan.incrementAndGet()
      ok("good")
    }
    val flaky = SpecTools.tool("flaky") { (_, _) =>
      flakyRan.incrementAndGet()
      ToolOutcome.Fatal(ValidationError("flaky", "backend down"))
    }
    built(Agent.builder("assistant", client).withTools(SpecTools.set(good, flaky)).withRuntime(new GraphRuntime(store)))
  }

  "Agent.abandon" should "give each unfinished call the reason, keep a finished call's result, and let the thread take a new turn" in {
    val client = ScriptedLLMClient.of(
      calling(call("g1", "good"), call("x1", "flaky")),
      CompletionFixture.simple("moved on")
    )
    val goodRan  = new AtomicInteger()
    val flakyRan = new AtomicInteger()
    val store    = new InMemoryCheckpointer
    val agent    = failing(client, store, goodRan, flakyRan)
    val thread   = ThreadId("abandoned")
    cause(agent.run(thread, "go").error) shouldBe a[GraphError.ToolFailed]

    agent.abandon(thread, "abandoned: flaky is down") shouldBe Right(())

    // the thread is at rest, settled as a failure, and nothing was asked of the model or of a tool
    val settled = store.latest(thread).toOption.flatten.map(_.checkpoint).getOrElse(fail("no checkpoint"))
    settled.status shouldBe CheckpointStatus.Failed
    settled.outcome.collect { case RunOutcome.Failed(kind, _) => kind } shouldBe Some("Abandoned")
    client.callCount shouldBe 1

    val done = agent.run(thread, "carry on").value
    done.answer shouldBe Some("moved on")
    ToolResultRule.violations(done.messages) shouldBe empty
    ToolResultRule.violations(client.sent.last) shouldBe empty
    results(client.sent.last) should contain theSameElementsAs Vector(
      "g1" -> "good",
      "x1" -> "abandoned: flaky is down"
    )
    goodRan.get shouldBe 1
    flakyRan.get shouldBe 1
  }

  it should "record a default reason, and leave nothing to recover or to abandon again" in {
    val client = ScriptedLLMClient.of(calling(call("x1", "flaky")), CompletionFixture.simple("never"))
    val store  = new InMemoryCheckpointer
    val agent  = failing(client, store, new AtomicInteger(), new AtomicInteger())
    val thread = ThreadId("default-reason")
    agent.run(thread, "go").isLeft shouldBe true

    agent.abandon(thread) shouldBe Right(())
    agent.recover(thread).error shouldBe GraphError.NothingToRecover(thread.value)
    agent.abandon(thread) shouldBe Left(GraphError.NothingToRecover(thread.value))
    client.callCount shouldBe 1

    val state = store.latest(thread).toOption.flatten.getOrElse(fail("no checkpoint"))
    state.pendingWrites shouldBe empty
  }

  it should "start the next turn's batch empty, so a provider reusing a call id gets that call's own result" in {
    val client = ScriptedLLMClient.of(
      calling(call("c1", "good"), call("c2", "flaky")),
      calling(call("c1", "good", "again")),
      CompletionFixture.simple("done")
    )
    val goodRan = new AtomicInteger()
    val agent   = failing(client, new InMemoryCheckpointer, goodRan, new AtomicInteger())
    val thread  = ThreadId("reused-ids")
    agent.run(thread, "go").isLeft shouldBe true
    agent.abandon(thread) shouldBe Right(())

    val done = agent.run(thread, "again").value
    done.answer shouldBe Some("done")
    ToolResultRule.violations(done.messages) shouldBe empty
    results(done.messages).map(_._1) shouldBe Vector("c1", "c2", "c1")
    goodRan.get shouldBe 2
  }

  it should "close a turn that stopped in its model call, which has no tool call to answer" in {
    val client = new ScriptedLLMClient(
      Left(ValidationError("provider", "unavailable")),
      Right(CompletionFixture.simple("back"))
    )
    val agent  = built(Agent.builder("assistant", client).withRuntime(new GraphRuntime(new InMemoryCheckpointer)))
    val thread = ThreadId("model-failed")
    agent.run(thread, "go").isLeft shouldBe true

    agent.abandon(thread) shouldBe Right(())
    agent.run(thread, "again").value.answer shouldBe Some("back")
  }

  it should "refuse a thread that does not exist, and one at rest, as having nothing to recover" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("hello"))
    val store  = new InMemoryCheckpointer
    val agent  = built(Agent.builder("assistant", client).withRuntime(new GraphRuntime(store)))

    agent.abandon(ThreadId("unknown")) shouldBe Left(GraphError.NothingToRecover("unknown"))

    val thread = ThreadId("at-rest")
    agent.run(thread, "hi").value.answer shouldBe Some("hello")
    val before = store.latest(thread).toOption.flatten.map(_.checkpoint.id)
    agent.abandon(thread) shouldBe Left(GraphError.NothingToRecover(thread.value))
    store.latest(thread).toOption.flatten.map(_.checkpoint.id) shouldBe before
  }

  it should "refuse a suspended thread, which stays resumable" in {
    val ran = new AtomicInteger()
    val deploy = SpecTools.tool("deploy") { (_, _) =>
      ran.incrementAndGet()
      ok("deployed")
    }
    val client = ScriptedLLMClient.of(calling(call("d1", "deploy")), CompletionFixture.simple("shipped"))
    val agent = built(
      Agent
        .builder("assistant", client)
        .withTools(SpecTools.set(deploy))
        .withMiddleware(new ApprovalMiddleware(_ => Some("review")))
    )
    val parked = agent.run("ship it").value
    val approval = parked.status match {
      case AgentStatus.Suspended(suspension) => suspension.approvals.head._1
      case other                             => fail(s"expected Suspended, got $other")
    }

    agent.abandon(parked.threadId) match {
      case Left(pending: GraphError.PendingInterrupts) => pending.interrupts should have size 1
      case other                                       => fail(s"expected PendingInterrupts, got $other")
    }
    agent.resume(parked.threadId, Map(parked.approve(approval))).value.answer shouldBe Some("shipped")
    ran.get shouldBe 1
  }

  it should "refuse a thread whose run is still live" in {
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val slow = SpecTools.tool("slow") { (_, _) =>
      started.countDown()
      release.await(30, TimeUnit.SECONDS)
      ok("slow")
    }
    val client = ScriptedLLMClient.of(calling(call("s1", "slow")), CompletionFixture.simple("finished"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(slow)))
    val thread = ThreadId("live")
    val run    = agent.start(thread, "go").fold(e => fail(e.message), identity)
    started.await(10, TimeUnit.SECONDS) shouldBe true

    agent.abandon(thread) match {
      case Left(_: GraphError.ThreadBusy) => ()
      case other                          => fail(s"expected ThreadBusy, got $other")
    }
    release.countDown()
    // the live run was not disturbed: its call's own result reached the model
    run.await().value.answer shouldBe Some("finished")
    results(client.sent.last) shouldBe Vector("s1" -> "slow")
  }

  it should "refuse a blank reason and another tenant, leaving the thread to recover" in {
    val client   = ScriptedLLMClient.of(calling(call("x1", "flaky")), CompletionFixture.simple("never"))
    val flakyRan = new AtomicInteger()
    val agent    = failing(client, new InMemoryCheckpointer, new AtomicInteger(), flakyRan)
    val thread   = ThreadId("refused")
    val mine     = RunConfig().withTenantId(TenantId("mine"))
    agent.run(thread, "go", mine).isLeft shouldBe true

    agent.abandon(thread, "  ", mine) match {
      case Left(invalid: ValidationError) => invalid.message should include("reason")
      case other                          => fail(s"expected a ValidationError, got $other")
    }
    agent.abandon(thread, config = RunConfig().withTenantId(TenantId("theirs"))) match {
      case Left(_: GraphError.TenantMismatch) => ()
      case other                              => fail(s"expected TenantMismatch, got $other")
    }
    // still incomplete: recover runs the call again
    agent.recover(thread, mine).isLeft shouldBe true
    flakyRan.get shouldBe 2
  }
}
