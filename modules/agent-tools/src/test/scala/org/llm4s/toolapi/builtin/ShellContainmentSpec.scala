package org.llm4s.toolapi.builtin

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.filesystem.FileConfig
import org.llm4s.toolapi.builtin.shell._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Success, Try }

/**
 * The shell tool gives a command a scrubbed environment, can hold the file arguments of a command to the file
 * tools' path policy, and refuses the flags that make a read-only command write a file (issue #1408, findings F3
 * and F9).
 *
 * The commands used (`env`, `cat`, `ls`, `head`, `file`, `wc`, `echo`) are POSIX programs: the tests that run
 * them are cancelled on Windows. Every file is a dummy inside a fresh temporary directory.
 */
class ShellContainmentSpec extends AnyFlatSpec with Matchers {

  private val isWindows = System.getProperty("os.name", "").toLowerCase.contains("win")

  private def posixOnly(): Unit = assume(!isWindows, "the commands used here are POSIX programs")

  private def newRoot(): Path = Files.createTempDirectory("shell-containment").toRealPath()

  private def run(config: ShellConfig, command: String): Either[String, ShellResult] =
    ShellTool
      .createSafe(config)
      .fold(e => fail(e.formatted), identity)
      .handler(SafeParameterExtractor(ujson.Obj("command" -> command)))

  /** What the parent JVM has, read the way a child process would receive it. */
  private def parentEnvironment: Map[String, String] = new ProcessBuilder().environment().asScala.toMap

  /** Variables the parent has that the default list does not pass (and that no OS re-adds on its own). */
  private def probes: Seq[String] =
    parentEnvironment.keys.toSeq
      .filter(name => !ShellConfig.DefaultInheritedEnvironment.contains(name) && !name.startsWith("_"))
      .filter(_.matches("[A-Za-z][A-Za-z0-9_]*"))
      .sorted

  private def childEnvironment(config: ShellConfig): Map[String, String] =
    run(config.copy(allowedCommands = Seq("env")), "env")
      .fold(e => fail(e), identity)
      .stdout
      .linesIterator
      .flatMap(_.split("=", 2) match {
        case Array(name, value) => Some(name -> value)
        case _                  => None
      })
      .toMap

  // ---- F3a: the environment

  "A shell command" should "not inherit the process environment by default" in {
    posixOnly()
    val others = probes
    assume(others.nonEmpty, "the test process has no variable beyond the default list")

    childEnvironment(ShellConfig()).keySet.intersect(others.toSet) shouldBe empty
  }

  it should "inherit everything when asked to (the control that shows the check above can fail)" in {
    posixOnly()
    val others = probes
    assume(others.nonEmpty, "the test process has no variable beyond the default list")

    childEnvironment(ShellConfig(inheritedEnvironment = None)).keySet.intersect(others.toSet) should not be empty
  }

  it should "receive the variables that are named, with the parent's value, and no others" in {
    posixOnly()
    val others = probes
    assume(others.size >= 2, "the test process has fewer than two variables beyond the default list")
    val named = others.head

    val env = childEnvironment(ShellConfig(inheritedEnvironment = Some(Seq(named))))

    env.get(named) shouldBe parentEnvironment.get(named)
    env.keySet.intersect(others.tail.toSet) shouldBe empty
  }

  it should "receive the default variables that the process has" in {
    posixOnly()
    val expected = ShellConfig.DefaultInheritedEnvironment.filter(parentEnvironment.contains)
    assume(expected.nonEmpty, "the test process has none of the default variables")

    val env = childEnvironment(ShellConfig())

    expected.foreach(name => withClue(name)(env.get(name) shouldBe parentEnvironment.get(name)))
  }

  it should "receive the configured environment, which wins over an inherited value" in {
    posixOnly()
    val env = childEnvironment(
      ShellConfig(
        environment = Map("LLM4S_TEST_EXPLICIT" -> "yes", "PATH" -> "/explicit/path"),
        inheritedEnvironment = Some(Seq("PATH"))
      )
    )

    env.get("LLM4S_TEST_EXPLICIT") shouldBe Some("yes")
    env.get("PATH") shouldBe Some("/explicit/path")
  }

  "ShellConfig.development" should "keep inheriting the whole environment, and readOnly should scrub it" in {
    ShellConfig.development().inheritedEnvironment shouldBe None
    ShellConfig.readOnly().inheritedEnvironment shouldBe Some(ShellConfig.DefaultInheritedEnvironment)
  }

  // ---- F3b: the path policy

  private def policyFor(root: Path): FileConfig =
    FileConfig(allowedPaths = Some(Seq(root.toString)), blockedPaths = Seq.empty)

  private def withinRoot(root: Path) = ShellConfig.readOnlyWithin(policyFor(root), Some(root.toString))

  private def refused(result: Either[String, ShellResult]): String =
    result.left.toOption.getOrElse(fail(s"expected a refusal, got $result"))

  "ShellConfig.readOnlyWithin" should "keep the read-only commands and set the policy" in {
    val policy = policyFor(newRoot())
    val config = ShellConfig.readOnlyWithin(policy, Some("/work"))

    config.pathPolicy shouldBe Some(policy)
    config.workingDirectory shouldBe Some("/work")
    config.allowedCommands shouldBe ShellConfig.readOnly().allowedCommands
  }

  it should "run a command on a file inside the allowed directory" in {
    posixOnly()
    val root = newRoot()
    Files.writeString(root.resolve("notes.txt"), "inside-content")

    val result = run(withinRoot(root), "cat notes.txt").fold(e => fail(e), identity)

    result.exitCode shouldBe 0
    result.stdout should include("inside-content")
  }

  it should "refuse a file outside the allowed directory, by absolute path, and not run the command" in {
    posixOnly()
    val root    = newRoot()
    val outside = newRoot()
    Files.writeString(outside.resolve("secret.txt"), "OUTSIDE-SECRET")

    refused(run(withinRoot(root), s"cat ${outside.resolve("secret.txt")}")) should include("outside the allowed paths")
  }

  it should "refuse a file outside the allowed directory reached by .." in {
    posixOnly()
    val base = newRoot()
    Files.createDirectories(base.resolve("root"))
    Files.writeString(base.resolve("secret.txt"), "OUTSIDE-SECRET")

    refused(run(withinRoot(base.resolve("root")), "cat ../secret.txt")) should include("outside the allowed paths")
  }

  it should "refuse a file reached through a link that leads out of the allowed directory" in {
    posixOnly()
    val root    = newRoot()
    val outside = newRoot()
    Files.writeString(outside.resolve("secret.txt"), "OUTSIDE-SECRET")
    Try(Files.createSymbolicLink(root.resolve("link"), outside)) match {
      case Success(_) => ()
      case Failure(e) => cancel(s"symbolic links cannot be created here: ${e.getClass.getSimpleName}")
    }

    refused(run(withinRoot(root), "cat link/secret.txt")) should include("outside the allowed paths")
  }

  it should "refuse a command whose working directory is outside the allowed directory" in {
    posixOnly()
    val root    = newRoot()
    val outside = newRoot()
    val config  = ShellConfig.readOnlyWithin(policyFor(root), Some(outside.toString))

    refused(run(config, "ls")) should include("working directory is outside")
  }

  it should "refuse the file after -- as well, and allow it when it is inside" in {
    posixOnly()
    val root    = newRoot()
    val outside = newRoot()
    Files.writeString(outside.resolve("secret.txt"), "OUTSIDE-SECRET")
    Files.writeString(root.resolve("-odd.txt"), "dash-file")

    refused(run(withinRoot(root), s"cat -- ${outside.resolve("secret.txt")}")) should include("outside")
    run(withinRoot(root), "cat -- -odd.txt").map(_.stdout.trim) shouldBe Right("dash-file")
  }

  it should "refuse a flag that carries a path, and the flags that make ls follow links" in {
    posixOnly()
    val root = newRoot()
    Files.writeString(root.resolve("a.txt"), "a")
    val config = withinRoot(root)

    refused(run(config, "head -n1/etc/passwd")) should include("carries a path")
    refused(run(config, "ls -L")) should include("follows links")
    refused(run(config, "ls -lH")) should include("follows links")
    refused(run(config, "ls --dereference")) should include("follows links")
    run(config, "ls -l").map(_.exitCode) shouldBe Right(0)
  }

  it should "leave commands that take no file arguments alone" in {
    posixOnly()
    val root = newRoot()

    run(withinRoot(root), "echo hello").map(_.stdout.trim) shouldBe Right("hello")
  }

  // ---- F9 and the flags that read a list of files, with or without a policy

  "The read-only shell" should "refuse the flags of file and wc that write a file or read a list of files" in {
    posixOnly()
    val root   = newRoot()
    val config = ShellConfig.readOnly(Some(root.toString))

    List("file -C", "file -bC", "file --compile", "file -m magic", "file --magic-file=magic", "file -f list")
      .foreach(command => withClue(command)(refused(run(config, command)) should include("is not allowed for 'file'")))
    refused(run(config, "wc --files0-from=list")) should include("is not allowed for 'wc'")
    Files.exists(root.resolve("magic.mgc")) shouldBe false
  }

  it should "refuse file-reading date flags and validate exempt commands' working directories" in {
    val root   = newRoot()
    val config = withinRoot(root)
    Seq("date -f /outside", "date --file=/outside", "date -r /outside", "date --reference=/outside")
      .foreach(command => refused(run(config, command)) should include("is not allowed"))
    refused(run(config.copy(workingDirectory = Some(root.getParent.toString)), "date")) should
      include("working directory is outside")
  }

  it should "apply denied flags to absolute executable names" in {
    val root   = newRoot()
    val config = ShellConfig(allowedCommands = Seq("/usr/bin/file"), workingDirectory = Some(root.toString))
    refused(run(config, "/usr/bin/file -C")) should include("is not allowed")
    Files.exists(root.resolve("magic.mgc")) shouldBe false
  }

  it should "still run file and wc with their ordinary flags" in {
    posixOnly()
    val root = newRoot()
    Files.writeString(root.resolve("a.txt"), "one two\n")
    val config = ShellConfig.readOnly(Some(root.toString))

    run(config, "wc -w a.txt").map(_.stdout.trim.startsWith("2")) shouldBe Right(true)
    run(config, "file -b a.txt").map(_.exitCode) shouldBe Right(0)
  }
}
