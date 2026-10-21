package org.llm4s.config

import org.llm4s.agent.graph.{ ClaimPolicy, InMemoryCheckpointer, WatchPolicy }
import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/** Stage 5 slice S5 (#2133, design 4.54): a runtime's claim and watch policies from `llm4s.agent.runtime`. */
class RuntimeConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def source(hocon: String, env: Map[String, String] = Map.empty) = ReferenceConfig.withEnv(hocon, env)

  "The reference.conf defaults" should "be the policies' own defaults" in {
    RuntimeConfigLoader.claimPolicy(source("")).value shouldBe ClaimPolicy.default
    RuntimeConfigLoader.watchPolicy(source("")).value shouldBe WatchPolicy.default
  }

  "Overrides" should "set each policy's settings" in {
    val s = source(
      """llm4s.agent.runtime {
        |  claims { ttl = 1 minute, renewEvery = 15 seconds, label = "pod-a" }
        |  watch  { enabled = false, pollInterval = 2 seconds }
        |}""".stripMargin
    )
    RuntimeConfigLoader.claimPolicy(s).value shouldBe ClaimPolicy(1.minute, 15.seconds, Some("pod-a"))
    RuntimeConfigLoader.watchPolicy(s).value shouldBe WatchPolicy(enabled = false, 2.seconds)
  }

  "The claim label" should "be bound to LLM4S_RUNTIME_LABEL" in {
    RuntimeConfigLoader.claimPolicy(source("", Map("LLM4S_RUNTIME_LABEL" -> "worker-7"))).value.label shouldBe
      Some("worker-7")
  }

  "A value a policy refuses" should "be a ConfigurationError naming the section" in {
    val claims = RuntimeConfigLoader
      .claimPolicy(source("llm4s.agent.runtime.claims { ttl = 5 seconds, renewEvery = 10 seconds }"))
      .left
      .value
    claims shouldBe a[ConfigurationError]
    claims.message should (include("llm4s.agent.runtime.claims").and(include("renewEvery")))

    val watch =
      RuntimeConfigLoader.watchPolicy(source("llm4s.agent.runtime.watch.pollInterval = 0 seconds")).left.value
    watch.message should include("llm4s.agent.runtime.watch")
  }

  "A mistyped value" should "be a ConfigurationError naming the key" in {
    RuntimeConfigLoader
      .claimPolicy(source("llm4s.agent.runtime.claims.ttl = soon"))
      .left
      .value
      .message should include("llm4s.agent.runtime.claims.ttl")
  }

  "runtime" should "build a GraphRuntime over the checkpointer, or fail as either policy does" in {
    val store = new InMemoryCheckpointer()
    RuntimeConfigLoader.runtime(source(""), store).isRight shouldBe true
    RuntimeConfigLoader
      .runtime(source("llm4s.agent.runtime.watch.pollInterval = 0 seconds"), store)
      .left
      .value
      .message should include("llm4s.agent.runtime.watch")
  }
}
