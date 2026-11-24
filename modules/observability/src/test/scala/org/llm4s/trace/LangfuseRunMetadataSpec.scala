package org.llm4s.trace

import org.llm4s.llmconnect.model.UsageSummary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The trace metadata an ended agent run becomes: a delegated child's names its parent (design 4.47). */
class LangfuseRunMetadataSpec extends AnyFlatSpec with Matchers {

  private def run(parentThread: Option[String], parentRun: Option[String]) =
    TraceEvent.AgentRunEnded(
      "thread-1",
      "run-1",
      "researcher",
      TraceEvent.RunStatus.Completed,
      Seq.empty,
      UsageSummary(),
      parentThreadId = parentThread,
      parentRunId = parentRun
    )

  "LangfuseTracing.runMetadata" should "carry a child run's parent thread and run" in {
    val metadata = LangfuseTracing.runMetadata(run(Some("parent"), Some("parent-run")))
    metadata("parent_thread_id").str shouldBe "parent"
    metadata("parent_run_id").str shouldBe "parent-run"
    metadata("agent").str shouldBe "researcher"
  }

  it should "carry no parent ids for a top-level run" in {
    val metadata = LangfuseTracing.runMetadata(run(None, None))
    metadata.obj.contains("parent_thread_id") shouldBe false
    metadata.obj.contains("parent_run_id") shouldBe false
  }

  it should "name a blocked run's guardrail beside its status, and the agent active at the end (#2829)" in {
    val blocked = run(None, None)
      .withStatus(TraceEvent.RunStatus.Blocked)
      .withBlockedBy("pii")
      .withActiveAgent("billing")
    val metadata = LangfuseTracing.runMetadata(blocked)
    metadata("status").str shouldBe "blocked"
    metadata("blocked_by").str shouldBe "pii"
    metadata("agent").str shouldBe "researcher"
    metadata("active_agent").str shouldBe "billing"
    LangfuseTracing.runMetadata(run(None, None)).obj.keySet should contain noneOf ("blocked_by", "active_agent")
  }

  "AgentRunEnded.toJson" should "name the parent only for a child run" in {
    run(Some("parent"), Some("parent-run")).toJson("parent_run_id").str shouldBe "parent-run"
    run(None, None).toJson.obj.contains("parent_thread_id") shouldBe false
  }
}
