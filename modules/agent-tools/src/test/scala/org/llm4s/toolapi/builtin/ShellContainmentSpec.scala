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

  /** A contains `linksub -> outside/sub`; `outside/secret.txt` exists. Returns (A, outside). */
  private def linkThenDotDot(): (Path, Path) = {
    val root    = newRoot()
    val outside = newRoot()
    Files.createDirectories(outside.resolve("sub"))
    Files.writeString(outside.resolve("secret.txt"), "SECRET-OUT")
    Try(Files.createSymbolicLink(root.resolve("linksub"), outside.resolve("sub"))) match {
      case Success(_) => ()
      case Failure(e) => cancel(s"symbolic links cannot be created here: ${e.getClass.getSimpleName}")
    }
    (root, outside)
  }

  it should "refuse .. after a link that leads out, which the OS applies after following the link" in {
    posixOnly()
    val (root, _) = linkThenDotDot()

    refused(run(withinRoot(root), "cat linksub/../secret.txt")) should include("outside the allowed paths")
    refused(run(withinRoot(root), "ls linksub/..")) should include("outside the allowed paths")
  }

  it should "refuse .. after a link that leads into a blocked directory" in {
    posixOnly()
    val (root, outside) = linkThenDotDot()
    val config = ShellConfig.readOnlyWithin(
      FileConfig(allowedPaths = None, blockedPaths = Seq(outside.toString)),
      Some(root.toString)
    )

    refused(run(config, "cat linksub/../secret.txt")) should include("outside the allowed paths")
  }

  it should "still allow .. after a link that stays inside, judged at the link target's real parent" in {
    posixOnly()
    val root = newRoot()
    Files.createDirectories(root.resolve("inner/deep"))
    Files.writeString(root.resolve("inner/x.txt"), "physical-parent")
    Try(Files.createSymbolicLink(root.resolve("l2"), root.resolve("inner/deep"))) match {
      case Success(_) => ()
      case Failure(e) => cancel(s"symbolic links cannot be created here: ${e.getClass.getSimpleName}")
    }

    run(withinRoot(root), "cat l2/../x.txt").map(_.stdout.trim) shouldBe Right("physical-parent")
  }

  it should "refuse .. after a link that stays inside physically but leaves the allowed area as Windows reads it" in {
    posixOnly()
    val base = newRoot()
    val root = base.resolve("root")
    Files.createDirectories(root.resolve("inner/deep"))
    Files.writeString(root.resolve("secret.txt"), "physical-target")
    Files.writeString(base.resolve("secret.txt"), "OUTSIDE-SECRET")
    Try(Files.createSymbolicLink(root.resolve("l2"), root.resolve("inner/deep"))) match {
      case Success(_) => ()
      case Failure(e) => cancel(s"symbolic links cannot be created here: ${e.getClass.getSimpleName}")
    }

    // POSIX `cat` would read root/secret.txt, but on Windows the same spelling is base/secret.txt
    refused(run(withinRoot(root), "cat l2/../../secret.txt")) should include("outside the allowed paths")
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

  it should "refuse abbreviations of the denied long options, which GNU programs accept" in {
    val root   = newRoot()
    val config = withinRoot(root)
    Seq(
      "date --fil /outside",
      "date --f=/outside",
      "date --ref=/outside",
      "file --comp",
      "file --magic=magic",
      "file --files-f list",
      "wc --files0 list",
      "wc --f=list"
    ).foreach(command => withClue(command)(refused(run(config, command)) should include("is not allowed")))
    Seq("ls --deref", "ls --dereference-command", "ls --de")
      .foreach(command => withClue(command)(refused(run(config, command)) should include("follows links")))
  }

  it should "still accept long options that only share a prefix with a denied one" in {
    posixOnly()
    val root = newRoot()
    Files.writeString(root.resolve("a.txt"), "one two\n")
    val config = withinRoot(root)

    // The programs ran (BSD versions may reject these GNU options, so only the refusal is asserted)
    run(config, "wc --words a.txt").isRight shouldBe true
    run(config, "ls --directory .").isRight shouldBe true
    run(config, "date --rfc-3339=date").isRight shouldBe true
  }

  it should "refuse a denied flag after a -- that an option consumed as its argument" in {
    posixOnly()
    val root = newRoot()
    Files.writeString(root.resolve("list"), "/etc/hosts\n")
    Files.writeString(root.resolve("x"), "x")
    // getopt reads `--` as the argument of -F (or -e, -P), then reads what follows as options
    Seq("file -F -- -f list", "file -F -- -C -m x", "file -e -- --files-from list", "file -P -- -M x")
      .foreach(command => withClue(command)(refused(run(ShellConfig.readOnly(Some(root.toString)), command))))
    Files.exists(root.resolve("x.mgc")) shouldBe false
  }

  it should "refuse a link-following ls flag after a consumed --, under a policy" in {
    posixOnly()
    val root = newRoot()
    refused(run(withinRoot(root), "ls -I -- -L .")) should include("follows links")
    refused(run(withinRoot(root), "ls -w -- -n1/etc")) should include("carries a path")
  }

  it should "refuse file -M, which reads magic files it does not name as arguments" in {
    val root   = newRoot()
    val config = ShellConfig.readOnly(Some(root.toString))
    Seq("file -M magic x", "file -bM magic x", "file -Mmagic x")
      .foreach(command => withClue(command)(refused(run(config, command)) should include("is not allowed for 'file'")))
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

  // ---- #1723: option values attached to their flag

  /**
   * A root holding `in.txt`, `pats` (a pattern file), a link `lout` to `outside/secret.txt` (`None` where links
   * cannot be made) and `blocked.txt`, which the returned policy blocks. Returns (root, link made, config) with
   * `grep` and `sort` added to the read-only allowlist.
   */
  private def attachedValueFixture(): (Path, Boolean, ShellConfig) = {
    val root    = newRoot()
    val outside = newRoot()
    Files.writeString(outside.resolve("secret.txt"), "OUTSIDE-SECRET\n")
    Files.writeString(root.resolve("in.txt"), "alpha\nOUTSIDE-SECRET\nbeta\n")
    Files.writeString(root.resolve("pats"), "alpha\n")
    Files.writeString(root.resolve("blocked.txt"), "OUTSIDE-SECRET\n")
    val linked = Try(Files.createSymbolicLink(root.resolve("lout"), outside.resolve("secret.txt"))).isSuccess
    val policy = FileConfig(
      allowedPaths = Some(Seq(root.toString)),
      blockedPaths = Seq(root.resolve("blocked.txt").toString)
    )
    val base = ShellConfig.readOnlyWithin(policy, Some(root.toString))
    (root, linked, base.copy(allowedCommands = base.allowedCommands ++ Seq("grep", "sort")))
  }

  /**
   * Every spelling of an option value the program opens as a file; `<v>` is replaced by the file. Each one is its own
   * test, for a link out of the allowed directory and for a blocked file.
   */
  private def attachedSpellings: Seq[String] = Seq(
    "grep -f<v> in.txt",               // short option, value attached
    "grep -if<v> in.txt",              // clustered short options, value attached to the last
    "grep -inf<v> in.txt",             // longer cluster
    "grep --file=<v> in.txt",          // long option with =value
    "grep --fil=<v> in.txt",           // abbreviated long option (getopt_long prefix) with =value
    "grep --f=<v> in.txt",             // shortest abbreviation
    "grep -e -- -f<v> in.txt",         // after a -- that -e consumed as its value
    "sort --random-source=<v> in.txt", // another program's file-valued long option
    "sort --random-s=<v> in.txt"
  )

  attachedSpellings.foreach { spelling =>
    val viaLink = spelling.replace("<v>", "lout")
    "The path policy" should s"refuse `$viaLink`, a link out of the allowed directory attached to its flag (#1723)" in {
      val (_, linked, config) = attachedValueFixture()
      if (!linked) cancel("symbolic links cannot be created here")

      refused(run(config, viaLink)) should include("outside the allowed paths")
    }

    val viaBlocked = spelling.replace("<v>", "blocked.txt")
    it should s"refuse `$viaBlocked`, a blocked file attached to its flag (#1723)" in {
      val (_, _, config) = attachedValueFixture()

      refused(run(config, viaBlocked)) should include("outside the allowed paths")
    }
  }

  "The path policy" should "still refuse the value given as a separate argument (the control)" in {
    val (_, linked, config) = attachedValueFixture()
    if (!linked) cancel("symbolic links cannot be created here")

    refused(run(config, "grep -f lout in.txt")) should include("outside the allowed paths")
    refused(run(config, "grep --file lout in.txt")) should include("outside the allowed paths")
    refused(run(config, "grep -f blocked.txt in.txt")) should include("outside the allowed paths")
  }

  Seq("grep -f.. in.txt", "grep -if.. in.txt", "grep --file=.. in.txt").foreach { command =>
    it should s"refuse `$command`, an attached .. that leads out of the allowed directory" in {
      val (_, _, config) = attachedValueFixture()

      refused(run(config, command)) should include("outside the allowed paths")
    }
  }

  it should "refuse a flag too long to check value by value" in {
    val (_, _, config) = attachedValueFixture()

    refused(run(config, "grep -e" + ("a" * 5000) + " in.txt")) should include("too long")
  }

  // ---- #1723: the cost of the check. A path check costs file-system lookups, and a command can carry thousands of
  // flags with thousands of tails each; before the budget, 50 flags of 4096 letters took about 50 s on macOS, all
  // before the command started, so `ShellConfig.timeout` did not cover it. Every command below ends in `../zz`, so a
  // refusal naming it shows that everything before it was checked and passed, and nothing is run.

  /** The command's refusal, and how long the tool took to give it. */
  private def timedRefusal(config: ShellConfig, command: String): (String, Long) = {
    val started = System.nanoTime()
    val reason  = refused(run(config, command))
    (reason, (System.nanoTime() - started) / 1000000)
  }

  /** Far above the ~0.1 s these take, far below the minutes they took: room for a slow Windows runner. */
  private def CheapCheckMillis: Long = 5000L

  it should "check many long flags quickly, one lookup for each distinct tail that names nothing (#1723)" in {
    val (_, _, config) = attachedValueFixture()
    val flags          = Seq.fill(200)("-e" + ("a" * 4094)).mkString(" ")

    val (reason, millis) = timedRefusal(config, s"grep $flags in.txt ../zz")
    reason should include("'../zz' is outside the allowed paths")
    millis should be < CheapCheckMillis
  }

  it should "run a command with one 4094-character pattern, its tails checked cheaply (#1723)" in {
    posixOnly()
    val (_, _, config) = attachedValueFixture()
    val started        = System.nanoTime()

    val result = run(config, "grep -e" + ("a" * 4094) + " in.txt").fold(e => fail(e), identity)
    (System.nanoTime() - started) / 1000000 should be < CheapCheckMillis
    result.exitCode shouldBe 1 // ran, and found no match
    val mixed = run(config, "grep -ie" + new scala.util.Random(1723).alphanumeric.take(4093).mkString + " in.txt")
    mixed.fold(e => fail(e), _.exitCode) shouldBe 1
  }

  it should "check a repeated short-option cluster once (#1723)" in {
    val (_, _, config) = attachedValueFixture()

    val (reason, millis) = timedRefusal(config, "ls " + Seq.fill(10000)("-aaaa").mkString(" ") + " ../zz")
    reason should include("'../zz' is outside the allowed paths")
    millis should be < CheapCheckMillis
  }

  it should "refuse a command whose path checks would exceed the lookup budget, quickly (#1723)" in {
    val (_, _, config) = attachedValueFixture()
    val clusters       = (1 to 10000).map(i => s"-a$i").mkString(" ")
    // `~` is not a plain name character, so no tail of these is skipped
    val tildes = (1 to 200).map(i => "-e" + (s"$i~" * 4094).take(4094)).mkString(" ")
    // 200 distinct flags of 4094 letters: about 800000 distinct tails, a lookup each, far over the budget
    val letters = (1 to 200).map(i => "-e" + new scala.util.Random(i).alphanumeric.take(4094).mkString).mkString(" ")

    Seq(s"ls $clusters ../zz", s"grep $tildes in.txt ../zz", s"grep $letters in.txt ../zz").foreach { command =>
      val (reason, millis) = timedRefusal(config, command)
      withClue(command.take(40)) {
        reason should include("too long or too many to check against the path policy")
        reason should include("20000 path lookups")
        millis should be < CheapCheckMillis
      }
    }
  }

  it should "still check the short tails of a long flag (#1723)" in {
    val (root, linked, config) = attachedValueFixture()
    // A blocked file with a name of 255 characters, which every common file system can hold; existing names longer
    // still are resolved in full too (SingleNameCheckSpec, and the next test where the file system allows them)
    val longest = "n" * 255
    Files.writeString(root.resolve(longest), "OUTSIDE-SECRET\n")
    val blocking = config.copy(pathPolicy =
      config.pathPolicy.map(p => p.copy(blockedPaths = p.blockedPaths :+ root.resolve(longest).toString))
    )

    refused(run(config, "grep -" + ("i" * 4000) + "fblocked.txt in.txt")) should include("outside the allowed paths")
    refused(run(blocking, "grep -" + ("i" * 3800) + "f" + longest + " in.txt")) should include(
      "outside the allowed paths"
    )
    refused(run(blocking, "grep -e" + longest + " in.txt")) should include("outside the allowed paths")
    if (linked)
      refused(run(config, "grep -" + ("i" * 4000) + "flout in.txt")) should include("outside the allowed paths")
  }

  it should "resolve in full a tail that names an existing link, however long its name (#1723)" in {
    val (root, _, config) = attachedValueFixture()
    // Plant the longest of these link names the file system here accepts; the check must not depend on which
    val planted = Seq(3000, 1000, 255).iterator
      .map(length => "l" * length)
      .find(name => Try(Files.createSymbolicLink(root.resolve(name), root.getParent.resolve("elsewhere"))).isSuccess)
    planted match {
      case None => cancel("symbolic links cannot be created here")
      case Some(name) =>
        withClue(s"a link of ${name.length} characters") {
          refused(run(config, s"grep -e$name in.txt")) should include("outside the allowed paths")
          refused(run(config, "grep -" + ("i" * (4000 - name.length)) + s"f$name in.txt")) should include(
            "outside the allowed paths"
          )
        }
    }
  }

  "The shell tool" should "read the value of sort -t as a separator, not as options or a path (#1723)" in {
    assume(!isWindows, "on Windows -t is refused, since sort.exe may read it as /T (see the next test)")
    val (root, _, config) = attachedValueFixture()

    Seq("sort -to in.txt", "sort -rto in.txt", "sort -t/ in.txt", "sort --field-separator=o in.txt", "sort -tT in.txt")
      .foreach { command =>
        withClue(command) {
          (run(config, command).left.toOption.getOrElse("") should not).include("is not allowed")
          (run(config.copy(pathPolicy = None), command).left.toOption.getOrElse("") should not)
            .include("is not allowed")
          (run(config, command).left.toOption.getOrElse("") should not).include("carries a path")
        }
      }
    // An option before the -t is still read: -o, then the separator
    refused(run(config, "sort -ot in.txt")) should include("is not allowed for 'sort'")
    // A separator given as the next argument is checked as a path: an option before -t may have taken `-t` itself
    refused(run(config, "sort -t / in.txt")) should include("outside the allowed paths")
    Files.exists(root.resolve("o")) shouldBe false
  }

  it should "refuse sort's o and t, in either case, in a cluster or a / switch, on Windows (#1723)" in {
    assume(isWindows, "only Windows has sort.exe")
    val (root, _, config) = attachedValueFixture()

    Seq(
      "sort -to in.txt",
      "sort -t/ in.txt",
      "sort -O in.txt",
      "sort /O out in.txt",
      "sort /o out in.txt",
      "sort /OUTPUT out in.txt",
      "sort /T . in.txt",
      "sort /temporary . in.txt"
    ).foreach { command =>
      withClue(command) {
        refused(run(config, command)) should include("is not allowed for 'sort'")
        refused(run(config.copy(pathPolicy = None), command)) should include("is not allowed for 'sort'")
      }
    }
    Files.exists(root.resolve("out")) shouldBe false
  }

  it should "run sort with an attached separator (#1723)" in {
    posixOnly()
    val (_, _, config) = attachedValueFixture()

    run(config, "sort -tp -k2 in.txt").map(_.exitCode) shouldBe Right(0)
    run(config, "sort -to in.txt").map(_.exitCode) shouldBe Right(0)
  }

  it should "still run commands whose attached values stay inside the allowed directory" in {
    posixOnly()
    val (_, _, config) = attachedValueFixture()

    run(config, "grep -fpats in.txt").map(_.stdout.trim) shouldBe Right("alpha")
    run(config, "grep --file=pats in.txt").map(_.stdout.trim) shouldBe Right("alpha")
    run(config, "grep -iealpha in.txt").map(_.stdout.trim) shouldBe Right("alpha")
    run(config, "grep -e -- -x in.txt").map(_.exitCode) shouldBe Right(1) // no match, but it ran
    run(config, "head -n1 in.txt").map(_.stdout.trim) shouldBe Right("alpha")
    run(config, "sort -r -k1 in.txt").map(_.exitCode) shouldBe Right(0)
    run(config, "ls -la").map(_.exitCode) shouldBe Right(0)
    run(config, "wc -l in.txt").map(_.stdout.trim.startsWith("3")) shouldBe Right(true)
  }

  /** sort's refused options in every spelling: they write a file, run a program or read a list of files. */
  private def sortRefusals: Seq[String] = Seq(
    "sort --files0-from=lout",
    "sort --files0-from=blocked.txt",
    "sort --files0-from=pats",
    "sort --files0-from pats",
    "sort --files0=pats",
    "sort --fil=pats",
    "sort -k -- --files0-from=pats",
    "sort -o out in.txt",
    "sort -oout in.txt",
    "sort -roout in.txt",
    "sort -k -- -oout in.txt",
    "sort --output=out in.txt",
    "sort --out out in.txt",
    "sort --o=out in.txt",
    "sort -T . in.txt",
    "sort -T. in.txt",
    "sort -rT. in.txt",
    "sort --temporary-directory=. in.txt",
    "sort --temp . in.txt",
    "sort --t=. in.txt",
    "sort --compress-program=sh in.txt",
    "sort --compress=sh in.txt"
  )

  sortRefusals.foreach { command =>
    "The shell tool" should s"refuse `$command`, with or without a path policy (#1723)" in {
      val (root, _, config) = attachedValueFixture()

      refused(run(config, command)) should include("is not allowed for 'sort'")
      refused(run(config.copy(pathPolicy = None), command)) should include("is not allowed for 'sort'")
      Files.exists(root.resolve("out")) shouldBe false
    }
  }

  /** A program spelled as Windows or a case-insensitive file system still finds it, and the rest of the command. */
  private def programSpellings: Seq[(String, String)] = Seq(
    "FILE"                -> "-C",
    "File"                -> "-m magic x",
    "file.exe"            -> "-C",
    "FILE.EXE"            -> "--compile",
    "file.cmd"            -> "-C",
    "file.exe."           -> "-C",
    "C:\\tools\\File.exe" -> "-C",
    "SORT"                -> "-oout",
    "Date.exe"            -> "--ref=x",
    "WC"                  -> "--files0-from=x"
  )

  programSpellings.foreach { case (program, rest) =>
    it should s"refuse `$program $rest`: denied options match the program whatever its case or .exe" in {
      val root   = newRoot()
      val config = ShellConfig(allowedCommands = Seq(program), workingDirectory = Some(root.toString))

      // Single quotes keep the backslashes of a Windows path
      refused(run(config, s"'$program' $rest")) should include("is not allowed for")
      Files.exists(root.resolve("magic.mgc")) shouldBe false
      Files.exists(root.resolve("out")) shouldBe false
    }
  }
}
