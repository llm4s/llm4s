package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.Comparator
import scala.concurrent.duration._
import scala.util.Using

/**
 * #3051: one `searchFiles` or regex `modifyFile` holds the file lock and the command gate, so its time and its
 * response must be bounded: a deadline across all its lines, a clamped `contextLines`, cut lines, a response within
 * `maxOutputSize`, and a replacement that cannot grow past `maxFileSize`.
 */
class WorkspaceSearchBoundsSpec extends AnyFlatSpec with TestTimeLimit with Matchers with BeforeAndAfterAll {

  private val root: Path    = Files.createTempDirectory("workspace-search-bounds")
  private val isWindowsHost = System.getProperty("os.name").startsWith("Windows")

  private def workspace(matchTimeLimit: FiniteDuration = WorkspaceAgentInterfaceImpl.MatchTimeLimit) =
    new WorkspaceAgentInterfaceImpl(root.toString, isWindowsHost, matchTimeLimit = matchTimeLimit)

  private def write(name: String, lines: Seq[String]): Unit =
    Files.write(root.resolve(name), lines.mkString("\n").getBytes(StandardCharsets.UTF_8)): Unit

  private def refusal(body: => Any): WorkspaceAgentException =
    intercept[WorkspaceAgentException](body)

  override def afterAll(): Unit =
    Using.resource(Files.walk(root))(
      _.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p): Unit)
    )

  "searchFiles" should "search the issue's empty-loop pattern as a literal, at once" in {
    write("empty-loop.txt", Seq("x" * 40, "(){2000000000}b"))
    val started  = System.nanoTime()
    val response = workspace().searchFiles(List("empty-loop.txt"), "(){2000000000}b", "regex")
    response.matches.map(_.line) shouldBe List(2)
    (System.nanoTime() - started).nanos should be < 5.seconds
  }

  it should "stop at its time limit, however many lines each stay within the step budget" in {
    // each line costs a whole step budget: the pattern never matches, so before the fix every line ran in turn
    write("slow.txt", Seq.fill(200)("a" * 40))
    val started = System.nanoTime()
    val e       = refusal(workspace(300.millis).searchFiles(List("slow.txt"), "(.*a){25}b", "regex"))
    e.code shouldBe "TIMEOUT"
    (System.nanoTime() - started).nanos should be < 10.seconds
  }

  it should "clamp contextLines to 0..20, never overflowing" in {
    write("context.txt", (1 to 100).map(i => if (i == 50) "needle" else s"line $i"))
    Seq(Int.MaxValue, 1000000, 21).foreach { n =>
      val m = workspace().searchFiles(List("context.txt"), "needle", "literal", contextLines = Some(n)).matches.head
      withClue(n) {
        m.contextBefore shouldBe (30 until 50).map(i => s"line $i").toList
        m.contextAfter shouldBe (51 to 70).map(i => s"line $i").toList
      }
    }
    val none = workspace().searchFiles(List("context.txt"), "needle", "literal", contextLines = Some(-5)).matches.head
    none.contextBefore shouldBe Nil
    none.contextAfter shouldBe Nil
  }

  it should "cut a long line to a window around its match, and a long context line to its start" in {
    val long = "a" * 4000 + "needle" + "b" * 4000
    write("minified.txt", Seq("c" * 5000, long))
    val m = workspace().searchFiles(List("minified.txt"), "needle", "literal", contextLines = Some(1)).matches.head
    m.matchText.length shouldBe WorkspaceAgentInterfaceImpl.MaxSearchLineChars
    m.matchText should include("needle")
    m.contextBefore shouldBe List("c" * WorkspaceAgentInterfaceImpl.MaxSearchLineChars)
  }

  it should "keep the response within maxOutputSize, as a truncated result" in {
    // a 200 KB file with a match on every other line: 100 matches, each with up to 40 context lines of 1000
    // characters, about 4 MB of response before the fix
    val wide = "w" * 1000
    write("wide.txt", (0 until 100).flatMap(_ => Seq("needle" + wide.drop(6), wide)))
    val response = workspace().searchFiles(List("wide.txt"), "needle", "literal", contextLines = Some(20))
    val carried = response.matches.iterator.map { m =>
      (m.matchText +: (m.contextBefore ++ m.contextAfter)).iterator.map(_.length.toLong).sum
    }.sum
    response.matches should not be empty
    carried should be <= WorkspaceSandboxConfig.DefaultLimits.maxOutputSize
    response.isTruncated shouldBe true
  }

  "modifyFile's regex replacement" should "stop at its time limit, leaving the file unchanged" in {
    val lines = Seq.fill(200)("a" * 40)
    write("slow-replace.txt", lines)
    val before = Files.readAllBytes(root.resolve("slow-replace.txt"))
    val op     = RegexReplaceOperation(pattern = "(.*a){25}b", replacement = "X", flags = Some("g"))
    refusal(workspace(300.millis).modifyFile("slow-replace.txt", List(op))).code shouldBe "TIMEOUT"
    Files.readAllBytes(root.resolve("slow-replace.txt")) shouldBe before
  }

  it should "refuse a replacement that would grow the file past maxFileSize, leaving it unchanged" in {
    write("grow.txt", Seq("a" * 2000))
    val before = Files.readAllBytes(root.resolve("grow.txt"))
    // an empty match at each of 2001 positions, each replaced by 1000 characters: 2 MB, over the 1 MB limit
    val regex = RegexReplaceOperation(pattern = "x*", replacement = "R" * 1000, flags = Some("g"))
    refusal(workspace().modifyFile("grow.txt", List(regex))).code shouldBe "SIZE_LIMIT_EXCEEDED"
    Files.readAllBytes(root.resolve("grow.txt")) shouldBe before
    // the literal fallback is bounded the same way: a refused pattern is replaced as text, here 200 times
    WorkspaceRegexSafetyManager.safeCompile("(){1000}").isLeft shouldBe true
    write("grow-literal.txt", Seq("(){1000}" * 200))
    val literalBefore = Files.readAllBytes(root.resolve("grow-literal.txt"))
    val literal       = RegexReplaceOperation(pattern = "(){1000}", replacement = "R" * 6000, flags = Some("g"))
    refusal(workspace().modifyFile("grow-literal.txt", List(literal))).code shouldBe "SIZE_LIMIT_EXCEEDED"
    Files.readAllBytes(root.resolve("grow-literal.txt")) shouldBe literalBefore
  }
}
