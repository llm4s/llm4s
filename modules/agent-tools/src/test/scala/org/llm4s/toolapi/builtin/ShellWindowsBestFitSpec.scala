package org.llm4s.toolapi.builtin

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.filesystem.FileConfig
import org.llm4s.toolapi.builtin.shell._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.Charset
import java.nio.file.{ Files, Path }

/**
 * On Windows a program that reads its command line in an ANSI code page may receive a character as a different ASCII
 * one ("best fit", WorstFit, #1766): `..／..／secret` as `../../secret`, `-ｏ` as `-o`. The shell tool's checks read
 * UTF-16, so on Windows such a character is refused in an argument, the working directory and an environment value,
 * with or without a path policy.
 *
 * `ShellTool.checkCommand` applies the Windows character rule on any host when asked (`windows = true`); on the
 * previous shell tool, which had no such rule, every command refused below passed its checks.
 */
class ShellWindowsBestFitSpec extends AnyFlatSpec with Matchers {

  private val isWindowsHost = System.getProperty("os.name", "").toLowerCase.startsWith("windows")

  /** Fullwidth reverse solidus, fullwidth solidus, fullwidth quote, yen, won, division slash. */
  private val Separators = Seq('\uFF3C', '\uFF0F', '\uFF02', '\u00A5', '\u20A9', '\u2215')

  private def newRoot(): Path = Files.createTempDirectory("shell-bestfit").toRealPath()

  private def contained(root: Path): ShellConfig =
    ShellConfig
      .readOnlyWithin(
        FileConfig(allowedPaths = Some(Seq(root.toString)), blockedPaths = Seq.empty),
        Some(root.toString)
      )
      .copy(allowedCommands = Seq("cat", "grep", "sort", "findstr", "type"))

  private def verdict(
    config: ShellConfig,
    command: String,
    windows: Boolean = true,
    ansi: Option[Charset] = None
  ): Option[String] =
    ShellTool.checkCommand(command, config, windows, ansi)._1

  private def refusedOnWindows(config: ShellConfig, command: String): String =
    withClue(command)(verdict(config, command).getOrElse(fail(s"'$command' was not refused")))

  "The shell tool on Windows" should "refuse a best-fit separator or quote in an operand" in {
    val root = newRoot()
    Separators.foreach { c =>
      val why = refusedOnWindows(contained(root), s"cat ..${c}..${c}secret")
      why should include(f"U+${c.toInt}%04X")
      why should include("not allowed on Windows")
      refusedOnWindows(contained(root), s"findstr x ..${c}outside${c}secret.txt")
    }
  }

  it should "refuse one in a value attached to a flag" in {
    val root = newRoot()
    Separators.foreach { c =>
      refusedOnWindows(contained(root), s"grep -f..${c}..${c}secret in.txt")
      refusedOnWindows(contained(root), s"grep --file=..${c}..${c}secret in.txt")
      refusedOnWindows(contained(root), s"findstr /G:..${c}..${c}secret in.txt")
    }
  }

  it should "refuse one without a path policy too" in {
    val root   = newRoot()
    val config = contained(root).copy(pathPolicy = None)
    Separators.foreach(c => refusedOnWindows(config, s"cat ..${c}..${c}secret"))
  }

  it should "refuse one in the working directory and in an environment value" in {
    val root = newRoot()
    Separators.foreach { c =>
      val dir = Files.createDirectory(root.resolve(s"sub${c}x"))
      refusedOnWindows(contained(root).copy(workingDirectory = Some(dir.toString)), "cat in.txt") should
        include("working directory")
      refusedOnWindows(contained(root).copy(environment = Map("LANG" -> s"..${c}x")), "cat in.txt") should
        include("environment variable 'LANG'")
    }
  }

  it should "refuse an option letter, dash or dot a code page best-fits from another character" in {
    val config = contained(newRoot()).copy(pathPolicy = None)
    Seq(
      "sort -\uFF4F out in.txt", // fullwidth o: `sort -o out` writes a file
      "sort -\u00F6 out in.txt", // o with diaeresis, `o` on code pages 932, 949, 950, 1251, 1253
      "sort \u00ADo out in.txt", // soft hyphen, `-` on code pages 932, 936, 950
      "cat \uFF0E\uFF0E",        // fullwidth full stops: `..`
      "sort /\uFF4F out in.txt"  // fullwidth o in a native switch: `/o`
    ).foreach(command => refusedOnWindows(config, command))
  }

  it should "keep allowing accented letters in an operand, which only best-fit to letters" in {
    val root = newRoot()
    verdict(contained(root), "grep caf\u00E9 r\u00E9sum\u00E9.txt") shouldBe None
    verdict(contained(root), "cat M\u00FCller.txt", ansi = Some(Charset.forName("windows-1252"))) shouldBe None
  }

  it should "refuse a character the host's ANSI code page cannot hold" in {
    val root   = newRoot()
    val cp1252 = Some(Charset.forName("windows-1252"))
    verdict(contained(root), "cat \u4E02.txt", ansi = cp1252) shouldBe defined
    verdict(contained(root), "cat \uD83D\uDE00.txt", ansi = cp1252) shouldBe defined
    verdict(contained(root), "cat r\u00E9sum\u00E9.txt", ansi = Some(Charset.forName("MS932"))) shouldBe defined
    verdict(contained(root), "cat \u4E02.txt", ansi = Some(Charset.forName("GBK"))) shouldBe None
  }

  it should "leave the arguments unchanged on POSIX, where nothing converts them" in {
    val root = newRoot()
    Separators.foreach { c =>
      verdict(contained(root), s"cat ..${c}..${c}secret", windows = false) shouldBe None
      verdict(contained(root).copy(pathPolicy = None), s"sort -k1 a${c}b", windows = false) shouldBe None
    }
  }

  it should "refuse them on a Windows host" in {
    assume(isWindowsHost, "only a Windows host converts arguments to an ANSI code page")
    val root   = newRoot()
    val config = contained(root)
    val tool   = ShellTool.createSafe(config).fold(e => fail(e.formatted), identity)
    Separators.foreach { c =>
      val result =
        tool.handler(SafeParameterExtractor(ujson.Obj("command" -> s"findstr x ..${c}outside${c}secret.txt")))
      result.left.toOption.getOrElse(fail(s"expected a refusal, got $result")) should include("not allowed on Windows")
    }
  }
}
