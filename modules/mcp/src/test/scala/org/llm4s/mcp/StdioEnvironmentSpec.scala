package org.llm4s.mcp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A stdio MCP server starts with an allowlisted environment plus what its config declares, never the JVM's whole
 * environment (#3070): the test JVM carries `LLM4S_MCP_TEST_SECRET` (`Test / envVars` in `build.sbt`), standing in
 * for an `OPENAI_API_KEY`, and the server must not see it unless asked to inherit everything.
 */
class StdioEnvironmentSpec extends AnyFlatSpec with Matchers {

  private val reply =
    """{"jsonrpc":"2.0","id":"$id","result":{"secret":$env{LLM4S_MCP_TEST_SECRET},"declared":$env{LLM4S_MCP_DECLARED},"path":$env{PATH},"cwd":$cwd}}"""

  private def childSees(
    env: Map[String, String] = Map.empty,
    workingDirectory: Option[Path] = None,
    inheritEnvironment: Boolean = false
  ): ujson.Value = {
    val transport = new StdioTransportImpl(
      StdioTestServers.echo(reply, "--ready"),
      "env-server",
      10.seconds,
      env,
      workingDirectory,
      inheritEnvironment
    )
    try {
      val response = transport.sendRequest(JsonRpcRequest("2.0", "1", "test/env", None))
      response.left.map(_.message) shouldBe a[Right[?, ?]]
      response.toOption.flatMap(_.result).getOrElse(ujson.Null)
    } finally transport.close()
  }

  "A stdio MCP server" should "not inherit a variable outside the allowlist, but keep PATH" in {
    val seen = childSees()
    seen("secret") shouldBe ujson.Null
    seen("path") should not be ujson.Null
  }

  it should "receive the variables its config declares" in {
    childSees(env = Map("LLM4S_MCP_DECLARED" -> "declared-value"))("declared") shouldBe ujson.Str("declared-value")
  }

  it should "inherit the JVM's whole environment only when asked" in {
    val seen = childSees(env = Map("LLM4S_MCP_DECLARED" -> "d"), inheritEnvironment = true)
    seen("secret") shouldBe ujson.Str("inherited-secret")
    seen("declared") shouldBe ujson.Str("d")
  }

  it should "start in the configured working directory" in {
    val dir = Files.createTempDirectory("llm4s-mcp-cwd")
    try Paths.get(childSees(workingDirectory = Some(dir))("cwd").str).toRealPath() shouldBe dir.toRealPath()
    finally Files.deleteIfExists(dir): Unit
  }

  "StdioEnvironment" should "keep allowlisted names in any case, drop the rest, then add the declared ones" in {
    val child = new java.util.HashMap[String, String](
      Map(
        "Path"                  -> "p",
        "HOME"                  -> "h",
        "SystemRoot"            -> "s",
        "OPENAI_API_KEY"        -> "sk",
        "AWS_SECRET_ACCESS_KEY" -> "a"
      ).asJava
    )
    StdioEnvironment.prepare(child, Map("ONE_KEY" -> "k", "HOME" -> "override"), inherit = false)
    child.asScala.toMap shouldBe Map("Path" -> "p", "HOME" -> "override", "SystemRoot" -> "s", "ONE_KEY" -> "k")
  }

  it should "keep everything when inheriting" in {
    val child = new java.util.HashMap[String, String](Map("OPENAI_API_KEY" -> "sk").asJava)
    StdioEnvironment.prepare(child, Map("ONE_KEY" -> "k"), inherit = true)
    child.asScala.toMap shouldBe Map("OPENAI_API_KEY" -> "sk", "ONE_KEY" -> "k")
  }

  "MCPServerConfig.stdio" should "carry env, working directory and inheritance to its transport" in {
    val dir    = Paths.get("work")
    val config = MCPServerConfig.stdio("s", Seq("srv"), env = Map("K" -> "v"), workingDirectory = Some(dir))
    config.transport shouldBe StdioTransport(Seq("srv"), "s", Map("K" -> "v"), Some(dir), inheritEnvironment = false)
  }
}
