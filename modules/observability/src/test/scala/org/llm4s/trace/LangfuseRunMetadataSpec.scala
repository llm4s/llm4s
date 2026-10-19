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
      "completed",
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

  "AgentRunEnded.toJson" should "name the parent only for a child run" in {
    run(Some("parent"), Some("parent-run")).toJson("parent_run_id").str shouldBe "parent-run"
    run(None, None).toJson.obj.contains("parent_thread_id") shouldBe false
  }
}
