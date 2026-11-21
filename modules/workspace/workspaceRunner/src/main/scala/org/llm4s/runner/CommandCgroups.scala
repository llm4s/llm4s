package org.llm4s.runner

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.TimeUnit
import scala.jdk.OptionConverters._
import scala.util.Try

/**
 * A cgroup per command (design §4.39, #2008): where the container has a writable cgroup v2 hierarchy, each `execute`
 * command is started in a cgroup of its own, made under the runner's, and the whole cgroup is killed
 * (`cgroup.kill`) when the command ends, times out or is cancelled. A process cannot leave its cgroup by starting a
 * session of its own, as it leaves its process group, so a daemon a command starts (`setsid`) is killed with it.
 *
 * @param parent the runner's own cgroup directory, under which each command's is made
 */
final private[runner] case class CommandCgroups(parent: Path) {

  /** A new, empty cgroup for one command; a failure when it cannot be made. */
  def create(): Try[CommandCgroup] =
    Try(CommandCgroup(Files.createDirectory(parent.resolve(s"llm4s-cmd-${java.util.UUID.randomUUID()}"))))
}

/** One command's cgroup: its process joins it before the program starts, and everything it starts stays in it. */
final private[runner] case class CommandCgroup(dir: Path) {

  /**
   * The argument vector that starts `program...` inside this cgroup: a fixed `/bin/sh` script joins it (writing its
   * own pid into `cgroup.procs`) and then `exec`s the program in place, so the pid is the program's. When it cannot
   * join, it writes why to `status` and exits 125 without running the program: the caller refuses the command
   * (`SANDBOX_UNAVAILABLE`), as when the confinement helper cannot confine.
   */
  def joinArgs(status: Path): List[String] = CommandCgroups.joinArgs(dir, status)

  /** Kills every process in the cgroup at once (`cgroup.kill`); those it starts while dying are killed too. */
  def kill(): Unit = Try(Files.writeString(dir.resolve("cgroup.kill"), "1")): Unit

  /** The live, non-zombie processes in the cgroup now. */
  def members: List[ProcessHandle] =
    Try(Files.readAllLines(dir.resolve("cgroup.procs"), StandardCharsets.UTF_8)).toOption.toList
      .flatMap(_.toArray.toList.map(_.toString.trim))
      .filter(l => l.nonEmpty && l.forall(_.isDigit))
      .flatMap(pid => Try(pid.toLong).toOption.filter(_ > 0))
      .flatMap(pid => ProcessHandle.of(pid).toScala)
      .filter(h => h.isAlive && !CommandCgroups.zombie(h.pid()))

  /** Removes the cgroup, once nothing is in it; `false` while something still is (or it cannot be removed). */
  def remove(): Boolean = Try(Files.deleteIfExists(dir)).isSuccess
}

private[runner] object CommandCgroups {

  /** Where the unified (v2) hierarchy is mounted. */
  val Root: Path = Paths.get("/sys/fs/cgroup")

  /** The shell the join script runs in: the system's, by absolute path. */
  val Shell: String = "/bin/sh"

  /** `$1` the cgroup directory, `$2` the status file, the rest the program and its arguments. */
  val JoinScript: String =
    "echo 0 > \"$1/cgroup.procs\" || { echo \"cannot join the command's cgroup $1\" > \"$2\"; exit 125; }; " +
      "shift 2; exec \"$@\""

  def joinArgs(dir: Path, status: Path): List[String] =
    List(Shell, "-c", JoinScript, "llm4s-cgroup", dir.toString, status.toString)

  /**
   * The cgroup v2 path of the process whose `/proc/<pid>/cgroup` reads `content`: its `0::` line. None on a cgroup v1
   * only host (no such line).
   */
  def unifiedPath(content: String): Option[String] =
    content.linesIterator.map(_.trim).collectFirst { case l if l.startsWith("0::") => l.stripPrefix("0::") }

  /**
   * Whether commands can each have a cgroup here, and under which directory; else why not. Every step is checked,
   * not assumed: a cgroup v2 hierarchy, the runner's own cgroup in it, a probe cgroup made there (the hierarchy is
   * writable), its `cgroup.kill` (Linux 5.14), and a process moved into it by the join script (`join`). The probe is
   * removed whatever the outcome.
   *
   * @param linux     whether this is Linux; nothing is tried elsewhere
   * @param root      where the unified hierarchy is mounted
   * @param ownCgroup the runner's `/proc/self/cgroup`
   * @param join      starts the join script for a probe cgroup, with a program that exits at once; whether it ran
   * @param make      makes a directory, the probe cgroup; the file system's, bar in tests
   */
  def detect(
    linux: Boolean,
    root: Path,
    ownCgroup: => Try[String],
    join: Path => Boolean,
    make: Path => Path = Files.createDirectory(_)
  ): Either[String, CommandCgroups] =
    if (!linux) Left("not on Linux")
    else
      for {
        content <- ownCgroup.toEither.left.map(e => s"the runner's cgroup cannot be read (${e.getMessage})")
        path    <- unifiedPath(content).toRight("there is no cgroup v2 (unified) hierarchy; cgroup v1 is not supported")
        parent = root.resolve(path.stripPrefix("/")).normalize()
        _ <- Either.cond(
          parent.startsWith(root) && Files.isRegularFile(parent.resolve("cgroup.procs")),
          (),
          s"the runner's cgroup $parent is not mounted at $root"
        )
        probe <- Try(make(parent.resolve(s"llm4s-probe-${java.util.UUID.randomUUID()}"))).toEither.left
          .map(e => s"$parent is not writable (${Option(e.getMessage).getOrElse(e.toString)})")
        checked = {
          val outcome =
            if (!Files.exists(probe.resolve("cgroup.kill"))) Left("the kernel has no cgroup.kill (it needs Linux 5.14)")
            else if (!Try(join(probe)).getOrElse(false)) Left(s"a process cannot be moved into a cgroup under $parent")
            else Right(CommandCgroups(parent))
          Try(Files.deleteIfExists(probe))
          outcome
        }
        cgroups <- checked
      } yield cgroups

  /** This runner's answer, decided once: the real hierarchy, joined by the real script. */
  lazy val platform: Either[String, CommandCgroups] =
    detect(
      System.getProperty("os.name", "").startsWith("Linux"),
      Root,
      Try(Files.readString(Paths.get("/proc/self/cgroup"))),
      probe =>
        Try {
          val p = ProcessRegistry.startHelper(
            new java.lang.ProcessBuilder((joinArgs(probe, Paths.get("/dev/null")) :+ "/bin/true"): _*)
              .redirectErrorStream(true)
              .redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
          )
          p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrElse(false)
    )

  /** What the runner says at startup about [[detect]]'s answer: in force, or not and why. */
  def report(detected: Either[String, CommandCgroups]): String =
    detected.fold(
      why =>
        s"Commands do not get a cgroup of their own ($why): a daemon a command starts (setsid) can outlive it. " +
          "Start the container with --cgroupns=private --cap-add SYS_ADMIN --security-opt apparmor=unconfined " +
          "(the image's entry point drops SYS_ADMIN before the runner starts) (design §4.39, #2008)",
      cg =>
        s"Each command runs in a cgroup of its own under ${cg.parent}, killed whole when it ends, times out or is " +
          "cancelled (design §4.39, #2008)"
    )

  /** Whether `pid` is a zombie (`/proc/<pid>/stat`'s state `Z`): it has exited and only waits to be reaped. */
  def zombie(pid: Long): Boolean =
    Try {
      val stat = Files.readString(Paths.get("/proc", pid.toString, "stat"))
      stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z")
    }.getOrElse(false)
}
