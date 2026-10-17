package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ ServerSocket, SocketTimeoutException }
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.util.Comparator
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * Confinement (design §4.39) through the runner's own `executeCommand`: on Linux every command starts through
 * `llm4s-confine`, and a helper that cannot confine refuses the command `SANDBOX_UNAVAILABLE`.
 *
 * A program's own exit 125 (no status file) is passed through as its exit code. No allowlisted program exits 125, so
 * that is covered at the helper level (`ConfineHelperSpec`) and by the `CommandScratch.unavailable` case below.
 */
class CommandConfinementExecuteSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val isLinux       = System.getProperty("os.name", "").startsWith("Linux")
  private val isWindowsHost = System.getProperty("os.name", "").startsWith("Windows")
  private val root: Path    = Files.createTempDirectory("confine-execute")
  private var made          = List.empty[Path]

  override def afterAll(): Unit = {
    (root :: made).foreach(dir =>
      Try(Files.walk(dir).sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p)))
    )
    super.afterAll()
  }

  /** A fresh workspace: `ro/a`, `b`, `src/`, `secret/s`. */
  private def tree(): Path = {
    val dir = Files.createTempDirectory("confine-ws")
    made = dir :: made
    Files.createDirectories(dir.resolve("ro"))
    Files.createDirectories(dir.resolve("src"))
    Files.createDirectories(dir.resolve("secret"))
    Files.writeString(dir.resolve("ro/a"), "original")
    Files.writeString(dir.resolve("b"), "bee")
    Files.writeString(dir.resolve("secret/s"), "top-secret-value")
    dir
  }

  /** The platform's confiner, on Linux the test build's helper; the test cancels elsewhere. */
  private def linuxConfiner(): Option[Confiner] = {
    assume(isLinux, "commands are confined on Linux only")
    val c = Confiner.platform()
    c.map(_.helper.toString) shouldBe Option(System.getProperty(Confiner.HelperProperty))
    c
  }

  private def workspace(dir: Path, config: WorkspaceSandboxConfig, confiner: Option[Confiner]) =
    new WorkspaceAgentInterfaceImpl(dir.toString, isWindows = false, Some(config), confiner = confiner)

  /** Exit code and stderr, or the refusal's code: a refused command never ran. */
  private def outcome(ws: WorkspaceAgentInterfaceImpl, command: String, wd: Option[String] = None) =
    Try(ws.executeCommand(command, wd)).toEither.left.map {
      case e: WorkspaceAgentException => e.code
      case e                          => throw e
    }

  private val readOnlyRo = WorkspaceSandboxConfig.Permissive.copy(readOnlyPaths = List("ro"))

  /** A stand-in helper: what llm4s-confine does when Landlock is missing. */
  private lazy val fake: Path = {
    val f = root.resolve("fake-confine")
    Files.writeString(
      f,
      "#!/bin/sh\nwhile [ \"$1\" != --status ]; do shift; done\nprintf 'Landlock ABI 2 is below the v3' > \"$2\"\nexit 125\n"
    )
    f.toFile.setExecutable(true)
    f
  }

  private val readOnlyWrites = List("rm ro/a", "mv ro/a b", "cp b ro/new", "touch ro/new")

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
    val ws       = new WorkspaceAgentInterfaceImpl(root.toString, isWindows = false, None, confiner = None)
    val prepared = ws.prepareCommand("ls", None, None, None).fold(e => fail(e.error), identity)
    prepared.builder.command().asScala.exists(_.contains("llm4s-confine")) shouldBe false
    prepared.scratch shouldBe None
  }

  "a runner whose helper cannot confine" should "refuse the command SANDBOX_UNAVAILABLE, with the reason" in {
    assume(!isWindowsHost)
    val ws = new WorkspaceAgentInterfaceImpl(root.toString, isWindows = false, None, confiner = Some(Confiner(fake)))
    val e  = intercept[WorkspaceAgentException](ws.executeCommand("ls"))
    e.code shouldBe "SANDBOX_UNAVAILABLE"
    e.error should include("Landlock ABI 2")
    Using.resource(Files.list(ws.scratchParent.get))(_.iterator().asScala.toList) shouldBe empty
  }

  it should "refuse the command SANDBOX_UNAVAILABLE over the WebSocket too, and leave no scratch directory" in {
    assume(!isWindowsHost)
    val ws = new WorkspaceAgentInterfaceImpl(root.toString, isWindows = false, None, confiner = Some(Confiner(fake)))
    val messages = new java.util.concurrent.ConcurrentLinkedQueue[WebSocketMessage]()
    val done     = scala.concurrent.Promise[CommandCompletedMessage]()
    val send: WebSocketMessage => Unit = { m =>
      messages.add(m)
      m match {
        case c: CommandCompletedMessage => done.trySuccess(c): Unit
        case _                          => ()
      }
    }
    new WebSocketCommandExecutor(ws, 1024L * 1024L).execute(ExecuteCommandCommand("c1", "ls"), new Object, send)
    Await.result(done.future, 10.seconds).exitCode shouldBe 1
    messages.asScala.collect { case ResponseMessage(e: WorkspaceAgentErrorResponse) => e.code }.toList shouldBe
      List("SANDBOX_UNAVAILABLE")
    Using.resource(Files.list(ws.scratchParent.get))(_.iterator().asScala.toList) shouldBe empty
  }

  it should "refuse SANDBOX_UNAVAILABLE when its scratch directory would lie inside the workspace" in {
    assume(!isWindowsHost && Files.isDirectory(Confiner.ScratchBase))
    // the workspace is the scratch base itself: every scratch parent would be inside it
    val ws = new WorkspaceAgentInterfaceImpl(
      Confiner.ScratchBase.toString,
      isWindows = false,
      None,
      confiner = Some(Confiner(fake))
    )
    val e = intercept[WorkspaceAgentException](ws.executeCommand("ls"))
    e.code shouldBe "SANDBOX_UNAVAILABLE"
    e.error should include("inside the workspace")
  }

  it should "refuse SANDBOX_UNAVAILABLE when the helper is missing" in {
    val ws = new WorkspaceAgentInterfaceImpl(
      root.toString,
      isWindows = false,
      None,
      confiner = Some(Confiner(root.resolve("no-such-helper")))
    )
    ws.prepareCommand("ls", None, None, None).swap.map(_.code) shouldBe Right("SANDBOX_UNAVAILABLE")
  }

  "a confined command" should "start through the helper" in {
    val confiner = linuxConfiner()
    val ws       = workspace(tree(), WorkspaceSandboxConfig.Permissive, confiner)
    val prepared = ws.prepareCommand("ls", None, None, None).fold(e => fail(e.error), identity)
    prepared.builder.command().get(0) shouldBe confiner.get.helper.toString
    val scratch = prepared.scratch.getOrElse(fail("a confined command has a scratch directory"))
    prepared.builder.environment().get("TMPDIR") shouldBe scratch.tmp.toString
    // the runner's own scratch is under /var/tmp, never /tmp, which commands may share
    scratch.dir.startsWith(Confiner.ScratchBase.toRealPath()) shouldBe true
    prepared.scratch.foreach(_.delete())
    ws.deleteScratch()
    Files.exists(ws.scratchParent.get) shouldBe false
  }

  /** A JVM program, run confined in a workspace outside /tmp, that makes a file in java.io.tmpdir (/tmp). */
  private def jvmTempFile(sharedTmp: Boolean): Either[String, ExecuteCommandResponse] = {
    val confiner = linuxConfiner().map(_.copy(sharedTmp = sharedTmp))
    val dir      = Files.createTempDirectory(Confiner.ScratchBase, "confine-jvm")
    made = dir :: made
    Files.writeString(
      dir.resolve("T.java"),
      "public class T { public static void main(String[] a) throws Exception { " +
        "java.io.File f = java.io.File.createTempFile(\"llm4s\", \".tmp\"); System.out.print(f); f.delete(); } }"
    )
    // java is never an extra command (it runs any code); the config names it here only to have a JVM to run
    val config = WorkspaceSandboxConfig.Permissive.copy(
      allowedCommands = WorkspaceSandboxConfig.Permissive.allowedCommands + "java"
    )
    outcome(workspace(dir, config, confiner), "java T.java")
  }

  it should "let a JVM write its temporary files in /tmp when the runner's attach mechanism is off" in {
    val result = jvmTempFile(sharedTmp = true)
    result.map(_.exitCode) shouldBe Right(0)
    result.map(_.stdout.startsWith("/tmp/")) shouldBe Right(true)
  }

  it should "not grant /tmp when the runner's attach mechanism could not be verified off" in {
    val result = jvmTempFile(sharedTmp = false)
    result.map(_.exitCode).fold(_ => 1, identity) should not be 0
    result.map(_.stderr).getOrElse("") should include("Permission denied")
  }

  it should "not write, delete or rename inside a read-only path" in {
    val confiner = linuxConfiner()
    for (command <- readOnlyWrites) {
      val dir   = tree()
      val perms = Files.getPosixFilePermissions(dir.resolve("ro/a"))
      outcome(workspace(dir, readOnlyRo, confiner), command) match {
        case Right(r) =>
          withClue(s"$command: ") {
            r.exitCode should not be 0
            r.stderr should include("Permission denied")
          }
        case Left(_) => () // the command policy refused it first: it never ran
      }
      withClue(s"$command: ") {
        Files.readString(dir.resolve("ro/a")) shouldBe "original"
        Files.exists(dir.resolve("ro/new")) shouldBe false
        Files.getPosixFilePermissions(dir.resolve("ro/a")) shouldBe perms
      }
    }
  }

  // Landlock (to ABI 8) does not govern metadata: chmod, chown and utimes on a readable file are not refused, so a
  // read-only file's mode can change, never its content. Pinned, so a change in what the kernel refuses is noticed.
  it should "still change a read-only file's mode, but not its content (Landlock does not cover metadata)" in {
    val confiner = linuxConfiner()
    val dir      = tree()
    outcome(workspace(dir, readOnlyRo, confiner), "chmod 777 ro/a").map(_.exitCode) shouldBe Right(0)
    Files.readString(dir.resolve("ro/a")) shouldBe "original"
  }

  it should "not write through a link into a read-only path" in {
    val confiner = linuxConfiner()
    val dir      = tree()
    Files.createSymbolicLink(dir.resolve("link"), dir.resolve("ro/a"))
    outcome(workspace(dir, readOnlyRo, confiner), "cp b link") match {
      case Right(r) => r.exitCode should not be 0
      case Left(_)  => ()
    }
    Files.readString(dir.resolve("ro/a")) shouldBe "original"
  }

  it should "not read outside allowedPaths" in {
    val confiner = linuxConfiner()
    val dir      = tree()
    val config   = WorkspaceSandboxConfig.Permissive.copy(allowedPaths = List("src"))
    for (command <- List("cat ../secret/s", "find ../secret", "grep -r top-secret ../secret"))
      outcome(workspace(dir, config, confiner), command, Some("src")) match {
        case Right(r) =>
          withClue(s"$command: ") {
            r.exitCode should not be 0
            (r.stdout should not).include("top-secret-value")
            (r.stdout should not).include("secret/s")
          }
        case Left(_) => () // the command policy refused it first: it never ran
      }
  }

  it should "not create entries directly in a directory above a read-only path (the pinned limitation)" in {
    val confiner = linuxConfiner()
    val dir      = tree()
    val ws       = workspace(dir, readOnlyRo, confiner)
    outcome(ws, "touch new").map(_.exitCode).fold(_ => 1, identity) should not be 0
    Files.exists(dir.resolve("new")) shouldBe false
    outcome(ws, "touch src/new").map(_.exitCode) shouldBe Right(0)
    Files.exists(dir.resolve("src/new")) shouldBe true
  }

  it should "not create a read-only path that does not exist yet" in {
    val confiner = linuxConfiner()
    val dir      = tree()
    val config   = WorkspaceSandboxConfig.Permissive.copy(readOnlyPaths = List("ro/new", "later/deeper"))
    val ws       = workspace(dir, config, confiner)
    for (command <- List("touch ro/new", "mkdir ro/new", "mkdir later"))
      withClue(s"$command: ") {
        outcome(ws, command).map(_.exitCode).fold(_ => 1, identity) should not be 0
      }
    Files.exists(dir.resolve("ro/new")) shouldBe false
    Files.exists(dir.resolve("later")) shouldBe false
    // what lies beside it stays writable
    outcome(ws, "touch ro/a").map(_.exitCode) shouldBe Right(0)
    outcome(ws, "touch src/new").map(_.exitCode) shouldBe Right(0)
  }

  "the runner's own helpers" should "never be a git, getfacl or setfacl planted on PATH, since they run unconfined" in {
    val confiner = linuxConfiner()
    assume(
      ProgramResolver.resolveRunnerTool("git", Nil).isDefined,
      "git is not installed in /usr/bin or /bin"
    )
    // A directory first on the runner's PATH that a confined command could write (as it can the home directory), and
    // a marker outside every grant: the planted programs write it whenever they run unconfined.
    val planted = Files.createTempDirectory("confine-planted")
    val outside = Files.createTempDirectory(Paths.get("/var/tmp"), "confine-marker")
    made = planted :: outside :: made
    val marker = outside.resolve("ran-unconfined")
    for (tool <- List("git", "getfacl", "setfacl")) {
      val real = ProgramResolver.resolveRunnerTool(tool, Nil).map(_.toString).getOrElse("/bin/true")
      val f    = planted.resolve(tool)
      Files.writeString(f, s"#!/bin/sh\necho $tool >> $marker\nexec $real \"$$@\"\n")
      f.toFile.setExecutable(true): Unit
    }
    val search = ProgramSearch(windows = false, Nil, Some(s"$planted:/usr/bin:/bin"), None, None)
    val dir    = tree()
    val ws = new WorkspaceAgentInterfaceImpl(
      dir.toString,
      isWindows = false,
      Some(WorkspaceSandboxConfig.Permissive),
      programSearch = search,
      confiner = confiner
    )
    // git's configuration lister runs before every git subcommand; the command's own git runs confined
    outcome(ws, "git status")
    // a compare-and-set write copies the file's ACL with getfacl and setfacl
    ws.writeFile(
      "b",
      "changed",
      expectedHash = Some(FileSnapshotResponse.hashOf("bee".getBytes(StandardCharsets.UTF_8)))
    )
    Files.readString(dir.resolve("b")) shouldBe "changed"
    withClue(Try(Files.readString(marker)).getOrElse("")) {
      Files.exists(marker) shouldBe false
    }
  }

  /** `curl` against a server on 127.0.0.1: whether it connected, and curl's outcome. */
  private def curlLocal(networkAllowed: Boolean): (Boolean, Either[String, ExecuteCommandResponse]) = {
    val confiner = linuxConfiner()
    assume(Files.isExecutable(Paths.get("/usr/bin/curl")), "curl is not installed")
    val config = WorkspaceSandboxConfig.Permissive
      .withExtraCommands("curl")
      .fold(e => fail(e), identity)
      .copy(networkAllowed = networkAllowed)
    Using.resource(new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { server =>
      server.setSoTimeout(3000)
      val accepted = Future {
        Try(Using.resource(server.accept()) { socket =>
          socket.getOutputStream.write("HTTP/1.0 200 OK\r\n\r\nok".getBytes(StandardCharsets.US_ASCII))
          socket.getOutputStream.flush()
        }).fold(
          {
            case _: SocketTimeoutException => false
            case e                         => throw e
          },
          _ => true
        )
      }
      val result =
        outcome(workspace(tree(), config, confiner), s"curl -sS --max-time 5 http://127.0.0.1:${server.getLocalPort}/")
      (Await.result(accepted, 10.seconds), result)
    }
  }

  it should "not reach the network when networkAllowed is false" in {
    val (connected, result) = curlLocal(networkAllowed = false)
    connected shouldBe false
    result.map(_.exitCode).fold(_ => 1, identity) should not be 0
  }

  it should "reach the network when networkAllowed is true" in {
    val (connected, result) = curlLocal(networkAllowed = true)
    connected shouldBe true
    result.map(r => (r.exitCode, r.stdout)) shouldBe Right((0, "ok"))
  }

  it should "leave no scratch directory behind, whether it completes or times out" in {
    val confiner = linuxConfiner()
    val dir      = tree()
    val ws       = workspace(dir, WorkspaceSandboxConfig.Permissive, confiner)
    def leftOver = Using.resource(Files.list(ws.scratchParent.get))(_.iterator().asScala.toList)
    ws.executeCommand("ls").exitCode shouldBe 0
    leftOver shouldBe empty
    Files.writeString(dir.resolve("somefile"), "x")
    val timedOut = Try(ws.executeCommand("tail -f somefile", None, Some(1.second)))
    timedOut.failed.toOption.collect { case e: WorkspaceAgentException => e.code } shouldBe Some("TIMEOUT")
    leftOver shouldBe empty
  }

  "an unconfined runner" should "let the same commands change a read-only path: the refusals above are confinement's" in {
    assume(!isWindowsHost)
    for (command <- readOnlyWrites :+ "cp b link" :+ "touch new") {
      val dir = tree()
      Files.createSymbolicLink(dir.resolve("link"), dir.resolve("ro/a"))
      withClue(s"$command: ") {
        outcome(workspace(dir, readOnlyRo, None), command).map(_.exitCode) shouldBe Right(0)
      }
    }
    val dir = tree()
    outcome(workspace(dir, readOnlyRo, None), "rm ro/a").map(_.exitCode) shouldBe Right(0)
    Files.exists(dir.resolve("ro/a")) shouldBe false
  }
}
