package org.llm4s.javaapi

import org.llm4s.agent.{ AgentResult, AgentStatus }
import org.llm4s.agent.graph.{ GraphError, ThreadId }
import org.llm4s.error.{ CancelledError, NetworkError, ValidationError }
import org.llm4s.llmconnect.model.ToolMessage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

import StreamFixtures.*
import SuspensionFixtures.{ call, calling, scripted }

/**
 * [[JAgent.pending]], [[JAgent.resume]] and [[JAgent.recover]] over the real agent runtime: only the
 * model is scripted; see [[SuspensionFixtures]] for the tools.
 */
class JAgentPendingSpec extends AnyFlatSpec with Matchers {

  private val ran = new CopyOnWriteArrayList[String]()

  private def agentOver(client: Scripted): JAgent = SuspensionFixtures.agentOver(client, ran)

  private def pendingOf(result: AgentResult): List[PendingInterrupt] = JAgent.pending(result).asScala.toList

  "JAgent.pending" should "list a suspended turn's approval as Java values, and resume completes it" in {
    ran.clear()
    val agent = agentOver(scripted(Right(calling(call("c1", "deploy", "prod"))), Right(completion("shipped"))))
    val first = agent.run("deploy").get()
    first.status shouldBe a[AgentStatus.Suspended]
    val List(p) = pendingOf(first): @unchecked
    p.kind shouldBe InterruptKind.APPROVAL
    p.toolName shouldBe "deploy"
    p.argumentsJson shouldBe """{"text":"prod"}"""
    p.reason() shouldBe java.util.Optional.of("deploying prod")
    p.questionJson() shouldBe java.util.Optional.empty()
    // the id is the turn's own
    first.status match {
      case AgentStatus.Suspended(approvals, _) => p.id shouldBe approvals.head._1.value
      case other                               => fail(s"expected Suspended, got $other")
    }
    ran.asScala shouldBe empty

    val done = agent.resume(first.threadId, java.util.List.of(Answer.approve(p.id))).get()
    done.answer shouldBe Some("shipped")
    ran.asScala.toList shouldBe List("prod")
    JAgent.pending(done) shouldBe empty
  }

  it should "list a tool's question with its JSON, and a reply resumes the call" in {
    val agent   = agentOver(scripted(Right(calling(call("c1", "confirm", "go"))), Right(completion("confirmed"))))
    val first   = agent.run("ask me").get()
    val List(p) = pendingOf(first): @unchecked
    p.kind shouldBe InterruptKind.QUESTION
    p.toolName shouldBe "confirm"
    p.argumentsJson shouldBe """{"text":"go"}"""
    p.questionJson() shouldBe java.util.Optional.of("""{"prompt":"really go?"}""")
    p.reason() shouldBe java.util.Optional.empty()

    val done = agent.resume(first.threadId, java.util.List.of(Answer.reply(p.id, """{"ok":true}"""))).get()
    done.answer shouldBe Some("confirmed")
    done.messages.collect { case t: ToolMessage => t.content }.head should include("really go? true")
  }

  it should "list approvals before questions, and a partial resume leaves the rest pending" in {
    ran.clear()
    val agent = agentOver(
      scripted(
        Right(calling(call("c1", "confirm", "go"), call("c2", "deploy", "one"), call("c3", "deploy", "two"))),
        Right(completion("all done"))
      )
    )
    val first   = agent.run("everything").get()
    val pending = pendingOf(first)
    pending.map(_.kind) shouldBe List(InterruptKind.APPROVAL, InterruptKind.APPROVAL, InterruptKind.QUESTION)
    pending.map(_.argumentsJson) shouldBe List("""{"text":"one"}""", """{"text":"two"}""", """{"text":"go"}""")
    pending.map(_.id).distinct should have size 3

    // answer one approval only: the other approval and the question stay pending
    val partial = agent.resume(first.threadId, java.util.List.of(Answer.approve(pending.head.id))).get()
    partial.status shouldBe a[AgentStatus.Suspended]
    partial.answer shouldBe None
    pendingOf(partial) shouldBe pending.tail

    val answers =
      java.util.List.of(Answer.reject(pending(1).id, "not today"), Answer.reply(pending(2).id, """{"ok":false}"""))
    val done = agent.resume(first.threadId, answers).get()
    done.answer shouldBe Some("all done")
    ran.asScala.toList shouldBe List("one")
    val results = done.messages.collect { case t: ToolMessage => t.content }
    results.exists(_.contains("Rejected: not today")) shouldBe true
    results.exists(_.contains("really go? false")) shouldBe true
  }

  it should "be empty for a completed turn and for a null result, and unmodifiable" in {
    val done = agentOver(scripted(Right(completion("hi")))).run("hi").get()
    JAgent.pending(done) shouldBe empty
    JAgent.pending(null) shouldBe empty
    val suspended = agentOver(scripted(Right(calling(call("c1", "deploy", "x"))))).run("go").get()
    val list      = JAgent.pending(suspended)
    an[UnsupportedOperationException] should be thrownBy list.clear()
    (list.getClass.getName should not).startWith("scala.")
  }

  "PendingInterrupt" should "be a value: equal by every field, with a readable toString" in {
    val agent = agentOver(scripted(Right(calling(call("c1", "deploy", "prod")))))
    val first = agent.run("deploy").get()
    val p     = JAgent.pending(first).get(0)
    val again = JAgent.pending(first).get(0)
    p shouldBe again
    p.hashCode shouldBe again.hashCode
    p should not be "a string"
    val other =
      JAgent.pending(agentOver(scripted(Right(calling(call("c1", "deploy", "staging"))))).run("x").get()).get(0)
    p should not be other
    p.toString shouldBe s"""PendingInterrupt(APPROVAL ${p.id}: deploy {"text":"prod"})"""
  }

  "InterruptKind" should "be a Java enum" in {
    classOf[InterruptKind].isEnum shouldBe true
    InterruptKind.valueOf("QUESTION") shouldBe InterruptKind.QUESTION
    InterruptKind.values.toList shouldBe List(InterruptKind.APPROVAL, InterruptKind.QUESTION)
  }

  "JAgent.resume" should "fail for a thread that is not suspended, an unknown thread, or an id the thread does not wait for" in {
    val agent = agentOver(scripted(Right(calling(call("c1", "deploy", "x"))), Right(completion("ok"))))
    val first = agent.run("go").get()
    val id    = JAgent.pending(first).get(0).id

    val unknownId = agent.resume(first.threadId, java.util.List.of(Answer.approve("no-such-interrupt")))
    unknownId.isFailure shouldBe true
    unknownId.getError().error shouldBe a[GraphError]
    // still suspended, and the real answer still works
    agent.resume(first.threadId, java.util.List.of(Answer.approve(id))).get().answer shouldBe Some("ok")

    val again = agent.resume(first.threadId, java.util.List.of(Answer.approve(id)))
    again.isFailure shouldBe true
    again.getError().error shouldBe a[GraphError]

    val unknownThread = agent.resume(ThreadId("no-such-thread"), java.util.List.of(Answer.approve(id)))
    unknownThread.isFailure shouldBe true
    unknownThread.getError().error shouldBe a[GraphError]
  }

  it should "refuse null arguments and malformed answers before resuming anything" in {
    val agent = agentOver(scripted(Right(calling(call("c1", "deploy", "x"))), Right(completion("ok"))))
    val first = agent.run("go").get()
    agent.resume(null.asInstanceOf[ThreadId], java.util.List.of()).getError().error shouldBe a[ValidationError]
    agent.resume(first.threadId, null).getError().error shouldBe a[ValidationError]
    agent.resume(first.threadId, java.util.Arrays.asList(null)).getError().error shouldBe a[ValidationError]
    val id = JAgent.pending(first).get(0).id
    agent.resume(first.threadId, java.util.List.of(Answer.edit(id, "{not json"))).getError().getMessage should
      include("not valid JSON")
    // nothing was resumed: the approval is still pending
    JAgent.pending(first).get(0).id shouldBe id
    agent.resume(first.threadId, java.util.List.of(Answer.approve(id))).get().answer shouldBe Some("ok")
  }

  it should "fail every call of an agent that did not build" in {
    val broken = new JAgent(Left(ValidationError("agent", "broken")))
    broken.resume(ThreadId("t"), java.util.List.of()).getError().getMessage should include("broken")
    broken.recover(ThreadId("t")).getError().getMessage should include("broken")
  }

  "JAgent.recover" should "continue a resumed turn that failed, re-running only what did not finish" in {
    ran.clear()
    val down  = NetworkError("down", None, "http://x")
    val agent = agentOver(scripted(Right(calling(call("c1", "deploy", "prod"))), Left(down), Right(completion("back"))))
    val first = agent.run("deploy").get()
    val failed = agent.resume(first.threadId, java.util.List.of(Answer.approve(JAgent.pending(first).get(0).id)))
    failed.isFailure shouldBe true
    ran.asScala.toList shouldBe List("prod")

    val recovered = agent.recover(first.threadId).get()
    recovered.answer shouldBe Some("back")
    ran.asScala.toList shouldBe List("prod") // the approved call is not run again
  }

  it should "continue a turn whose next run failed on the provider" in {
    val down  = NetworkError("down", None, "http://x")
    val agent = agentOver(scripted(Right(completion("hello")), Left(down), Right(completion("again"))))
    val first = agent.run("hi").get()
    agent.continueConversation(first, "more").isFailure shouldBe true
    agent.recover(first.threadId).get().answer shouldBe Some("again")
  }

  it should "continue a cancelled stream" in {
    val model    = parksOnce()
    val agent    = jAgentOf(model.client)(_.withStreaming())
    val threadId = ThreadId("recover-cancelled")
    val stream   = agent.stream(threadId, "hi", Recorder()).get()
    model.parked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    stream.cancel()
    stream.await().isFailure shouldBe true
    agent.recover(threadId).get().answer shouldBe Some("recovered")
  }

  it should "fail for a thread with nothing to recover, and refuse a null thread id" in {
    val agent = agentOver(scripted(Right(completion("hi"))))
    val first = agent.run("hi").get()
    agent.recover(first.threadId).getError().error shouldBe a[GraphError]
    agent.recover(ThreadId("no-such-thread")).isFailure shouldBe true
    agent.recover(null.asInstanceOf[ThreadId]).getError().error shouldBe a[ValidationError]
  }

  "JAgent.resume and recover" should "return a CancelledError to an interrupted caller, leaving the turn going" in {
    val release = new CountDownLatch(1)
    val calls   = new AtomicInteger(0)
    val slow = new Scripted(
      _ => Right(completion("x")),
      () =>
        if (calls.getAndIncrement() == 0) Right(calling(call("c1", "deploy", "x")))
        else {
          release.await(DeadlineSeconds, TimeUnit.SECONDS)
          Right(completion("late"))
        }
    )
    val agent = agentOver(slow)
    val first = agent.run("go").get()
    Thread.currentThread().interrupt()
    val interrupted = agent.resume(first.threadId, java.util.List.of(Answer.approve(JAgent.pending(first).get(0).id)))
    Thread.interrupted() shouldBe true // the flag is still set, and cleared here
    interrupted.getError().error shouldBe a[CancelledError]
    release.countDown()
    // the turn carried on and completed: once it has, the thread has nothing to recover
    awaitCondition(agent.recover(first.threadId).getError().error.isInstanceOf[GraphError.NothingToRecover]) shouldBe
      true
  }
}
