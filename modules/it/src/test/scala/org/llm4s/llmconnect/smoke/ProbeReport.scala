package org.llm4s.llmconnect.smoke

import org.scalatest.Assertions
import org.scalatest.exceptions.TestCanceledException

import scala.collection.mutable
import scala.util.{ Failure, Success, Try }

/**
 * Collects what a run of an assumption-probe suite found, so a person with an account gets a table
 * instead of having to read test output.
 *
 * Each probe checks one thing the provider module assumes about a service it has never been run
 * against. `probe` records the outcome of one:
 *  - `held`: the check passed;
 *  - `NOT HELD`: it failed, so the named assumption is wrong and its `fix` says where;
 *  - `skipped`: it was cancelled (no credentials) or never ran.
 *
 * Every assumption is registered up front, so a probe that never ran shows as `skipped` instead of
 * being missing from the table.
 *
 * @param title       the heading of the report
 * @param assumptions every assumption the suite probes, in the order the report lists them
 */
final class ProbeReport(title: String, assumptions: Seq[String]) {
  import ProbeReport.Status

  private val outcomes = mutable.LinkedHashMap.from(assumptions.map(_ -> Status.Skipped))

  /**
   * Runs `body` and records its outcome under `assumption`. The result, or the failure, is passed on, so
   * the test passes or fails as usual; a failure's message is prefixed with the assumption and `fix`.
   */
  def probe[A](assumption: String, fix: String)(body: => A): A = {
    require(outcomes.contains(assumption), s"no such probe registered: $assumption")
    val outcome = Try(body)
    val status = outcome match {
      case Success(_)                        => Status.Held
      case Failure(_: TestCanceledException) => Status.Skipped
      case Failure(_)                        => Status.NotHeld
    }
    synchronized(outcomes.update(assumption, status))
    outcome match {
      case Failure(_: TestCanceledException) => outcome.get
      case Failure(_)     => Assertions.withClue(s"ASSUMPTION NOT HELD: $assumption. Fix: $fix\n")(outcome.get)
      case Success(value) => value
    }
  }

  /** The status recorded for `assumption`, if it is registered. */
  def status(assumption: String): Option[Status] = synchronized(outcomes.get(assumption))

  /** The report: one line per assumption, then the counts. */
  def render(): String = synchronized {
    val width  = (assumptions.map(_.length) :+ "assumption".length).max
    val header = s"${"assumption".padTo(width, ' ')} | result"
    val rule   = s"${"-".*(width)}-+-${"-".*(8)}"
    val rows   = outcomes.toSeq.map { case (name, status) => s"${name.padTo(width, ' ')} | ${status.label}" }
    val counts = Status.values.toSeq.map(s => s"${s.label}=${outcomes.values.count(_ == s)}").mkString("  ")
    (s"$title: assumption report" +: header +: rule +: rows :+ counts).mkString("\n")
  }
}

object ProbeReport {

  /** What a probe found. */
  enum Status(val label: String) {
    case Held    extends Status("held")
    case NotHeld extends Status("NOT HELD")
    case Skipped extends Status("skipped")
  }
}
