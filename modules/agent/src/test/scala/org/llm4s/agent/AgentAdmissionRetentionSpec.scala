package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph._
import org.llm4s.error.ProcessingError
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._

/**
 * A run's retention is applied as it is admitted (#3254): only once its claim commit has landed, so a start refused
 * after its store claim - the commit fails - has pruned nothing; and a run that is admitted keeps what it kept before.
 */
class AgentAdmissionRetentionSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private val keepOne = RetentionPolicy(maxCheckpoints = Some(1))

  private def agentOver(store: Checkpointer, answers: Int) =
    built(
      Agent
        .builder("assistant", ScriptedLLMClient.of((1 to answers).map(n => CompletionFixture.simple(s"answer $n"))*))
        .withRuntime(new GraphRuntime(store))
    )

  private def checkpoints(store: Checkpointer, thread: ThreadId): Int =
    store.history(thread, None, 1000).fold(e => fail(e.message), identity).size

  "A start whose claim commit fails" should "prune nothing of the thread (#3254)" in {
    val failing = new AtomicBoolean(false)
    val raw     = new InMemoryCheckpointer
    val store = new ForwardingCheckpointer(raw) {
      override def commit(threadId: ThreadId, commit: Commit) =
        if (failing.get) Left(ProcessingError("commit", "the store is down")) else super.commit(threadId, commit)
    }
    val agent  = agentOver(store, 4)
    val thread = ThreadId("refused-start")
    (1 to 3).foreach(n => agent.run(thread, s"turn $n").value)
    val before = checkpoints(raw, thread)
    before should be > 1
    failing.set(true)
    agent.run(thread, "refused", RunConfig().withRetention(Some(keepOne))).isLeft shouldBe true
    checkpoints(raw, thread) shouldBe before
  }

  "An admitted start with a maxAge" should "keep the previous latest checkpoint, older than maxAge, by its run id (#3254)" in {
    val store  = new InMemoryCheckpointer
    val agent  = agentOver(store, 2)
    val thread = ThreadId("aged-previous")
    agent.run(thread, "turn 1").value
    val previous = store.latest(thread).fold(e => fail(e.message), identity).get.checkpoint
    Thread.sleep(30)
    agent.run(thread, "aged", RunConfig().withRetention(Some(RetentionPolicy(maxAge = Some(1.millisecond))))).value
    // a prune before the claim commit kept it as the thread's latest: so does the prune after it
    store.latestOfRuns(thread, Set(RunId(previous.runId))).fold(e => fail(e.message), identity).map(_.id) shouldBe
      Some(previous.id)
  }

  "An admitted start" should "keep, by its retention, what it kept when it pruned before its claim commit (#3254)" in {
    val store  = new InMemoryCheckpointer
    val agent  = agentOver(store, 4)
    val thread = ThreadId("admitted-start")
    (1 to 3).foreach(n => agent.run(thread, s"turn $n").value)
    val lastRun = store.latest(thread).fold(e => fail(e.message), identity).get.checkpoint.runId
    agent.run(thread, "kept", RunConfig().withRetention(Some(keepOne))).value
    val after = store.history(thread, None, 1000).fold(e => fail(e.message), identity)
    // one checkpoint from before the run - the previous turn's last - and the run's own
    after.map(_.runId).distinct.size shouldBe 2
    after.map(_.runId) should contain(lastRun)
    after.count(_.runId == lastRun) shouldBe 1
  }
}
