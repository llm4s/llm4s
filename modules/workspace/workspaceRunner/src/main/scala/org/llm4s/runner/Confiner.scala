package org.llm4s.runner

import java.lang.management.ManagementFactory
import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * `llm4s-confine` (design §4.39), on Linux: every `execute` command is started through it.
 *
 * @param helper    the helper's path
 * @param sharedTmp whether commands are granted `/tmp` read-write (JVM tools such as sbt write there whatever `TMPDIR`
 *                  says). Only when the runner's own JVM has its attach mechanism disabled: its trigger file and
 *                  socket live in `/tmp`, so a command that could write there could attach to the unconfined runner
 *                  and load code into it.
 */
final private[runner] case class Confiner(helper: Path, sharedTmp: Boolean = Confiner.attachDisabled())

private[runner] object Confiner {
  val HelperProperty: String = "llm4s.workspace.confine"
  val DefaultHelper: Path    = Paths.get("/usr/local/bin/llm4s-confine")

  /** Where the runner makes its private scratch parent: not `/tmp`, which commands may be granted. */
  val ScratchBase: Path = Paths.get("/var/tmp")

  /** On Linux the helper (the property's path, else the image's); elsewhere None: commands run unconfined. */
  def platform(): Option[Confiner] =
    if (!System.getProperty("os.name", "").startsWith("Linux")) None
    else Some(Confiner(Option(System.getProperty(HelperProperty)).map(Paths.get(_)).getOrElse(DefaultHelper)))

  /** Whether this JVM runs with `-XX:+DisableAttachMechanism`; anything that cannot be verified is `false`. */
  def attachDisabled(): Boolean =
    Try(
      ManagementFactory
        .getPlatformMXBean(classOf[com.sun.management.HotSpotDiagnosticMXBean])
        .getVMOption("DisableAttachMechanism")
        .getValue == "true"
    ).getOrElse(false)

  /**
   * The runner's own directory for its commands' scratch directories: made under `base` (owner-only, as every
   * temporary directory the JVM makes), never granted to a command. A failure when it cannot be made or would lie
   * inside the workspace (`realRoot`), where a command could reach it.
   */
  def scratchParent(base: Path, realRoot: Path): Try[Path] =
    Try(Files.createTempDirectory(base, "llm4s-runner-").toRealPath()).flatMap { dir =>
      if (!dir.startsWith(realRoot)) Try(dir)
      else {
        Try(Files.delete(dir))
        scala.util.Failure(new IllegalStateException(s"its scratch directory $dir would lie inside the workspace"))
      }
    }
}

/** A confined command's own directory: `tmp` is granted to it (its TMPDIR), `status` is not. */
final private[runner] case class CommandScratch(dir: Path) {
  def tmp: Path    = dir.resolve("tmp")
  def status: Path = dir.resolve("status")

  /** The helper's reason, when it could not confine: exit status 125 and the status file exists. */
  def unavailable(exitCode: Int): Option[String] =
    if (exitCode != 125 || !Files.exists(status)) None
    else Some(Try(Files.readString(status)).getOrElse("the sandbox could not be applied"))

  def delete(): Unit =
    Using(Files.walk(dir))(_.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))): Unit
}
