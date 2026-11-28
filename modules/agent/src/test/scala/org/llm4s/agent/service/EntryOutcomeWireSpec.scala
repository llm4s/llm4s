package org.llm4s.agent.service

import org.llm4s.agent.graph.CheckpointStatus
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The stored form of an entry's outcome (`outcome_json` in the SQLite and PostgreSQL queues). Pinned against the code
 * before #3255 added the blocked and output-invalid reasons: every outcome stored before reads back as it was written,
 * and is written the same way.
 */
class EntryOutcomeWireSpec extends AnyFlatSpec with TestTimeLimit with Matchers with EitherValues {

  private val pinned: Vector[(EntryOutcome, String)] = Vector(
    EntryOutcome.Done(Some("c1"), Some(CheckpointStatus.Completed)) ->
      """{"state":"done","checkpointId":"c1","status":"Completed"}""",
    EntryOutcome.Done(None, None) -> """{"state":"done"}""",
    EntryOutcome.Failed(FailureReason.AttemptsExhausted(5)) ->
      """{"state":"failed","reason":"attemptsExhausted","attempts":5}""",
    EntryOutcome.Failed(FailureReason.RunFailed("boom")) ->
      """{"state":"failed","reason":"runFailed","message":"boom"}""",
    EntryOutcome.Failed(FailureReason.Unrunnable("no worker")) ->
      """{"state":"failed","reason":"unrunnable","message":"no worker"}""",
    EntryOutcome.Failed(FailureReason.Abandoned)       -> """{"state":"failed","reason":"abandoned"}""",
    EntryOutcome.Cancelled(CancelReason.ThreadDeleted) -> """{"state":"cancelled","reason":"threadDeleted"}""",
    EntryOutcome.Cancelled(CancelReason.Requested(Some("stop"))) ->
      """{"state":"cancelled","reason":"requested","note":"stop"}""",
    EntryOutcome.Interrupted(Some(EntryId.unsafe(7))) -> """{"state":"interrupted","by":7}"""
  )

  "A blocked or output-invalid outcome" should "round-trip in its own stored form (#3255)" in {
    val blocked = EntryOutcome.Failed(FailureReason.Blocked("NoBoom"))
    ujson.write(EntryOutcome.toJson(blocked)) shouldBe """{"state":"failed","reason":"blocked","guardrail":"NoBoom"}"""
    EntryOutcome.fromJson(EntryOutcome.toJson(blocked)).value shouldBe blocked
    val invalid = EntryOutcome.Failed(FailureReason.OutputInvalid(Vector("a", "b")))
    ujson.write(
      EntryOutcome.toJson(invalid)
    ) shouldBe """{"state":"failed","reason":"outputInvalid","errors":["a","b"]}"""
    EntryOutcome.fromJson(EntryOutcome.toJson(invalid)).value shouldBe invalid
  }

  "A stored entry outcome" should "keep the wire format it had before #3255" in {
    pinned.foreach { case (outcome, json) =>
      ujson.write(EntryOutcome.toJson(outcome)) shouldBe json
      EntryOutcome.fromJson(ujson.read(json)).value shouldBe outcome
    }
  }
}
