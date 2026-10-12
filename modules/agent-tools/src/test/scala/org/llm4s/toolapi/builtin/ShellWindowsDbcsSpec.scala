package org.llm4s.toolapi.builtin

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.filesystem.FileConfig
import org.llm4s.toolapi.builtin.shell._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.Charset
import java.nio.file.{ Files, Path }

/**
 * On a Windows host whose ANSI code page is a double-byte one (932, 936, 950), some characters encode with a second
 * byte of 0x5C, the byte of `\` (#1807): `表` is 0x95 0x5C on code page 932, `功` 0xA5 0x5C on 950. The JDK puts an
 * argument holding a space in quotes, so `a 表` reaches the command line as `"a 表"`, whose bytes end `5C 22`, and a
 * program that splits its ANSI command line byte by byte reads `\"` as a literal quote and splits the arguments after
 * it differently from what the checks saw. The shell tool refuses such an argument when the JDK may quote it.
 *
 * `ShellTool.checkCommand` takes the code page, so these cases run on any host; on the previous shell tool every
 * command refused below passed its checks.
 */
class ShellWindowsDbcsSpec extends AnyFlatSpec with Matchers {

  private val isWindowsHost = System.getProperty("os.name", "").toLowerCase.startsWith("windows")

  private val MS932 = Charset.forName("MS932")
  private val GBK   = Charset.forName("GBK")
  private val MS936 = Charset.forName("x-mswin-936")
  private val MS950 = Charset.forName("MS950")

  /** Per code page, characters it holds whose encoding ends in 0x5C. */
  private val TrailBackslash: Seq[(Charset, Seq[Char])] = Seq(
    MS932 -> Seq('表', '十', 'ソ', '予', '―'), // 表 十 ソ 予 ―
    GBK   -> Seq('乗', '淺'),                // 乗 淺
    MS936 -> Seq('乗', '淺'),
    MS950 -> Seq('功', '許')                 // 功 許
  )

  private def newRoot(): Path = Files.createTempDirectory("shell-dbcs").toRealPath()

  private def config(root: Path): ShellConfig =
    ShellConfig
      .readOnlyWithin(
        FileConfig(allowedPaths = Some(Seq(root.toString)), blockedPaths = Seq.empty),
        Some(root.toString)
      )
      .copy(allowedCommands = Seq("cat", "grep", "findstr", "type"))

  private def verdict(config: ShellConfig, command: String, ansi: Option[Charset], windows: Boolean = true) =
    ShellTool.checkCommand(command, config, windows, ansi)._1

  "The shell tool on a double-byte Windows host" should "refuse an argument the JDK quotes that holds a character ending in 0x5C" in {
    val root = newRoot()
    Seq(config(root), config(root).copy(pathPolicy = None)).foreach { cfg =>
      TrailBackslash.foreach { case (cs, chars) =>
        chars.foreach { c =>
          Seq(s"findstr x 'a $c'", s"findstr x 'a $c.txt'", s"cat '$c b'", s"cat 'a\t$c'", s"cat '$c\"x'")
            .foreach { command =>
              withClue(s"${cs.name}: $command") {
                val why = verdict(cfg, command, Some(cs)).getOrElse(fail(s"'$command' was not refused"))
                why should include("0x5C")
                why should include(cs.name)
              }
            }
        }
      }
    }
    // The examples of #1807
    verdict(config(root), "cat 'a 表'", Some(MS932)) shouldBe defined
    verdict(config(root), "cat 'x 十'", Some(MS932)) shouldBe defined
    verdict(config(root), "cat 'a 功'", Some(MS950)) shouldBe defined
  }

  it should "keep allowing such a character in an argument the JDK does not quote" in {
    val root = newRoot()
    TrailBackslash.foreach { case (cs, chars) =>
      chars.foreach { c =>
        Seq(s"cat $c.txt", s"grep x $c$c.txt", s"findstr x a-$c").foreach { command =>
          withClue(s"${cs.name}: $command")(verdict(config(root), command, Some(cs)) shouldBe None)
        }
      }
    }
  }

  it should "keep allowing a quoted argument whose characters do not end in 0x5C" in {
    val root = newRoot()
    // 漢 and 字 hold no 0x5C byte on any of the three code pages
    Seq(MS932, GBK, MS950).foreach { cs =>
      withClue(cs.name)(verdict(config(root), "cat 'a 漢字.txt'", Some(cs)) shouldBe None)
    }
    // 功 is 0xB9 0xA6 on code page 936, and 表 0xB1 0xED: only the host's own code page matters
    verdict(config(root), "cat 'a 功'", Some(GBK)) shouldBe None
    verdict(config(root), "cat 'a 表'", Some(MS950)) shouldBe None
  }

  it should "leave hosts whose code page has no such character unchanged" in {
    val root = newRoot()
    verdict(config(root), "cat 'a 表'", Some(Charset.forName("x-windows-949"))) shouldBe None
    verdict(config(root), "cat 'a 表'", None) shouldBe None
    verdict(config(root), "cat 'a résumé'", Some(Charset.forName("windows-1252"))) shouldBe None
  }

  it should "leave POSIX unchanged, where nothing converts or quotes the arguments" in {
    val root = newRoot()
    TrailBackslash.foreach { case (cs, chars) =>
      chars.foreach(c => verdict(config(root), s"cat 'a $c'", Some(cs), windows = false) shouldBe None)
    }
  }

  it should "refuse them on a Windows host whose ANSI code page is double-byte" in {
    assume(isWindowsHost, "only a Windows host converts arguments to an ANSI code page")
    val host = WindowsBestFit.hostAnsiCodePage
    val char = host.flatMap(cs => TrailBackslash.find(_._1.name == cs.name).map(_._2.head))
    assume(char.isDefined, s"the host's ANSI code page (${host.fold("UTF-8")(_.name)}) is not 932, 936 or 950")
    val tool   = ShellTool.createSafe(config(newRoot())).fold(e => fail(e.formatted), identity)
    val result = tool.handler(SafeParameterExtractor(ujson.Obj("command" -> s"findstr x 'a ${char.get}.txt'")))
    result.left.toOption.getOrElse(fail(s"expected a refusal, got $result")) should include("0x5C")
  }
}
