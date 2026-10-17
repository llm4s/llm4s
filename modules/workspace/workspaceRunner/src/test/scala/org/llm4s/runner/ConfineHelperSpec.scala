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

  final private case class Ran(exit: Int, out: String, err: String)

  private def run(status: Path, grants: List[String], program: String*): Ran = {
    val pb = new ProcessBuilder(
      (List(helper.toString, "--status", status.toString) ++ grants ++ ("--" :: program.toList)).asJava
    )
    val p   = pb.start()
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
    val other         = Files.createTempDirectory("confine-other")
    Files.writeString(other.resolve("secret"), "s")
    val r = run(status, system ++ List("--ro", dir.toString), "/bin/cat", other.resolve("secret").toString)
    r.exit should not be 0
    r.err should include("Permission denied")
  }

  it should "refuse a write under a read-only grant, including truncation" in {
    val (dir, status) = fresh()
    Files.writeString(dir.resolve("f"), "keep")
    run(
      status,
      system ++ List("--ro", dir.toString),
      "/usr/bin/touch",
      dir.resolve("new").toString
    ).exit should not be 0
    run(
      status,
      system ++ List("--ro", dir.toString),
      "/usr/bin/truncate",
      "-s",
      "0",
      dir.resolve("f").toString
    ).exit should not be 0
    Files.readString(dir.resolve("f")) shouldBe "keep"
  }

  it should "allow writes under a read-write grant" in {
    val (dir, status) = fresh()
    run(status, system ++ List("--rw", dir.toString), "/usr/bin/touch", dir.resolve("new").toString).exit shouldBe 0
  }

  it should "refuse a device node under a read-write grant, though a FIFO is made there" in {
    val (dir, status) = fresh()
    assume(Files.isExecutable(Paths.get("/usr/bin/mknod")), "mknod is not installed")
    // the control: unconfined, this user can make the node (root with CAP_MKNOD, as in the container)
    val control = new ProcessBuilder("/usr/bin/mknod", dir.resolve("control").toString, "c", "1", "3").start()
    assume(control.waitFor() == 0, "this user cannot make device nodes, so a refusal would not be the sandbox's")
    val grants = system ++ List("--rw", dir.toString)
    run(status, grants, "/usr/bin/mknod", dir.resolve("null").toString, "c", "1", "3").exit should not be 0
    run(status, grants, "/usr/bin/mknod", dir.resolve("disk").toString, "b", "7", "0").exit should not be 0
    Files.exists(dir.resolve("null"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
    Files.exists(dir.resolve("disk"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
    run(status, grants, "/usr/bin/mknod", dir.resolve("fifo").toString, "p").exit shouldBe 0
  }

  it should "allow writing an existing file under --wfile but not creating a sibling" in {
    val (dir, status) = fresh()
    val f             = dir.resolve("f")
    Files.writeString(f, "a")
    val grants = system ++ List("--ro", dir.toString, "--wfile", f.toString)
    run(status, grants, "/usr/bin/truncate", "-s", "0", f.toString).exit shouldBe 0
    run(status, grants, "/usr/bin/touch", dir.resolve("g").toString).exit should not be 0
  }

  it should "refuse an internet socket with --no-net, and allow it without" in {
    val (_, status) = fresh()
    val server      = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
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
    val r           = run(status, List("--bogus", "x"), "/bin/true")
    r.exit shouldBe 125
    Files.readString(status) should include("--bogus")
    r.err should startWith("llm4s-confine: unavailable:")
  }

  it should "pass a program's own exit 125 through without a status file" in {
    val (_, status) = fresh()
    val r           = run(status, system, "/bin/sh", "-c", "exit 125")
    r.exit shouldBe 125
    Files.exists(status) shouldBe false
  }

  it should "write the status file, in a directory no grant covers, when a grant cannot be opened" in {
    val (dir, status) = fresh()
    Files.writeString(dir.resolve("f"), "a")
    // f is a regular file, so f/x fails to open with ENOTDIR: a failure other than a missing path
    val r = run(status, system ++ List("--ro", dir.resolve("f").resolve("x").toString), "/bin/true")
    r.exit shouldBe 125
    Files.readString(status) should include("cannot open")
  }

  it should "skip a grant whose path does not exist" in {
    val (dir, status) = fresh()
    run(status, system ++ List("--ro", dir.resolve("missing").toString), "/bin/true").exit shouldBe 0
  }
}
