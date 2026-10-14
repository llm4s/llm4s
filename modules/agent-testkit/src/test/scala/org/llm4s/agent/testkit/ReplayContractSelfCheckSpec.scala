package org.llm4s.agent.testkit

import org.llm4s.agent.graph.*
import org.llm4s.types.Result
import org.scalatest.{ Args, Reporter }
import org.scalatest.events.{ Event, TestFailed }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** [[ReplayContract]] fails targets and stores that break it: the suite has teeth. */
class ReplayContractSelfCheckSpec extends AnyFlatSpec with Matchers {

  /** The names of the contract's cases that fail for `suite`. */
  private def failures(suite: ReplayContract): Set[String] = {
    val failed = new ConcurrentLinkedQueue[String]()
    val reporter = new Reporter {
      def apply(event: Event): Unit = event match {
        case f: TestFailed => failed.add(f.testName): Unit
        case _             => ()
      }
    }
    suite.run(None, Args(reporter)): Unit
    // a case's name without the suite's subject prefix
    failed.asScala.toSet.map(name => name.substring(name.indexOf(") ") + 2))
  }

  /** A counter that performs its effect directly, with no guard. */
  final private class Unguarded extends ReplayContract.Subject {
    private val cells          = mutable.Map.empty[Int, Int].withDefaultValue(0)
    def name: String           = "an unguarded counter"
    def support: ReplaySupport = ReplaySupport.Unverifiable
    def perform(context: RunContext, slot: Int): Result[ujson.Value] = synchronized {
      cells(slot) += 1
      Right(ujson.Num(cells(slot)))
    }
    def observe(slot: Int): Result[String] = synchronized(Right(cells(slot).toString))
    def initial(slot: Int): String         = "0"
    def once(slot: Int): String            = "1"
    def interfere(slot: Int): Unit         = synchronized(cells(slot) = 100)
  }

  /** A "transactional" counter that records no applied key: the effect is not atomic with its evidence. */
  final private class NotTransactional extends ReplayContract.Subject with AppliedKeys {
    private val cells                        = mutable.Map.empty[Int, Int].withDefaultValue(0)
    def name: String                         = "a counter that records no applied key"
    def support: ReplaySupport               = ReplaySupport.Transactional
    def forget(key: EffectKey): Result[Unit] = Right(())
    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      ReplayGuard
        .transactional[Int](context, context.effectKey("counter.add", s"cell-$slot"), this)(synchronized {
          cells(slot) += 1
          Right(cells(slot))
        })
        .map(ujson.Num(_))
    def observe(slot: Int): Result[String] = synchronized(Right(cells(slot).toString))
    def initial(slot: Int): String         = "0"
    def once(slot: Int): String            = "1"
    def interfere(slot: Int): Unit         = synchronized(cells(slot) = 100)
  }

  /** A verifiable cell whose two slots share one key: the second effect replays the first's outcome. */
  final private class SharedKey extends ReplayContract.Subject {
    private val inner          = ReplaySubjects.VerifiableText()
    private val cells          = mutable.Map.empty[Int, String].withDefaultValue("text")
    def name: String           = "a verifiable cell whose effects share a key"
    def support: ReplaySupport = ReplaySupport.Verifiable
    def perform(context: RunContext, slot: Int): Result[ujson.Value] = {
      val key = context.effectKey("text.append")
      ReplayGuard
        .verifiable[String](context, key)(Right(StateCheck.of(cells(slot)))) {
          val next = cells(slot) + "!"
          Right(ReplayGuard.Plan(StateCheck.of(cells(slot)), StateCheck.of(next), s"$slot:$next") {
            cells(slot) = next
            Right(s"$slot:$next")
          })
        }
        .map(ujson.Str(_))
    }
    def observe(slot: Int): Result[String] = Right(cells(slot))
    def initial(slot: Int): String         = inner.initial(slot)
    def once(slot: Int): String            = inner.once(slot)
    def interfere(slot: Int): Unit         = cells(slot) = "replaced"
  }

  /** A store that never releases an effect record: its commits drop the releasing checkpoint's rule. */
  final private class NeverReleases(underlying: Checkpointer) extends Checkpointer {
    private val kept = new java.util.concurrent.ConcurrentHashMap[String, EffectRecord]()
    def commit(threadId: ThreadId, commit: Commit) = {
      commit.effects.foreach(r => kept.put(r.key.value, r))
      underlying.commit(threadId, commit)
    }
    def effect(threadId: ThreadId, key: EffectKey)       = Right(Option(kept.get(key.value)))
    def claim(threadId: ThreadId, request: ClaimRequest) = underlying.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: scala.concurrent.duration.FiniteDuration) =
      underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: FencingToken)                = underlying.release(threadId, token)
    def latest(threadId: ThreadId)                                      = underlying.latest(threadId)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long)          = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken)       = underlying.deleteThread(threadId, token)
  }

  "ReplayContract" should "fail a target that performs its effect without a guard" in {
    val failed = failures(new AnyFlatSpec with ReplayContract {
      protected def newSubject(): ReplayContract.Subject = new Unguarded
    })
    failed should contain(
      "should not perform again an effect that ran before its outcome was written, replaying its outcome"
    )
    failed should contain("should replay the stored outcome of an effect whose outcome was written before the crash")
  }

  it should "fail a transactional target whose effect does not record its key atomically" in {
    val failed = failures(new AnyFlatSpec with ReplayContract {
      protected def newSubject(): ReplayContract.Subject = new NotTransactional
    })
    failed should contain(
      "should not perform again an effect that ran before its outcome was written, replaying its outcome"
    )
  }

  it should "fail a target whose effects in one task share a key" in {
    val failed = failures(new AnyFlatSpec with ReplayContract {
      protected def newSubject(): ReplayContract.Subject = new SharedKey
    })
    failed should contain("should perform each effect once in a run that never crashes, the two never colliding")
  }

  it should "fail a store that keeps effect records past their window" in {
    val failed = failures(new AnyFlatSpec with ReplayContract {
      protected def newSubject(): ReplayContract.Subject                 = ReplaySubjects.UnverifiableLog()
      override protected def newCheckpointer(clock: Clock): Checkpointer = NeverReleases(InMemoryCheckpointer(clock))
    })
    failed should contain("should perform each effect once in a run that never crashes, the two never colliding")
  }

  it should "pass the reference targets" in {
    failures(new AnyFlatSpec with ReplayContract {
      protected def newSubject(): ReplayContract.Subject = ReplaySubjects.VerifiableText()
    }) shouldBe empty
  }
}
