package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.GraphError
import org.llm4s.agent.graph.sqlite.LocksFixture.*
import org.llm4s.llmconnect.model.ToolMessage
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * C2's `tool-queue` key across the C5 compatibility rules (design §4.29), against `locks-threads.db`, written by `main`
 * after C2 and before #1934: adopting locks only adds the key, so threads written without locks restore; dropping
 * locks while the key holds queued calls removes a key holding a value, so it is refused rather than stranding them.
 */
class SqliteLocksEvolutionSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  "A thread written without locks" should "continue at rest under an agent that adopted locks" in {
    val store = opened()
    store.latest(AtRest).value.value.checkpoint.snapshot.state.keySet should not contain "tool-queue"
    val next = agent(Scripted(calling("c3"), answer("second")), store, locked = true).run(AtRest, "again").value
    next.answer shouldBe Some("second")
    next.messages.collect { case t: ToolMessage => t.content }.last shouldBe "2"
    store.close()
  }

  it should "resume a suspended turn under an agent that adopted locks" in {
    val store = opened()
    store.latest(Parked).value.value.checkpoint.snapshot.shape shouldBe None
    val resumed = agent(Scripted(answer("done")), store, locked = true).resume(Parked, approvals(store, Parked))
    resumed.value.answer shouldBe Some("done")
    store.close()
  }

  "A thread with calls queued behind a lock" should "be refused by an agent that dropped its locks, and left as it was" in {
    val store = opened()
    store.latest(Queued).value.value.checkpoint.snapshot.state.keySet should contain("tool-queue")
    val before  = store.latest(Queued).value.value.checkpoint.id
    val answers = approvals(store, Queued)
    answers should have size 1
    val refused = agent(Scripted(answer("never")), store, locked = false).resume(Queued, answers).left.value
    refused shouldBe a[GraphError.RestoreRejected]
    refused.message should include("tool-queue")
    store.latest(Queued).value.value.checkpoint.id shouldBe before

    // under its own locks the queue drains in order: c1 once approved, then c2 behind it
    val done = agent(Scripted(answer("done")), store, locked = true).resume(Queued, answers).value
    done.answer shouldBe Some("done")
    done.messages.collect { case t: ToolMessage => t.content } shouldBe Vector("1", "2")
    store.close()
  }
}
