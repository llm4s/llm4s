package org.llm4s.toolapi.builtin.shell

import org.llm4s.toolapi.SafeParameterExtractor
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.atomic.AtomicReference
import javax.tools.ToolProvider
import scala.concurrent.duration._

/**
 * #3050: a shell command leaves no process behind - not on a timeout, not on an interrupt of the calling thread -
 * and a command that reads stdin sees its end at once. The commands are small Java programs run on this JVM's own
 * `java`, so the same spec runs on Windows (agent-tools is a whole module of `PlatformTests`).
 */
class ShellProcessSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val isWindows = System.getProperty("os.name", "").toLowerCase.startsWith("windows")

  private val ChildSource =
    """import java.nio.file.*;
      |
      |public class Child {
      |  public static void main(String[] args) throws Exception {
      |    switch (args[0]) {
      |      case "stdin": {
      |        while (System.in.read() != -1) { }
      |        System.out.println("eof");
      |        break;
      |      }
      |      case "flood": {
      |        int lines = Integer.parseInt(args[1]);
      |        java.io.PrintStream out = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false);
      |        java.io.PrintStream err = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), false);
      |        for (int i = 0; i < lines; i++) { out.println("out " + i); err.println("err " + i); }
      |        out.flush();
      |        err.flush();
      |        break;
      |      }
      |      case "sleep": {
      |        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
      |        Thread.sleep(120_000);
      |        break;
      |      }
      |      case "parent": {
      |        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
      |        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      |        new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "Child", "sleep", args[2])
      |          .redirectOutput(ProcessBuilder.Redirect.INHERIT)
      |          .redirectError(ProcessBuilder.Redirect.INHERIT)
      |          .start();
      |        System.out.println("started");
      |        Thread.sleep(120_000);
      |        break;
      |      }
      |      default: throw new IllegalArgumentException(args[0]);
      |    }
      |  }
      |}
      |""".stripMargin

  private lazy val workDir: Path = Files.createTempDirectory("llm4s-shell-process")

  private lazy val classDir: Path = {
    val source = workDir.resolve("Child.java")
    Files.writeString(source, ChildSource)
    val compiler = ToolProvider.getSystemJavaCompiler
    assert(compiler != null, "the tests need a JDK")
    val status = compiler.run(null, null, null, "-d", workDir.toString, source.toString)
    assert(status == 0, "Child.java did not compile")
    workDir
  }

  private val javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString

  private def child(args: String*): ProcessBuilder =
    new ProcessBuilder((Seq(javaExe, "-cp", classDir.toString, "Child") ++ args)*)

  override def afterAll(): Unit = {
    Files.walk(workDir).sorted(java.util.Comparator.reverseOrder()).forEach(p => p.toFile.delete())
    super.afterAll()
  }

  /** The pid a child wrote to `file`, once it has. */
  private def awaitPid(file: Path, within: FiniteDuration = 30.seconds): Long = {
    val deadline = within.fromNow
    while (!(Files.exists(file) && Files.size(file) > 0) && deadline.hasTimeLeft()) Thread.sleep(50)
    withClue(s"$file was never written: ")(Files.exists(file) shouldBe true)
    new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim.toLong
  }

  /** Whether `pid` is still running after `within` (a forcible kill completes asynchronously). */
  private def stillAlive(pid: Long, within: FiniteDuration = 10.seconds): Boolean = {
    val deadline = within.fromNow
    def alive    = ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)
    while (alive && deadline.hasTimeLeft()) Thread.sleep(50)
    alive
  }

  "ShellProcess" should "give a command the null device as stdin, so a read of it ends at once" in {
    val start   = System.nanoTime()
    val outcome = ShellProcess.run(child("stdin"), 30.seconds, 10000)
    val took    = (System.nanoTime() - start).nanos

    outcome match {
      case f: ShellProcess.Finished =>
        f.timedOut shouldBe false
        f.exitCode shouldBe 0
        f.stdout.trim shouldBe "eof"
      case other => fail(s"expected Finished, got $other")
    }
    took should be < 20.seconds
  }

  it should "keep all the output of a command that writes a lot and exits at once" in {
    // Several at a time, so that a reader is still behind when its process exits: the end of the output is then in
    // the pipe, and closing the streams before the readers finish would drop it.
    val lines = 20000
    val runs = (1 to 16).map { attempt =>
      scala.concurrent.Future(attempt -> ShellProcess.run(child("flood", lines.toString), 60.seconds, 10000000))(
        scala.concurrent.ExecutionContext.global
      )
    }
    val outcomes = scala.concurrent.Await.result(
      scala.concurrent.Future.sequence(runs)(implicitly, scala.concurrent.ExecutionContext.global),
      3.minutes
    )
    outcomes.foreach {
      case (attempt, f: ShellProcess.Finished) =>
        withClue(s"run $attempt: ") {
          f.exitCode shouldBe 0
          f.truncated shouldBe false
          f.stdout.linesIterator.count(_.nonEmpty) shouldBe lines
          f.stderr.linesIterator.count(_.nonEmpty) shouldBe lines
          f.stdout.linesIterator.toSeq.last shouldBe s"out ${lines - 1}"
        }
      case (attempt, other) => fail(s"run $attempt: expected Finished, got $other")
    }
  }

  it should "kill the whole process tree when a command times out" in {
    val parentPid = workDir.resolve("timeout-parent.pid")
    val childPid  = workDir.resolve("timeout-child.pid")

    val outcome = ShellProcess.run(child("parent", parentPid.toString, childPid.toString), 8.seconds, 10000)

    outcome match {
      case f: ShellProcess.Finished =>
        f.timedOut shouldBe true
        f.stdout should include("started")
      case other => fail(s"expected Finished, got $other")
    }
    withClue("the parent survived: ")(stillAlive(awaitPid(parentPid)) shouldBe false)
    withClue("the parent's child survived: ")(stillAlive(awaitPid(childPid)) shouldBe false)
  }

  it should "kill the whole process tree, and keep the interrupt, when the calling thread is interrupted" in {
    val parentPid   = workDir.resolve("interrupt-parent.pid")
    val childPid    = workDir.resolve("interrupt-child.pid")
    val outcome     = new AtomicReference[Either[Throwable, ShellProcess.Outcome]]()
    val interrupted = new AtomicReference[java.lang.Boolean](false)

    val caller = new Thread(() => {
      outcome.set(
        scala.util
          .Try(ShellProcess.run(child("parent", parentPid.toString, childPid.toString), 2.minutes, 10000))
          .toEither
      )
      interrupted.set(Thread.currentThread().isInterrupted)
    })
    caller.setDaemon(true)
    caller.start()

    val parent     = awaitPid(parentPid)
    val grandchild = awaitPid(childPid)
    caller.interrupt()
    caller.join(30.seconds.toMillis)

    caller.isAlive shouldBe false
    outcome.get shouldBe Right(ShellProcess.Interrupted)
    interrupted.get.booleanValue shouldBe true
    withClue("the parent survived: ")(stillAlive(parent) shouldBe false)
    withClue("the parent's child survived: ")(stillAlive(grandchild) shouldBe false)
  }

  "ShellTool" should "not wait out the timeout on a read-only command that reads stdin" in {
    // `cat` with no file argument (ShellConfig.readOnly); `findstr` is Windows' stdin filter.
    val (program, command) = if (isWindows) ("findstr", "findstr x") else ("cat", "cat")
    val config             = ShellConfig(allowedCommands = Seq(program), timeout = 30.seconds)
    val start              = System.nanoTime()

    val result = ShellTool
      .createSafe(config)
      .fold(e => fail(e.formatted), _.handler(SafeParameterExtractor(ujson.Obj("command" -> command))))

    result.left.map(_.message).map(_.timedOut) shouldBe Right(false)
    (System.nanoTime() - start).nanos should be < 20.seconds
  }
}
