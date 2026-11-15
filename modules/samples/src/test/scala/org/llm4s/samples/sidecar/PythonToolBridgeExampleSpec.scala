package org.llm4s.samples.sidecar

import org.llm4s.agent.AgentStatus
import org.llm4s.llmconnect.model.ToolMessage
import org.llm4s.mcp.MCPToolRegistry
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.sys.process._
import scala.util.Try

/**
 * The bridge `docs/guide/replacing-a-python-sidecar.md` teaches, run for real: `sentiment_tool.py` started by
 * `python3` over stdio, its tool listed through MCP, and an agent turn in which a scripted model calls it and receives
 * what Python computed.
 *
 * It needs a `python3` on the `PATH` with the MCP Python SDK installed
 * (`pip install -r modules/samples/src/main/python/requirements.txt`). CI's ubuntu test job installs it in a virtual
 * environment and puts that first on the `PATH`; anywhere it is missing, the tests are cancelled with a message saying
 * so, rather than failing.
 */
class PythonToolBridgeExampleSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val python = "python3"
  private val review = "The new release is fast and reliable, and the support team was helpful."

  /** Why the bridge cannot run here, if it cannot: no script, no interpreter, or no MCP SDK for it. */
  private lazy val unavailable: Option[String] =
    PythonToolBridgeExample.script() match {
      case None => Some("sentiment_tool.py not found from the working directory")
      case Some(_) =>
        val probe = Try(Seq(python, "-c", "import mcp.server.mcpserver").!(ProcessLogger(_ => (), _ => ())))
        if (probe.toOption.contains(0)) None
        else
          Some(
            s"`$python` with the MCP Python SDK (mcp 2.x) is not available: " +
              "pip install -r modules/samples/src/main/python/requirements.txt to run these tests"
          )
    }

  private lazy val registry: MCPToolRegistry =
    new MCPToolRegistry(
      Seq(PythonToolBridgeExample.serverConfig(python, PythonToolBridgeExample.script().get))
    )

  private def requirePython(): Unit = unavailable.foreach(reason => cancel(reason))

  override def afterAll(): Unit =
    if (unavailable.isEmpty) registry.close()

  "The Python MCP server" should "offer analyze_sentiment, with the schema from its type hints" in {
    requirePython()
    val tool = registry.tools.find(_.name == PythonToolBridgeExample.ToolName)
    tool shouldBe defined
    tool.get.description should include("sentiment")
    val parameters = tool.get.toOpenAITool()("function")("parameters")
    parameters("properties").obj.keySet shouldBe Set("text")
    parameters("required").arr.map(_.str) shouldBe Seq("text")
  }

  it should "run the Python function when the tool is called" in {
    requirePython()
    val result = registry.execute(
      org.llm4s.toolapi.ToolCallRequest(PythonToolBridgeExample.ToolName, ujson.Obj("text" -> "slow and broken"))
    )
    result.map(_("label").str) shouldBe Right("negative")
  }

  "An agent turn" should "call the Python tool and receive its result" in {
    requirePython()
    val client = PythonToolBridgeExample.scriptedModel(review)

    val result = PythonToolBridgeExample.run(registry, client, review)

    result.map(_.status.isInstanceOf[AgentStatus.Completed]) shouldBe Right(true)
    client.callCount shouldBe 2
    client.calls.head.options.tools.map(_.name) should contain(PythonToolBridgeExample.ToolName)
    val toolReply = client.calls(1).messages.collect { case t: ToolMessage => t.content }
    toolReply should have size 1
    toolReply.head should include("positive")
    result.toOption.flatMap(_.answer).getOrElse("") should include("positive")
  }
}
