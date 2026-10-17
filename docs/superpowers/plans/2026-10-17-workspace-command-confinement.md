# Workspace Command Confinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every command the workspace runner's `execute` starts on Linux runs under a kernel-enforced Landlock ruleset (`allowedPaths`, `readOnlyPaths`) and, when `networkAllowed = false`, a seccomp filter that refuses network sockets. Off Linux, nothing changes.

**Architecture:** A small C helper, `llm4s-confine`, installs the restrictions and `execve`s the command in its own place. A pure Scala module, `CommandConfinement`, turns the sandbox config into the helper's arguments. `prepareCommand` puts the helper in front of each command on Linux and maps the helper's failure to `SANDBOX_UNAVAILABLE`. A new `WORKSPACE_SANDBOX_CONFIG` variable lets a container be given the full config.

**Tech Stack:** C (Linux UAPI headers only), Scala 3.7.1, sbt, sbt-native-packager Docker, ScalaTest, GitHub Actions.

**Spec:** `docs/design/typed-agent-runtime/4.39-workspace-command-confinement.md` (issue #1983). Read it before any task.

## Global Constraints

- Landlock ABI **v3 or later**; anything lower is `SANDBOX_UNAVAILABLE` on Linux.
- Helper exit status **125** plus the `--status` file = confinement unavailable. Status 125 without the file is the program's own exit code.
- The helper is installed at **`/usr/local/bin/llm4s-confine`** in the image. The runner reads the system property **`llm4s.workspace.confine`** for another path (tests); there is no environment variable.
- Off Linux (macOS, Windows), commands run unconfined, and the runner logs this **once**. Never refuse there.
- Only `execute` commands are confined. The runner's own helper processes (`getfacl`, `setfacl`, git config lister: `ProcessRegistry.startHelper`) are not.
- `CommandPolicy` stays exactly as it is.
- Scala rules from CLAUDE.md: `Result`/`Either`, no `try/catch` outside safety packages (use `Try`/`Using`), no infix, durations are `FiniteDuration`, no `System.getenv` outside `RunnerMain`.
- Every commit is signed off: `git commit -s`. End each message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- CHANGELOG: one entry under `## [Unreleased]` → `### Agent harness (Phase 3)`. Read CLAUDE.md's CHANGELOG rule; never read the whole file.

## Review Focus

1. **Symlink inside the workspace leading into a read-only path** (`ln -s ro/x link; cp a link`): the write must be refused, because Landlock judges the target inode. Test in Task 4.
2. **A program that itself exits 125** (`sort` with a bad option returns 2, so use a test script under the confiner or `ls`; see Task 4) must keep exit 125 as its result, not become `SANDBOX_UNAVAILABLE`. Test in Task 4.
3. **`readOnlyPaths` entry equal to an `allowedPaths` entry, or containing one**: the whole entry is read-only, with no walk. Test in Task 2.
4. **`allowedPaths` / `readOnlyPaths` entry that is missing, or a symlink**: a missing one is skipped. A symlink entry is resolved to its real path first, as the runner's path policy does (`effective`), so the rule binds the target. Test in Task 2.
5. **The per-command scratch directory is removed after timeout, cancellation and refusal**, not only after normal exit. Test in Task 4, via timeout.

---

### Task 1: Configure a container's whole sandbox (`WORKSPACE_SANDBOX_CONFIG`)

**Files:**
- Modify: `modules/workspace/workspaceShared/src/main/scala/org/llm4s/shared/WorkspaceSandboxConfig.scala` (add `ConfigEnvVar`, `fromJson`; fix the `networkAllowed` Scaladoc at line 24)
- Modify: `modules/workspace/workspaceRunner/src/main/scala/org/llm4s/runner/RunnerMain.scala:36-82`
- Modify: `modules/workspace/workspaceClient/src/main/scala/org/llm4s/workspace/ContainerisedWorkspace.scala` (constructor and `dockerRunArgs` at 627-645; Scaladoc at 33-42)
- Modify: `modules/workspace/workspaceClient/src/main/scala/org/llm4s/codegen/CodeWorker.scala:35`, `CodeGenExample.scala:23,52`
- Test: `modules/workspace/workspaceClient/src/test/scala/org/llm4s/workspace/ContainerisedWorkspaceDockerArgsSpec.scala`
- Test: create `modules/workspace/workspaceShared/src/test/scala/org/llm4s/shared/WorkspaceSandboxConfigJsonSpec.scala` (check the directory exists; if `workspaceShared` has no test directory, put the spec in `modules/workspace/workspaceRunner/src/test/scala/org/llm4s/runner/`)

**Interfaces:**
- Produces: `WorkspaceSandboxConfig.ConfigEnvVar: String = "WORKSPACE_SANDBOX_CONFIG"`, `WorkspaceSandboxConfig.fromJson(json: String): Either[String, WorkspaceSandboxConfig]` (parsed and validated), `ContainerisedWorkspace(workspaceDir, imageName, hostPort, extraAllowedCommands = Set.empty, sandboxConfig: Option[WorkspaceSandboxConfig] = None)`, `ContainerisedWorkspace.dockerRunArgs(..., extraAllowedCommands: Set[String], sandboxConfig: Option[WorkspaceSandboxConfig]): Either[String, Seq[String]]`.

- [ ] **Step 1: Write the failing tests**

`WorkspaceSandboxConfigJsonSpec`:

```scala
package org.llm4s.shared

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class WorkspaceSandboxConfigJsonSpec extends AnyFlatSpec with Matchers {

  "WorkspaceSandboxConfig.fromJson" should "read what upickle writes" in {
    val cfg = WorkspaceSandboxConfig.Permissive.copy(
      readOnlyPaths = List("ro"),
      allowedPaths = List("src", "ro"),
      networkAllowed = true
    )
    WorkspaceSandboxConfig.fromJson(upickle.default.write(cfg)) shouldBe Right(cfg)
  }

  it should "fill missing keys from the defaults" in {
    WorkspaceSandboxConfig.fromJson("""{"networkAllowed":true}""").map(_.networkAllowed) shouldBe Right(true)
  }

  it should "refuse malformed JSON, naming the variable" in {
    val left = WorkspaceSandboxConfig.fromJson("{not json").swap.getOrElse("")
    left should include(WorkspaceSandboxConfig.ConfigEnvVar)
  }

  it should "refuse a config validate refuses" in {
    WorkspaceSandboxConfig.fromJson("""{"defaultCommandTimeoutSeconds":0}""").isLeft shouldBe true
  }
}
```

If `WorkspaceSandboxConfig.copy` is not accessible (the class is a plain `final case class`, so it should be), build the config with the constructor's named arguments instead.

`ContainerisedWorkspaceDockerArgsSpec`: change the `args` helper to pass `None` for the new parameter, and add:

```scala
  it should "pass a sandbox config as JSON in WORKSPACE_SANDBOX_CONFIG, before the image" in {
    val cfg   = WorkspaceSandboxConfig.Permissive.copy(readOnlyPaths = List("ro"), networkAllowed = true)
    val built = ContainerisedWorkspace
      .dockerRunArgs("ws-test", 18080, 8080, "/tmp/ws", "llm4s/workspace-runner:dev", Set.empty, Some(cfg))
      .fold(fail(_), identity)
    val env = built.sliding(2).collectFirst { case Seq("-e", v) if v.startsWith("WORKSPACE_SANDBOX_CONFIG=") => v }
    env.map(_.stripPrefix("WORKSPACE_SANDBOX_CONFIG=")).map(WorkspaceSandboxConfig.fromJson) shouldBe Some(Right(cfg))
    built.last shouldBe "llm4s/workspace-runner:dev"
  }

  it should "check extra commands against the sandbox config it is given" in {
    ContainerisedWorkspace
      .dockerRunArgs("ws-test", 18080, 8080, "/tmp/ws", "img", Set("sh"), Some(WorkspaceSandboxConfig.Permissive))
      .isLeft shouldBe true
  }
```

Import `org.llm4s.shared.WorkspaceSandboxConfig` in the spec.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "workspaceShared/testOnly *WorkspaceSandboxConfigJsonSpec" "workspaceClient/testOnly *ContainerisedWorkspaceDockerArgsSpec" 2>&1 | grep -E '\[error\]|FAILED|Tests:' | head -20`
Expected: compilation errors (`fromJson`, `ConfigEnvVar`, the 7-argument `dockerRunArgs` not found).

- [ ] **Step 3: Implement**

In `WorkspaceSandboxConfig`'s companion, next to `ExtraCommandsEnvVar`:

```scala
  /** The runner's environment variable holding a whole sandbox config as JSON, in place of a profile (design §4.39). */
  val ConfigEnvVar: String = "WORKSPACE_SANDBOX_CONFIG"

  /** A config from its JSON (as `rw` writes it; missing keys take the defaults), validated. */
  def fromJson(json: String): Either[String, WorkspaceSandboxConfig] =
    scala.util
      .Try(upickle.default.read[WorkspaceSandboxConfig](json))
      .toEither
      .left
      .map(e => s"$ConfigEnvVar is not a valid sandbox config: ${e.getMessage}")
      .flatMap(cfg => validate(cfg).map(_ => cfg))
```

Fix the `networkAllowed` Scaladoc line to: `@param networkAllowed Whether commands may open network sockets; on Linux the runner enforces it (design §4.39)`.

In `RunnerMain`, `profileConfig` becomes the choice between the JSON config and the profile:

```scala
  private def profileConfig: Option[WorkspaceSandboxConfig] = {
    val rawProfile = Option(System.getenv("WORKSPACE_SANDBOX_PROFILE")).map(_.trim).filter(_.nonEmpty)
    val rawJson    = Option(System.getenv(WorkspaceSandboxConfig.ConfigEnvVar)).map(_.trim).filter(_.nonEmpty)
    (rawJson, rawProfile) match {
      case (Some(_), Some(_)) =>
        val msg = s"Set ${WorkspaceSandboxConfig.ConfigEnvVar} or WORKSPACE_SANDBOX_PROFILE, not both"
        logger.error(msg)
        throw new IllegalArgumentException(msg)
      case (Some(json), None) =>
        WorkspaceSandboxConfig.fromJson(json) match {
          case Right(cfg) => Some(cfg)
          case Left(msg) =>
            logger.error(msg)
            throw new IllegalArgumentException(msg)
        }
      case (None, None) => None // let WorkspaceAgentInterfaceImpl apply default Permissive
      case (None, Some(value)) =>
        // ... the existing fromProfileName / validate match, unchanged
    }
  }
```

Update the comment block above `sandboxConfig` to mention `WORKSPACE_SANDBOX_CONFIG`.

In `ContainerisedWorkspace`, add the constructor parameter `val sandboxConfig: Option[WorkspaceSandboxConfig] = None` (document it: "the runner's whole sandbox config, passed as `WORKSPACE_SANDBOX_CONFIG`; `None` leaves the runner on its profile"). Pass it to `dockerRunArgs`. `dockerRunArgs`:

```scala
  private[workspace] def dockerRunArgs(
    containerName: String,
    hostPort: Int,
    containerPort: Int,
    workspaceDir: String,
    imageName: String,
    extraAllowedCommands: Set[String],
    sandboxConfig: Option[WorkspaceSandboxConfig]
  ): Either[String, Seq[String]] = {
    val extra = extraAllowedCommands.toSeq.sorted.mkString(",")
    for {
      _ <- sandboxConfig.fold[Either[String, Unit]](Right(()))(WorkspaceSandboxConfig.validate)
      _ <- sandboxConfig.getOrElse(WorkspaceSandboxConfig.Permissive).withExtraCommands(extra)
    } yield {
      val extraEnv  = if (extra.isEmpty) Seq.empty else Seq("-e", s"${WorkspaceSandboxConfig.ExtraCommandsEnvVar}=$extra")
      val configEnv =
        sandboxConfig.toSeq.flatMap(c => Seq("-e", s"${WorkspaceSandboxConfig.ConfigEnvVar}=${upickle.default.write(c)}"))
      Seq("docker", "run", "-d", "--name", containerName, "-p", s"$hostPort:$containerPort") ++
        extraEnv ++ configEnv ++ Seq("-v", s"$workspaceDir:/workspace", imageName)
    }
  }
```

`docker run` gets an argument vector (a `ProcessBuilder`, line 186), so the JSON needs no shell quoting.

`CodeWorker` takes `sandboxConfig: Option[WorkspaceSandboxConfig] = None` and passes it on. In `CodeGenExample`, add

```scala
  /** sbt fetches the build's dependencies, so its commands need the network (design §4.39). */
  val SandboxConfig: WorkspaceSandboxConfig =
    WorkspaceSandboxConfig.Permissive.copy(networkAllowed = true)
```

and pass `Some(SandboxConfig)` where it builds the `CodeWorker` (line 52).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "workspaceShared/testOnly *WorkspaceSandboxConfigJsonSpec" "workspaceClient/testOnly *ContainerisedWorkspaceDockerArgsSpec" 2>&1 | grep -E '\[error\]|FAILED|Tests:'`
Expected: `Tests: succeeded N, failed 0` for both.

- [ ] **Step 5: Compile everything that uses these, then commit**

Run: `sbt workspaceClient/Test/compile workspaceRunner/Test/compile workspaceSamples/compile it/Test/compile 2>&1 | grep -E '\[error\]' | head`
Expected: no output.

```bash
git add -A modules/workspace
git commit -s -m "feat(workspace): WORKSPACE_SANDBOX_CONFIG gives a container its whole sandbox config (#1983)"
```

---

### Task 2: `CommandConfinement`: the helper's arguments, pure

**Files:**
- Create: `modules/workspace/workspaceRunner/src/main/scala/org/llm4s/runner/CommandConfinement.scala`
- Test: create `modules/workspace/workspaceRunner/src/test/scala/org/llm4s/runner/CommandConfinementSpec.scala`

**Interfaces:**
- Consumes: `WorkspaceSandboxConfig` (`allowedPaths`, `readOnlyPaths`, `networkAllowed`).
- Produces:

```scala
private[runner] object CommandConfinement {
  enum Grant(val flag: String) {
    case ReadExec  extends Grant("--rx")     // system and toolchain directories
    case ReadOnly  extends Grant("--ro")
    case ReadWrite extends Grant("--rw")     // workspace, scratch tmp, /dev/null
    case ReadWriteExec extends Grant("--rwx") // the runner user's home (sdkman's binaries live there)
    case WriteFile extends Grant("--wfile")  // an existing regular file in a directory above a read-only path
  }
  final case class Rule(grant: Grant, path: java.nio.file.Path)

  /** System locations every command needs, as they exist on this machine. */
  def systemRules(home: Path, javaHome: Option[Path]): List[Rule]

  /** The workspace's rules: allowedPaths (or the root), with readOnlyPaths carved out by the walk. */
  def workspaceRules(config: WorkspaceSandboxConfig, realRoot: Path): List[Rule]

  /** The helper's argument vector, up to and including `--`. */
  def helperArgs(helper: Path, status: Path, rules: List[Rule], noNetwork: Boolean): List[String]
}
```

- [ ] **Step 1: Write the failing tests**

Each test builds a fresh tree with `Files.createTempDirectory`, resolved with `toRealPath()` (macOS's `/var` is a link to `/private/var`). Write a small helper in the spec:

```scala
  private def tree(paths: String*): Path = {
    val root = Files.createTempDirectory("confine").toRealPath()
    paths.foreach { p =>
      val target = root.resolve(p)
      if (p.endsWith("/")) Files.createDirectories(target)
      else { Files.createDirectories(target.getParent); Files.writeString(target, "x") }
    }
    root
  }
  private def cfg(allowed: List[String] = Nil, ro: List[String] = Nil) =
    WorkspaceSandboxConfig.Permissive.copy(allowedPaths = allowed, readOnlyPaths = ro)
  import CommandConfinement.{ Grant, Rule }
```

Tests (each one `it should ... in { ... }`):

1. No `allowedPaths`, no `readOnlyPaths`: `workspaceRules(cfg(), root) shouldBe List(Rule(Grant.ReadWrite, root))`.
2. `readOnlyPaths = List("ro")` with tree `ro/`, `src/`, `a.txt`, `.git/`: the result, as a set, is `ReadOnly root`, `ReadOnly root/ro`, `ReadWrite root/src`, `ReadWrite root/.git`, `WriteFile root/a.txt`. There is no `ReadWrite root`.
3. A nested read-only path, `readOnlyPaths = List("a/b")` with tree `a/b/`, `a/c/`, `a/f.txt`, `d/`: `ReadOnly root`, `ReadWrite root/d`, `ReadOnly root/a`, `ReadOnly root/a/b`, `ReadWrite root/a/c`, `WriteFile root/a/f.txt`.
4. `allowedPaths = List("src", "docs")` with tree `src/`, `docs/`, `secret/`: exactly `ReadWrite src`, `ReadWrite docs`. Nothing names `secret` or `root`.
5. `allowedPaths = List("src")`, `readOnlyPaths = List("src")`: exactly `ReadOnly src` (Review Focus 3).
6. `allowedPaths = List("src")`, `readOnlyPaths = List(".")`: exactly `ReadOnly src`. The read-only root contains the allowed path.
7. A missing `allowedPaths` entry (`List("nope")`) gives `Nil`. A missing `readOnlyPaths` entry (`List("nope")`) gives `List(ReadWrite root)` (Review Focus 4).
8. A symlink child of a walked directory gets no rule: tree `ro/`, plus `Files.createSymbolicLink(root.resolve("l"), root.resolve("ro"))`, with `readOnlyPaths = List("ro")`. No rule's path is `root/l`.
9. A `readOnlyPaths` entry that is a symlink binds its target: `Files.createSymbolicLink(root.resolve("ro-link"), root.resolve("ro"))` with `readOnlyPaths = List("ro-link")` produces `ReadOnly root/ro` (Review Focus 4).
10. An `allowedPaths` or `readOnlyPaths` entry leading outside the root (`"../x"`) gives no rule outside `root`. Assert `rules.forall(_.path.startsWith(root))`.
11. `helperArgs(Paths.get("/h"), Paths.get("/s/status"), List(Rule(Grant.ReadOnly, Paths.get("/a")), Rule(Grant.ReadWrite, Paths.get("/b"))), noNetwork = true)` is `List("/h", "--status", "/s/status", "--ro", "/a", "--rw", "/b", "--no-net", "--")`. With `noNetwork = false`, there is no `"--no-net"`.
12. `systemRules(home, None)` contains `Rule(Grant.ReadWriteExec, home)` and `Rule(Grant.ReadWrite, Paths.get("/dev/null"))` when `/dev/null` exists. Every rule's path exists (it filters missing ones).

- [ ] **Step 2: Run them to verify they fail**

Run: `sbt "workspaceRunner/testOnly *CommandConfinementSpec" 2>&1 | grep -E '\[error\]|FAILED|Tests:' | head`
Expected: compile error, `CommandConfinement` not found.

- [ ] **Step 3: Implement**

```scala
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
 * read-only path.
 */
private[runner] object CommandConfinement {

  enum Grant(val flag: String) {
    case ReadExec      extends Grant("--rx")
    case ReadOnly      extends Grant("--ro")
    case ReadWrite     extends Grant("--rw")
    case ReadWriteExec extends Grant("--rwx")
    case WriteFile     extends Grant("--wfile")
  }

  final case class Rule(grant: Grant, path: Path)

  private val SystemDirs = List("/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32", "/etc", "/opt")
  private val ReadOnlySystem = List("/proc", "/sys", "/dev/urandom", "/dev/random", "/dev/zero")

  def systemRules(home: Path, javaHome: Option[Path]): List[Rule] = {
    val rx  = (SystemDirs.map(Paths.get(_)) ++ javaHome).map(Rule(Grant.ReadExec, _))
    val ro  = ReadOnlySystem.map(p => Rule(Grant.ReadOnly, Paths.get(p)))
    val rw  = List(Rule(Grant.ReadWrite, Paths.get("/dev/null")))
    val rwx = List(Rule(Grant.ReadWriteExec, home))
    (rx ++ ro ++ rw ++ rwx).filter(r => Files.exists(r.path))
  }

  def workspaceRules(config: WorkspaceSandboxConfig, realRoot: Path): List[Rule] = {
    def real(p: String): Option[Path] =
      Try(realRoot.resolve(p).normalize().toRealPath()).toOption.filter(_.startsWith(realRoot))
    val bases     = if (config.allowedPaths.isEmpty) List(realRoot) else config.allowedPaths.flatMap(real).distinct
    val readOnly  = config.readOnlyPaths.flatMap(real).distinct
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
        if (under.nonEmpty) carve(child, under)
        else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) List(Rule(Grant.ReadWrite, child))
        else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) List(Rule(Grant.WriteFile, child))
        else Nil // a link, socket or device: reached, if at all, through its target's own rule
      }
    }

  def helperArgs(helper: Path, status: Path, rules: List[Rule], noNetwork: Boolean): List[String] =
    List(helper.toString, "--status", status.toString) ++
      rules.flatMap(r => List(r.grant.flag, r.path.toString)) ++
      (if (noNetwork) List("--no-net") else Nil) ++ List("--")
}
```

`/opt` is read and execute because the runner itself is installed under `/opt/docker`. The command may read the runner's jars; that is harmless, and the runner holds no secrets there. Record this in the design record in Task 5. If test 12 cannot rely on `/dev/null` existing on Windows, guard that assertion with `if (Files.exists(Paths.get("/dev/null")))`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "workspaceRunner/testOnly *CommandConfinementSpec" 2>&1 | grep -E '\[error\]|FAILED|Tests:'`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add modules/workspace/workspaceRunner/src
git commit -s -m "feat(workspace-runner): CommandConfinement turns the sandbox config into Landlock grants (#1983)"
```

---

### Task 3: `llm4s-confine`, the C helper, and its sbt build for tests

**Files:**
- Create: `modules/workspace/workspaceRunner/src/main/c/llm4s-confine.c`
- Modify: `build.sbt:1279-1300` (the `workspaceRunner` project)
- Test: create `modules/workspace/workspaceRunner/src/test/scala/org/llm4s/runner/ConfineHelperSpec.scala`

**Interfaces:**
- Produces: the binary's CLI, `llm4s-confine --status FILE [--rx P|--ro P|--rw P|--rwx P|--wfile P]... [--no-net] -- /abs/program args...`; exit 125 + status file when it cannot confine; the system property `llm4s.workspace.confine`, set for `workspaceRunner` tests on Linux to the compiled helper.

- [ ] **Step 1: Write the failing spec**

`ConfineHelperSpec` runs the helper directly with `ProcessBuilder`, not through the runner. On a non-Linux OS every test is cancelled (`assume`). On Linux, a missing property is a failure, not a skip.

```scala
package org.llm4s.runner

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._

class ConfineHelperSpec extends AnyFlatSpec with Matchers {

  private val isLinux = System.getProperty("os.name", "").startsWith("Linux")

  private def helper: Path = {
    assume(isLinux, "llm4s-confine is Linux-only")
    val p = Option(System.getProperty("llm4s.workspace.confine"))
      .getOrElse(fail("llm4s.workspace.confine is not set: the build compiles the helper for Linux tests"))
    Paths.get(p)
  }

  private val system = List("--rx", "/usr", "--rx", "/bin", "--rx", "/lib", "--rx", "/etc") ++
    List("/lib64").filter(p => Files.exists(Paths.get(p))).flatMap(p => List("--rx", p)) ++
    List("--rw", "/dev/null")

  private final case class Ran(exit: Int, out: String, err: String)

  private def run(status: Path, grants: List[String], program: String*): Ran = {
    val pb = new ProcessBuilder((List(helper.toString, "--status", status.toString) ++ grants ++ ("--" :: program.toList)).asJava)
    val p  = pb.start()
    val out = new String(p.getInputStream.readAllBytes())
    val err = new String(p.getErrorStream.readAllBytes())
    Ran(p.waitFor(), out, err)
  }

  private def fresh(): (Path, Path) = {
    val dir = Files.createTempDirectory("confine-spec").toRealPath()
    (dir, dir.resolve("status"))
  }

  "llm4s-confine" should "run a program it may run, with its exit code" in {
    val (dir, status) = fresh()
    Files.writeString(dir.resolve("f"), "hello")
    val r = run(status, system ++ List("--ro", dir.toString), "/bin/cat", dir.resolve("f").toString)
    (r.exit, r.out) shouldBe ((0, "hello"))
    Files.exists(status) shouldBe false
  }

  it should "refuse a read outside every grant" in {
    val (dir, status) = fresh()
    val other = Files.createTempDirectory("confine-other")
    Files.writeString(other.resolve("secret"), "s")
    val r = run(status, system ++ List("--ro", dir.toString), "/bin/cat", other.resolve("secret").toString)
    r.exit should not be 0
    r.err should include("Permission denied")
  }

  it should "refuse a write under a read-only grant, including truncation" in {
    val (dir, status) = fresh()
    Files.writeString(dir.resolve("f"), "keep")
    run(status, system ++ List("--ro", dir.toString), "/usr/bin/touch", dir.resolve("new").toString).exit should not be 0
    run(status, system ++ List("--ro", dir.toString), "/usr/bin/truncate", "-s", "0", dir.resolve("f").toString).exit should not be 0
    Files.readString(dir.resolve("f")) shouldBe "keep"
  }

  it should "allow writes under a read-write grant" in {
    val (dir, status) = fresh()
    run(status, system ++ List("--rw", dir.toString), "/usr/bin/touch", dir.resolve("new").toString).exit shouldBe 0
  }

  it should "allow writing an existing file under --wfile but not creating a sibling" in {
    val (dir, status) = fresh()
    val f = dir.resolve("f")
    Files.writeString(f, "a")
    val grants = system ++ List("--ro", dir.toString, "--wfile", f.toString)
    run(status, grants, "/usr/bin/truncate", "-s", "0", f.toString).exit shouldBe 0
    run(status, grants, "/usr/bin/touch", dir.resolve("g").toString).exit should not be 0
  }

  it should "refuse an internet socket with --no-net, and allow it without" in {
    val (dir, status) = fresh()
    val server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    try {
      // bash's /dev/tcp opens a TCP socket; bash is the test's tool, not a workspace command
      val connect = List("/bin/bash", "-c", s"exec 3<>/dev/tcp/127.0.0.1/${server.getLocalPort}")
      run(status, system ++ List("--no-net"), connect: _*).exit should not be 0
      run(status, system, connect: _*).exit shouldBe 0
    } finally server.close()
  }

  it should "leave a program that needs no network working under --no-net" in {
    val (_, status) = fresh()
    run(status, system ++ List("--no-net"), "/usr/bin/getent", "passwd", "root").exit shouldBe 0
  }

  it should "report unavailable through the status file and exit 125 on a bad argument" in {
    val (_, status) = fresh()
    val r = run(status, List("--bogus", "x"), "/bin/true")
    r.exit shouldBe 125
    Files.readString(status) should include("--bogus")
    r.err should startWith("llm4s-confine: unavailable:")
  }

  it should "pass a program's own exit 125 through without a status file" in {
    val (_, status) = fresh()
    val r = run(status, system, "/bin/sh", "-c", "exit 125")
    r.exit shouldBe 125
    Files.exists(status) shouldBe false
  }

  it should "skip a grant whose path does not exist" in {
    val (dir, status) = fresh()
    run(status, system ++ List("--ro", dir.resolve("missing").toString), "/bin/true").exit shouldBe 0
  }
}
```

Check that `/bin/sh`, `/bin/bash`, `/usr/bin/getent`, `/usr/bin/truncate` and `/usr/bin/touch` exist on the GitHub Ubuntu runner (they do on `ubuntu-latest`). Use `/bin/true` (`/usr/bin/true` on merged-usr systems is the same file, because `/bin` is a link to `/usr/bin` there). `getent` may open an `AF_UNIX` socket (nscd) and falls back without one, so this test proves only that `--no-net` leaves an ordinary program working.

- [ ] **Step 2: Wire the build, and run the spec to verify it fails**

In `build.sbt`, inside `workspaceRunner`'s `.settings(...)`, add:

```scala
    // llm4s-confine (design §4.39), compiled for this machine so the runner's tests confine commands as the image does.
    // Linux only: elsewhere commands run unconfined and the helper is never used. A Linux machine without `cc` fails
    // here, by design: on Linux an unconfined command is refused, so the tests could not run.
    Test / resourceGenerators += Def.task {
      val log = streams.value.log
      if (!sys.props.getOrElse("os.name", "").startsWith("Linux")) Nil
      else {
        val src = (Compile / sourceDirectory).value / "c" / "llm4s-confine.c"
        val out = target.value / "llm4s-confine"
        if (!out.exists || src.lastModified > out.lastModified) {
          val cmd = Seq("cc", "-O2", "-Wall", "-Wextra", "-Werror", "-o", out.getAbsolutePath, src.getAbsolutePath)
          log.info(s"Compiling ${src.getName}")
          if (scala.sys.process.Process(cmd).! != 0) sys.error(s"Could not compile ${src}: ${cmd.mkString(" ")}")
        }
        sys.props("llm4s.workspace.confine") = out.getAbsolutePath
        Nil
      }
    }.taskValue,
```

The tests run unforked (`workspaceRunner` sets no `Test / fork`), so setting the property in sbt's JVM reaches them. Check first with `grep -n 'Test / fork' build.sbt` around lines 1279-1300. If the project forks, use `Test / javaOptions += s"-Dllm4s.workspace.confine=${out}"` instead.

Run (on Linux; on macOS the spec cancels): `sbt "workspaceRunner/testOnly *ConfineHelperSpec" 2>&1 | grep -E '\[error\]|FAILED|Tests:|canceled'`
Expected on Linux: the compile step fails ("Could not compile"). On macOS: every test canceled.

If you are on macOS, run the Linux steps in a container from the repo root:
`docker run --rm -v "$PWD":/src -w /src eclipse-temurin:21-jdk bash -c 'apt-get update -qq && apt-get install -y -qq gcc libc6-dev >/dev/null && cc -O2 -Wall -Wextra -Werror -o /tmp/c modules/workspace/workspaceRunner/src/main/c/llm4s-confine.c && echo built'`
Also run the whole spec's commands by hand there with `/tmp/c`. Docker Desktop's kernel must support Landlock (`cat /sys/kernel/security/lsm` contains `landlock`); if it does not, record that and rely on CI. The `test` job runs on Linux.

- [ ] **Step 3: Write the helper**

`modules/workspace/workspaceRunner/src/main/c/llm4s-confine.c`:

```c
/*
 * llm4s-confine: runs one workspace command under the runner's sandbox (design 4.39, #1983).
 *
 *   llm4s-confine --status FILE [--rx P|--ro P|--rw P|--rwx P|--wfile P]... [--no-net] -- /abs/program args...
 *
 * Installs a Landlock ruleset (ABI v3 or later) granting only the listed paths, sets no_new_privs, installs a seccomp
 * filter refusing non-Unix sockets when --no-net is given, then execs the program in its own place: same pid, session
 * and process group, so the runner's kill still finds it. Every process the program starts inherits all of it.
 *
 * Threat model: model-issued commands inside the workspace container; the container is the outer boundary. When it
 * cannot confine, it writes why to FILE and stderr and exits 125 without running the program; the runner reports
 * SANDBOX_UNAVAILABLE only when FILE exists, so a program's own exit 125 is not mistaken for it.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/landlock.h>
#include <linux/seccomp.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define UNAVAILABLE 125

static const char *status_path;

static void unavailable(const char *fmt, ...) {
  char msg[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(msg, sizeof msg, fmt, ap);
  va_end(ap);
  fprintf(stderr, "llm4s-confine: unavailable: %s\n", msg);
  if (status_path) {
    int fd = open(status_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd >= 0) {
      ssize_t ignored = write(fd, msg, strlen(msg));
      (void)ignored;
      close(fd);
    }
  }
  exit(UNAVAILABLE);
}

#define FS_READ (LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR)
#define FS_EXEC LANDLOCK_ACCESS_FS_EXECUTE
#define FS_WRITE                                                                                                     \
  (LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REMOVE_FILE |                 \
   LANDLOCK_ACCESS_FS_MAKE_CHAR | LANDLOCK_ACCESS_FS_MAKE_DIR | LANDLOCK_ACCESS_FS_MAKE_REG |                       \
   LANDLOCK_ACCESS_FS_MAKE_SOCK | LANDLOCK_ACCESS_FS_MAKE_FIFO | LANDLOCK_ACCESS_FS_MAKE_BLOCK |                    \
   LANDLOCK_ACCESS_FS_MAKE_SYM | LANDLOCK_ACCESS_FS_REFER | LANDLOCK_ACCESS_FS_TRUNCATE)
#define FS_FILE_ONLY                                                                                                 \
  (LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_EXECUTE |                      \
   LANDLOCK_ACCESS_FS_TRUNCATE)
#define FS_HANDLED (FS_READ | FS_EXEC | FS_WRITE)

static void add_rule(int ruleset, const char *path, __u64 access) {
  int fd = open(path, O_PATH | O_CLOEXEC | O_NOFOLLOW);
  if (fd < 0) {
    if (errno == ENOENT) return; /* a path missing now has nothing to grant: skipped (design 4.39) */
    unavailable("cannot open %s: %s", path, strerror(errno));
  }
  struct stat st;
  if (fstat(fd, &st) != 0) unavailable("cannot stat %s: %s", path, strerror(errno));
  if (S_ISLNK(st.st_mode)) { /* never follow: the runner passes real paths, a link here is a race */
    close(fd);
    return;
  }
  if (!S_ISDIR(st.st_mode)) access &= FS_FILE_ONLY;
  struct landlock_path_beneath_attr attr = {.allowed_access = access, .parent_fd = fd};
  if (syscall(SYS_landlock_add_rule, ruleset, LANDLOCK_RULE_PATH_BENEATH, &attr, 0) != 0)
    unavailable("cannot grant %s: %s", path, strerror(errno));
  close(fd);
}

#if defined(__x86_64__)
#define NATIVE_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define NATIVE_ARCH AUDIT_ARCH_AARCH64
#endif

static void deny_network(void) {
#ifndef NATIVE_ARCH
  unavailable("--no-net is not supported on this architecture");
#else
  struct sock_filter filter[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NATIVE_ARCH, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS), /* a foreign ABI (i386 socketcall) */
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
#if defined(__x86_64__)
    BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000 /* __X32_SYSCALL_BIT */, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS), /* the x32 ABI */
#endif
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_setup, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),                     /* io_uring opens sockets itself */
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 2, 0),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socketpair, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),                             /* any other call */
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])), /* socket(pair): the domain */
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
  };
  struct sock_fprog prog = {.len = sizeof filter / sizeof filter[0], .filter = filter};
  if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &prog) != 0)
    unavailable("cannot install the network filter: %s", strerror(errno));
#endif
}
```

Jump offsets count instructions skipped after the jump. `socket` true → skip 2 to the domain load; `socketpair` true →
skip 1 to the same load; neither → the ALLOW. The Step 1 socket test proves the filter; check the offsets by hand once.

The `main`:

```c
int main(int argc, char **argv) {
  int i = 1, no_net = 0;
  if (argc > 2 && strcmp(argv[1], "--status") == 0) {
    status_path = argv[2];
    i = 3;
  } else {
    unavailable("usage: llm4s-confine --status FILE [grants] [--no-net] -- program args");
  }

  int abi = (int)syscall(SYS_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
  if (abi < 0) unavailable("Landlock is not available in this kernel: %s", strerror(errno));
  if (abi < 3) unavailable("Landlock ABI %d is below the v3 this sandbox needs (kernel 6.2)", abi);

  struct landlock_ruleset_attr ra = {.handled_access_fs = FS_HANDLED};
  int ruleset = (int)syscall(SYS_landlock_create_ruleset, &ra, sizeof ra, 0);
  if (ruleset < 0) unavailable("cannot create the Landlock ruleset: %s", strerror(errno));

  for (; i < argc && strcmp(argv[i], "--") != 0; i++) {
    const char *opt = argv[i];
    if (strcmp(opt, "--no-net") == 0) { no_net = 1; continue; }
    if (i + 1 >= argc) unavailable("%s needs a path", opt);
    const char *path = argv[++i];
    if (strcmp(opt, "--rx") == 0) add_rule(ruleset, path, FS_READ | FS_EXEC);
    else if (strcmp(opt, "--ro") == 0) add_rule(ruleset, path, FS_READ);
    else if (strcmp(opt, "--rw") == 0) add_rule(ruleset, path, FS_READ | FS_WRITE);
    else if (strcmp(opt, "--rwx") == 0) add_rule(ruleset, path, FS_READ | FS_WRITE | FS_EXEC);
    else if (strcmp(opt, "--wfile") == 0)
      add_rule(ruleset, path, LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_TRUNCATE);
    else unavailable("unknown option %s", opt);
  }
  if (i >= argc || i + 1 >= argc) unavailable("no program after --");
  char **program = &argv[i + 1];

  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) unavailable("cannot set no_new_privs: %s", strerror(errno));
  if (syscall(SYS_landlock_restrict_self, ruleset, 0) != 0)
    unavailable("cannot enforce the Landlock ruleset: %s", strerror(errno));
  close(ruleset);
  if (no_net) deny_network();

  execv(program[0], program);
  fprintf(stderr, "llm4s-confine: cannot run %s: %s\n", program[0], strerror(errno));
  return errno == ENOENT ? 127 : 126; /* the program's own failure to start, as a shell reports it: not 125 */
}
```

Note the ordering: everything that can fail with `unavailable` happens before `execv`. `--wfile` grants read too, because the walk's `ReadOnly` on the parent directory already allows reading it.

- [ ] **Step 4: Run the spec to verify it passes (Linux)**

Run: `sbt "workspaceRunner/testOnly *ConfineHelperSpec" 2>&1 | grep -E '\[error\]|FAILED|Tests:'`
Expected on Linux: `Tests: succeeded 10, failed 0`. If you can only run it in the Docker container from Step 2, run each case's command line by hand there. Then push and read the `test` job's result for this spec before calling the task done.

- [ ] **Step 5: Commit**

```bash
git add build.sbt modules/workspace/workspaceRunner/src
git commit -s -m "feat(workspace-runner): llm4s-confine applies Landlock and a no-network seccomp filter (#1983)"
```

---

### Task 4: Confine every `execute` command in the runner

**Files:**
- Create: `modules/workspace/workspaceRunner/src/main/scala/org/llm4s/runner/Confiner.scala`
- Modify: `modules/workspace/workspaceRunner/src/main/scala/org/llm4s/runner/WorkspaceAgentInterfaceImpl.scala`. That covers the constructor (35-43); `prepareCommandOrThrow`, adding the helper after the env and `PATH` set-up (1780-1804); `executeCommand` (1836-1956); and `PreparedCommand` (1973).
- Modify: `modules/workspace/workspaceRunner/src/main/scala/org/llm4s/runner/WebSocketCommandExecutor.scala` (`stream`'s completion, 279-320)
- Modify: `modules/workspace/workspaceRunner/src/main/scala/org/llm4s/runner/RunnerMain.scala` (log the confinement once at startup)
- Test: create `modules/workspace/workspaceRunner/src/test/scala/org/llm4s/runner/CommandConfinementExecuteSpec.scala`

**Interfaces:**
- Consumes: `CommandConfinement.{systemRules, workspaceRules, helperArgs}` (Task 2), and the helper and the `llm4s.workspace.confine` property (Task 3).
- Produces:

```scala
/** Where `llm4s-confine` is, on Linux; None elsewhere. */
final private[runner] case class Confiner(helper: Path)
private[runner] object Confiner {
  val HelperProperty = "llm4s.workspace.confine"
  val DefaultHelper: Path = Paths.get("/usr/local/bin/llm4s-confine")
  def platform(): Option[Confiner]   // Linux: Some(property or DefaultHelper); else None
}

/** A confined command's own directory: `tmp` (granted, TMPDIR) and `status` (not granted). */
final private[runner] case class CommandScratch(dir: Path) {
  def tmp: Path = dir.resolve("tmp")
  def status: Path = dir.resolve("status")
  /** The helper's reason, when it could not confine: status 125 and the file exists. */
  def unavailable(exitCode: Int): Option[String]
  def delete(): Unit
}

final private[runner] case class PreparedCommand(builder: ProcessBuilder, timeout: FiniteDuration, scratch: Option[CommandScratch] = None)
```

The new `WorkspaceAgentInterfaceImpl` constructor parameter is `confiner: Option[Confiner] = Confiner.platform()`, last in the list.

- [ ] **Step 1: Write the failing tests**

`CommandConfinementExecuteSpec` goes through the runner's own `executeCommand`. Copy the setup style of `WorkspaceCommandsSpec`: a temp root, `new WorkspaceAgentInterfaceImpl(root.toString, isWindows = false, Some(config))`.

Platform-independent tests (run everywhere):

```scala
  "CommandScratch.unavailable" should "be the status file's reason only when the exit is 125 and the file exists" in {
    val s = CommandScratch(Files.createTempDirectory("scratch"))
    Files.createDirectories(s.tmp)
    s.unavailable(125) shouldBe None
    Files.writeString(s.status, "Landlock ABI 2 is below")
    s.unavailable(1) shouldBe None
    s.unavailable(125) shouldBe Some("Landlock ABI 2 is below")
    s.delete()
    Files.exists(s.dir) shouldBe false
  }

  "a runner off Linux" should "start commands without the helper" in {
    val ws = new WorkspaceAgentInterfaceImpl(root.toString, isWindows = false, None, confiner = None)
    val prepared = ws.prepareCommand("ls", None, None, None).fold(e => fail(e.error), identity)
    prepared.builder.command().asScala.exists(_.contains("llm4s-confine")) shouldBe false
    prepared.scratch shouldBe None
  }

  "a runner whose helper cannot confine" should "refuse the command SANDBOX_UNAVAILABLE, with the reason" in {
    assume(!isWindowsHost)
    // a stand-in helper: what llm4s-confine does when Landlock is missing
    val fake = Files.createTempFile("fake-confine", "")
    Files.writeString(fake, "#!/bin/sh\nwhile [ \"$1\" != --status ]; do shift; done\nprintf 'Landlock ABI 2 is below the v3' > \"$2\"\nexit 125\n")
    fake.toFile.setExecutable(true)
    val ws = new WorkspaceAgentInterfaceImpl(root.toString, isWindows = false, None, confiner = Some(Confiner(fake)))
    val e = intercept[WorkspaceAgentException](ws.executeCommand("ls"))
    e.code shouldBe "SANDBOX_UNAVAILABLE"
    e.error should include("Landlock ABI 2")
  }

  it should "refuse SANDBOX_UNAVAILABLE when the helper is missing" in {
    val ws = new WorkspaceAgentInterfaceImpl(root.toString, isWindows = false, None,
      confiner = Some(Confiner(root.resolve("no-such-helper"))))
    ws.prepareCommand("ls", None, None, None).swap.map(_.code) shouldBe Right("SANDBOX_UNAVAILABLE")
  }
```

`setsid` is applied only on Linux, so on macOS the fake helper runs directly. That is fine: these tests are about the mapping.

Linux tests, through the real helper. Guard each one with `assume(isLinux)`, and on Linux `Confiner.platform()` must find the helper (see Task 3). Use `Permissive` with `ReadWriteCommands` (cp, mv, rm, touch, chmod). In each test, build the tree, run the command, then assert both the exit code and the file system:

1. `readOnlyPaths = List("ro")`: `rm ro/a`, `mv ro/a b`, `cp b ro/new`, `touch ro/new` and `chmod 777 ro/a` each give a non-zero exit with stderr containing `Permission denied`, and `ro/a` keeps its content. Note that `CommandPolicy` may refuse some of these first, with its own code. If one throws a `WorkspaceAgentException` instead of exiting, that also counts as refused: assert that the file system is unchanged either way.
2. A symlink into a read-only path (Review Focus 1): create the link with `Files.createSymbolicLink(root.resolve("link"), root.resolve("ro/a"))`, then run `cp b link`. It is refused, and `ro/a` is unchanged.
3. `allowedPaths = List("src")` with `secret/s` present: `cat secret/s`, run with `workingDirectory = Some("src")` and the argument `../secret/s`. `CommandPolicy` may refuse this as outside the allowed paths, or the kernel refuses it. Assert that `secret/s`'s content never appears in stdout.
4. The pinned limitation: with `readOnlyPaths = List("ro")`, `touch new` at the root fails, and `touch src/new` succeeds.
5. Network: `networkAllowed = false` with `curl` added (`WorkspaceSandboxConfig.Permissive.withExtraCommands("curl")`), against a `ServerSocket` the test opens on 127.0.0.1. The command is `curl -sS --max-time 5 http://127.0.0.1:<port>/`; its exit is non-zero, and the server's `accept` (on a thread with `setSoTimeout(3000)`) gets no connection. With `networkAllowed = true`, the same command connects: the test's server accepts and answers `HTTP/1.0 200 OK\r\n\r\nok`, and curl prints `ok`. Skip with `assume` if `/usr/bin/curl` is absent.
6. A program's own exit 125 (Review Focus 2): no allowlisted program exits 125, so cover it at the helper level instead (Task 3 already does), plus the `CommandScratch.unavailable(125)` unit test above. Write a comment in this spec saying so.
7. Scratch cleanup (Review Focus 5): run `ls` and check that no `llm4s-cmd-*` directory remains under the scratch parent. Then run a command that times out, `tail -f somefile` with `timeout = Some(1.second)`, and check the same. Expose the scratch parent as `private[runner] def scratchParent: Path` on `WorkspaceAgentInterfaceImpl` so the test can look.
8. An unconfined control: the same `rm ro/a` with `confiner = None` succeeds. Then the refusals in 1-4 come from confinement, not from something else.

- [ ] **Step 2: Run them to verify they fail**

Run: `sbt "workspaceRunner/testOnly *CommandConfinementExecuteSpec" 2>&1 | grep -E '\[error\]|FAILED|Tests:' | head`
Expected: compile errors (`Confiner`, `CommandScratch`, the `confiner` parameter and `PreparedCommand.scratch` not found).

- [ ] **Step 3: Implement**

`Confiner.scala`:

```scala
package org.llm4s.runner

import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._
import scala.util.Try

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

  def unavailable(exitCode: Int): Option[String] =
    if (exitCode != 125 || !Files.exists(status)) None
    else Some(Try(Files.readString(status)).getOrElse("the sandbox could not be applied"))

  def delete(): Unit =
    Try(Files.walk(dir).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))): Unit
}
```

In `WorkspaceAgentInterfaceImpl`:

- Constructor: add `confiner: Option[Confiner] = Confiner.platform()` as the last parameter, documented in the Scaladoc `@param` list.
- Add `private[runner] val scratchParent: Path = Files.createTempDirectory("llm4s-runner-")`. It is created by the runner, outside the workspace and outside the home directory. Note that `/tmp` is not granted, so commands cannot reach it. Before relying on that, check `java.io.tmpdir` in the image (`/tmp`) and that `user.home` (`/root`) is not under it.
- Add `private val home: Path = Paths.get(System.getProperty("user.home")).toAbsolutePath` and `private val javaHome = Option(System.getProperty("java.home")).map(Paths.get(_))`.
- At the end of `prepareCommandOrThrow`, replace `PreparedCommand(builder, timeout.getOrElse(...))` with:

```scala
    val scratch = confiner.map { c =>
      if (!Files.isExecutable(c.helper))
        throw new WorkspaceAgentException(
          s"Commands are confined on Linux and the sandbox helper ${c.helper} is missing; the command was not run",
          "SANDBOX_UNAVAILABLE",
          None
        )
      val s = CommandScratch(Files.createTempDirectory(scratchParent, "llm4s-cmd-"))
      Files.createDirectories(s.tmp)
      val rules = CommandConfinement.systemRules(home, javaHome) ++
        CommandConfinement.workspaceRules(config, effective(rootPath)) :+
        CommandConfinement.Rule(CommandConfinement.Grant.ReadWrite, s.tmp)
      builder.command(
        (CommandConfinement.helperArgs(c.helper, s.status, rules, noNetwork = !config.networkAllowed) ++
          builder.command().asScala).asJava
      ): Unit
      builder.environment().put("TMPDIR", s.tmp.toString)
      s
    }
    PreparedCommand(builder, timeout.getOrElse(config.defaultCommandTimeout), scratch)
```

`ProcessRegistry.inOwnGroup` then puts `setsid` before the helper, because the helper's path is absolute. Check this with a unit assertion: on Linux, `prepared.builder.command().get(0)` is the helper before `spawn`. Check too that `setsid` is applied inside `spawn` (`ProcessRegistry.scala:100`).

The `cmd.exe` branch is Windows-only. There `confiner` is `None`, so it never combines with confinement.

- `executeCommand`: wrap everything after `val prepared = ...` so that the scratch is always deleted, using `Try(...)` plus `prepared.scratch.foreach(_.delete())` in every exit path (the scalafix bans `try/finally`; follow how the file already uses `Try { ... }.recover`). After `exitCode` is known:

```scala
    prepared.scratch.flatMap(_.unavailable(exitCode)).foreach { reason =>
      throw new WorkspaceAgentException(s"The command could not be confined, so it was not run: $reason", "SANDBOX_UNAVAILABLE", None)
    }
```

  Do this before building `ExecuteCommandResponse`. The simplest shape that deletes the scratch on every path: compute `val outcome = Try { ...existing body producing ExecuteCommandResponse... }`, then `prepared.scratch.foreach(_.delete())`, then `outcome.get`.

- `WebSocketCommandExecutor.stream`, in the `onComplete` `Success(rawExitCode)` branch, before sending the `ExecuteCommandResponse`:

```scala
          prepared.scratch.flatMap(_.unavailable(rawExitCode)) match {
            case Some(reason) =>
              prepared.scratch.foreach(_.delete())
              fail(s"The command could not be confined, so it was not run: $reason", "SANDBOX_UNAVAILABLE", None)
            case None =>
              prepared.scratch.foreach(_.delete())
              // ...the existing response and CommandCompletedMessage
          }
```

  Also delete the scratch in the `survivedKill` branch, in the `Failure` branch, and on the paths in `execute` where the prepared command never starts: `NotStarted`, refused, the early-cancel exit 143, and a failed announce. Wrap each in one local `def discard(): Unit = prepared.scratch.foreach(_.delete())`.

- `RunnerMain`: after building `workspaceInterface`, log once:

```scala
  Confiner.platform() match {
    case Some(c) => logger.info(s"Commands are confined by ${c.helper} (Landlock, and seccomp when networkAllowed=false)")
    case None    => logger.warn("Not on Linux: commands run unconfined; only the command policy applies (design §4.39)")
  }
```

- [ ] **Step 4: Run the new spec and every runner spec**

Run: `sbt workspaceRunner/test 2>&1 | grep -E '\[error\]|FAILED|Tests:|canceled' | tail -20`
Expected: no failures. On Linux every existing spec now runs confined, so a failure in, say, `WorkspaceCommandsSpec` means a grant is missing. Read the failing command's stderr for `Permission denied`, find the path, and decide whether it belongs in `systemRules` (a system file) or is a real refusal. Never widen the workspace grants to make a test pass. If you are on macOS, these tests run unconfined, so the Linux run in CI is the real check: push the branch and read the `test` job.

- [ ] **Step 5: Commit**

```bash
git add modules/workspace/workspaceRunner/src
git commit -s -m "feat(workspace-runner): confine every execute command on Linux; SANDBOX_UNAVAILABLE when it cannot (#1983)"
```

---

### Task 5: The image, the live tier, docs and the follow-up issue

**Files:**
- Modify: `project/WorkspaceRunnerDocker.scala` (mapping plus compile step)
- Modify: `build.sbt` (only if the Docker mapping must live there; prefer `WorkspaceRunnerDocker.settings`)
- Create: `modules/it/src/test/scala/org/llm4s/workspace/WorkspaceConfinementIntegrationSpec.scala`
- Modify: `modules/workspace/workspaceRunner/src/test/scala/org/llm4s/runner/WorkspaceCommandsSpec.scala` (a new live command string, kept in step)
- Modify: `docs/reference/workspace-sandbox.md` (the `networkAllowed` row at 119, the matrix row at 658, Phase 2 at 689-700, plus a new "Command confinement" section)
- Modify: `docs/design/typed-agent-runtime/4.39-workspace-command-confinement.md` (`/opt`, `/proc`, `/sys`, `/dev/*` grants; the system property instead of an environment variable)
- Modify: `CHANGELOG.md` (one entry)

**Interfaces:**
- Consumes: everything above; `ContainerisedWorkspace(..., extraAllowedCommands, sandboxConfig)` from Task 1.

- [ ] **Step 1: Build the helper into the image**

In `WorkspaceRunnerDocker.settings`, add a mapping, so the source is in the build context under `/opt/docker`:

```scala
    Docker / mappings += (baseDirectory.value / "src" / "main" / "c" / "llm4s-confine.c") -> "/opt/docker/confine/llm4s-confine.c",
```

Append to `devToolingCommands`, after the Scala install:

```scala
    // llm4s-confine (design §4.39): every execute command runs under it. Static, so it needs nothing from the image at
    // run time; the compiler is removed in the same layer.
    Cmd(
      "RUN",
      "apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && " +
        "gcc -O2 -static -Wall -Wextra -Werror -o /usr/local/bin/llm4s-confine /opt/docker/confine/llm4s-confine.c && " +
        "apt-get purge -y gcc libc6-dev && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*"
    ),
```

Check with `sbt workspaceRunner/Docker/stage && grep -n 'confine' modules/workspace/workspaceRunner/target/docker/stage/Dockerfile`. The `COPY` of `opt` must come before the `RUN`. The plugin's main stage copies `/opt/docker` from `stage0`, and `dockerCommands ++=` appends after it. If the file lands somewhere else, adjust the path in the `RUN`.

Then build and check the image:

```bash
sbt workspaceRunner/Docker/publishLocal 2>&1 | tail -5
docker run --rm --entrypoint /usr/local/bin/llm4s-confine llm4s/workspace-runner:<tag> --status /tmp/s --rx / -- /bin/true; echo "exit $?"
```

Expected: `exit 0`, or `exit 125` with "Landlock is not available" on a Docker Desktop kernel without Landlock. Record which.

- [ ] **Step 2: Write the live suite**

`WorkspaceConfinementIntegrationSpec`, tagged `@Workspace` (exactly one tier tag), modelled on `WorkspaceBackendsIntegrationSpec`: the same `Tier.require` gate and the same image property. It starts its own `ContainerisedWorkspace` with `sandboxConfig = Some(...)` and `extraAllowedCommands = Set("curl")`. Use a different `HostPort` from the other suites in `modules/it`; grep for `HostPort` to pick an unused one.

1. **Read-only:** start with `sandboxConfig = Some(Permissive.copy(readOnlyPaths = List("ro")))` and a host dir holding `ro/a` and `b`. `executeCommand("rm ro/a")` exits non-zero or is refused, and `cp b ro/a` the same; `ro/a` on the host is unchanged.
2. **Allowed paths:** `allowedPaths = List("src")`, with `secret/s` on the host. `cat ../secret/s` from `workingDirectory = Some("src")` never prints the secret.
3. **No network:** `networkAllowed = false`. `curl -sS --max-time 5 http://127.0.0.1:8080/` (the runner's own port inside the container) exits non-zero.
4. **Network allowed:** `networkAllowed = true`. The same command exits 0. The runner's `/` answers.

Each case needs its own container, because the config is fixed at container start. Keep the suite to two containers: case A covers 1 and 3 with `readOnlyPaths = List("ro")` and `networkAllowed = false`; case B covers 2 and 4 with `allowedPaths = List("src")` and `networkAllowed = true`. In case B, `curl` runs from `src` as its working directory.

Add those exact command strings to `WorkspaceCommandsSpec`. Its header requires every live command string to be run there too. Assert there that the runner prepares them, `prepareCommand(...).isRight`.

Run (needs Docker and the image from Step 1): `LLM4S_DOCKER_TESTS=true sbt "it/testOnly org.llm4s.workspace.WorkspaceConfinementIntegrationSpec" 2>&1 | grep -E 'FAILED|Tests:|canceled'`
Expected: passes where the kernel has Landlock. Otherwise every command is refused `SANDBOX_UNAVAILABLE`; note the kernel and rely on the `Workspace Integration` CI job (#1982 runs it on this PR).

- [ ] **Step 3: Docs**

In `docs/reference/workspace-sandbox.md`:
- Row 119: `| networkAllowed | Boolean | Whether commands may open network sockets (default false). Enforced on Linux by the runner's seccomp filter |`.
- Row 658: `| Network restrictions | Enforced on Linux (seccomp) | ... |`, matching the table's other columns.
- The Phase 2 section (689-700): paths for commands and network are now enforced on Linux by kernel confinement (#1983). Drop the "Open" bullet.
- A new `## Command confinement` section, about 25 lines. Cover what is granted (copy the list from design 4.39), the read-only limitation, `SANDBOX_UNAVAILABLE`, off-Linux behaviour, `WORKSPACE_SANDBOX_CONFIG` (with a `ContainerisedWorkspace(..., sandboxConfig = Some(...))` example), and the daemon-kill gap with the follow-up issue's link. Run `scripts/check-docs-site-links.sh` afterwards.

In design 4.39: add `/opt`, `/proc`, `/sys` and `/dev/{urandom,random,zero}` to the grant list. `/proc` is readable, but Landlock's ptrace scoping stops reads of another process's `mem`/`environ`. Replace the sentence "`RunnerMain` takes the helper's path from an environment variable" with the `llm4s.workspace.confine` system property. Remove "`/proc` of other processes" from the list of unreachable places, and put the ptrace sentence in its place.

CHANGELOG, as the first bullet under `### Agent harness (Phase 3)` in `## [Unreleased]`:

```markdown
- **Changed** on Linux every workspace `execute` command runs under kernel confinement (Landlock paths, seccomp
  no-network): `readOnlyPaths`/`allowedPaths` bind commands, and **commands have no network unless
  `networkAllowed = true`**; `WORKSPACE_SANDBOX_CONFIG` configures a container [#1983](https://github.com/llm4s/llm4s/issues/1983); [design §4.39](docs/design/typed-agent-runtime/4.39-workspace-command-confinement.md).
```

- [ ] **Step 4: File the follow-up issue and link it**

```bash
gh issue create --title "[ENHANCEMENT] Workspace runner: a cgroup per command, so a daemon cannot outlive its command" --body "$(cat <<'EOF'
Follow-up to #1983 (design §4.39). Commands are now confined by Landlock and seccomp, which every descendant inherits, so a daemon a command starts stays confined. It still escapes the runner's kill: a new session leaves the command's process group, and only a cgroup follows it.

A cgroup per command needs a writable cgroup v2 hierarchy in the container (a private cgroup namespace with a writable `/sys/fs/cgroup`), which a default `docker run` does not give. This issue decides whether `ContainerisedWorkspace` should start containers that way, what users running the image themselves must add, and what the runner does without it.

Acceptance: a command that starts a daemon (`setsid`) cannot leave a process running after the command ends, timeout or cancellation; a live `@Workspace` test covers it.
EOF
)"
```

Put the issue's link in design 4.39's "Out of scope" paragraph and in the docs section from Step 3.

- [ ] **Step 5: Verify the whole branch, then commit**

Run:
```bash
sbt scalafmtAll "workspaceShared/test" "workspaceClient/test" "workspaceRunner/test" it/itTierCheck 2>&1 | grep -E '\[error\]|FAILED|Tests:' | tail -20
scripts/check-docs-site-links.sh 2>&1 | tail -3
```
Expected: no errors, no failures.

```bash
git add -A
git commit -s -m "feat(workspace): llm4s-confine in the runner image; live confinement suite; docs (#1983)"
```

Then push and open the PR. Its body closes #1983 (and #870, if #1983 says so), and notes that the `Workspace Integration` job runs on it under #1982.
