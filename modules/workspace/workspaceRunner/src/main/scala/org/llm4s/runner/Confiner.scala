package org.llm4s.runner

import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/** `llm4s-confine` (design §4.39), on Linux: every `execute` command is started through it. */
final private[runner] case class Confiner(helper: Path)

private[runner] object Confiner {
  val HelperProperty: String = "llm4s.workspace.confine"
  val DefaultHelper: Path    = Paths.get("/usr/local/bin/llm4s-confine")

  /** On Linux the helper (the property's path, else the image's); elsewhere None: commands run unconfined. */
  def platform(): Option[Confiner] =
    if (!System.getProperty("os.name", "").startsWith("Linux")) None
    else Some(Confiner(Option(System.getProperty(HelperProperty)).map(Paths.get(_)).getOrElse(DefaultHelper)))
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
