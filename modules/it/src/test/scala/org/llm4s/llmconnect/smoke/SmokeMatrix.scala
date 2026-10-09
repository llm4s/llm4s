package org.llm4s.llmconnect.smoke

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters._

/**
 * The provider x capability matrix a maintainer reads after `sbt testSmoke`.
 *
 * Each provider spec records one [[Outcome]] per capability as its checks finish into [[SmokeMatrix.shared]]; the
 * matrix is then printed when that spec ends and once more, in full, when the JVM exits. The rendering is a pure
 * function of a snapshot, so it is tested without any provider; the offline self-test records into matrices of its
 * own, so its deliberate failures never reach the shared one.
 */
final class SmokeMatrix {

  private val cells = new ConcurrentHashMap[(String, Capability), Outcome]()

  /** Records `outcome` for `provider`'s `capability`, replacing an earlier one. */
  def record(provider: String, capability: Capability, outcome: Outcome): Unit = {
    cells.put((provider, capability), outcome)
    ()
  }

  /** Everything recorded so far. */
  def snapshot: Map[(String, Capability), Outcome] = cells.asScala.toMap

  /** The matrix for one provider alone. */
  def renderProvider(provider: String): String =
    SmokeMatrix.render(snapshot.filter { case ((name, _), _) => name == provider })
}

object SmokeMatrix {

  /** The matrix of the running JVM: the provider specs record here, and it is what is printed. */
  val shared: SmokeMatrix = new SmokeMatrix

  private def symbol(outcome: Option[Outcome]): String = outcome match {
    case Some(Outcome.Held)             => "held"
    case Some(Outcome.Failed(_))        => "FAILED"
    case Some(Outcome.NotApplicable(_)) => "n/a"
    case Some(Outcome.Skipped(_))       => "skipped"
    case None                           => "-"
  }

  /**
   * Renders `cells` as a table of providers (rows) against capabilities (columns), followed by a note for every
   * FAILED and n/a cell, so the table says what each provider does not support and why.
   */
  def render(cells: Map[(String, Capability), Outcome]): String =
    if (cells.isEmpty) "(no capability outcomes recorded)"
    else {
      val providers    = cells.keys.map(_._1).toSeq.distinct.sorted
      val capabilities = Capability.values.toSeq
      val labelWidth   = providers.map(_.length).max.max("provider".length)
      val widths       = capabilities.map(c => c.label.length.max("skipped".length))
      def row(first: String, rest: Seq[String]): String =
        (first.padTo(labelWidth, ' ') +: rest.zip(widths).map { case (cell, width) => cell.padTo(width, ' ') })
          .mkString("  ")
          .stripTrailing
      val header = row("provider", capabilities.map(_.label))
      val body = providers.map { provider =>
        row(provider, capabilities.map(capability => symbol(cells.get((provider, capability)))))
      }
      val notes = for {
        provider   <- providers
        capability <- capabilities
        note <- cells.get((provider, capability)).collect {
          case Outcome.Failed(message)       => s"  FAILED  $provider / ${capability.label}: $message"
          case Outcome.NotApplicable(reason) => s"  n/a     $provider / ${capability.label}: $reason"
        }
      } yield note
      (Seq(header) ++ body ++ (if (notes.isEmpty) Nil else "" +: "Notes:" +: notes)).mkString("\n")
    }

  private lazy val installShutdownReport: Unit = {
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      val all = shared.snapshot
      if (all.keys.map(_._1).toSet.size > 1) {
        println(s"\nCapability matrix, all providers:\n${render(all)}")
        System.out.flush()
      }
    }))
    ()
  }

  /** Arranges for the full matrix to print once, when the JVM exits (only if more than one provider ran). */
  def printAtExit(): Unit = installShutdownReport
}
