package org.llm4s.agent.graph

import org.llm4s.llmconnect.model.UsageSummary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/**
 * The stored form of a [[RunEvent]] (#2781). The cases that record facts about a run hold a `...Details` class, but
 * a thread's event log - in the SQLite and PostgreSQL checkpointers, and in every log already written - holds each
 * event flat, its fields beside its `$type`. This pins that JSON, byte for byte, in both directions.
 */
class RunEventWireSpec extends AnyFlatSpec with Matchers {

  private val usage     = UsageSummary()
  private val usageJson = write(usage)

  private val stored: Seq[(RunEvent, String)] = Seq(
    RunEvent.RunStarted(RunStartedDetails(Some("t"), Some("p"))) ->
      """{"$type":"RunStarted","tenantId":"t","principal":"p"}""",
    RunEvent.RunStarted(RunStartedDetails()) -> """{"$type":"RunStarted","tenantId":null,"principal":null}""",
    RunEvent.RunRecovered(RunRecoveredDetails("c1", Some("t"), None)) ->
      """{"$type":"RunRecovered","fromCheckpoint":"c1","tenantId":"t","principal":null}""",
    RunEvent.RunRecovered(RunRecoveredDetails("c1", None, Some("p"), Some("r0"))) ->
      """{"$type":"RunRecovered","fromCheckpoint":"c1","tenantId":null,"principal":"p","takenOverFrom":"r0"}""",
    RunEvent.TaskCompleted             -> """"TaskCompleted"""",
    RunEvent.TaskFailed("boom")        -> """{"$type":"TaskFailed","message":"boom"}""",
    RunEvent.TaskSuspended("i1")       -> """{"$type":"TaskSuspended","interruptId":"i1"}""",
    RunEvent.CheckpointCommitted(3)    -> """{"$type":"CheckpointCommitted","superstep":3}""",
    RunEvent.RunCompleted              -> """"RunCompleted"""",
    RunEvent.RunSuspended(Vector("i")) -> """{"$type":"RunSuspended","interrupts":["i"]}""",
    RunEvent.RunResumed(RunResumedDetails(Vector("i"), Some("t"), Some("p"))) ->
      """{"$type":"RunResumed","answered":["i"],"tenantId":"t","principal":"p"}""",
    RunEvent.RunFailed(RunFailedDetails("boom")) -> """{"$type":"RunFailed","message":"boom"}""",
    RunEvent.RunFailed(RunFailedDetails("boom", Some(usage), 2L)) ->
      s"""{"$$type":"RunFailed","message":"boom","usage":$usageJson,"steps":2}""",
    RunEvent.RunCancelled(RunStoppedDetails()) -> """{"$type":"RunCancelled"}""",
    RunEvent.RunCancelled(RunStoppedDetails(Some(usage), 4)) ->
      s"""{"$$type":"RunCancelled","usage":$usageJson,"steps":4}""",
    RunEvent.RunTimedOut(RunStoppedDetails())        -> """{"$type":"RunTimedOut"}""",
    RunEvent.RunTimedOut(RunStoppedDetails(None, 1)) -> """{"$type":"RunTimedOut","steps":1}""",
    RunEvent.StateUpdated(StateUpdatedDetails("c1", Some("n"), Some("t"), None)) ->
      """{"$type":"StateUpdated","fromCheckpoint":"c1","asNode":"n","tenantId":"t","principal":null}""",
    RunEvent.ThreadForked(ThreadForkedDetails("src", "c1", None, Some("p"))) ->
      """{"$type":"ThreadForked","fromThread":"src","fromCheckpoint":"c1","tenantId":null,"principal":"p"}""",
    RunEvent.Custom("name", 2, ujson.Obj("k" -> 1)) ->
      """{"$type":"Custom","name":"name","version":2,"payload":{"k":1}}"""
  )

  "A RunEvent" should "be written flat, its details' fields beside its $type" in
    stored.foreach { case (event, json) => write(event) shouldBe json }

  it should "read back from its stored form" in
    stored.foreach { case (event, json) => read[RunEvent](json) shouldBe event }

  it should "read a RunCancelled or RunTimedOut stored before they carried usage" in {
    read[RunEvent](""""RunCancelled"""") shouldBe RunEvent.RunCancelled(RunStoppedDetails.empty)
    read[RunEvent](""""RunTimedOut"""") shouldBe RunEvent.RunTimedOut(RunStoppedDetails.empty)
  }

  it should "read a RunRecovered stored before takenOverFrom, and a RunFailed stored before usage and steps" in {
    read[RunEvent]("""{"$type":"RunRecovered","fromCheckpoint":"c1","tenantId":null,"principal":null}""") shouldBe
      RunEvent.RunRecovered(RunRecoveredDetails("c1"))
    read[RunEvent]("""{"$type":"RunFailed","message":"boom"}""") shouldBe RunEvent.RunFailed(RunFailedDetails("boom"))
  }

  "An EventRecord" should "store its event flat" in {
    val record = EventRecord(
      "th",
      7L,
      "r1",
      FencingToken(3),
      Some("c1"),
      None,
      None,
      java.time.Instant.parse("2026-10-02T12:00:00Z"),
      RunEvent.RunFailed(RunFailedDetails("boom", None, 1L))
    )
    val json = ujson.read(write(record))
    json("event") shouldBe ujson.Obj("$type" -> "RunFailed", "message" -> "boom", "steps" -> 1)
    read[EventRecord](write(record)) shouldBe record
  }
}
