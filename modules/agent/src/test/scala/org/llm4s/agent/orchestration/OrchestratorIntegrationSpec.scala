package org.llm4s.agent.orchestration

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.agent.{ Agent, CompletionFixture, FailingLLMClient, Handoff, NTurnFakeLLMClient }
import org.llm4s.error.{ NetworkError, RateLimitError, SimpleError, UnknownError }
import org.llm4s.types.Result
import org.llm4s.llmconnect.model.{ AssistantMessage, ToolMessage }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.scalatest.Outcome
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import upickle.default.{ macroRW, ReadWriter }

import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Real `Agent`s (tool calling, handoffs) running as nodes of an orchestrated DAG, and failure
 * propagation out of a node (issue #999).
 *
 * Pure-function DAG shapes (linear, parallel, diamond, timing) are already covered by
 * PlanRunnerSpec and IntegrationSpec, so they are not repeated here.
 */
class OrchestratorIntegrationSpec extends AnyFlatSpec with Matchers with ScalaFutures {

  implicit val ec: ExecutionContext                    = ExecutionContext.global
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  override def withFixture(test: NoArgTest): Outcome = {
    val loggers = Seq(
      "org.llm4s.agent.orchestration.TypedAgent$",
      "org.llm4s.agent.orchestration.PlanRunner",
      "org.llm4s.agent.orchestration.Policies$"
    ).map(LoggerFactory.getLogger(_).asInstanceOf[LBLogger])
    val previous = loggers.map(_.getLevel)
    loggers.foreach(_.setLevel(Level.OFF))
    try super.withFixture(test)
    finally loggers.zip(previous).foreach { case (l, lv) => l.setLevel(lv) }
  }

  final case class AdditionResult(sum: Double)
  object AdditionResult {
    implicit val rw: ReadWriter[AdditionResult] = macroRW
  }

  private def adderTool(invocations: AtomicInteger): ToolFunction[Map[String, Any], AdditionResult] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Adder parameters")
      .withRequiredField("a", Schema.number("First operand"))
      .withRequiredField("b", Schema.number("Second operand"))
    ToolBuilder[Map[String, Any], AdditionResult]("adder", "Adds two numbers", schema)
      .withHandler { extractor =>
        for {
          a <- extractor.getDouble("a")
          b <- extractor.getDouble("b")
        } yield {
          invocations.incrementAndGet()
          AdditionResult(a + b)
        }
      }
      .buildSafe()
      .fold(e => fail(s"adder tool failed to build: $e"), identity)
  }

  "An Agent used as a DAG node" should "run its tool and feed the tool result to the downstream node" in {
    val invocations = new AtomicInteger(0)
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall("adder", ujson.Obj("a" -> 3, "b" -> 4)),
      CompletionFixture.simple("done")
    )

    // The node returns the content of the tool message the agent recorded, so the assertion
    // depends on the tool really having executed rather than on the canned model reply.
    val agentNode = TypedAgent.fromFuture[String, String]("tool-caller") { query =>
      Future {
        new Agent(client)
          .run(query, new ToolRegistry(Seq(adderTool(invocations))), maxSteps = Some(5))
          .map(_.conversation.messages.collect { case m: ToolMessage => m.content }.mkString("|"))
      }
    }
    val downstream = TypedAgent.fromFunction[String, String]("downstream")(s => Right(s"downstream:$s"))

    val toolCaller = Node("tool-caller", agentNode)
    val after      = Node("downstream", downstream)
    val plan       = Plan.builder.addNode(toolCaller).addNode(after).addEdge(Edge("e", toolCaller, after)).build

    whenReady(PlanRunner().execute(plan, Map("tool-caller" -> "What is 3 + 4?"))) { result =>
      val outputs = result.fold(e => fail(s"plan failed: $e"), identity)
      invocations.get() shouldBe 1
      outputs("tool-caller").asInstanceOf[String] should include("7")
      outputs("downstream").asInstanceOf[String] shouldBe s"downstream:${outputs("tool-caller")}"
    }
  }

  it should "resolve a handoff to a specialist and pass the specialist's answer downstream" in {
    val specialist = new Agent(new NTurnFakeLLMClient(CompletionFixture.simple("Specialist answer: 42")))
    val handoff    = Handoff.to(specialist, "Math specialist")
    val primary = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall(handoff.handoffId, ujson.Obj("reason" -> "Needs specialist"), "call_handoff")
    )

    val handoffNode = TypedAgent.fromFuture[String, String]("handoff-agent") { query =>
      Future {
        new Agent(primary)
          .run(query, ToolRegistry.empty, handoffs = Seq(handoff), maxSteps = Some(10))
          .map(_.conversation.messages.collect { case m: AssistantMessage => m.content }.filter(_.nonEmpty).last)
      }
    }
    val downstream = TypedAgent.fromFunction[String, String]("post-handoff")(s => Right(s"received:$s"))

    val n1   = Node("handoff-agent", handoffNode)
    val n2   = Node("post-handoff", downstream)
    val plan = Plan.builder.addNode(n1).addNode(n2).addEdge(Edge("e", n1, n2)).build

    whenReady(PlanRunner().execute(plan, Map("handoff-agent" -> "What is the answer?"))) { result =>
      val outputs = result.fold(e => fail(s"plan failed: $e"), identity)
      outputs("handoff-agent") shouldBe "Specialist answer: 42"
      outputs("post-handoff") shouldBe "received:Specialist answer: 42"
    }
  }

  "A failing DAG node" should "return its NetworkError unchanged and never run downstream nodes" in {
    val original           = NetworkError("Connection refused", None, "http://internal-service")
    val downstreamExecuted = new AtomicBoolean(false)

    val failing = TypedAgent.fromFunction[String, String]("failing")(_ => Left(original))
    val downstream = TypedAgent.fromFunction[String, String]("downstream") { _ =>
      downstreamExecuted.set(true)
      Right("unreachable")
    }

    val n1   = Node("failing", failing)
    val n2   = Node("downstream", downstream)
    val plan = Plan.builder.addNode(n1).addNode(n2).addEdge(Edge("e", n1, n2)).build

    whenReady(PlanRunner().execute(plan, Map("failing" -> "trigger"))) { result =>
      result.fold(identity, o => fail(s"expected failure, got $o")) shouldBe original
      downstreamExecuted.get() shouldBe false
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Additional coverage: real parallelism, concurrency limit, failure containment, cancellation,
  // invalid plans, type mismatch, real-agent failures
  // ---------------------------------------------------------------------------------------------

  private def fn[I, O](name: String)(f: I => Result[O]): TypedAgent[I, O] = TypedAgent.fromFunction[I, O](name)(f)

  private def node[I, O](id: String, agent: TypedAgent[I, O]): Node[I, O] = Node(id, agent)

  "Parallel branches" should "really run at the same time: each waits for the other to start" in {
    // Each branch signals its start and then waits (bounded) for the other branch's signal. A runner
    // that executed the branches one after the other could never see both signals and would fail.
    val aStarted = new CountDownLatch(1)
    val bStarted = new CountDownLatch(1)

    def branch(name: String, mine: CountDownLatch, other: CountDownLatch) =
      fn[String, String](name) { in =>
        mine.countDown()
        if (other.await(5, TimeUnit.SECONDS)) Right(s"$name:$in") else Left(SimpleError(s"$name never saw its sibling"))
      }

    val source = node("src", fn[String, String]("src")(s => Right(s)))
    val a      = node("a", branch("a", aStarted, bStarted))
    val b      = node("b", branch("b", bStarted, aStarted))
    val plan = Plan.builder
      .addNode(source)
      .addNode(a)
      .addNode(b)
      .addEdge(Edge("src-a", source, a))
      .addEdge(Edge("src-b", source, b))
      .build

    whenReady(PlanRunner().execute(plan, Map("src" -> "x"))) { result =>
      val outputs = result.fold(e => fail(s"plan failed: $e"), identity)
      outputs("a") shouldBe "a:x"
      outputs("b") shouldBe "b:x"
    }
  }

  it should "all complete before a join node starts" in {
    val finished   = new AtomicInteger(0)
    val seenAtJoin = new AtomicInteger(-1)
    def worker(n: String) = fn[String, String](n) { in =>
      finished.incrementAndGet()
      Right(s"$n:$in")
    }
    val source = node("src", fn[String, String]("src")(s => Right(s)))
    val w1     = node("w1", worker("w1"))
    val w2     = node("w2", worker("w2"))
    val w3     = node("w3", worker("w3"))
    val join = node(
      "join",
      fn[String, String]("join") { in =>
        seenAtJoin.set(finished.get())
        Right(s"joined:$in")
      }
    )
    val plan = Plan.builder
      .addNode(source)
      .addNode(w1)
      .addNode(w2)
      .addNode(w3)
      .addNode(join)
      .addEdge(Edge("s1", source, w1))
      .addEdge(Edge("s2", source, w2))
      .addEdge(Edge("s3", source, w3))
      .addEdge(Edge("j1", w1, join))
      .addEdge(Edge("j2", w2, join))
      .addEdge(Edge("j3", w3, join))
      .build

    whenReady(PlanRunner().execute(plan, Map("src" -> "x"))) { result =>
      val outputs = result.fold(e => fail(s"plan failed: $e"), identity)
      seenAtJoin.get() shouldBe 3
      outputs.keySet shouldBe Set("src", "w1", "w2", "w3", "join")
    }
  }

  "Fan-out" should "run every one of N workers exactly once" in {
    val runs = new ConcurrentHashMap[String, AtomicInteger]()
    val n    = 12
    val src  = node("src", fn[String, String]("src")(s => Right(s)))
    val workers = (1 to n).map { i =>
      val id = s"w$i"
      runs.put(id, new AtomicInteger(0))
      node(
        id,
        fn[String, Int](id) { in =>
          runs.get(id).incrementAndGet()
          Right(in.length + i)
        }
      )
    }
    val builder = workers.foldLeft(Plan.builder.addNode(src)) { (b, w) =>
      b.addNode(w).addEdge(Edge(s"e-${w.id}", src, w))
    }

    whenReady(PlanRunner().execute(builder.build, Map("src" -> "abc"))) { result =>
      val outputs = result.fold(e => fail(s"plan failed: $e"), identity)
      outputs should have size (n + 1)
      (1 to n).foreach { i =>
        runs.get(s"w$i").get() shouldBe 1
        outputs(s"w$i") shouldBe (3 + i)
      }
    }
  }

  "A linear pipeline with changing types" should "pass each node's typed output to the next" in {
    val a = node("a", fn[String, Int]("a")(s => Right(s.length)))
    val b = node("b", fn[Int, List[Int]]("b")(n => Right(List.fill(n)(n))))
    val c = node("c", fn[List[Int], String]("c")(xs => Right(xs.mkString("-"))))
    val plan =
      Plan.builder.addNode(a).addNode(b).addNode(c).addEdge(Edge("ab", a, b)).addEdge(Edge("bc", b, c)).build

    whenReady(PlanRunner().execute(plan, Map("a" -> "four"))) { result =>
      val outputs = result.fold(e => fail(s"plan failed: $e"), identity)
      outputs("a") shouldBe 4
      outputs("b") shouldBe List(4, 4, 4, 4)
      outputs("c") shouldBe "4-4-4-4"
    }
  }

  "The concurrency limit" should "run at most maxConcurrentNodes of a wide batch at once, then the rest" in {
    val running  = new AtomicInteger(0)
    val peak     = new AtomicInteger(0)
    val started  = new AtomicInteger(0)
    val firstTwo = new CountDownLatch(2)
    val allFour  = new CountDownLatch(4)
    val gate     = new CountDownLatch(1)

    def gated(id: String) = node(
      id,
      fn[String, String](id) { in =>
        val now = running.incrementAndGet()
        peak.accumulateAndGet(now, math.max(_, _))
        started.incrementAndGet()
        firstTwo.countDown()
        allFour.countDown()
        gate.await(10, TimeUnit.SECONDS)
        running.decrementAndGet()
        Right(in)
      }
    )
    val nodes = Seq("n1", "n2", "n3", "n4").map(gated)
    val plan  = nodes.foldLeft(Plan.builder)((b, n) => b.addNode(n)).build

    val future = new PlanRunner(maxConcurrentNodes = 2).execute(plan, nodes.map(_.id -> "x").toMap)

    firstTwo.await(5, TimeUnit.SECONDS) shouldBe true
    // With the gate closed a third node must not be able to start. A bounded negative wait is the only way
    // to observe that something does not happen.
    allFour.await(300, TimeUnit.MILLISECONDS) shouldBe false
    started.get() shouldBe 2
    gate.countDown()

    whenReady(future) { result =>
      result.isRight shouldBe true
      started.get() shouldBe 4
      peak.get() shouldBe 2
    }
  }

  "A failure in one parallel branch" should "return that error unchanged and keep the join node from running" in {
    val original   = NetworkError("Connection refused", None, "http://internal-service")
    val joinRan    = new AtomicBoolean(false)
    val siblingRan = new AtomicBoolean(false)
    val src        = node("src", fn[String, String]("src")(s => Right(s)))
    val bad        = node("bad", fn[String, String]("bad")(_ => Left(original)))
    val good = node(
      "good",
      fn[String, String]("good") { s =>
        siblingRan.set(true)
        Right(s)
      }
    )
    val join = node(
      "join",
      fn[String, String]("join") { s =>
        joinRan.set(true)
        Right(s)
      }
    )
    val plan = Plan.builder
      .addNode(src)
      .addNode(bad)
      .addNode(good)
      .addNode(join)
      .addEdge(Edge("s-bad", src, bad))
      .addEdge(Edge("s-good", src, good))
      .addEdge(Edge("bad-join", bad, join))
      .addEdge(Edge("good-join", good, join))
      .build

    whenReady(PlanRunner().execute(plan, Map("src" -> "x"))) { result =>
      result.fold(identity, o => fail(s"expected failure, got $o")) shouldBe original
      joinRan.get() shouldBe false
    }
  }

  "An LLM error inside an Agent node" should "come out of the plan unchanged" in {
    val original = RateLimitError("provider", 30.seconds)
    val agentNode = TypedAgent.fromFuture[String, String]("llm-agent") { q =>
      Future(new Agent(new FailingLLMClient(original)).run(q, ToolRegistry.empty).map(_.status.toString))
    }
    val n    = node("llm-agent", agentNode)
    val next = node("next", fn[String, String]("next")(s => Right(s"after:$s")))
    val plan = Plan.builder.addNode(n).addNode(next).addEdge(Edge("e", n, next)).build

    whenReady(PlanRunner().execute(plan, Map("llm-agent" -> "hello"))) { result =>
      result.fold(identity, o => fail(s"expected failure, got $o")) shouldBe original
    }
  }

  it should "run in parallel with another Agent node, each with its own tools and replies" in {
    val invocations = new AtomicInteger(0)
    val adder = TypedAgent.fromFuture[String, String]("adder") { q =>
      val client = new NTurnFakeLLMClient(
        CompletionFixture.withToolCall("adder", ujson.Obj("a" -> 20, "b" -> 22)),
        CompletionFixture.simple("sum computed")
      )
      Future(
        new Agent(client)
          .run(q, new ToolRegistry(Seq(adderTool(invocations))))
          .map(_.conversation.messages.collect { case m: ToolMessage => m.content }.mkString)
      )
    }
    val talker = TypedAgent.fromFuture[String, String]("talker") { q =>
      Future(
        new Agent(new NTurnFakeLLMClient(CompletionFixture.simple(s"echo $q")))
          .run(q, ToolRegistry.empty)
          .map(_.conversation.messages.last.content)
      )
    }
    val a    = node("adder", adder)
    val t    = node("talker", talker)
    val plan = Plan.builder.addNode(a).addNode(t).build

    whenReady(PlanRunner().execute(plan, Map("adder" -> "add", "talker" -> "hi"))) { result =>
      val outputs = result.fold(e => fail(s"plan failed: $e"), identity)
      invocations.get() shouldBe 1
      outputs("adder").asInstanceOf[String] should include("42")
      outputs("talker") shouldBe "echo hi"
    }
  }

  "Cancellation" should "stop a plan whose token is already cancelled before any node runs" in {
    val ran   = new AtomicBoolean(false)
    val token = CancellationToken()
    token.cancel()
    val n = node(
      "n",
      fn[String, String]("n") { s =>
        ran.set(true); Right(s)
      }
    )
    val plan = Plan.builder.addNode(n).build

    whenReady(PlanRunner().execute(plan, Map("n" -> "x"), token)) { result =>
      val error = result.fold(identity, o => fail(s"expected cancellation, got $o"))
      error shouldBe a[OrchestrationError.PlanExecutionError]
      error.message.toLowerCase should include("cancel")
      ran.get() shouldBe false
    }
  }

  it should "return promptly, without waiting for a blocked node, and skip downstream nodes" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val nextRan = new AtomicBoolean(false)
    val token   = CancellationToken()
    val blocking = node(
      "blocking",
      fn[String, String]("blocking") { s =>
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
        Right(s)
      }
    )
    val next = node(
      "next",
      fn[String, String]("next") { s =>
        nextRan.set(true); Right(s)
      }
    )
    val plan = Plan.builder.addNode(blocking).addNode(next).addEdge(Edge("e", blocking, next)).build

    val future = PlanRunner().execute(plan, Map("blocking" -> "x"), token)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    future.isCompleted shouldBe false
    token.cancel()

    try
      whenReady(future) { result =>
        val error = result.fold(identity, o => fail(s"expected cancellation, got $o"))
        error shouldBe a[OrchestrationError.PlanExecutionError]
        error.message should include("cancelled")
      }
    finally release.countDown()
    nextRan.get() shouldBe false
  }

  "Invalid plans" should "be rejected with PlanValidationError before any node runs: cycle through three nodes" in {
    val ran = new AtomicInteger(0)
    def counting(id: String) = node(
      id,
      fn[String, String](id) { s =>
        ran.incrementAndGet(); Right(s)
      }
    )
    val n1 = counting("a")
    val n2 = counting("b")
    val n3 = counting("c")
    val plan = Plan.builder
      .addNode(n1)
      .addNode(n2)
      .addNode(n3)
      .addEdge(Edge("ab", n1, n2))
      .addEdge(Edge("bc", n2, n3))
      .addEdge(Edge("ca", n3, n1))
      .build

    whenReady(PlanRunner().execute(plan, Map("a" -> "x"))) { result =>
      result.fold(identity, o => fail(s"expected failure, got $o")) shouldBe a[OrchestrationError.PlanValidationError]
      ran.get() shouldBe 0
    }
  }

  it should "reject a node that depends on itself" in {
    val n1   = node("a", fn[String, String]("a")(s => Right(s)))
    val plan = Plan.builder.addNode(n1).addEdge(Edge("aa", n1, n1)).build

    whenReady(PlanRunner().execute(plan, Map("a" -> "x"))) { result =>
      result.fold(identity, o => fail(s"expected failure, got $o")) shouldBe a[OrchestrationError.PlanValidationError]
    }
  }

  it should "reject a non-positive concurrency limit" in {
    an[IllegalArgumentException] should be thrownBy new PlanRunner(0)
  }

  "An edge whose types do not match at run time" should "fail the plan with an error, not an exception, and skip downstream nodes" in {
    val downstreamRan = new AtomicBoolean(false)
    val producer      = node("producer", fn[String, Int]("producer")(s => Right(s.length)))
    val consumer      = node("consumer", fn[String, String]("consumer")(s => Right(s.toUpperCase)))
    val after = node(
      "after",
      fn[String, String]("after") { s =>
        downstreamRan.set(true); Right(s)
      }
    )
    // Types are erased in the plan, so a mismatched edge can be built with a cast; the runner has to cope.
    val badEdge = Edge("bad", producer, consumer.asInstanceOf[Node[Int, String]])
    val plan = Plan.builder
      .addNode(producer)
      .addNode(consumer)
      .addNode(after)
      .addEdge(badEdge)
      .addEdge(Edge("ca", consumer, after))
      .build

    whenReady(PlanRunner().execute(plan, Map("producer" -> "abc"))) { result =>
      result.isLeft shouldBe true
      downstreamRan.get() shouldBe false
    }
  }

  "A node whose function throws" should "fail the plan with an error carrying the cause, not throw" in {
    val boom = new IllegalStateException("kaboom")
    val n    = node("n", fn[String, String]("n")(_ => throw boom))
    val plan = Plan.builder.addNode(n).build

    whenReady(PlanRunner().execute(plan, Map("n" -> "x"))) { result =>
      val error = result.fold(identity, o => fail(s"expected failure, got $o"))
      error shouldBe a[UnknownError]
      error.message should include("kaboom")
    }
  }

  it should "reject an edge that refers to a node missing from the plan, as an error rather than an exception" in {
    val present = node("present", fn[String, String]("present")(s => Right(s)))
    val missing = node("missing", fn[String, String]("missing")(s => Right(s)))
    // `missing` is never added to the builder, only referenced by the edge
    val plan = Plan.builder.addNode(present).addEdge(Edge("dangling", present, missing)).build

    val outcome = scala.util.Try(PlanRunner().execute(plan, Map("present" -> "x")))
    outcome.isSuccess shouldBe true
    whenReady(outcome.get) { result =>
      result.fold(identity, o => fail(s"expected failure, got $o")) shouldBe a[OrchestrationError.PlanValidationError]
    }
  }

  "An unbalanced fan-in" should "place the join after its longest upstream branch and run it only then" in {
    val slowDone = new AtomicBoolean(false)
    val sawSlow  = new AtomicBoolean(false)
    val src      = node("src", fn[String, String]("src")(s => Right(s)))
    val fast     = node("fast", fn[String, String]("fast")(s => Right(s)))
    val mid      = node("mid", fn[String, String]("mid")(s => Right(s)))
    val slow = node(
      "slow",
      fn[String, String]("slow") { s =>
        slowDone.set(true)
        Right(s)
      }
    )
    val join = node(
      "join",
      fn[String, String]("join") { s =>
        sawSlow.set(slowDone.get())
        Right(s)
      }
    )
    val plan = Plan.builder
      .addNode(src)
      .addNode(fast)
      .addNode(mid)
      .addNode(slow)
      .addNode(join)
      .addEdge(Edge("s-f", src, fast))
      .addEdge(Edge("s-m", src, mid))
      .addEdge(Edge("m-s", mid, slow))
      .addEdge(Edge("f-j", fast, join))
      .addEdge(Edge("sl-j", slow, join))
      .build

    val batches = plan.getParallelBatches.fold(e => fail(s"no batches: $e"), identity)
    batches.map(_.map(_.id).toSet) shouldBe List(Set("src"), Set("fast", "mid"), Set("slow"), Set("join"))

    whenReady(PlanRunner().execute(plan, Map("src" -> "x"))) { result =>
      result.isRight shouldBe true
      sawSlow.get() shouldBe true
    }
  }
}
