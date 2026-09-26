package org.llm4s.agent.orchestration

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.agent.Agent
import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ ToolRegistry, ToolBuilder, Schema }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.Outcome
import org.slf4j.LoggerFactory
import upickle.default.{ ReadWriter, macroRW }
import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration._
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

/**
 * Integration tests for multi-agent DAG orchestration with tool calling.
 *
 * Covers:
 *  - Linear pipeline: data flows through agents A -> B -> C
 *  - Parallel branches: two independent branches both execute and results merge
 *  - Tool calling within an orchestrated DAG node
 *  - Fan-out / fan-in: one source fans out to N workers
 *  - Node failure propagation: downstream nodes are not called after failure
 *  - Agent handoff within orchestration
 */
class OrchestratorIntegrationSpec extends AnyFlatSpec with Matchers with ScalaFutures {

  implicit val ec: ExecutionContext                    = ExecutionContext.global
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  // -------------------------------------------------------------------------
  // Silence noisy orchestration logs for clean test output
  // -------------------------------------------------------------------------

  override def withFixture(test: NoArgTest): Outcome = {
    val loggers = Seq(
      "org.llm4s.agent.orchestration.TypedAgent$",
      "org.llm4s.agent.orchestration.PlanRunner",
      "org.llm4s.agent.orchestration.Policies$"
    ).map(LoggerFactory.getLogger(_).asInstanceOf[LBLogger])

    val prevLevels = loggers.map(_.getLevel)
    loggers.foreach(_.setLevel(Level.OFF))

    try super.withFixture(test)
    finally loggers.zip(prevLevels).foreach { case (l, lv) => l.setLevel(lv) }
  }

  // -------------------------------------------------------------------------
  // Minimal mock LLM client helpers
  // -------------------------------------------------------------------------

  /** Always returns the same fixed text response. */
  class FixedResponseClient(text: String) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(
        Completion(
          id = "mock-id",
          created = System.currentTimeMillis(),
          content = text,
          model = "mock-model",
          message = AssistantMessage(text),
          usage = None
        )
      )
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /** Cycles through a list of completions. */
  class SequencedResponseClient(responses: Seq[Result[Completion]]) extends LLMClient {
    private val idx = new AtomicInteger(0)
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val i = idx.getAndIncrement()
      if (i < responses.size) responses(i)
      else responses.last
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private def mkCompletion(text: String, toolCalls: Seq[ToolCall] = Seq.empty): Completion =
    Completion(
      id = s"id-${System.nanoTime()}",
      created = System.currentTimeMillis(),
      content = text,
      model = "mock-model",
      message = AssistantMessage(text, toolCalls),
      toolCalls = toolCalls.toList,
      usage = None
    )

  // -------------------------------------------------------------------------
  // Domain types
  // -------------------------------------------------------------------------

  case class RawText(value: String)
  case class NormalizedText(value: String)
  case class Summary(value: String)
  case class WorkerResult(workerId: Int, output: String)
  case class AggregatedResults(results: List[WorkerResult])

  // Tool result type used in tool-calling test
  case class AdditionResult(sum: Double)
  object AdditionResult {
    implicit val rw: ReadWriter[AdditionResult] = macroRW
  }

  // =========================================================================
  // 1. Linear pipeline: Agent A -> Agent B -> Agent C
  // =========================================================================

  "Linear pipeline (A -> B -> C)" should "pass data through all three nodes correctly" in {

    val agentA = TypedAgent.fromFunction[RawText, NormalizedText]("normalizer") { raw =>
      Right(NormalizedText(raw.value.toLowerCase.trim))
    }

    val agentB = TypedAgent.fromFunction[NormalizedText, Summary]("summarizer") { normalized =>
      Right(Summary(s"summary-of:${normalized.value}"))
    }

    val agentC = TypedAgent.fromFunction[Summary, String]("formatter") { summary =>
      Right(s"[FORMATTED] ${summary.value}")
    }

    val nodeA = Node("normalizer", agentA)
    val nodeB = Node("summarizer", agentB)
    val nodeC = Node("formatter", agentC)

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addNode(nodeC)
      .addEdge(Edge("a-b", nodeA, nodeB))
      .addEdge(Edge("b-c", nodeB, nodeC))
      .build

    val runner        = PlanRunner()
    val initialInputs = Map("normalizer" -> RawText("  Hello WORLD  "))

    whenReady(runner.execute(plan, initialInputs)) { result =>
      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)

      outputs.size shouldBe 3

      val normalized = outputs("normalizer").asInstanceOf[NormalizedText]
      normalized.value shouldBe "hello world"

      val summary = outputs("summarizer").asInstanceOf[Summary]
      summary.value shouldBe "summary-of:hello world"

      val formatted = outputs("formatter").asInstanceOf[String]
      formatted shouldBe "[FORMATTED] summary-of:hello world"
    }
  }

  // =========================================================================
  // 2. Parallel branches: two independent agents both execute and merge
  // =========================================================================

  "Parallel branches" should "execute both branches and make both results available in the merged output" in {

    val branch1Executed = new AtomicBoolean(false)
    val branch2Executed = new AtomicBoolean(false)

    val branch1 = TypedAgent.fromFunction[String, String]("branch-1") { input =>
      branch1Executed.set(true)
      Right(s"b1:$input")
    }

    val branch2 = TypedAgent.fromFunction[String, String]("branch-2") { input =>
      branch2Executed.set(true)
      Right(s"b2:$input")
    }

    // A merge node that accepts input from either branch
    val merger = TypedAgent.fromFunction[String, String]("merger")(input => Right(s"merged:$input"))

    val nodeB1    = Node("branch-1", branch1)
    val nodeB2    = Node("branch-2", branch2)
    val nodeMerge = Node("merger", merger)

    val plan = Plan.builder
      .addNode(nodeB1)
      .addNode(nodeB2)
      .addNode(nodeMerge)
      .addEdge(Edge("b1-merge", nodeB1, nodeMerge))
      // branch-2 is independent (no edge to merger) so both run in parallel
      .build

    val runner = PlanRunner()
    val initialInputs = Map(
      "branch-1" -> "data",
      "branch-2" -> "data"
    )

    whenReady(runner.execute(plan, initialInputs)) { result =>
      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)

      // Both branches must have executed
      branch1Executed.get() shouldBe true
      branch2Executed.get() shouldBe true

      // Both branch outputs are available
      outputs("branch-1").asInstanceOf[String] shouldBe "b1:data"
      outputs("branch-2").asInstanceOf[String] shouldBe "b2:data"

      // Merge ran after branch-1
      outputs("merger").asInstanceOf[String] shouldBe "merged:b1:data"
    }
  }

  // =========================================================================
  // 3. Tool calling within an orchestrated DAG node
  // =========================================================================

  "Tool calling within a DAG node" should "execute the tool and make its result available downstream" in {

    // Track whether the tool was actually invoked
    val toolCallCount = new AtomicInteger(0)

    // Build a simple adder tool
    val adderSchema = Schema
      .`object`[Map[String, Any]]("Adder parameters")
      .withRequiredField("a", Schema.number("First operand"))
      .withRequiredField("b", Schema.number("Second operand"))

    val adderTool = ToolBuilder[Map[String, Any], AdditionResult](
      "adder",
      "Adds two numbers",
      adderSchema
    ).withHandler { extractor =>
      for {
        a <- extractor.getDouble("a")
        b <- extractor.getDouble("b")
      } yield {
        toolCallCount.incrementAndGet()
        AdditionResult(a + b)
      }
    }.buildSafe()

    // The LLM mock: first response requests a tool call; second response
    // uses the tool result to produce the final answer.
    val toolCall = ToolCall(
      id = "call_add",
      name = "adder",
      arguments = ujson.Obj("a" -> ujson.Num(3), "b" -> ujson.Num(4))
    )

    val llmClient = new SequencedResponseClient(
      Seq(
        Right(mkCompletion("Let me add those", Seq(toolCall))),
        Right(mkCompletion("The sum is 7"))
      )
    )

    // A TypedAgent wrapping an Agent that has the adder tool
    val toolCallingNode = TypedAgent.fromFuture[String, String]("tool-caller") { query =>
      Future {
        adderTool match {
          case Left(err) => Left(err)
          case Right(tool) =>
            val registry = new ToolRegistry(Seq(tool))
            val agent    = new Agent(llmClient)
            agent
              .run(query, registry, maxSteps = Some(5))
              .map { finalState =>
                finalState.conversation.messages
                  .collect { case m: AssistantMessage if m.content.nonEmpty => m.content }
                  .lastOption
                  .getOrElse("no response")
              }
        }
      }
    }

    val downstreamNode = TypedAgent.fromFunction[String, String]("downstream") { result =>
      Right(s"downstream-received:$result")
    }

    val nodeToolCaller = Node("tool-caller", toolCallingNode)
    val nodeDownstream = Node("downstream", downstreamNode)

    val plan = Plan.builder
      .addNode(nodeToolCaller)
      .addNode(nodeDownstream)
      .addEdge(Edge("tool-to-downstream", nodeToolCaller, nodeDownstream))
      .build

    val runner        = PlanRunner()
    val initialInputs = Map("tool-caller" -> "What is 3 + 4?")

    whenReady(runner.execute(plan, initialInputs)) { result =>
      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)

      // The tool must have been called
      toolCallCount.get() shouldBe 1

      // The tool-caller node produced a result
      val toolCallerOutput = outputs("tool-caller").asInstanceOf[String]
      toolCallerOutput should include("7")

      // Downstream node received and processed the result
      val downstreamOutput = outputs("downstream").asInstanceOf[String]
      downstreamOutput should startWith("downstream-received:")
    }
  }

  // =========================================================================
  // 4. Fan-out / fan-in: one source fans out to N workers
  // =========================================================================

  "Fan-out / fan-in" should "run all N workers and aggregate their outputs" in {

    val N              = 4
    val executionFlags = (0 until N).map(_ => new AtomicBoolean(false))

    val sourceAgent = TypedAgent.fromFunction[String, String]("source")(input => Right(s"source:$input"))

    val workerAgents = (0 until N).map { i =>
      TypedAgent.fromFunction[String, WorkerResult](s"worker-$i") { input =>
        executionFlags(i).set(true)
        Right(WorkerResult(i, s"worker-$i-processed:$input"))
      }
    }

    // Aggregator collects input from first worker (simplified - real
    // aggregation would require a custom multi-input node; this verifies
    // that all workers actually ran via the execution flags).
    val aggregatorAgent = TypedAgent.fromFunction[WorkerResult, AggregatedResults]("aggregator") { firstResult =>
      Right(AggregatedResults(List(firstResult)))
    }

    val nodeSource     = Node("source", sourceAgent)
    val workerNodes    = (0 until N).map(i => Node(s"worker-$i", workerAgents(i)))
    val nodeAggregator = Node("aggregator", aggregatorAgent)

    val planBuilder = Plan.builder
      .addNode(nodeSource)
      .addNode(nodeAggregator)

    // Add all worker nodes and fan-out edges from source
    val builderWithWorkers = workerNodes.foldLeft(planBuilder) { (b, wn) =>
      b.addNode(wn).addEdge(Edge(s"source-to-${wn.id}", nodeSource, wn))
    }

    // Connect first worker to aggregator (fan-in simplified to one connection)
    val finalBuilder = builderWithWorkers.addEdge(
      Edge("worker-0-to-agg", workerNodes(0), nodeAggregator)
    )

    val plan = finalBuilder.build

    val runner        = PlanRunner()
    val initialInputs = Map("source" -> "task-data")

    whenReady(runner.execute(plan, initialInputs), timeout(8.seconds)) { result =>
      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)

      // Source ran
      outputs("source").asInstanceOf[String] shouldBe "source:task-data"

      // All N workers must have executed
      executionFlags.zipWithIndex.foreach { case (flag, i) =>
        withClue(s"worker-$i did not execute")(flag.get() shouldBe true)
      }

      // Aggregator produced output
      val agg = outputs("aggregator").asInstanceOf[AggregatedResults]
      agg.results should have size 1
      agg.results.head.workerId shouldBe 0
    }
  }

  // =========================================================================
  // 5. Node failure propagation: downstream nodes must not execute
  // =========================================================================

  "Node failure propagation" should "stop execution and not call downstream nodes when a node fails" in {

    val downstreamExecuted = new AtomicBoolean(false)

    val failingAgent = TypedAgent.fromFunction[String, String]("failing-node") { _ =>
      Left(
        OrchestrationError.NodeExecutionError(
          "failing-node",
          "failing-node",
          "Simulated network failure"
        )
      )
    }

    val downstreamAgent = TypedAgent.fromFunction[String, String]("downstream-node") { _ =>
      downstreamExecuted.set(true)
      Right("should-not-reach-here")
    }

    val nodeFail       = Node("failing-node", failingAgent)
    val nodeDownstream = Node("downstream-node", downstreamAgent)

    val plan = Plan.builder
      .addNode(nodeFail)
      .addNode(nodeDownstream)
      .addEdge(Edge("fail-to-downstream", nodeFail, nodeDownstream))
      .build

    val runner        = PlanRunner()
    val initialInputs = Map("failing-node" -> "trigger")

    whenReady(runner.execute(plan, initialInputs)) { result =>
      // Plan execution must fail
      result.isLeft shouldBe true

      result.left.foreach(error => error shouldBe an[OrchestrationError])

      // Downstream must NOT have run
      downstreamExecuted.get() shouldBe false
    }
  }

  "Node failure propagation" should "propagate NetworkError from a DAG node" in {

    val networkErrorAgent = TypedAgent.fromFunction[String, String]("network-error-node") { _ =>
      Left(NetworkError("Connection refused", None, "http://internal-service"))
    }

    val nodeNetErr = Node("network-error-node", networkErrorAgent)

    val plan = Plan.builder
      .addNode(nodeNetErr)
      .build

    val runner        = PlanRunner()
    val initialInputs = Map("network-error-node" -> "request")

    whenReady(runner.execute(plan, initialInputs)) { result =>
      result.isLeft shouldBe true
      result.left.foreach(error => error shouldBe a[NetworkError])
    }
  }

  // =========================================================================
  // 6. Agent handoff within orchestration
  // =========================================================================

  "Agent handoff within orchestration" should "resolve handoff and make result available to downstream node" in {

    // Specialist agent responds immediately with a final answer
    val specialistClient = new FixedResponseClient("Specialist answer: 42")
    val specialistAgent  = new Agent(specialistClient)

    // Primary agent LLM: first call triggers handoff, second call (inside
    // specialist) is handled by specialistClient.
    val handoff = org.llm4s.agent.Handoff.to(specialistAgent, "Math specialist")

    // Construct the handoff tool call payload that the primary LLM emits.
    // The handoff tool name is the handoff's handoffId.
    val handoffToolCall = ToolCall(
      id = "call_handoff",
      name = handoff.handoffId,
      arguments = ujson.Obj("reason" -> ujson.Str("Needs specialist"))
    )

    val primaryClient = new SequencedResponseClient(
      Seq(
        Right(mkCompletion("Handing off to specialist", Seq(handoffToolCall))),
        Right(mkCompletion("Specialist answer: 42"))
      )
    )

    // A TypedAgent that wraps an Agent run with handoff capability
    val handoffNode = TypedAgent.fromFuture[String, String]("handoff-agent") { query =>
      Future {
        val primaryAgent = new Agent(primaryClient)
        primaryAgent
          .run(query, ToolRegistry.empty, handoffs = Seq(handoff), maxSteps = Some(10))
          .map { finalState =>
            finalState.conversation.messages
              .collect { case m: AssistantMessage if m.content.nonEmpty => m.content }
              .lastOption
              .getOrElse("no specialist response")
          }
      }
    }

    val downstreamNode = TypedAgent.fromFunction[String, String]("post-handoff") { result =>
      Right(s"received:$result")
    }

    val nodeHandoff    = Node("handoff-agent", handoffNode)
    val nodeDownstream = Node("post-handoff", downstreamNode)

    val plan = Plan.builder
      .addNode(nodeHandoff)
      .addNode(nodeDownstream)
      .addEdge(Edge("handoff-to-downstream", nodeHandoff, nodeDownstream))
      .build

    val runner        = PlanRunner()
    val initialInputs = Map("handoff-agent" -> "What is the answer?")

    whenReady(runner.execute(plan, initialInputs)) { result =>
      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)

      // Handoff node produced a result
      val handoffOutput = outputs("handoff-agent").asInstanceOf[String]
      handoffOutput should include("42")

      // Downstream received and processed it
      val downstreamOutput = outputs("post-handoff").asInstanceOf[String]
      downstreamOutput should startWith("received:")
      downstreamOutput should include("42")
    }
  }

  // =========================================================================
  // 7. Parallel execution timing: nodes in same batch run concurrently
  // =========================================================================

  "Parallel batch execution" should "complete faster than sequential would" in {

    val SLEEP_MS = 80L

    def slowNode(id: String): Node[String, String] =
      Node(
        id,
        TypedAgent.fromFuture[String, String](id) { _ =>
          Future {
            Thread.sleep(SLEEP_MS)
            Right(s"$id:done")
          }
        }
      )

    val nodes = (1 to 4).map(i => slowNode(s"slow-$i")).toList

    val plan = nodes.foldLeft(Plan.builder)((b, n) => b.addNode(n)).build

    val initialInputs = (1 to 4).map(i => s"slow-$i" -> s"input-$i").toMap[String, Any]

    val runner    = PlanRunner()
    val startTime = System.currentTimeMillis()

    whenReady(runner.execute(plan, initialInputs)) { result =>
      val elapsed = System.currentTimeMillis() - startTime

      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)
      outputs should have size 4

      // All four run in parallel; must finish well under 4 * SLEEP_MS
      elapsed should be < (SLEEP_MS * 3)
    }
  }

  // =========================================================================
  // 8. Fan-out then linear continuation
  // =========================================================================

  "Fan-out with downstream continuation" should "complete all workers and then run continuation node" in {

    val workerCount = new AtomicInteger(0)

    val source = TypedAgent.fromFunction[String, String]("src")(v => Right(s"src:$v"))
    val w1 = TypedAgent.fromFunction[String, String]("w1") { v =>
      workerCount.incrementAndGet(); Right(s"w1:$v")
    }
    val w2 = TypedAgent.fromFunction[String, String]("w2") { v =>
      workerCount.incrementAndGet(); Right(s"w2:$v")
    }
    val w3 = TypedAgent.fromFunction[String, String]("w3") { v =>
      workerCount.incrementAndGet(); Right(s"w3:$v")
    }
    val cont = TypedAgent.fromFunction[String, String]("cont")(v => Right(s"cont:$v"))

    val nSrc  = Node("src", source)
    val nW1   = Node("w1", w1)
    val nW2   = Node("w2", w2)
    val nW3   = Node("w3", w3)
    val nCont = Node("cont", cont)

    val plan = Plan.builder
      .addNode(nSrc)
      .addNode(nW1)
      .addNode(nW2)
      .addNode(nW3)
      .addNode(nCont)
      .addEdge(Edge("src-w1", nSrc, nW1))
      .addEdge(Edge("src-w2", nSrc, nW2))
      .addEdge(Edge("src-w3", nSrc, nW3))
      .addEdge(Edge("w1-cont", nW1, nCont))
      .build

    val runner        = PlanRunner()
    val initialInputs = Map("src" -> "data")

    whenReady(runner.execute(plan, initialInputs)) { result =>
      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)

      outputs("src").asInstanceOf[String] shouldBe "src:data"

      // All three workers ran
      workerCount.get() shouldBe 3
      outputs("w1").asInstanceOf[String] shouldBe "w1:src:data"
      outputs("w2").asInstanceOf[String] shouldBe "w2:src:data"
      outputs("w3").asInstanceOf[String] shouldBe "w3:src:data"

      // Continuation ran after w1
      outputs("cont").asInstanceOf[String] shouldBe "cont:w1:src:data"
    }
  }
}
