package org.llm4s.mcp

import cats.data.ValidatedNel
import cats.syntax.validated._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class MCPToolRegistryHealthCheckSpec extends AnyFlatSpec with Matchers {

  private def mkConfig(name: String): MCPServerConfig =
    MCPServerConfig.stdio(name, Seq("echo", "noop"))

  final private class TestRegistry(
    servers: Seq[MCPServerConfig],
    responses: Map[String, ValidatedNel[String, MCPServerConfig]]
  ) extends MCPToolRegistry(servers, Seq.empty, 1.minute, initializeOnStartup = false) {

    override private[mcp] def createAndInitializeClient(
      server: MCPServerConfig
    ): ValidatedNel[String, MCPServerConfig] =
      responses.getOrElse(server.name, fail(s"Unexpected server ${server.name}"))
  }

  "healthCheck" should "mark all servers healthy when initialization succeeds" in {
    val serverA = mkConfig("alpha")
    val serverB = mkConfig("beta")

    val registry = new TestRegistry(
      servers = Seq(serverA, serverB),
      responses = Map(
        "alpha" -> serverA.validNel[String],
        "beta"  -> serverB.validNel[String]
      )
    )

    registry.healthCheck() shouldBe Map(
      "alpha" -> true,
      "beta"  -> true
    )
  }

  it should "flag servers as unhealthy when initialization fails" in {
    val serverA = mkConfig("alpha")
    val serverB = mkConfig("beta")

    val registry = new TestRegistry(
      servers = Seq(serverA, serverB),
      responses = Map(
        "alpha" -> serverA.validNel[String],
        "beta"  -> "boom".invalidNel[MCPServerConfig]
      )
    )

    registry.healthCheck() shouldBe Map(
      "alpha" -> true,
      "beta"  -> false
    )
  }
}
