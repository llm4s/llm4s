package org.llm4s.agent.testkit

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.agent.graph.{ ReplaySupport, RunConfig, RunContext, StateUpdate, TenantId, ThreadId }
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, Glob, LockKey, ToolOutcome, ToolSet }
import org.llm4s.agent.harness.{ BackendRefused, FileBackend, FileContext, VPath }
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.{ ToolCall, ToolMessage }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.atomic.AtomicInteger

/**
 * The contract every [[org.llm4s.agent.harness.FileBackend]] must meet (design §4.25), as a ScalaTest
 * suite: mix it into a spec and implement [[newBackend]]. Every case runs the backend's operations as
 * tool calls of a real agent run, so a state backend's updates are committed by the runtime and an
 * external backend's effects go through its replay guard, as they would in production:
 *
 * {{{
 * class StateFileBackendContractSpec extends AnyFlatSpec with FileBackendContract:
 *   protected def newBackend(): FileBackend = StateFileBackend(maxBytes = 200)
 * }}}
 *
 * It checks each operation and its refusals; that two mutations of one path in one batch both apply,
 * the second against the first's result, because their locks conflict; that locks conflict for one path
 * and for a directory and a path beneath it, and not for siblings; and, when the backend says so, that
 * a batch over its bound refuses exactly one write, the overflowing one ([[boundedBatch]]) and that two tenants
 * see separate trees ([[tenantScoped]]).
 *
 * Replay is [[ReplayContract]]'s: an external backend also runs it, with [[FileBackendContract.EditSubject]]
 * as the subject, once per support class it offers.
 */
@Experimental
trait FileBackendContract extends AnyFlatSpecLike with Matchers with EitherValues:

  /** A fresh, empty backend for one case. */
  protected def newBackend(): FileBackend

  /** Whether one backend keeps a tree per tenant: a store or a resolved backend does. */
  protected def tenantScoped: Boolean = false

  /**
   * Writes `(path, content)` that the backend accepts one by one, in order, until the last, which is
   * over its bound; `None` when the backend has no bound a test can reach.
   */
  protected def boundedBatch: Option[Vector[(String, String)]] = None

  import FileBackendContract.*

  private val runs = new AtomicInteger()

  /** Runs `batches` - each one model message of calls - then a final answer, returning every tool result. */
  private def run(backend: FileBackend, tenant: Option[String], batches: Vector[Vector[String]]): Vector[String] =
    val ids = Iterator.from(1)
    val replies = batches.map(batch =>
      Reply.toolCalls(batch.map(c => ToolCall(s"c${ids.next()}", "fs", ujson.Obj("text" -> c)))*)
    ) :+
      Reply.text("done")
    val agent  = Agent.builder("contract", ScriptedLLMClient.sequence(replies*)).withTools(tools(backend)).build().value
    val config = RunConfig().withTenantId(tenant.map(TenantId(_)))
    val result: AgentResult =
      agent.run(ThreadId(s"file-contract-${runs.incrementAndGet()}-${System.nanoTime()}"), "go", config).value
    result.messages.collect { case t: ToolMessage => t.content }.toVector

  private def one(backend: FileBackend, calls: String*): Vector[String] =
    run(backend, None, calls.toVector.map(Vector(_)))

  "A file backend" should "write, read, list, glob and grep what it holds" in {
    val results = one(
      newBackend(),
      "write|/src/a.txt|one\ntwo",
      "write|/src/sub/b.txt|three",
      "read|/src/a.txt",
      "ls|/src",
      "glob|/|**/*.txt",
      "grep|/|t[wh]"
    )
    results(0) should include("created=true")
    results(1) should include("created=true")
    results(2) shouldBe "one\ntwo"
    results(3) shouldBe "/src/a.txt\n/src/sub/"
    results(4) shouldBe "/src/a.txt\n/src/sub/b.txt"
    results(5) shouldBe "/src/a.txt:2:two\n/src/sub/b.txt:1:three"
  }

  it should "edit one occurrence, refuse an absent or ambiguous one, and replace all on request" in {
    val results = one(
      newBackend(),
      "write|/a.txt|x y x",
      "edit|/a.txt|x|z",
      "edit|/a.txt|q|z",
      "editall|/a.txt|x|z",
      "read|/a.txt"
    )
    results(1) should startWith("error:")
    results(1) should include("2 times")
    results(2) should (startWith("error:").and(include("not found")))
    results(3) should include("replacements=2")
    results(4) shouldBe "z y z"
  }

  it should "delete a file, and refuse a missing one or a read of it after" in {
    val results = one(newBackend(), "write|/d/a.txt|x", "delete|/d/a.txt", "delete|/d/a.txt", "read|/d/a.txt")
    results(1) should include("wasDirectory=false")
    // typed, so a caller can tell a missing file from any other refusal
    results(2) should startWith("error: [NotFound]")
    results(3) should startWith("error: [NotFound]")
  }

  it should "apply a walk's predicate before its limit, so a refused file takes no place" in {
    val results = one(newBackend(), "write|/a.txt|x", "write|/b.txt|x", "first|/|/a.txt", "firstmatch|/|x|/a.txt")
    results(2) shouldBe "/b.txt"
    results(3) shouldBe "/b.txt:1:x"
  }

  it should "refuse a read or list of what is not there, and binary content" in {
    val results = one(newBackend(), "read|/missing", "ls|/missing", "write|/bin|a\u0000b", "edit|/missing|a|b")
    results.foreach(_ should startWith("error:"))
    results(0) should startWith("error: [NotFound]")
    results(1) should startWith("error: [NotFound]")
    results(3) should startWith("error: [NotFound]")
  }

  "Two mutations of one path in one batch" should "both apply, the second against the first's result" in {
    val results = run(
      newBackend(),
      None,
      Vector(Vector("write|/a.txt|one two"), Vector("edit|/a.txt|one|1", "edit|/a.txt|two|2"), Vector("read|/a.txt"))
    )
    results.last shouldBe "1 2"
  }

  "A backend's locks" should "conflict for one path, and for a directory and a path beneath it" in {
    val backend = newBackend()
    def conflict(a: String, b: String) =
      backend.locks(path(a)).exists(x => backend.locks(path(b)).exists(_.conflictsWith(x)))
    conflict("/a.txt", "/a.txt") shouldBe true
    // C2 schedules a batch once and checks each call's locks again when it runs, from the stored rendering:
    // a backend's locks must be a function of the path alone, and survive rendering
    Seq("/a.txt", "/d/a.txt", "/A b/é.txt").foreach { p =>
      backend.locks(path(p)) shouldBe backend.locks(path(p))
      backend.locks(path(p)).map(k => LockKey(k.render)) shouldBe backend.locks(path(p))
    }
    conflict("/d", "/d/a.txt") shouldBe true
    backend.locks(path("/a.txt")) should not be empty
  }

  "A batch over the backend's bound" should "refuse exactly one write" in {
    boundedBatch.foreach { writes =>
      val results = run(newBackend(), None, Vector(writes.map((p, c) => s"write|$p|$c")))
      // a state backend's lock orders the batch, so the last write overflows; a store's bound is exact
      // in its transaction, whichever write it sees last
      results.count(_.startsWith("error:")) shouldBe 1
      results.count(_.contains("created=true")) shouldBe writes.size - 1
    }
  }

  "A tenant-scoped backend" should "keep each tenant's files from the others" in {
    if tenantScoped then
      val backend = newBackend()
      run(backend, Some("a"), Vector(Vector("write|/note.md|a's")))
      run(backend, Some("a"), Vector(Vector("read|/note.md"))).head shouldBe "a's"
      run(backend, Some("b"), Vector(Vector("read|/note.md"))).head should startWith("error:")
      run(backend, None, Vector(Vector("read|/note.md"))).head should startWith("error:")
  }

object FileBackendContract:

  private def path(s: String): VPath = VPath(s).fold(e => throw new IllegalArgumentException(e.message), identity)

  final private case class Call(text: String) derives ReadWriter

  private val spec: AgentToolSpec[Call] =
    AgentToolSpec[Call](
      "fs",
      "File operations",
      Schema.`object`[Call]("fs").withRequiredField("text", Schema.string("op|path|args"))
    )

  private val Mutations = Set("write", "edit", "editall", "delete")

  /** One tool over every operation: `op|path|args`, locked as a mutation of `path` when it is one. */
  private def tools(backend: FileBackend): ToolSet =
    def locks(call: Call): Set[LockKey] =
      call.text.split('|').toList match
        case op :: p :: _ if Mutations(op) => VPath(p).fold(_ => Set(LockKey("")), backend.locks)
        case _                             => Set.empty
    def mutated[A](r: Result[(A, StateUpdate)]): ToolOutcome =
      r.fold(e => errorOf(e), (a, u) => ToolOutcome.Success(ujson.Str(fields(a)), u))
    def read[A](r: Result[A])(render: A => String): ToolOutcome =
      r.fold(e => errorOf(e), a => ToolOutcome.Success(ujson.Str(render(a))))
    val tool = AgentTool(spec.withLocks(locks), backend.writes) { (call, context) =>
      val ctx = FileContext(context)
      call.text.split("\\|", -1).toList match
        case "write" :: p :: content :: Nil       => mutated(backend.write(path(p), content, ctx))
        case "edit" :: p :: old :: next :: Nil    => mutated(backend.edit(path(p), old, next, replaceAll = false, ctx))
        case "editall" :: p :: old :: next :: Nil => mutated(backend.edit(path(p), old, next, replaceAll = true, ctx))
        case "delete" :: p :: Nil                 => mutated(backend.delete(path(p), ctx))
        case "read" :: p :: Nil                   => read(backend.read(path(p), 0, 1000, ctx))(_.lines.mkString("\n"))
        case "ls" :: p :: Nil =>
          read(backend.ls(path(p), 1000, ctx))(
            _.items.map(i => i.path.value + (if i.isDirectory then "/" else "")).mkString("\n")
          )
        case "glob" :: p :: pattern :: Nil =>
          read(backend.glob(Glob(pattern), path(p), 1000, ctx))(_.items.map(_.value).mkString("\n"))
        // the first file `find` returns that is not `skip`: `accept` must apply before the limit
        case "first" :: p :: skip :: Nil =>
          read(backend.find(path(p), _.value != skip, 1, ctx))(_.items.map(_.value).mkString("\n"))
        case "firstmatch" :: p :: regex :: skip :: Nil =>
          read(backend.search(path(p), java.util.regex.Pattern.compile(regex), _.value != skip, 1, ctx))(
            _.items.map(m => s"${m.path.value}:${m.line}:${m.text}").mkString("\n")
          )
        case "grep" :: p :: regex :: Nil =>
          read(backend.grep(regex, path(p), None, 1000, ctx))(
            _.items.map(m => s"${m.path.value}:${m.line}:${m.text}").mkString("\n")
          )
        case other => ToolOutcome.Error(s"bad call $other")
    }
    ToolSet.of(tool).fold(e => throw new IllegalStateException(e.message), identity)

  /**
   * A refusal as the text the model sees, prefixed `error:` so the cases can tell it apart, and its kind when it is
   * not a plain refusal (`error: [NotFound] ...`), so a case can check the kind a caller branches on.
   */
  private def errorOf(error: org.llm4s.error.LLMError): ToolOutcome =
    val kind = error match
      case r: BackendRefused if r.kind != BackendRefused.Kind.Refused => s"[${r.kind}] "
      case _                                                          => ""
    BackendRefused.toolOutcome(error) match
      case ToolOutcome.Error(message) => ToolOutcome.Success(ujson.Str(s"error: $kind$message"))
      case fatal                      => fatal

  /** `created=true`, `replacements=2`: a product's fields by name. */
  private def fields(p: Any): String = p match
    case product: Product =>
      product.productElementNames.zip(product.productIterator).map((n, v) => s"$n=$v").mkString(",")
    case other => other.toString

  /**
   * A [[ReplayContract.Subject]] over a file backend: slot `n` is the file `/replay-<n>.txt`, seeded
   * `x`, and the effect is `edit("x", "xy")`, which shows a second application (`xyy`). `seed` writes a
   * slot's file straight to the target, as setup and as another writer; `observe` reads it from the
   * target; `applied` counts a transactional target's applied keys.
   */
  final class EditSubject(
    val name: String,
    backend: FileBackend,
    val support: ReplaySupport,
    seed: (VPath, String) => Unit,
    read: VPath => Result[String],
    applied: () => Int = () => 0
  ) extends ReplayContract.Subject:
    private def file(slot: Int): VPath = path(s"/replay-$slot.txt")
    ReplayContract.Slots.foreach(slot => seed(file(slot), "x"))

    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      backend
        .edit(file(slot), "x", "xy", replaceAll = false, FileContext.withoutState(context))
        .map((edited, _) => ujson.Num(edited.replacements))

    def observe(slot: Int): Result[String] = read(file(slot))
    def initial(slot: Int): String         = "x"
    def once(slot: Int): String            = "xy"
    def interfere(slot: Int): Unit         = seed(file(slot), "replaced")
    override def appliedKeys: Int          = applied()
