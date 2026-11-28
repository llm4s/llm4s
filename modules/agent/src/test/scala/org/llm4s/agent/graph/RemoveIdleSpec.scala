package org.llm4s.agent.graph

import org.llm4s.testutil.TestTimeLimit
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

/**
 * The sweep's removal of an idle thread (#3260) judges the thread again under its own claim: a metadata update that
 * landed after the first read and before the claim - it changes no checkpoint - keeps the thread.
 */
class RemoveIdleSpec extends AnyFlatSpec with TestTimeLimit with Matchers with EitherValues {

  "An idle thread updated just before the sweep's claim" should "be kept, its new metadata with it (#3260)" in {
    val store  = InMemoryCheckpointer()
    val thread = ThreadId("updated-before-claim")
    val claim  = store.claim(thread, ClaimRequest(RunId("r"), None, 30.seconds)).value
    val checkpoint = Checkpoint(
      Checkpoint.CurrentFormat,
      "c1",
      None,
      thread.value,
      "r",
      CheckpointStatus.Completed,
      Instant.now(),
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false),
      None,
      Some(claim.token)
    )
    store.commit(thread, Commit(claim.token, Some(checkpoint))).value
    store.release(thread, claim.token).value
    val judgedAt = Instant.now()
    // the first read sees a stale update time; by the claim, a metadata update has landed
    var reads = 0
    def updatedAt(): org.llm4s.types.Result[Option[Instant]] = {
      reads += 1
      if (reads == 1) Right(Some(Instant.EPOCH))
      else {
        Right(Some(Instant.now()))
      }
    }
    RetentionSweep.removeIdle(store, thread, judgedAt, () => updatedAt(), Map.empty) shouldBe false
    store.latest(thread).value shouldBe defined
    reads shouldBe 2
    // read idle both times: removed
    RetentionSweep.removeIdle(store, thread, Instant.now(), () => Right(Some(Instant.EPOCH)), Map.empty) shouldBe true
    store.latest(thread).value shouldBe None
  }
}
