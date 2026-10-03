package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class RunConfigSpec extends AnyFlatSpec with Matchers {

  "RunBudgets" should "default to 1000 supersteps, no timeout and 16 concurrent tasks" in {
    RunBudgets.default shouldBe RunBudgets(1000, None, 16)
  }

  it should "refuse non-positive values from of" in {
    RunBudgets.of(maxSupersteps = 0).left.toOption.get shouldBe a[ValidationError]
    RunBudgets.of(maxConcurrency = 0).left.toOption.get shouldBe a[ValidationError]
    RunBudgets.of(timeout = Some(0.millis)).left.toOption.get shouldBe a[ValidationError]
    RunBudgets.of(maxSupersteps = 5, timeout = Some(1.second), maxConcurrency = 2) shouldBe
      Right(RunBudgets(5, Some(1.second), 2))
  }

  it should "throw from apply on a non-positive value" in {
    an[IllegalArgumentException] should be thrownBy RunBudgets(maxConcurrency = -1)
  }

  it should "set fields with with* setters" in {
    RunBudgets.default.withTimeout(2.seconds).withMaxConcurrency(4).withMaxSupersteps(9) shouldBe
      RunBudgets(9, Some(2.seconds), 4)
    RunBudgets(timeout = Some(1.second)).withTimeout(None).timeout shouldBe None
  }

  it should "refuse a non-positive value from every with* setter, as apply does" in {
    def refused(set: => RunBudgets): String = intercept[IllegalArgumentException](set).getMessage
    refused(RunBudgets.default.withMaxSupersteps(0)) shouldBe
      "requirement failed: maxSupersteps must be positive, was 0"
    refused(RunBudgets.default.withMaxConcurrency(0)) shouldBe
      "requirement failed: maxConcurrency must be positive, was 0"
    refused(RunBudgets.default.withMaxConcurrency(-2)) shouldBe
      "requirement failed: maxConcurrency must be positive, was -2"
    refused(RunBudgets.default.withTimeout(0.millis)) shouldBe
      "requirement failed: timeout must be positive, was 0 milliseconds"
    refused(RunBudgets.default.withTimeout(Some(-1.second))) shouldBe
      "requirement failed: timeout must be positive, was -1 seconds"
    refused(RunBudgets.default.withMaxSupersteps(0)) shouldBe refused(RunBudgets(maxSupersteps = 0))
  }

  "RunConfig" should "generate a fresh run id per call" in {
    RunConfig().runId should not be RunConfig().runId
  }

  it should "carry identity and metadata through with* setters" in {
    val c = RunConfig()
      .withRunId(RunId("r1"))
      .withTenantId(TenantId("acme"))
      .withPrincipal(Principal("alice"))
      .withMetadata(Map("k" -> "v"))
    (c.runId.value, c.tenantId.map(_.value), c.principal.map(_.value), c.metadata) shouldBe
      (("r1", Some("acme"), Some("alice"), Map("k" -> "v")))
  }

  "RunContext" should "expose the task's position and report its thread's interrupt without clearing it" in {
    val seen = new java.util.concurrent.atomic.AtomicReference[(RunPosition, Boolean)]()
    val b    = GraphBuilder("ctx", "1")
    val only = b.node[Unit]("only") { (_, _, ctx) =>
      Thread.currentThread().interrupt()
      seen.set(ctx.position -> ctx.isCancelled)
      Thread.interrupted(): Unit
      NodeResult.Continue(Command.empty)
    }
    val graph = b.compile[Unit, Unit](only)(_ => Right(())).toOption.get
    GraphTestSupport.runInMemory(graph, (), RunConfig().withRunId(RunId("r9")), thread = "th")
    val (position, cancelled) = seen.get
    (position.threadId.value, position.runId.value, position.nodeId.value, position.superstep) shouldBe
      (("th", "r9", "only", 0))
    cancelled shouldBe true
  }
}
