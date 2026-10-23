package org.llm4s.deploy

import org.llm4s.agent.graph.InMemoryCheckpointer
import org.llm4s.agent.graph.spi.CheckpointStore
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.duration.*

/**
 * [[ServiceRuntime]] opened from configuration alone - here `reference.conf`'s defaults: the `memory` store, the demo
 * agent and Prometheus metrics (design 4.60).
 */
class ServiceRuntimeSpec extends AnyFlatSpec with TestTimeLimit with Matchers with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(15, Seconds), interval = Span(50, Millis))

  "ServiceRuntime.open" should "open the configured store, run queued runs, record metrics, then drain and close" in {
    val runtime = ServiceRuntime.open(AgentServiceConfig.default.copy(toolDelay = 10.millis)).toOption.get
    runtime.store.kind shouldBe "memory"
    runtime.start()
    eventually(runtime.service.ready().status shouldBe 200)

    val entry = runtime.service.submit(None, "metered", """{"query": "count me"}""", None).body("entryId").str
    eventually(runtime.service.entry(None, entry).body("state").str shouldBe "done")

    val text = runtime.metricsText.map(_()).getOrElse(fail("metrics are on by default"))
    text should include("llm4s_agent_runs_started_total")

    runtime.drain()
    runtime.service.ready().status shouldBe 503
    runtime.drain().finished shouldBe empty
    runtime.close()
    runtime.store.isClosed shouldBe true
    runtime.close()
  }

  "ServiceRuntime.over" should "serve no metrics with metrics off, and close a store it was given" in {
    val memory  = new InMemoryCheckpointer()
    val store   = CheckpointStore.unmanaged("memory", memory).withRunQueue(memory.runQueue)
    val bare    = CheckpointStore.unmanaged("bare", new InMemoryCheckpointer())
    val runtime = ServiceRuntime.over(store, AgentServiceConfig.default.copy(metricsEnabled = false)).toOption.get
    runtime.metricsText shouldBe None
    runtime.close()
    store.isClosed shouldBe true

    ServiceRuntime.over(bare, AgentServiceConfig.default).isLeft shouldBe true
  }
}
