package org.llm4s.agent.testkit

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.{ RunConfig, TenantId, ThreadId }
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.agent.harness.{ FileBackend, FileToolLimits, FileTools }
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.{ ToolCall, ToolMessage }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/**
 * The contract [[org.llm4s.agent.harness.FileTools]] meets over a [[org.llm4s.agent.harness.FileBackend]]
 * (design §4.32), as a ScalaTest suite: mix it into a spec and implement [[newBackend]]. A backend that passes
 * [[FileBackendContract]] should pass this too; it runs the seven tools, by their public names and arguments,
 * as a model calls them in a real agent run:
 *
 * {{{
 * class WorkspaceFileToolsSpec extends AnyFlatSpec with FileToolsContract:
 *   protected def newBackend(): FileBackend = WorkspaceFileBackend(...)
 * }}}
 *
 * It checks each tool and its refusals; that two edits of one file in one message both apply, in order; that a
 * read in the same message as a write sees the file whole, before or after the write (over thread state,
 * before: `FileToolsSpec` checks that); and, when the backend says
 * so, that a batch over its bound refuses exactly one write ([[boundedBatch]]) and that two tenants' files stay
 * apart ([[tenantScoped]]).
 */
@Experimental
trait FileToolsContract extends AnyFlatSpecLike with Matchers with EitherValues:

  /** A fresh, empty backend for one case. */
  protected def newBackend(): FileBackend

  /** Whether one backend keeps a tree per tenant: a store or a resolved backend does. */
  protected def tenantScoped: Boolean = false

  /**
   * Writes `(path, content)` the backend accepts one by one, in order, until the last, which is over its
   * bound; `None` when the backend has no bound a test can reach.
   */
  protected def boundedBatch: Option[Vector[(String, String)]] = None

  import FileToolsContract.*

  private val runs = new AtomicInteger()

  /** Runs `batches`, each one model message, then a final answer; every tool result, in order. */
  private def run(backend: FileBackend, tenant: Option[String], batches: Vector[Vector[Call]]): Vector[String] =
    val ids     = Iterator.from(1)
    val replies = batches.map(b => Reply.toolCalls(b.map((n, a) => ToolCall(s"c${ids.next()}", n, a))*))
    val tools   = ToolSet.of(FileTools(backend, FileToolLimits.default)*).value
    val agent =
      Agent
        .builder("contract", ScriptedLLMClient.sequence((replies :+ Reply.text("done"))*))
        .withTools(tools)
        .build()
        .value
    val config = RunConfig().withTenantId(tenant.map(TenantId(_)))
    agent
      .run(ThreadId(s"file-tools-contract-${runs.incrementAndGet()}-${System.nanoTime()}"), "go", config)
      .value
      .messages
      .collect { case t: ToolMessage => t.content }
      .toVector

  private def one(backend: FileBackend, calls: Call*): Vector[String] =
    run(backend, None, calls.toVector.map(Vector(_)))

  "The file tools" should "write, read, list, glob and grep what the backend holds" in {
    val out = one(
      newBackend(),
      write("/src/a.txt", "one\ntwo"),
      write("/src/sub/b.txt", "three"),
      read("/src/a.txt"),
      FileTools.Ls   -> ujson.Obj("path" -> "/src"),
      FileTools.Glob -> ujson.Obj("pattern" -> "**/*.txt", "path" -> "/"),
      FileTools.Grep -> ujson.Obj("pattern" -> "t[wh]", "path" -> "/src", "include" -> "*.txt")
    )
    out(0) shouldBe "Created /src/a.txt (7 bytes)"
    out(1) shouldBe "Created /src/sub/b.txt (5 bytes)"
    out(2) shouldBe "     1\tone\n     2\ttwo"
    out(3) shouldBe "/src/a.txt (7 bytes)\n/src/sub/"
    out(4) shouldBe "/src/a.txt\n/src/sub/b.txt"
    out(5) shouldBe "/src/a.txt:2: two\n/src/sub/b.txt:1: three"
  }

  it should "edit one occurrence, refuse an absent or ambiguous one, and replace all on request" in {
    val out = one(
      newBackend(),
      write("/a.txt", "x y x"),
      edit("/a.txt", "x", "z"),
      edit("/a.txt", "q", "z"),
      edit("/a.txt", "x", "z", all = true),
      read("/a.txt")
    )
    out(1) should (include("error").and(include("2 times")))
    out(2) should (include("error").and(include("not found")))
    out(3) shouldBe "Edited /a.txt: 2 replacements"
    out(4) shouldBe "     1\tz y z"
  }

  it should "overwrite, delete, and refuse a missing file or binary content" in {
    val out = one(
      newBackend(),
      write("/d/a.txt", "x"),
      write("/d/a.txt", "yy"),
      FileTools.Delete -> ujson.Obj("path" -> "/d/a.txt"),
      FileTools.Delete -> ujson.Obj("path" -> "/d/a.txt"),
      read("/d/a.txt"),
      write("/bin", "a\u0000b")
    )
    out(1) shouldBe "Overwrote /d/a.txt (2 bytes)"
    out(2) shouldBe "Deleted /d/a.txt"
    out.drop(3).foreach(_ should include("error"))
  }

  it should "page a read by offset and limit, with the total line count" in {
    val out = one(
      newBackend(),
      write("/a.txt", "1\n2\n3"),
      FileTools.ReadFile -> ujson.Obj("path" -> "/a.txt", "offset" -> 2, "limit" -> 1)
    )
    out(1) shouldBe "     2\t2\n(lines 2-2 of 3; continue with offset=3)"
  }

  "Two edits of one file in one message" should "both apply, the second against the first's result" in {
    val out = run(
      newBackend(),
      None,
      Vector(
        Vector(write("/a.txt", "one two")),
        Vector(edit("/a.txt", "one", "1"), edit("/a.txt", "two", "2")),
        Vector(read("/a.txt"))
      )
    )
    out.last shouldBe "     1\t1 2"
  }

  "A read in the same message as a write" should "see the file whole, before or after the write" in {
    val out = run(
      newBackend(),
      None,
      Vector(Vector(write("/a.txt", "before")), Vector(write("/a.txt", "after"), read("/a.txt")))
    )
    // a read takes no lock, so it is not ordered with the write: a state backend's read sees the batch's
    // snapshot, an external backend's whichever version it holds when the read runs
    Set("     1\tbefore", "     1\tafter") should contain(out.last)
  }

  "A message of writes over the backend's bound" should "refuse exactly one" in {
    boundedBatch.foreach { writes =>
      val out = run(newBackend(), None, Vector(writes.map((p, c) => write(p, c))))
      out.count(_.contains("error")) shouldBe 1
      out.count(_.startsWith("Created")) shouldBe writes.size - 1
    }
  }

  "A tenant-scoped backend" should "keep each tenant's files from the others" in {
    if tenantScoped then
      val backend = newBackend()
      run(backend, Some("a"), Vector(Vector(write("/note.md", "a's"))))
      run(backend, Some("a"), Vector(Vector(read("/note.md")))).head shouldBe "     1\ta's"
      run(backend, Some("b"), Vector(Vector(read("/note.md")))).head should include("error")
  }

object FileToolsContract:

  /** A tool name and its arguments. */
  private type Call = (String, ujson.Obj)

  private def write(path: String, content: String): Call =
    FileTools.WriteFile -> ujson.Obj("path" -> path, "content" -> content)

  private def read(path: String): Call = FileTools.ReadFile -> ujson.Obj("path" -> path)

  private def edit(path: String, old: String, next: String, all: Boolean = false): Call =
    FileTools.EditFile -> ujson.Obj("path" -> path, "old_string" -> old, "new_string" -> next, "replace_all" -> all)
