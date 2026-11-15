package org.llm4s.runner

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * `llm4s-confine` (design §4.39), on Linux: every `execute` command is started through it.
 *
 * @param helper    the helper's path
 * @param sharedTmp whether commands are granted `/tmp` read-write (JVM tools such as sbt write there whatever `TMPDIR`
 *                  says). Only when the runner's own JVM keeps nothing in `/tmp` ([[Confiner.jvmLeavesTmpAlone]]): its
 *                  attach trigger file and socket live there, so a command that could write there could attach to the
 *                  unconfined runner and load code into it.
 */
final private[runner] case class Confiner(helper: Path, sharedTmp: Boolean = Confiner.jvmLeavesTmpAlone()) {

  /**
   * What the helper gives a command on this kernel (`llm4s-confine --report`), or why it cannot say: the helper is
   * missing, Landlock is unavailable or below v3 (then every command is refused), or its answer is not understood.
   */
  def protections(): Either[String, Confiner.Protections] =
    Try {
      val p = new ProcessBuilder(helper.toString, "--report").redirectErrorStream(true).start()
      p.getOutputStream.close()
      val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      if (p.waitFor() != 0) Left(out)
      else Confiner.Protections.parse(out).toRight(s"$helper --report answered '$out'")
    }.toEither.left.map(e => s"cannot run $helper: ${e.getMessage}").flatten
}

private[runner] object Confiner {

  /**
   * The Landlock protections a confined command gets (#2009), as `llm4s-confine --report` prints them: the ABI in
   * use and the scopes it adds. Filesystem rights are always there (ABI v3 or later); from ABI v6 (kernel 6.12) abstract
   * Unix sockets and signals are scoped to the command's own domain, so it cannot reach an abstract socket made
   * outside it nor signal the runner. The helper chooses; this only reads its answer.
   */
  final case class Protections(abi: Int, scopes: Set[String]) {

    /** Whether abstract Unix sockets and signals are both scoped. */
    def scoped: Boolean = Protections.Scopes.subsetOf(scopes)

    /** One phrase for the runner's startup log. */
    def describe: String =
      if (scoped) s"Landlock ABI $abi: filesystem rights, and abstract Unix sockets and signals scoped to the command"
      else
        s"Landlock ABI $abi: filesystem rights only; abstract Unix sockets and signals are not scoped (that needs ABI " +
          s"${Protections.ScopesAbi}, kernel 6.12)"
  }

  object Protections {

    /** The first Landlock ABI with scopes. */
    val ScopesAbi: Int = 6

    /** The scopes the helper sets from [[ScopesAbi]], by the names it reports. */
    val Scopes: Set[String] = Set("abstract-unix-socket", "signal")

    private val Line = raw"landlock-abi=(\d{1,4}) scopes=([a-z,-]+)".r

    /** The helper's report line (`landlock-abi=8 scopes=abstract-unix-socket,signal`, `scopes=none`), if it is one. */
    def parse(line: String): Option[Protections] = line.trim match {
      case Line(abi, "none") => Some(Protections(abi.toInt, Set.empty))
      case Line(abi, names)  => Some(Protections(abi.toInt, names.split(',').toSet))
      case _                 => None
    }
  }

  val HelperProperty: String = "llm4s.workspace.confine"
  val DefaultHelper: Path    = Paths.get("/usr/local/bin/llm4s-confine")

  /** Where the runner makes its private scratch parent: not `/tmp`, which commands may be granted. */
  val ScratchBase: Path = Paths.get("/var/tmp")

  /** On Linux the helper (the property's path, else the image's); elsewhere None: commands run unconfined. */
  def platform(): Option[Confiner] =
    if (!System.getProperty("os.name", "").startsWith("Linux")) None
    else Some(Confiner(Option(System.getProperty(HelperProperty)).map(Paths.get(_)).getOrElse(DefaultHelper)))

  /**
   * The JVM flags that keep the runner's JVM out of `/tmp`: no attach mechanism (its trigger file and socket) and no
   * shared perf-data file (`hsperfdata`). The runner's own temporary files are kept out by `java.io.tmpdir`, which
   * the image points at a runner-private directory, and [[GitSandbox.checkedEmptyDirectory]] checks.
   */
  val TmpFreeFlags: List[String] = List("DisableAttachMechanism", "PerfDisableSharedMem")

  /** Whether this JVM runs with every one of [[TmpFreeFlags]] on; anything that cannot be verified is `false`. */
  def jvmLeavesTmpAlone(): Boolean =
    jvmLeavesTmpAlone(name =>
      Try(
        ManagementFactory
          .getPlatformMXBean(classOf[com.sun.management.HotSpotDiagnosticMXBean])
          .getVMOption(name)
          .getValue
      ).toOption
    )

  /** [[jvmLeavesTmpAlone]] over a lookup of a VM option's value: every flag must read `true`. */
  def jvmLeavesTmpAlone(vmOption: String => Option[String]): Boolean =
    TmpFreeFlags.forall(f => vmOption(f).contains("true"))

  /**
   * The runner's own directory for its commands' scratch directories: made under `base` (owner-only, as every
   * temporary directory the JVM makes), never granted to a command. A failure when it cannot be made or would lie
   * inside the workspace (`realRoot`) or another path commands may write (`writable`, real paths), where a command
   * could reach it.
   */
  def scratchParent(base: Path, realRoot: Path, writable: Seq[Path] = Nil): Try[Path] =
    Try(Files.createTempDirectory(base, "llm4s-runner-").toRealPath()).flatMap { dir =>
      (realRoot +: writable).find(dir.startsWith(_)) match {
        case None => Try(dir)
        case Some(p) =>
          Try(Files.delete(dir))
          val where = if (p == realRoot) "the workspace" else s"$p, which commands may write"
          scala.util.Failure(new IllegalStateException(s"its scratch directory $dir would lie inside $where"))
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
