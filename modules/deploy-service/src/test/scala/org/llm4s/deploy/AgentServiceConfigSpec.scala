package org.llm4s.deploy

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*

/** [[AgentServiceConfig]] from `llm4s.deploy-service` (design 4.60). */
class AgentServiceConfigSpec extends AnyFlatSpec with Matchers {

  private val defaults =
    """agent { id = "assistant", model = "demo", toolDelay = 2 seconds }
      |events { wait = 15 seconds, pageSize = 100 }
      |metrics { enabled = true }""".stripMargin

  private def source(overrides: String = ""): ConfigSource =
    ConfigSource.string(s"llm4s.deploy-service { $defaults\n$overrides }")

  "AgentServiceConfig" should "read reference.conf's defaults" in {
    AgentServiceConfig.load() shouldBe Right(AgentServiceConfig.default)
    AgentServiceConfig.load(source()) shouldBe Right(AgentServiceConfig.default)
  }

  it should "read every setting" in {
    val loaded = AgentServiceConfig.load(
      source(
        """agent { id = "helper", model = "PROVIDER", toolDelay = 0 seconds }
          |events { wait = 1 second, pageSize = 7 }
          |metrics.enabled = false""".stripMargin
      )
    )
    loaded.map(c => (c.agentId.value, c.model, c.toolDelay, c.eventsWait, c.pageSize, c.metricsEnabled)) shouldBe
      Right(("helper", AgentModel.Provider, Duration.Zero, 1.second, 7, false))
  }

  it should "refuse an unknown model, a negative delay, a zero wait, a page size out of range and a bad agent id" in {
    Seq(
      """agent.model = "gpt""""           -> "agent.model",
      "agent.toolDelay = -1 second"       -> "agent.toolDelay",
      "events.wait = 0 seconds"           -> "events.wait",
      "events.pageSize = 0"               -> "events.pageSize",
      "events.pageSize = 1001"            -> "events.pageSize",
      """agent.id = """""                 -> "agent.id",
      """metrics.enabled = "sometimes"""" -> "metrics.enabled",
      """events.wait = "soon""""          -> "events.wait"
    ).foreach { (setting, key) =>
      withClue(setting) {
        AgentServiceConfig.load(source(setting)).left.map(_.message) match {
          case Left(message) => message should include(s"llm4s.deploy-service.$key")
          case Right(config) => fail(s"accepted $config")
        }
      }
    }
  }

  it should "refuse a configuration with no llm4s.deploy-service block" in {
    AgentServiceConfig.load(ConfigSource.string("other = 1")).isLeft shouldBe true
  }
}
