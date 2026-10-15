package org.llm4s.agent.harness

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.middleware.*
import org.llm4s.agent.graph.tool.*
import org.llm4s.agent.graph.toolloop.*
import org.llm4s.agent.graph.toolloop.ToolLoopFixtures.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/** Stage 3 slice F4 (#1908, design §4.34): `ToolOutputOffload` through the tool loop, and `OffloadBackend`. */
class ToolOutputOffloadSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val agentA = AgentId.unsafe("a")

  /** A model that plays `turns` in order and records every request it was sent. */
  final private class ScriptedModel(turns: (Vector[Message] => AssistantMessage)*) extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
      val turn = seen.size
      seen.add(messages)
      turns
        .lift(turn)
        .map(play => Right(completion(play(messages))))
        .getOrElse(Left(ValidationError("model", s"no turn $turn")))
    }
    def request(i: Int): Vector[Message] = seen.get(i)
  }

  private def answer(text: String): Vector[Message] => AssistantMessage = _ => AssistantMessage(text)

  /** One model message calling `big` once per `(id, size)`. */
  private def calling(calls: (String, Int)*): Vector[Message] => AssistantMessage =
    _ => AssistantMessage(None, calls.map((id, n) => ToolCall(id, "big", ujson.Obj("text" -> n.toString))))

  /** A tool returning `n` lines of 40 characters, recording each call's idempotency key. */
  final private class Big {
    val keys = new CopyOnWriteArrayList[String]()
    val tool: AgentTool[Echo] =
      AgentTool(
        AgentToolSpec[Echo]("big", "Big", Schema.`object`[Echo]("big").withRequiredField("text", Schema.string("n")))
      ) { (a, context) =>
        keys.add(context.idempotencyKey.value)
        ToolOutcome.Success(ujson.Str(lines(a.text.toInt)))
      }
  }

  private def lines(n: Int): String = (1 to n).map(i => f"line $i%05d " + "x" * 29).mkString("\n")

  private def loopOf(model: ModelStep, stack: Seq[AgentMiddleware], tools: AgentTool[?]*): ToolLoop =
    ToolLoop
      .build("offload", "1", agentA, Vector(LoopAgent(agentA, model, ToolSet.of(tools*).value).withMiddleware(stack)))
      .value

  private def offloadsOf(state: ThreadState): Offloads = state.get(ToolOutputOffload.key).value

  private def toolResults(state: ThreadState): Vector[String] =
    state.get(Messages.key).value.map(_.message).collect { case t: ToolMessage => t.content }

  private def backend: CompositeBackend =
    CompositeBackend(StateFileBackend(), ToolOutputOffload.Mount -> OffloadBackend).value

  private def path(s: String): VPath = VPath(s).value

  "A large Success" should "be stored under its call's key, the model shown a preview and the path" in {
    val big   = new Big
    val model = ScriptedModel(calling("c1" -> 1000, "c2" -> 3), answer("done"))
    val mw    = new ToolOutputOffload(thresholdTokens = 1000)
    val (state, _) = runInMemory(loopOf(model, Seq(mw), big.tool).graph, AgentInput("go")).answered
    val key        = big.keys.asScala.find(k => offloadsOf(state).get(k).isDefined).value
    val stored     = offloadsOf(state).get(key).value
    stored.content shouldBe lines(1000)
    stored.truncated shouldBe false
    stored.tool shouldBe "big"
    val results = toolResults(state)
    results.head should include(s"/large_tool_results/$key")
    results.head should include("line 00010")
    (results.head should not).include("line 00011")
    results.head should include("990 more lines")
    // a small result is passed on as it is
    results(1) shouldBe lines(3)
    offloadsOf(state).results should have size 1
    // the model saw the preview, never the whole result
    model.request(1).collect { case t: ToolMessage => t.content.length }.max should be < 3000
  }

  it should "be cut to maxResultBytes, and say so" in {
    val big        = new Big
    val model      = ScriptedModel(calling("c1" -> 1000), answer("done"))
    val mw         = new ToolOutputOffload(thresholdTokens = 1000, maxResultBytes = 5000, maxTotalBytes = 10000)
    val (state, _) = runInMemory(loopOf(model, Seq(mw), big.tool).graph, AgentInput("go")).answered
    val stored     = offloadsOf(state).results.head
    stored.truncated shouldBe true
    // measured as a checkpoint stores it: each line break is two bytes in a JSON string
    org.llm4s.agent.harness.Text.jsonBytes(stored.content) should (be <= 5000L and be > 4900L)
    lines(1000) should startWith(stored.content)
    stored.originalBytes shouldBe lines(1000).length.toLong
    toolResults(state).head should include(s"Only its first ${stored.content.length} characters are stored")
  }

  it should "render a JSON result as the loop sends it" in {
    val json = AgentTool(
      AgentToolSpec[Echo]("big", "Big", Schema.`object`[Echo]("big").withRequiredField("text", Schema.string("n")))
    )((a, _) => ToolOutcome.Success(ujson.Arr((1 to a.text.toInt).map(i => ujson.Obj("i" -> i, "pad" -> "y" * 30))*)))
    val model      = ScriptedModel(calling("c1" -> 500), answer("done"))
    val (state, _) =
      runInMemory(loopOf(model, Seq(new ToolOutputOffload(thresholdTokens = 1000)), json).graph, AgentInput("go")).answered
    offloadsOf(state).results.head.content should startWith("""[{"i":1,"pad":"yyy""")
  }

  "The offloads" should "stay within maxTotalBytes under a parallel batch, evicting the oldest (rules 1, 12)" in {
    val big   = new Big
    val model = ScriptedModel(calling("c1" -> 200, "c2" -> 200, "c3" -> 200), answer("done"))
    // each result is about 8 KB: room for two
    val mw         = new ToolOutputOffload(thresholdTokens = 1000, maxResultBytes = 9000, maxTotalBytes = 17000)
    val (state, _) = runInMemory(loopOf(model, Seq(mw), big.tool).graph, AgentInput("go")).answered
    val offloads   = offloadsOf(state)
    offloads.results should have size 2
    offloads.bytes should be <= 17000L
    offloads.evicted should have size 1
    // an evicted path reads as a tool error that says so
    val evicted = path(s"/large_tool_results/${offloads.evicted.head}")
    val refused = backend.read(evicted, 0, 10, FileContext(testRunContext(), state)).left.value
    refused.message should include("evicted")
    // a kept one reads in pages
    val kept = path(s"/large_tool_results/${offloads.results.head.key}")
    backend.read(kept, 1, 2, FileContext(testRunContext(), state)).value.lines shouldBe
      Vector(lines(3).split("\n")(1), lines(3).split("\n")(2))
  }

  it should "give a reused provider call id a new path, so an old path never names a newer call (rule 4)" in {
    val big        = new Big
    val model      = ScriptedModel(calling("same" -> 300), calling("same" -> 400), answer("done"))
    val mw         = new ToolOutputOffload(thresholdTokens = 1000)
    val (state, _) = runInMemory(loopOf(model, Seq(mw), big.tool).graph, AgentInput("go")).answered
    val offloads   = offloadsOf(state)
    offloads.results.map(_.key).distinct should have size 2
    offloads.results.map(_.content.split("\n").length) shouldBe Vector(300, 400)
  }

  "A call whose task failed" should "commit no offload, and store one under the same key on recover (rules 4, 10)" in {
    val broken = new AtomicBoolean(true)
    val failing = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("failing")
      override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome = {
        val out = next()
        if (broken.getAndSet(false)) ToolOutcome.Fatal(ValidationError("store", "down")) else out
      }
    }
    val big         = new Big
    val store       = new InMemoryCheckpointer
    val thread      = ThreadId("recovered")
    val model       = ScriptedModel(calling("c1" -> 1000), answer("done"))
    val loop        = loopOf(model, Seq(failing, new ToolOutputOffload(thresholdTokens = 1000)), big.tool)
    val (failed, _) = GraphRuntime(store).start(thread, loop.graph, AgentInput("go")).awaited.value.failed
    offloadsOf(failed).results shouldBe empty
    val (state, _) = GraphRuntime(store).recover(thread, loop.graph).awaited.value.answered
    big.keys.asScala.distinct should have size 1
    offloadsOf(state).results.map(_.key) shouldBe Vector(big.keys.get(0))
  }

  "An output Block" should "restore the offloads to their value before the turn (rule 5)" in {
    val guard = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("guard")
      override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
        if (answer.contains("secret")) Left(GuardrailBlocked("secrets", "no")) else Right(BoundaryResult(answer))
    }
    val big    = new Big
    val store  = new InMemoryCheckpointer
    val thread = ThreadId("blocked")
    val model =
      ScriptedModel(calling("c1" -> 1000), answer("fine"), calling("c2" -> 1000), answer("the secret"))
    val loop       = loopOf(model, Seq(guard, new ToolOutputOffload(thresholdTokens = 1000)), big.tool)
    val rt         = GraphRuntime(store)
    val (after, _) = rt.start(thread, loop.graph, AgentInput("one")).awaited.value.answered
    offloadsOf(after).results should have size 1
    val (blocked, error) = rt.start(thread, loop.graph, AgentInput("two")).awaited.value.failed
    error shouldBe a[GuardrailBlocked]
    offloadsOf(blocked) shouldBe offloadsOf(after)
  }

  "With summarisation" should "drop the offloads the summary covers, at the next model step" in {
    val big   = new Big
    val store = new InMemoryCheckpointer
    val thread = ThreadId("cleanup")
    val model = ScriptedModel(calling("c1" -> 1000), answer("first"), answer("second"), answer("third"), answer("4th"))
    val summary = new SummaryCompressor {
      def compress(p: Option[String], m: Vector[Message], max: Int, c: ModelContext): Result[String] = Right("summary")
    }
    val loop = loopOf(
      model,
      Seq(
        new SummarizationMiddleware(SummaryTrigger.Messages(5), keepRecent = 1, compressor = summary),
        new ToolOutputOffload(thresholdTokens = 1000)
      ),
      big.tool
    )
    val rt                 = GraphRuntime(store)
    def turn(q: String)    = rt.start(thread, loop.graph, AgentInput(q)).awaited.value.answered._1
    offloadsOf(turn("one")).results should have size 1
    turn("two").get(SummarizationMiddleware.key).value shouldBe None
    // the third turn's request has 7 messages: the summary now covers the tool result
    val three = turn("three")
    three.get(SummarizationMiddleware.key).value shouldBe defined
    offloadsOf(three).results should have size 1
    // the next model step reads the summary and drops it
    val four = turn("four")
    offloadsOf(four).results shouldBe empty
    offloadsOf(four).evicted should have size 1
  }

  "OffloadBackend" should "list and search the results beneath its mount, and refuse every mutation" in {
    val big        = new Big
    val model      = ScriptedModel(calling("c1" -> 1000), answer("done"))
    val (state, _) =
      runInMemory(loopOf(model, Seq(new ToolOutputOffload(thresholdTokens = 1000)), big.tool).graph, AgentInput("go"))
        .answered
    val ctx  = FileContext(testRunContext(), state)
    val key  = offloadsOf(state).results.head.key
    val file = path(s"/large_tool_results/$key")
    backend.ls(ToolOutputOffload.Mount, 10, ctx).value.items.map(_.path) shouldBe Vector(file)
    backend.grep("line 00500", ToolOutputOffload.Mount, None, 10, ctx).value.items.map(_.line) shouldBe Vector(500)
    backend.write(file, "x", ctx).left.value.message should include("read-only")
    backend.delete(file, ctx).left.value.message should include("read-only")
    backend.edit(file, "line", "x", replaceAll = true, ctx).left.value.message should include("read-only")
    backend.write(path("/large_tool_results/new"), "x", ctx).left.value shouldBe a[BackendRefused]
    BackendRefused.toolOutcome(backend.write(file, "x", ctx).left.value) shouldBe a[ToolOutcome.Error]
    // a path never stored reads as not found
    backend.read(path("/large_tool_results/nothing"), 0, 1, ctx).left.value.message should include("not found")
    OffloadBackend.writes shouldBe empty
  }

  it should "hold nothing in an agent without ToolOutputOffload, whose graph has no offloads key (rule 7)" in {
    val (state, _) = runInMemory(loopOf(ScriptedModel(answer("hi")), Seq.empty).graph, AgentInput("q")).answered
    state.get(ToolOutputOffload.key).left.value shouldBe a[GraphError.UnknownStateKey]
    backend.ls(ToolOutputOffload.Mount, 10, FileContext(testRunContext(), state)).value.items shouldBe empty
  }

  "Offloads" should "replace a result with the same key, ignore an absent drop, and remember a bounded evicted list" in {
    def result(k: String, n: Int) = OffloadedResult(k, "t", "c" * n, n.toLong, truncated = false)
    val one                       = Offloads.empty.applied(OffloadUpdate.Put(result("a", 10), 1000))
    val again                     = one.applied(OffloadUpdate.Put(result("a", 20), 1000))
    again.results.map(_.content.length) shouldBe Vector(20)
    again.applied(OffloadUpdate.Drop(Vector("zz"))) shouldBe again
    val many = (1 to ToolOutputOffload.MaxEvicted + 10).foldLeft(Offloads.empty) { (o, i) =>
      o.applied(OffloadUpdate.Put(result(s"k$i", 10), 20))
    }
    many.results.map(_.key) shouldBe Vector(s"k${ToolOutputOffload.MaxEvicted + 10}")
    many.evicted should have size ToolOutputOffload.MaxEvicted.toLong
  }

  they should "round-trip through the key's codecs" in {
    val offloads = Offloads(Vector(OffloadedResult("k", "t", "c", 1L, truncated = true)), Vector("e"))
    val json     = upickle.default.writeJs(offloads)(using ToolOutputOffload.key.stateCodec)
    upickle.default.read[Offloads](json)(using ToolOutputOffload.key.stateCodec) shouldBe offloads
    val update = OffloadUpdate.Put(offloads.results.head, 10L)
    val uj     = upickle.default.writeJs[OffloadUpdate](update)
    upickle.default.read[OffloadUpdate](uj) shouldBe update
  }

  "ToolOutputOffload" should "refuse limits out of range" in {
    an[IllegalArgumentException] should be thrownBy new ToolOutputOffload(thresholdTokens = 999)
    an[IllegalArgumentException] should be thrownBy new ToolOutputOffload(maxResultBytes = 0)
    an[IllegalArgumentException] should be thrownBy new ToolOutputOffload(maxResultBytes = 10, maxTotalBytes = 5)
  }

  it should "hash a caller-built key that is not a path segment" in {
    ToolOutputOffload.segmentOf("abc") shouldBe "abc"
    ToolOutputOffload.segmentOf("a/b") should have length 64
    ToolOutputOffload.segmentOf("..") should have length 64
  }
}
