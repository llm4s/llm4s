package org.llm4s.samples.sidecar

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, ToolMessage }
import org.llm4s.mcp.{ MCPServerConfig, MCPToolRegistry }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result

import java.nio.file.{ Files, Path, Paths }
import scala.concurrent.duration._
import scala.util.Using

/**
 * A Python-only function as an llm4s agent tool, with no sidecar service and no hand-written HTTP contract.
 *
 * `modules/samples/src/main/python/sentiment_tool.py` wraps a plain Python function in an MCP server with the official
 * MCP Python SDK. This example starts it as a subprocess over stdio ([[MCPServerConfig.stdio]]), lists its tools
 * through an [[MCPToolRegistry]] and hands them to an [[Agent]]. The tool's schema comes from the Python type hints.
 *
 * The model is a `ScriptedLLMClient` from `llm4s-testkit`: it asks for `analyze_sentiment`, then answers with what
 * the Python tool returned, so the example needs no API key. The guide is `docs/guide/replacing-a-python-sidecar.md`.
 *
 * {{{
 * python3 -m pip install -r modules/samples/src/main/python/requirements.txt   # the MCP Python SDK, in a venv
 * sbt "samples/runMain org.llm4s.samples.sidecar.PythonToolBridgeExample"
 * sbt "samples/runMain org.llm4s.samples.sidecar.PythonToolBridgeExample /path/to/venv/bin/python"
 * }}}
 */
object PythonToolBridgeExample {

  /** The Python script, relative to the repository root (where `sbt` runs) or to the samples module. */
  val ScriptCandidates: Seq[Path] = Seq(
    Paths.get("modules", "samples", "src", "main", "python", "sentiment_tool.py"),
    Paths.get("src", "main", "python", "sentiment_tool.py")
  )

  /** The name of the Python tool, as the `@server.tool()` decorator derives it from the function. */
  val ToolName = "analyze_sentiment"

  /** The script, if it is found from the current directory. */
  def script(): Option[Path] = ScriptCandidates.find(p => Files.isRegularFile(p)).map(_.toAbsolutePath)

  /** The MCP server: the Python interpreter running the script, spoken to over its stdin and stdout. */
  def serverConfig(python: String, script: Path): MCPServerConfig =
    MCPServerConfig.stdio(name = "python-sentiment", command = Seq(python, script.toString), timeout = 30.seconds)

  /**
   * The scripted model. On the user's question it calls the Python tool; once the tool's result is in the
   * conversation it answers with that result, so the answer shows what Python computed.
   */
  def scriptedModel(review: String): ScriptedLLMClient =
    ScriptedLLMClient.respondingTo {
      case c if toolResult(c).isDefined =>
        Reply.text(s"The review's sentiment, from the Python tool: ${toolResult(c).getOrElse("")}")
      case _ =>
        Reply.toolCall(ToolName, ujson.write(ujson.Obj("text" -> review)))
    }

  private def toolResult(conversation: Conversation): Option[String] =
    conversation.messages.collect { case t: ToolMessage => t.content }.lastOption

  /** One agent turn with the Python tools: the same code with a real `LLMClient` from `LLMConnect.getClient`. */
  def run(tools: ToolRegistry, client: LLMClient, review: String): Result[AgentResult] =
    for {
      _ <- Either.cond(
        tools.tools.exists(_.name == ToolName),
        (),
        ProcessingError(
          "python-tool-bridge",
          s"the Python MCP server offered no $ToolName tool: is the MCP Python SDK installed for the interpreter used? " +
            "(pip install -r modules/samples/src/main/python/requirements.txt)"
        )
      )
      agent <- Agent
        .builder("review-analyst", client)
        .withTools(tools)
        .withSystemPrompt("Classify customer reviews with the analyze_sentiment tool, then report its result.")
        .build()
      result <- agent.run(s"What is the sentiment of this review? $review")
    } yield result

  def main(args: Array[String]): Unit = {
    val python = args.headOption.getOrElse("python3")
    val review = "The new release is fast and reliable, and the support team was helpful."

    val outcome = for {
      path <- script().toRight(
        ProcessingError("python-tool-bridge", s"sentiment_tool.py not found; run from the repository root")
      )
      result <- Using.resource(new MCPToolRegistry(Seq(serverConfig(python, path)))) { tools =>
        println(s"Tools from Python: ${tools.tools.map(_.name).mkString(", ")}")
        run(tools, scriptedModel(review), review)
      }
    } yield result

    outcome match {
      case Right(result) =>
        println(s"Status: ${result.status}")
        println(result.answer.getOrElse("(no answer)"))
      case Left(error) =>
        System.err.println(s"Failed: ${error.formatted}")
        sys.exit(1)
    }
  }
}
