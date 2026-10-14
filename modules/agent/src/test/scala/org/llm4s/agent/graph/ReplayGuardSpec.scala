package org.llm4s.agent.graph

import org.llm4s.error.{ CancelledError, ProcessingError }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

/**
 * [[ReplayGuard]] on its own: each support class against a journal kept across "runs" of one task, every
 * row of the crash table, bounds, decoding and ownership (design §4.24). The runtime and the stores are
 * covered by `EffectReplayRuntimeSpec`, `CheckpointerContract` and `ReplayContract`.
 */
class ReplayGuardSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  /** A journal that outlives a run, as the thread's store does, and can die before or after a write. */
  private class Journal extends EffectJournal {
    val records                   = mutable.Map.empty[String, EffectRecord]
    var failNext: Option[Boolean] = None // Some(true): fail after writing; Some(false): fail without writing
    def lookup(key: EffectKey)    = Right(records.get(key.value))
    def record(key: EffectKey)    = records.get(key.value)
    def write(record: EffectRecord): Result[Unit] = failNext match {
      case Some(afterWriting) =>
        failNext = None
        if afterWriting then records.update(record.key.value, record)
        Left(ProcessingError("journal", "down"))
      case None =>
        records.update(record.key.value, record)
        Right(())
    }
  }

  private def context(journal: EffectJournal, task: String = "1.0", owner: Option[String] = None): RunContext =
    new RunContext(
      RunConfig(),
      RunPosition(ThreadId("t"), RunId("r"), "r/1", TaskId(task), NodeId("n"), 1),
      NodeEventSink.none,
      journal = journal,
      owner = owner.map(TaskId(_))
    )

  /** A counter store whose increments record their key, atomically. */
  final private class Counters extends AppliedKeys {
    var value     = 0
    val applied   = mutable.Map.empty[String, Int]
    val forgotten = mutable.Buffer.empty[String]
    val tokens    = mutable.Buffer.empty[Long]
    def forget(key: EffectKey, token: FencingToken) = {
      tokens += token.value; forgotten += key.value; applied.remove(key.value); Right(())
    }
    def add(key: EffectKey)     = Right(applied.getOrElseUpdate(key.value, { value += 1; value }))
    def addFailing: Result[Int] = Left(ProcessingError("store", "down"))
  }

  private def refusal(result: Result[?]): String = result.left.value match {
    case r: GraphError.EffectReplayRefused => r.reason
    case other                             => fail(s"not a refusal: $other")
  }

  "ReplayGuard.transactional" should "record the outcome, forget the applied key, and replay the outcome without the effect" in {
    val journal = new Journal
    val store   = new Counters
    val c       = context(journal)
    val key     = c.effectKey("counter.add", "a")
    ReplayGuard.transactional[Int](c, key, store)(_ => store.add(key)).value shouldBe 1
    store.applied shouldBe empty
    journal.record(key).value.completed shouldBe true
    ReplayGuard.transactional[Int](c, key, store)(_ => store.add(key)).value shouldBe 1
    store.value shouldBe 1
  }

  it should "answer a replay after the effect and before its outcome from the target's applied key" in {
    val journal = new Journal
    val store   = new Counters
    val c       = context(journal)
    val key     = c.effectKey("counter.add", "a")
    // the second write - the outcome - fails: the effect ran, its outcome is not recorded
    val crashing = new Journal {
      private val writes = new AtomicInteger()
      override def write(record: EffectRecord) =
        if writes.incrementAndGet() == 2 then Left(ProcessingError("journal", "down")) else super.write(record)
    }
    val first = context(crashing)
    ReplayGuard.transactional[Int](first, key, store)(_ => store.add(key)).isLeft shouldBe true
    store.applied.keySet shouldBe Set(key.value)
    // the replay finds the begun record, so it trusts the applied key: no second increment
    ReplayGuard.transactional[Int](context(crashing), key, store)(_ => store.add(key)).value shouldBe 1
    store.value shouldBe 1
    store.applied shouldBe empty
    crashing.record(key).value.completed shouldBe true
    journal.records shouldBe empty
  }

  it should "hand the run's claim token to the target's every operation, and fail when the target refuses it" in {
    val journal = new Journal
    val store   = new Counters
    val c = new RunContext(
      RunConfig(),
      RunPosition(ThreadId("t"), RunId("r"), "r/1", TaskId("1.0"), NodeId("n"), 1, Some(FencingToken(42L))),
      NodeEventSink.none,
      journal = journal
    )
    val key  = c.effectKey("counter.add", "a")
    val seen = mutable.Buffer.empty[Long]
    ReplayGuard
      .transactional[Int](c, key, store) { token =>
        seen += token.value; store.add(key)
      }
      .value shouldBe 1
    seen shouldBe Seq(42L)
    store.tokens shouldBe Seq(42L, 42L) // the stale-leftover forget before, and the forget after
    key.scope shouldBe c.effectKey("other").scope
    // a target that refuses the token - a successor has touched it - changes nothing, and the task fails
    val other = c.effectKey("counter.add", "b")
    ReplayGuard
      .transactional[Int](c, other, store)(_ => Left(ProcessingError("store", "stale token")))
      .left
      .value shouldBe a[ProcessingError]
    journal.record(other).value.completed shouldBe false
  }

  it should "forget a stale applied key with no begun record before the effect, never trusting it" in {
    val journal = new Journal
    val store   = new Counters
    val c       = context(journal)
    val key     = c.effectKey("counter.add", "a")
    // a leftover of a crashed task whose records were released: a re-created thread, or a replanned task id
    store.applied.update(key.value, 41)
    ReplayGuard.transactional[Int](c, key, store)(_ => store.add(key)).value shouldBe 1
    store.value shouldBe 1
  }

  it should "leave a failed effect begun, so its replay runs it again" in {
    val journal = new Journal
    val store   = new Counters
    val c       = context(journal)
    val key     = c.effectKey("counter.add", "a")
    ReplayGuard.transactional[Int](c, key, store)(_ => store.addFailing).left.value shouldBe a[ProcessingError]
    journal.record(key).value.completed shouldBe false
    ReplayGuard.transactional[Int](c, key, store)(_ => store.add(key)).value shouldBe 1
  }

  "ReplayGuard.verifiable" should "run the plan only when the effect must run, and replay the outcome" in {
    val journal = new Journal
    var cell    = "a"
    val plans   = new AtomicInteger()
    def append(c: RunContext) =
      ReplayGuard.verifiable[String](c, c.effectKey("text.append", "cell"))(Right(StateCheck.of(cell))) {
        plans.incrementAndGet()
        // an edit's own check against the current state, which a replay must never repeat
        if !cell.endsWith("a") then Left(ProcessingError("edit", "old not found"))
        else
          val next = cell + "!"
          Right(ReplayGuard.Plan(StateCheck.of(cell), StateCheck.of(next), s"now $next") {
            cell = next; Right(s"now $next")
          })
      }
    append(context(journal)).value shouldBe "now a!"
    append(context(journal)).value shouldBe "now a!"
    plans.get shouldBe 1
    cell shouldBe "a!"
  }

  it should "return the presumed outcome of an effect that landed before its outcome was written" in {
    var cell = "a"
    val journal = new Journal {
      private val writes = new AtomicInteger()
      override def write(record: EffectRecord) =
        if writes.incrementAndGet() == 2 then Left(ProcessingError("journal", "down")) else super.write(record)
    }
    def append(c: RunContext) =
      ReplayGuard.verifiable[String](c, c.effectKey("text.append", "cell"))(Right(StateCheck.of(cell))) {
        val next = cell + "!"
        Right(ReplayGuard.Plan(StateCheck.of(cell), StateCheck.of(next), s"now $next") {
          cell = next; Right(s"now $next")
        })
      }
    append(context(journal)).isLeft shouldBe true
    cell shouldBe "a!"
    append(context(journal)).value shouldBe "now a!"
    cell shouldBe "a!"
    journal.records.values.head.completed shouldBe true
  }

  it should "run again an effect whose intent was written but which did not land, and refuse a changed target" in {
    var cell    = "a"
    val journal = new Journal
    def append(c: RunContext) =
      ReplayGuard.verifiable[String](c, c.effectKey("text.append", "cell"))(Right(StateCheck.of(cell))) {
        val next = cell + "!"
        Right(ReplayGuard.Plan(StateCheck.of(cell), StateCheck.of(next), next) { cell = next; Right(next) })
      }
    journal.failNext = Some(true) // the intent lands, then the journal dies: the effect never runs
    append(context(journal)).isLeft shouldBe true
    cell shouldBe "a"
    append(context(journal)).value shouldBe "a!"

    val other = new Journal
    other.failNext = Some(true)
    cell = "b"
    append(context(other)).isLeft shouldBe true
    cell = "changed by another thread"
    val reason = refusal(append(context(other)))
    reason should include(StateCheck.of("changed by another thread").value)
    reason should include(StateCheck.of("b").value)
    reason should include(StateCheck.of("b!").value)
    cell shouldBe "changed by another thread"
  }

  it should "refuse a presumed outcome over the cap before the effect runs, recording nothing" in {
    val journal = new Journal
    val ran     = new AtomicInteger()
    val c       = context(journal)
    val result =
      ReplayGuard.verifiable[String](c, c.effectKey("big", "x"), maxOutcomeBytes = 16)(Right(StateCheck.absent)) {
        Right(ReplayGuard.Plan(StateCheck.absent, StateCheck.of("x"), "x" * 100) { ran.incrementAndGet(); Right("x") })
      }
    refusal(result) should include("over the 16-byte cap")
    ran.get shouldBe 0
    journal.records shouldBe empty
  }

  "ReplayGuard.unverifiable" should "refuse to run again an effect that may have run, unless it is idempotent" in {
    val journal = new Journal
    val ran     = new AtomicInteger()
    val c       = context(journal)
    val key     = c.effectKey("sandbox.execute", "ls")
    journal.failNext = Some(true)
    ReplayGuard.unverifiable[Int](c, key, idempotent = false)(Right(ran.incrementAndGet())).isLeft shouldBe true
    ran.get shouldBe 0
    refusal(ReplayGuard.unverifiable[Int](c, key, idempotent = false)(Right(ran.incrementAndGet()))) should include(
      "may already have run"
    )
    ran.get shouldBe 0
    ReplayGuard.unverifiable[Int](c, key, idempotent = true)(Right(ran.incrementAndGet())).value shouldBe 1
    ReplayGuard.unverifiable[Int](c, key, idempotent = false)(Right(ran.incrementAndGet())).value shouldBe 1
    ran.get shouldBe 1
  }

  it should "keep an oversized outcome's completion without the outcome, and refuse its replay rather than run it" in {
    val journal = new Journal
    val ran     = new AtomicInteger()
    val c       = context(journal)
    val key     = c.effectKey("sandbox.execute", "cat big")
    def run = ReplayGuard.unverifiable[String](c, key, idempotent = true, maxOutcomeBytes = 8) {
      ran.incrementAndGet()
      Right("y" * 100)
    }
    run.value shouldBe "y" * 100
    refusal(run) should include("102 bytes")
    ran.get shouldBe 1
  }

  "ReplayGuard" should "refuse an outcome that no longer decodes, and a record of another support class" in {
    val journal = new Journal
    val c       = context(journal)
    val key     = c.effectKey("op", "x")
    ReplayGuard.unverifiable[String](c, key, idempotent = false)(Right("text")).value shouldBe "text"
    refusal(ReplayGuard.unverifiable[Int](c, key, idempotent = false)(Right(1))) should include("does not decode")
    refusal(ReplayGuard.verifiable[String](c, key)(Right(StateCheck.absent))(Left(ProcessingError("x", "y")))) should
      include("Unverifiable")
    val begun = c.effectKey("op", "begun")
    journal.failNext = Some(true)
    ReplayGuard.unverifiable[String](c, begun, idempotent = false)(Right("t")).isLeft shouldBe true
    refusal(ReplayGuard.transactional[String](c, begun, new Counters)(_ => Right("t"))) should include("Transactional")
  }

  it should "not start an effect whose task was cancelled after its intent was written" in {
    val journal = new Journal
    val ran     = new AtomicInteger()
    val c       = context(journal)
    val outcome = new java.util.concurrent.atomic.AtomicReference[Result[Int]]()
    // on a thread of its own, interrupted as a cancelled task's is
    Thread
      .ofVirtual()
      .start { () =>
        Thread.currentThread().interrupt()
        outcome.set(
          ReplayGuard.unverifiable[Int](c, c.effectKey("op"), idempotent = false)(Right(ran.incrementAndGet()))
        )
      }
      .join()
    outcome.get.left.value shouldBe a[CancelledError]
    journal.record(c.effectKey("op")).value.completed shouldBe false
    ran.get shouldBe 0
  }

  it should "file a continuation's records under the task it stands in for, and keep the owner on completion" in {
    val journal = new Journal
    val key     = IdempotencyKey("call")
    val first   = context(journal, task = "2.0")
    val k       = first.effectKeyUnder(key, "op", Seq("x"))
    journal.failNext = Some(true)
    ReplayGuard.unverifiable[Int](first, k, idempotent = true)(Right(1)).isLeft shouldBe true
    journal.record(k).value.taskId shouldBe TaskId("2.0")
    // the call runs again after approval, in a continuation standing in for 2.0
    val continuation = context(journal, task = "4.1", owner = Some("2.0"))
    continuation.effectKeyUnder(key, "op", Seq("x")) shouldBe k
    ReplayGuard.unverifiable[Int](continuation, k, idempotent = true)(Right(2)).value shouldBe 2
    journal.record(k).value.taskId shouldBe TaskId("2.0")
    val fresh = continuation.effectKeyUnder(key, "op", Seq("y"))
    ReplayGuard.unverifiable[Int](continuation, fresh, idempotent = false)(Right(3)).value shouldBe 3
    journal.record(fresh).value.taskId shouldBe TaskId("2.0")
  }

  it should "replay a retried node outside a runtime from the context's own records" in {
    val c = new RunContext(
      RunConfig(),
      RunPosition(ThreadId("t"), RunId("r"), "", TaskId("0.0"), NodeId("n"), 0),
      NodeEventSink.none
    )
    val ran = new AtomicInteger()
    def run = ReplayGuard.unverifiable[Int](c, c.effectKey("op"), idempotent = false)(Right(ran.incrementAndGet()))
    run.value shouldBe 1
    run.value shouldBe 1
    ran.get shouldBe 1
  }
}
