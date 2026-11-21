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

  // ABI v6 scopes (#2009). Each case runs the same command at the kernel's ABI and with --max-abi 5 (the helper's first
  // option after --status), where it must succeed: so a refusal is the scope's, and below v6 the helper behaves as
  // before #2009.

  private def report(extra: String*): Ran = {
    val p   = new ProcessBuilder((List(helper.toString, "--report") ++ extra).asJava).start()
    val out = new String(p.getInputStream.readAllBytes())
    val err = new String(p.getErrorStream.readAllBytes())
    Ran(p.waitFor(), out, err)
  }

  /** This kernel's Landlock ABI, as the helper reports it. */
  private def kernelAbi: Int = {
    val r = report()
    withClue(s"--report: $r") {
      r.exit shouldBe 0
      Confiner.Protections.parse(r.out).map(_.abi).getOrElse(fail(s"not a report: ${r.out}"))
    }
  }

  private def scopedKernel(): Unit = {
    val abi = kernelAbi
    info(s"Landlock ABI $abi, kernel ${System.getProperty("os.version")}")
    assume(abi >= Confiner.Protections.ScopesAbi, s"Landlock ABI $abi has no scopes (kernel 6.12)")
  }

  private def BelowScopes: List[String] = List("--max-abi", (Confiner.Protections.ScopesAbi - 1).toString)

  "llm4s-confine --report" should "name the kernel's ABI, with both scopes from v6 and none below" in {
    val abi = kernelAbi
    abi should be >= 3
    val expected = if (abi >= 6) "abstract-unix-socket,signal" else "none"
    report().out.trim shouldBe s"landlock-abi=$abi scopes=$expected"
    report(BelowScopes: _*).out.trim shouldBe s"landlock-abi=${math.min(abi, 5)} scopes=none"
    if (abi >= 6) report("--max-abi", "6").out.trim shouldBe "landlock-abi=6 scopes=abstract-unix-socket,signal"
  }

  it should "refuse an ABI below v3 as the sandbox does, and a bad --max-abi" in {
    val r = report("--max-abi", "2")
    r.exit shouldBe 125
    r.err should include("Landlock ABI 2 is below the v3")
    report("--max-abi", "x").exit shouldBe 125
    report("--max-abi").exit shouldBe 125
  }

  "llm4s-confine with --max-abi" should "confine as at that ABI" in {
    val (dir, status) = fresh()
    Files.writeString(dir.resolve("f"), "hello")
    val r = run(status, BelowScopes ++ system ++ List("--ro", dir.toString), "/bin/cat", dir.resolve("f").toString)
    (r.exit, r.out) shouldBe ((0, "hello"))
    run(status, BelowScopes ++ system, "/bin/cat", dir.resolve("f").toString).exit should not be 0
  }

  "llm4s-confine at Landlock ABI v6 or later" should "not let a command signal a process outside it (the runner)" in {
    scopedKernel()
    val (_, status) = fresh()
    val target      = new ProcessBuilder("/bin/sleep", "60").start()
    try {
      val kill = List("/bin/sh", "-c", s"kill -TERM ${target.pid()}")
      val r    = run(status, system, kill: _*)
      withClue(s"kill: $r") {
        r.exit should not be 0
        r.err should include("not permitted")
      }
      target.isAlive shouldBe true
      run(status, BelowScopes ++ system, kill: _*).exit shouldBe 0
      target.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    } finally target.destroyForcibly(): Unit
  }

  it should "still let a command signal what it started itself" in {
    scopedKernel()
    val (_, status) = fresh()
    run(status, system, "/bin/sh", "-c", "sleep 60 & kill -TERM $! && wait $!; test $? -eq 143").exit shouldBe 0
  }

  /** A python3 program connecting to abstract socket `name`: exit 0 when connected, 1 with the error otherwise. */
  private def connectTo(name: String): List[String] =
    List(
      "/usr/bin/python3",
      "-c",
      "import socket, sys\n" +
        "s = socket.socket(socket.AF_UNIX)\n" +
        s"try: s.connect('\\0$name')\n" +
        "except OSError as e: print(e, file=sys.stderr); sys.exit(1)\n"
    )

  it should "not let a command connect to an abstract Unix socket made outside it, even under --no-net" in {
    scopedKernel()
    assume(Files.isExecutable(Paths.get("/usr/bin/python3")), "python3 is not installed")
    val (_, status) = fresh()
    val name        = s"llm4s-confine-spec-${System.nanoTime()}"
    // the listener, unconfined: binds, says so, and accepts until it is stopped
    val server = new ProcessBuilder(
      "/usr/bin/python3",
      "-c",
      "import socket, sys\n" +
        "s = socket.socket(socket.AF_UNIX)\n" +
        s"s.bind('\\0$name')\n" +
        "s.listen(8)\n" +
        "print('ready', flush=True)\n" +
        "while True: s.accept()[0].close()\n"
    ).start()
    try {
      new java.io.BufferedReader(new java.io.InputStreamReader(server.getInputStream)).readLine() shouldBe "ready"
      val unconfined = new ProcessBuilder(connectTo(name).asJava).start()
      unconfined.waitFor() shouldBe 0 // the control: the socket is there to connect to
      for (grants <- List(system, system :+ "--no-net")) {
        val r = run(status, grants, connectTo(name): _*)
        withClue(s"connect ($grants): $r") {
          r.exit shouldBe 1
          r.err should include("not permitted")
        }
        run(status, BelowScopes ++ grants, connectTo(name): _*).exit shouldBe 0
      }
    } finally server.destroyForcibly(): Unit
  }

  it should "still let a command use an abstract Unix socket it made itself" in {
    scopedKernel()
    assume(Files.isExecutable(Paths.get("/usr/bin/python3")), "python3 is not installed")
    val (_, status) = fresh()
    val name        = s"llm4s-confine-own-${System.nanoTime()}"
    val program = "import socket\n" +
      "s = socket.socket(socket.AF_UNIX)\n" +
      s"s.bind('\\0$name')\n" +
      "s.listen(1)\n" +
      "c = socket.socket(socket.AF_UNIX)\n" +
      s"c.connect('\\0$name')\n"
    val r = run(status, system :+ "--no-net", "/usr/bin/python3", "-c", program)
    withClue(s"python: $r")(r.exit shouldBe 0)
  }
}
