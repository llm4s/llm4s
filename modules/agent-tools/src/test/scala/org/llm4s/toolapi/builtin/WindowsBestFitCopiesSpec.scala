package org.llm4s.toolapi.builtin

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

/**
 * `WindowsBestFit.scala` is generated twice (#1766): into this module for ShellTool and into the workspace runner for
 * its command policy, which share no dependency that could hold one copy. `scripts/generate-windows-best-fit.py
 * --check` needs the network or the downloaded tables, so this spec keeps the two copies in step in every build: apart
 * from the `package` line and the visibility of the object, they must be the same file, table included.
 */
class WindowsBestFitCopiesSpec extends AnyFlatSpec with Matchers {

  private val AgentTools  = "modules/agent-tools/src/main/scala/org/llm4s/toolapi/builtin/shell/WindowsBestFit.scala"
  private val Runner      = "modules/workspace/workspaceRunner/src/main/scala/org/llm4s/runner/WindowsBestFit.scala"
  private val Declaration = """(?m)^private\[\w+\] object WindowsBestFit \{$""".r

  private lazy val repoRoot: Path =
    Iterator
      .iterate(Paths.get(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")) && Files.exists(dir.resolve(Runner)))
      .getOrElse(fail("could not find the repository root (a directory with build.sbt) above the working directory"))

  /** The file's lines with Windows line endings normalised, so a checkout with CRLF compares equal. */
  private def read(relative: String): String =
    new String(Files.readAllBytes(repoRoot.resolve(relative)), StandardCharsets.UTF_8).replace("\r\n", "\n")

  /** The file without what legitimately differs between the copies: its package and the object's visibility. */
  private def normalised(relative: String, pkg: String, visibility: String): Seq[String] = {
    val text  = read(relative)
    val lines = text.split("\n", -1).toSeq
    withClue(s"$relative: ") {
      lines.count(_ == s"package $pkg") shouldBe 1
      Declaration.findAllIn(text).toSeq shouldBe Seq(s"private[$visibility] object WindowsBestFit {")
    }
    lines.filterNot(_ == s"package $pkg").map(Declaration.replaceAllIn(_, "<declaration>"))
  }

  "the two generated WindowsBestFit copies" should "be identical apart from package and visibility" in {
    val agentTools = normalised(AgentTools, "org.llm4s.toolapi.builtin.shell", "builtin")
    val runner     = normalised(Runner, "org.llm4s.runner", "runner")
    val firstDifference = agentTools.zipAll(runner, "<end of file>", "<end of file>").zipWithIndex.collectFirst {
      case ((a, r), i) if a != r =>
        s"line ${i + 1} (ignoring the package line): agent-tools has\n  $a\nthe runner has\n  $r"
    }
    withClue("run scripts/generate-windows-best-fit.py to regenerate both copies; ") {
      firstDifference shouldBe None
    }
    agentTools.exists(_.contains("private val Encoded: String = Seq(")) shouldBe true
  }
}
