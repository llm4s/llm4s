package org.llm4s.runner

import org.llm4s.shared.WorkspaceSandboxConfig

import java.nio.file.{ Files, LinkOption, Path, Paths }
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * What a command may touch, as `llm4s-confine`'s arguments (design §4.39). Pure apart from reading which paths exist:
 * the helper enforces, this only decides.
 *
 * Landlock grants are allow-only and a right on a directory covers everything beneath it, so a read-only path inside
 * a writable one is carved out by a walk: each directory above it gets read only, its other entries read-write, and
 * its existing files write. A command therefore cannot create or delete entries directly in a directory above a
 * read-only path. A read-only path that does not exist yet is carved to its deepest existing ancestor, so a command
 * cannot create it.
 */
private[runner] object CommandConfinement {

  enum Grant(val flag: String) {
    case ReadExec      extends Grant("--rx")    // system and toolchain directories
    case ReadOnly      extends Grant("--ro")
    case ReadWrite     extends Grant("--rw")    // workspace, scratch tmp, /dev/null
    case ReadWriteExec extends Grant("--rwx")   // the runner user's home (sdkman's binaries live there)
    case WriteFile     extends Grant("--wfile") // an existing regular file in a directory above a read-only path
  }

  final case class Rule(grant: Grant, path: Path)

  private val SystemDirs     = List("/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32", "/etc", "/opt")
  private val ReadOnlySystem = List("/proc", "/sys", "/dev/urandom", "/dev/random", "/dev/zero")

  /**
   * System locations every command needs, as they exist on this machine, by real path.
   *
   * Landlock grants union, so a grant that contains the workspace root, or lies inside it, would void
   * `readOnlyPaths` and `allowedPaths`: such a rule is dropped. A workspace under the home directory therefore gets
   * no home grant, and build tools there cannot write their caches.
   *
   * `/opt` is read and execute because the runner itself is installed under `/opt/docker`; a command may read the
   * runner's jars, which is harmless.
   */
  def systemRules(home: Path, javaHome: Option[Path], realRoot: Path): List[Rule] = {
    val rx  = (SystemDirs.map(Paths.get(_)) ++ javaHome).map(Rule(Grant.ReadExec, _))
    val ro  = ReadOnlySystem.map(p => Rule(Grant.ReadOnly, Paths.get(p)))
    val rw  = List(Rule(Grant.ReadWrite, Paths.get("/dev/null")))
    val rwx = List(Rule(Grant.ReadWriteExec, home))
    (rx ++ ro ++ rw ++ rwx)
      .flatMap(r => Try(r.path.toRealPath()).toOption.map(Rule(r.grant, _)))
      .filterNot(r => realRoot.startsWith(r.path) || r.path.startsWith(realRoot))
  }

  /**
   * The workspace's rules: allowedPaths (or the root), with readOnlyPaths carved out by the walk.
   *
   * A read-only path that does not exist yet is carved down to its deepest existing ancestor, which therefore gets
   * read only: a command cannot create it, nor any directory on the way to it.
   */
  def workspaceRules(config: WorkspaceSandboxConfig, realRoot: Path): List[Rule] = {
    def real(p: String): Option[Path] =
      Try(realRoot.resolve(p).normalize().toRealPath()).toOption.filter(_.startsWith(realRoot))
    // where a missing path would be: its deepest existing ancestor's real path, with the rest appended
    def wouldBe(p: String): Option[Path] = {
      val target = realRoot.resolve(p).normalize()
      Iterator
        .iterate(Option(target))(_.flatMap(a => Option(a.getParent)))
        .takeWhile(_.isDefined)
        .flatten
        .flatMap(a => Try(a.toRealPath().resolve(a.relativize(target)).normalize()).toOption)
        .nextOption()
        .filter(_.startsWith(realRoot))
    }
    val bases    = if (config.allowedPaths.isEmpty) List(realRoot) else config.allowedPaths.flatMap(real).distinct
    val readOnly = config.readOnlyPaths.flatMap(p => real(p).orElse(wouldBe(p))).distinct
    bases.flatMap { base =>
      if (readOnly.exists(r => base.startsWith(r))) List(Rule(Grant.ReadOnly, base))
      else carve(base, readOnly.filter(_.startsWith(base)))
    }
  }

  /** `dir`'s rules with each of `readOnly` (all inside it, none above it) left read-only. */
  private def carve(dir: Path, readOnly: List[Path]): List[Rule] =
    if (readOnly.isEmpty) List(Rule(Grant.ReadWrite, dir))
    else if (readOnly.contains(dir)) List(Rule(Grant.ReadOnly, dir))
    else {
      val children = Using(Files.list(dir))(_.iterator().asScala.toList.sorted).getOrElse(Nil)
      Rule(Grant.ReadOnly, dir) :: children.flatMap { child =>
        val under = readOnly.filter(_.startsWith(child))
        val isDir = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
        // a read-only path itself (a real path, never a link) or a directory on the way to one; a link on the way (a
        // missing read-only path's name may be a dangling one) is handled as any other link
        if (under.nonEmpty && !Files.isSymbolicLink(child) && (isDir || readOnly.contains(child))) carve(child, under)
        else if (isDir) List(Rule(Grant.ReadWrite, child))
        else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) List(Rule(Grant.WriteFile, child))
        else Nil // a link, socket or device: reached, if at all, through its target's own rule
      }
    }

  /** The helper's argument vector, up to and including `--`. */
  def helperArgs(helper: Path, status: Path, rules: List[Rule], noNetwork: Boolean): List[String] =
    List(helper.toString, "--status", status.toString) ++
      rules.flatMap(r => List(r.grant.flag, r.path.toString)) ++
      (if (noNetwork) List("--no-net") else Nil) ++ List("--")
}
