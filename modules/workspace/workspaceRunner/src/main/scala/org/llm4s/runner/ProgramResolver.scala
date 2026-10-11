package org.llm4s.runner

import org.llm4s.shared.WorkspaceSandboxConfig

import java.io.File
import java.nio.file.{ Files, Path, Paths }
import scala.jdk.OptionConverters._
import scala.util.Try

/**
 * Where the runner looks for the programs it starts (#1790): the host's facts that decide it, read once.
 *
 * @param windows           whether the host is Windows: programs are files named `<name>.exe` or `<name>.com`, and the
 *                          system directories are searched before `PATH`
 * @param systemDirectories on Windows, the system directory (`%SystemRoot%\System32`) and the Windows directory
 *                          (`%SystemRoot%`), searched first; empty elsewhere
 * @param path              the runner's `PATH`, unsplit
 * @param workingDirectory  the runner's own current directory, never searched
 * @param javaDirectory     the directory the runner's `java` executable was loaded from; never searched for its own
 *                          sake, only as an entry of `PATH`
 */
final case class ProgramSearch(
  windows: Boolean,
  systemDirectories: Seq[Path],
  path: Option[String],
  workingDirectory: Option[Path],
  javaDirectory: Option[Path]
)

object ProgramSearch {

  /** The search of the host the runner runs on. */
  def host(): ProgramSearch = {
    val windows = System.getProperty("os.name", "").startsWith("Windows")
    // scalafix:off DisableSyntax.NoSystemGetenv
    val env = (name: String) => Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty)
    // scalafix:on DisableSyntax.NoSystemGetenv
    val systemDirectories =
      if (!windows) Nil
      else {
        val windowsDirectory = env("SystemRoot")
          .orElse(env("windir"))
          .flatMap(dir => Try(Paths.get(dir)).toOption)
          .filter(_.isAbsolute)
          .getOrElse(Paths.get("C:\\Windows"))
        Seq(windowsDirectory.resolve("System32"), windowsDirectory)
      }
    val javaDirectory = Try(ProcessHandle.current().info().command().toScala).toOption.flatten
      .flatMap(command => Try(Option(Paths.get(command).getParent)).toOption.flatten)
      .orElse(Option(System.getProperty("java.home")).map(home => Paths.get(home, "bin")))
    ProgramSearch(
      windows = windows,
      systemDirectories = systemDirectories,
      path = env("PATH"),
      workingDirectory = Option(System.getProperty("user.dir")).flatMap(dir => Try(Paths.get(dir)).toOption),
      javaDirectory = javaDirectory
    )
  }
}

/**
 * Turns an allowlisted program name into the absolute path the runner starts (#1790).
 *
 * The runner used to hand `ProcessBuilder` the bare name. On Windows, `CreateProcess` then searches the directory the
 * runner's `java.exe` came from and the runner's '''current directory''' before the system directory and `PATH`, and
 * appends `.exe`; an agent that could write `git.exe` there (the runner started in the workspace, or in any directory
 * it can write) had it run by the next `git status`. On POSIX the JDK searches `PATH` itself, after changing to the
 * command's working directory, so an empty or relative entry (`.`, `bin`) - or an entry inside the workspace - finds
 * a program the agent wrote.
 *
 * The runner therefore searches for the program itself and starts the absolute path it finds:
 *  - on Windows: the system directory, then the Windows directory, then each `PATH` entry in order, for `<name>.exe`
 *    and then `<name>.com` (`CreateProcess` appends only `.exe`; `PATHEXT` is not used, so no `.bat` or `.cmd`);
 *    elsewhere: each `PATH` entry in order, for an executable regular file `<name>`;
 *  - never the runner's current directory, nor `java`'s directory except as a `PATH` entry;
 *  - never an empty or relative `PATH` entry, which would be read from the command's working directory;
 *  - never a directory inside the workspace, as written or after following links, nor a file whose real path is
 *    inside the workspace (a link from a trusted directory into it).
 *
 * A program found only where the runner does not look - the workspace, the runner's working directory or a skipped
 * `PATH` entry - is refused with `EXECUTABLE_NOT_ALLOWED`; one found nowhere with `EXECUTABLE_NOT_FOUND`.
 */
private[runner] object ProgramResolver {

  val ExecutableNotAllowed = "EXECUTABLE_NOT_ALLOWED"
  val ExecutableNotFound   = "EXECUTABLE_NOT_FOUND"

  /** The name an allowlist entry or a command's first word is matched by; see [[WorkspaceSandboxConfig.programKey]]. */
  def programKey(name: String, windows: Boolean): String = WorkspaceSandboxConfig.programKey(name, windows)

  /**
   * The absolute path to start for `program`, or why it is refused.
   *
   * @param program        the program's key (see [[programKey]])
   * @param search         the host's search
   * @param untrustedRoots directories nothing inside of is ever run: the workspace root, as configured and as its real
   *                       path, and the command's working directory
   */
  def resolve(
    program: String,
    search: ProgramSearch,
    untrustedRoots: Seq[Path]
  ): Either[CommandPolicy.Refusal, Path] =
    resolveIn(program, search, untrustedRoots, search.systemDirectories.map(_.toString) ++ pathEntries(search))

  /**
   * The absolute path of a program that must come from the Windows system directories (`cmd.exe`, which runs the
   * built-ins), never `PATH`.
   */
  def resolveSystem(
    program: String,
    search: ProgramSearch,
    untrustedRoots: Seq[Path]
  ): Either[CommandPolicy.Refusal, Path] =
    resolveIn(program, search, untrustedRoots, search.systemDirectories.map(_.toString))

  /** The `PATH` entries as written, in order, the empty ones as `""`. */
  private def pathEntries(search: ProgramSearch): Seq[String] =
    search.path.toSeq.flatMap(_.split(File.pathSeparator, -1).toSeq).map { entry =>
      val trimmed = entry.trim
      // Windows allows a PATH entry in double quotes (one holding `;`)
      if (search.windows && trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\""))
        trimmed.substring(1, trimmed.length - 1)
      else trimmed
    }

  /**
   * The `PATH` a command's process is given (#1792): the runner's, keeping only the entries the runner itself would
   * search (see [[resolve]]) - absolute, outside the workspace and the command's working directory as written and
   * after following links (for an entry that does not exist yet, its nearest existing ancestor's links), and not the
   * runner's own working directory - in their order.
   *
   * The runner starts the program itself by an absolute path, but the program may look another one up by name: a
   * `#!/usr/bin/env x` script, a launcher such as `sbt` starting `java`, `git` starting a helper. It searches the
   * `PATH` it inherits, from the workspace as its working directory, so an empty or relative entry, or one inside the
   * workspace, would let it run a file the agent wrote.
   *
   * When no entry is kept, the child gets the platform's system directories instead (#1805): on Windows the system
   * and Windows directories, elsewhere [[PosixFallbackPath]]. Without a `PATH`, bash and sh fall back to a built-in
   * default that ends in `.` - the workspace - and on Windows a child's own search also covers the system directories,
   * so a fallback that names them is both what a program expects and never the working directory.
   *
   * Only directories are vetted: a program in a kept directory that is itself a link into the workspace is followed
   * by a child, though [[resolve]] refuses it. Making one needs a write outside the workspace or a link the operator
   * made; checking every file of every `PATH` directory for each command would cost more than it guards.
   *
   * @return the sanitised `PATH`, or None only when even the fallback directories are untrusted (the workspace is the
   *         file system's root); the caller then removes `PATH`, since an empty one is read as the current directory
   */
  def childPath(search: ProgramSearch, untrustedRoots: Seq[Path]): Option[String] = {
    val trusted = trustedDirectories(search, untrustedRoots)
    val kept    = pathEntries(search).filter(entry => trusted(entry).isDefined).distinct
    def fallback = (if (search.windows) search.systemDirectories.map(_.toString) else PosixFallbackPath)
      .filter(entry => trusted(entry).isDefined)
      .distinct
    Some(if (kept.nonEmpty) kept else fallback).filter(_.nonEmpty).map(_.mkString(File.pathSeparator))
  }

  /** The `PATH` a POSIX command's process gets when none of the runner's entries is kept: the system's binaries. */
  val PosixFallbackPath: Seq[String] = Seq("/usr/bin", "/bin")

  /**
   * Whether a `PATH` entry names a directory the runner may run a program from: the directory, or None when the entry
   * is empty, relative, unparseable, inside an untrusted root (as written or after following links), or the runner's
   * own working directory (unless that is a system directory).
   */
  private def trustedDirectories(search: ProgramSearch, untrustedRoots: Seq[Path]): String => Option[Path] = {
    val untrusted = expand(untrustedRoots)
    val cwd       = expand(search.workingDirectory.toSeq)
    val system    = search.systemDirectories.map(_.toAbsolutePath.normalize())
    def insideUntrusted(path: Path): Boolean =
      expand(Seq(path)).exists(p => untrusted.exists(root => p.startsWith(root)))
    entry =>
      Try(Paths.get(entry)).toOption
        .filter(dir => entry.nonEmpty && dir.isAbsolute)
        .map(_.normalize())
        .filterNot(insideUntrusted)
        .filterNot(dir => !system.contains(dir) && expand(Seq(dir)).exists(cwd.contains))
  }

  private def resolveIn(
    program: String,
    search: ProgramSearch,
    untrustedRoots: Seq[Path],
    entries: Seq[String]
  ): Either[CommandPolicy.Refusal, Path] = {
    val untrusted = expand(untrustedRoots)
    val names     = fileNames(program, search.windows)
    val trusted   = trustedDirectories(search, untrustedRoots)

    def insideUntrusted(path: Path): Boolean =
      expand(Seq(path)).exists(p => untrusted.exists(root => p.startsWith(root)))

    def runnable(file: Path): Boolean =
      Try(Files.isRegularFile(file) && (search.windows || Files.isExecutable(file))).getOrElse(false)

    val found = entries.iterator
      .flatMap(entry => trusted(entry).iterator)
      .flatMap(dir => names.iterator.map(dir.resolve))
      .find(file => runnable(file) && !insideUntrusted(file))

    found.toRight {
      // Only where the runner would not look: say so, since an agent may have put it there. A relative or empty PATH
      // entry is where the JDK would have read it: from the command's working directory.
      val skippedEntries = entries.flatMap { entry =>
        Try(Paths.get(entry)).toOption.toSeq.flatMap { dir =>
          if (entry.nonEmpty && dir.isAbsolute) Seq(dir) else untrustedRoots.map(_.resolve(dir))
        }
      }
      val elsewhere =
        (skippedEntries ++ untrustedRoots ++ search.workingDirectory.toSeq ++ search.javaDirectory.toSeq).iterator
          .flatMap(dir => names.iterator.map(name => Try(dir.resolve(name)).toOption))
          .flatten
          .find(runnable)
      elsewhere match {
        case Some(file) =>
          CommandPolicy.Refusal(
            ExecutableNotAllowed,
            s"'$program' is found only at '$file', where the runner never starts a program from: the workspace, the " +
              "runner's own working directory or Java directory, or a relative or empty PATH entry."
          )
        case None =>
          CommandPolicy.Refusal(
            ExecutableNotFound,
            s"'$program' was not found in " +
              (if (search.windows) "the Windows system directory, the Windows directory or " else "") +
              "an absolute PATH entry outside the workspace."
          )
      }
    }
  }

  /** The file names `program` may have: on Windows `<name>.exe` then `<name>.com`, elsewhere `<name>`. */
  private def fileNames(program: String, windows: Boolean): Seq[String] =
    if (windows) Seq(program + ".exe", program + ".com") else Seq(program)

  /**
   * Each path as written (absolute, normalized) and as its real path: where it does not exist, its nearest existing
   * ancestor's real path with the rest appended, so an entry not yet made under a link into the workspace is judged
   * by where the link leads (#1805).
   */
  private def expand(paths: Seq[Path]): Seq[Path] =
    paths.flatMap { p =>
      val absolute = p.toAbsolutePath.normalize()
      absolute +: realPath(absolute).toSeq
    }.distinct

  private def realPath(absolute: Path): Option[Path] =
    Iterator
      .iterate(Option(absolute))(_.flatMap(p => Option(p.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .flatMap(ancestor => Try(ancestor.toRealPath().resolve(ancestor.relativize(absolute))).toOption)
      .nextOption()
      .map(_.normalize())
}
