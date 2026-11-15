package org.llm4s.runner

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try, Using }

/**
 * A cgroup per command (design §4.39, #2008): when the runner gives commands cgroups, and what it does without them.
 * A temporary directory stands in for the cgroup hierarchy; the real one, and a `setsid` daemon killed with its
 * command, are `WorkspaceCgroupIntegrationSpec`'s (`@Workspace`).
 */
class CommandCgroupsSpec extends AnyFlatSpec with Matchers {

  /** A stand-in hierarchy whose root (the runner's own cgroup, in a private namespace) has `cgroup.procs`. */
  private def hierarchy(): Path = {
    val root = Files.createTempDirectory("cgroup-root")
    Files.writeString(root.resolve("cgroup.procs"), "1\n")
    root
  }

  /** Makes a probe cgroup as the kernel does: with its interface files, `cgroup.kill` among them (Linux 5.14+). */
  private def kernelCgroup(dir: Path): Path = {
    Files.createDirectory(dir)
    Files.writeString(dir.resolve("cgroup.procs"), "")
    Files.writeString(dir.resolve("cgroup.kill"), "")
    dir
  }

  private def entries(dir: Path): List[String] =
    scala.util.Using.resource(Files.list(dir))(_.iterator().asScala.map(_.getFileName.toString).toList).sorted

  "unifiedPath" should "read the cgroup v2 line of /proc/self/cgroup" in {
    CommandCgroups.unifiedPath("0::/\n") shouldBe Some("/")
    CommandCgroups.unifiedPath("12:cpu,cpuacct:/docker/x\n0::/system.slice/docker-x.scope\n") shouldBe
      Some("/system.slice/docker-x.scope")
  }

  it should "find none on a cgroup v1 only host" in {
    CommandCgroups.unifiedPath("12:cpu,cpuacct:/docker/x\n11:memory:/docker/x\n") shouldBe None
  }

  "detect" should "give commands no cgroup off Linux, trying nothing" in {
    var asked = false
    CommandCgroups.detect(
      linux = false,
      hierarchy(),
      { asked = true; Success("0::/") },
      _ => { asked = true; true }
    ) shouldBe Left("not on Linux")
    asked shouldBe false
  }

  it should "give commands no cgroup when the runner's cannot be read, or the host has only cgroup v1" in {
    val root = hierarchy()
    CommandCgroups.detect(true, root, Failure(new java.io.IOException("no /proc")), _ => true).left.toOption.get should
      include("cannot be read")
    CommandCgroups.detect(true, root, Success("4:pids:/x\n"), _ => true).left.toOption.get should include("cgroup v1")
  }

  it should "give commands no cgroup when the runner's own is not mounted where it should be" in {
    val root = hierarchy()
    CommandCgroups.detect(true, root, Success("0::/elsewhere"), _ => true).left.toOption.get should
      include("is not mounted")
    // a path that would lead out of the hierarchy is never used
    CommandCgroups.detect(true, root.resolve("sub"), Success("0::/../"), _ => true).left.toOption.get should
      include("is not mounted")
  }

  it should "give commands no cgroup when the hierarchy is read-only (a default docker run)" in {
    val root = hierarchy()
    CommandCgroups
      .detect(true, root, Success("0::/"), _ => true, _ => throw new java.io.IOException("Read-only file system"))
      .left
      .toOption
      .get should (include("is not writable").and(include("Read-only file system")))
  }

  it should "give commands no cgroup without cgroup.kill, and remove its probe" in {
    val root = hierarchy()
    CommandCgroups.detect(true, root, Success("0::/"), _ => true).left.toOption.get should include("5.14")
    entries(root) shouldBe List("cgroup.procs")
  }

  it should "give commands no cgroup when a process cannot join one" in {
    val root   = hierarchy()
    var probed = Option.empty[Path]
    CommandCgroups
      .detect(true, root, Success("0::/"), p => { probed = Some(p); false }, kernelCgroup)
      .left
      .toOption
      .get should include("cannot be moved")
    probed.map(_.getParent) shouldBe Some(root)
    probed.get.getFileName.toString should startWith("llm4s-probe-")
  }

  it should "give each command a cgroup under the runner's own when every check passes" in {
    val root = hierarchy()
    Files.createDirectories(root.resolve("system.slice/docker-x.scope"))
    Files.writeString(root.resolve("system.slice/docker-x.scope/cgroup.procs"), "")
    var joined = Option.empty[Path]
    CommandCgroups.detect(
      true,
      root,
      Success("0::/system.slice/docker-x.scope\n"),
      p => { joined = Some(p); true },
      kernelCgroup
    ) shouldBe Right(CommandCgroups(root.resolve("system.slice/docker-x.scope")))
    joined.map(_.getParent) shouldBe Some(root.resolve("system.slice/docker-x.scope"))
  }

  "report" should "say when commands' daemons can outlive them, why, and what the container needs" in {
    val why = CommandCgroups.report(Left("/sys/fs/cgroup is not writable (Read-only file system)"))
    why should include("can outlive it")
    why should include("Read-only file system")
    why should include("--cgroupns=private --cap-add SYS_ADMIN --security-opt apparmor=unconfined")
  }

  it should "say where commands' cgroups are made when they are in force" in {
    CommandCgroups.report(Right(CommandCgroups(java.nio.file.Paths.get("/sys/fs/cgroup")))) should
      (include("cgroup of its own under /sys/fs/cgroup").and(include("killed whole")))
  }

  "A command's cgroup" should "be made under the runner's, killed by cgroup.kill, and removed once empty" in {
    val root = hierarchy()
    val cg   = CommandCgroups(root).create().get
    cg.dir.getParent shouldBe root
    cg.dir.getFileName.toString should startWith("llm4s-cmd-")
    cg.kill()
    Files.readString(cg.dir.resolve("cgroup.kill")) shouldBe "1"
    Files.delete(cg.dir.resolve("cgroup.kill"))
    cg.remove() shouldBe true
    Files.exists(cg.dir) shouldBe false
    cg.remove() shouldBe true // removing it again is harmless
  }

  it should "list its live members from cgroup.procs" in {
    val cg = CommandCgroups(hierarchy()).create().get
    Files.writeString(cg.dir.resolve("cgroup.procs"), s"${ProcessHandle.current().pid()}\n\n")
    cg.members.map(_.pid()) shouldBe List(ProcessHandle.current().pid())
    Files.writeString(cg.dir.resolve("cgroup.procs"), "")
    cg.members shouldBe Nil
  }

  "The join script" should "run the program in place once it has joined the cgroup" in {
    assume(!System.getProperty("os.name", "").startsWith("Windows"), "a POSIX /bin/sh")
    val cg     = CommandCgroups(hierarchy()).create().get
    val status = Files.createTempDirectory("scratch").resolve("status")
    val p      = new java.lang.ProcessBuilder((cg.joinArgs(status) ++ List("/bin/sh", "-c", "echo $$")).asJava).start()
    p.waitFor(30, TimeUnit.SECONDS) shouldBe true
    p.exitValue() shouldBe 0
    val printed = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
    // the pid it wrote into cgroup.procs (`0`, the writer) is the program's own: exec, not a child
    printed shouldBe p.pid().toString
    Files.readString(cg.dir.resolve("cgroup.procs")).trim shouldBe "0"
    Files.exists(status) shouldBe false
  }

  it should "not run the program, and say why, when it cannot join" in {
    assume(!System.getProperty("os.name", "").startsWith("Windows"), "a POSIX /bin/sh")
    val gone    = CommandCgroup(Files.createTempDirectory("cgroup").resolve("missing"))
    val scratch = Files.createTempDirectory("scratch")
    val marker  = scratch.resolve("ran")
    val p = new java.lang.ProcessBuilder(
      (gone.joinArgs(scratch.resolve("status")) ++ List("/bin/sh", "-c", s"touch '$marker'")).asJava
    ).start()
    p.waitFor(30, TimeUnit.SECONDS) shouldBe true
    p.exitValue() shouldBe 125
    Files.exists(marker) shouldBe false
    // as the confinement helper's own refusal is read: SANDBOX_UNAVAILABLE, naming the cause
    CommandScratch(scratch).unavailable(125).get should include("cannot join the command's cgroup")
    Try(p.destroy())
  }

  /** A stand-in for llm4s-confine that confines nothing: it runs what follows its `--`. */
  private lazy val passthrough: Path = {
    val f = Files.createTempDirectory("helper").resolve("pass-confine")
    Files.writeString(f, "#!/bin/sh\nwhile [ \"$1\" != -- ]; do shift; done\nshift\nexec \"$@\"\n")
    f.toFile.setExecutable(true)
    f
  }

  private def runner(cgroups: Either[String, CommandCgroups], confiner: Option[Confiner]) =
    new WorkspaceAgentInterfaceImpl(
      Files.createTempDirectory("cgroup-ws").toString,
      isWindows = false,
      None,
      confiner = confiner,
      cgroups = cgroups
    )

  "A confined command" should "join a cgroup of its own before the helper, when commands get cgroups" in {
    assume(!System.getProperty("os.name", "").startsWith("Windows"), "a POSIX /bin/sh")
    val ws       = runner(Right(CommandCgroups(hierarchy())), Some(Confiner(passthrough, sharedTmp = false)))
    val prepared = ws.prepareCommand("ls", None, None, None).fold(e => fail(e.error), identity)
    val cg       = prepared.cgroup.getOrElse(fail("no cgroup"))
    prepared.builder.command().asScala.take(7).toList shouldBe
      cg.joinArgs(prepared.scratch.get.status) :+ passthrough.toString
    Files.isDirectory(cg.dir) shouldBe true
    prepared.discard()
    Files.exists(cg.dir) shouldBe false
  }

  it should "start as before, with no cgroup, when commands get none" in {
    assume(!System.getProperty("os.name", "").startsWith("Windows"), "a POSIX /bin/sh")
    val ws       = runner(Left("not writable"), Some(Confiner(passthrough, sharedTmp = false)))
    val prepared = ws.prepareCommand("ls", None, None, None).fold(e => fail(e.error), identity)
    prepared.cgroup shouldBe None
    prepared.builder.command().asScala.head shouldBe passthrough.toString
    prepared.discard()
    // and unconfined (off Linux) a command never gets one, whatever the hierarchy
    runner(Right(CommandCgroups(hierarchy())), None)
      .prepareCommand("ls", None, None, None)
      .fold(e => fail(e.error), identity)
      .cgroup shouldBe None
  }

  it should "have its whole cgroup killed when it ends" in {
    assume(!System.getProperty("os.name", "").startsWith("Windows"), "a POSIX /bin/sh")
    val root = hierarchy()
    val ws   = runner(Right(CommandCgroups(root)), Some(Confiner(passthrough, sharedTmp = false)))
    val r    = ws.executeCommand("echo hi")
    r.exitCode shouldBe 0
    r.stdout.trim shouldBe "hi"
    val made = entries(root).filter(_.startsWith("llm4s-cmd-"))
    made should have size 1
    // it joined (the shell wrote `0`), and the registry wrote cgroup.kill once the command ended
    Files.readString(root.resolve(made.head).resolve("cgroup.procs")).trim shouldBe "0"
    Files.readString(root.resolve(made.head).resolve("cgroup.kill")) shouldBe "1"
  }

  it should "be refused SANDBOX_UNAVAILABLE, and never run, when its cgroup cannot be made" in {
    assume(!System.getProperty("os.name", "").startsWith("Windows"), "a POSIX /bin/sh")
    val gone = Files.createTempDirectory("cgroup").resolve("missing")
    val ws   = runner(Right(CommandCgroups(gone)), Some(Confiner(passthrough, sharedTmp = false)))
    ws.prepareCommand("ls", None, None, None).left.map(_.code) shouldBe Left("SANDBOX_UNAVAILABLE")
    Using.resource(Files.list(ws.scratchParent.get))(_.iterator().asScala.toList) shouldBe empty
  }
}
