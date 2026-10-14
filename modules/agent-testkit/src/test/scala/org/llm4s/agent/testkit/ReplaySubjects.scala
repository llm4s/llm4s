package org.llm4s.agent.testkit

import org.llm4s.agent.graph.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/**
 * Reference targets for [[ReplayContract]], one per support class, each slot a counter-like cell of its
 * own whose effect is not idempotent, so a second application shows: the contract would see it.
 */
object ReplaySubjects:

  /** A transactional counter store: `add` records `(key, new value)` with the increment, atomically. */
  final class TransactionalCounters extends ReplayContract.Subject with AppliedKeys:
    private val cells   = mutable.Map.empty[Int, Int].withDefaultValue(0)
    private val applied = mutable.Map.empty[String, Int]

    def name: String              = "a transactional counter store"
    def support: ReplaySupport    = ReplaySupport.Transactional
    override def appliedKeys: Int = synchronized(applied.size)

    def forget(key: EffectKey): Result[Unit] = synchronized(Right(applied.remove(key.value): Unit))

    /** Adds one to `slot`'s cell, unless `key` was applied: then its recorded value, and no write. */
    private def add(key: EffectKey, slot: Int): Result[Int] = synchronized {
      Right(applied.getOrElseUpdate(key.value, { cells(slot) += 1; cells(slot) }))
    }

    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      val key = context.effectKey("counter.add", s"cell-$slot")
      ReplayGuard.transactional[Int](context, key, this)(add(key, slot)).map(ujson.Num(_))

    def observe(slot: Int): Result[String] = synchronized(Right(cells(slot).toString))
    def initial(slot: Int): String         = "0"
    def once(slot: Int): String            = "1"
    def interfere(slot: Int): Unit         = synchronized(cells(slot) = 100)

  /** A verifiable text cell per slot: the effect appends `"!"`, reporting the new length. */
  final class VerifiableText extends ReplayContract.Subject:
    private val cells = mutable.Map.empty[Int, String].withDefaultValue("text")

    def name: String           = "a verifiable text cell"
    def support: ReplaySupport = ReplaySupport.Verifiable

    private def read(slot: Int): String = synchronized(cells(slot))

    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      val key = context.effectKey("text.append", s"cell-$slot")
      ReplayGuard
        .verifiable[Int](context, key)(Right(StateCheck.of(read(slot)))) {
          val current = read(slot)
          val next    = current + "!"
          Right(ReplayGuard.Plan(StateCheck.of(current), StateCheck.of(next), next.length) {
            synchronized(cells(slot) = next)
            Right(next.length)
          })
        }
        .map(ujson.Num(_))

    def observe(slot: Int): Result[String] = Right(read(slot))
    def initial(slot: Int): String         = "text"
    def once(slot: Int): String            = "text!"
    def interfere(slot: Int): Unit         = synchronized(cells(slot) = "replaced")

  /** An unverifiable log per slot: the effect appends a line, reporting how many there are. */
  final class UnverifiableLog extends ReplayContract.Subject:
    private val lines = mutable.Map.empty[Int, Int].withDefaultValue(0)

    def name: String           = "an unverifiable append-only log"
    def support: ReplaySupport = ReplaySupport.Unverifiable

    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      val key = context.effectKey("log.append", s"log-$slot")
      ReplayGuard
        .unverifiable[Int](context, key, idempotent = false)(synchronized {
          lines(slot) += 1
          Right(lines(slot))
        })
        .map(ujson.Num(_))

    def observe(slot: Int): Result[String] = synchronized(Right(lines(slot).toString))
    def initial(slot: Int): String         = "0"
    def once(slot: Int): String            = "1"
    def interfere(slot: Int): Unit         = synchronized(lines(slot) += 10)

  /** An unverifiable, idempotent flag per slot: the effect sets it, so running it again changes nothing. */
  final class IdempotentFlag extends ReplayContract.Subject:
    private val flags = mutable.Map.empty[Int, String].withDefaultValue("unset")

    def name: String                 = "an unverifiable idempotent flag"
    def support: ReplaySupport       = ReplaySupport.Unverifiable
    override def idempotent: Boolean = true

    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      val key = context.effectKey("flag.set", s"flag-$slot")
      ReplayGuard
        .unverifiable[String](context, key, idempotent = true)(synchronized {
          flags(slot) = "set"
          Right("set")
        })
        .map(ujson.Str(_))

    def observe(slot: Int): Result[String] = synchronized(Right(flags(slot)))
    def initial(slot: Int): String         = "unset"
    def once(slot: Int): String            = "set"
    def interfere(slot: Int): Unit         = synchronized(flags(slot) = "cleared")

/** The contract on the reference targets, over the in-memory store. */
class TransactionalReplayContractSpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject = ReplaySubjects.TransactionalCounters()

class VerifiableReplayContractSpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject = ReplaySubjects.VerifiableText()

class UnverifiableReplayContractSpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject = ReplaySubjects.UnverifiableLog()

class IdempotentReplayContractSpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject = ReplaySubjects.IdempotentFlag()
