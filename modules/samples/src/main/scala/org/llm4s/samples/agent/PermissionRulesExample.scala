package org.llm4s.samples.agent

import org.llm4s.agent.graph.middleware.{ PermissionMiddleware, PermissionRule }
import org.llm4s.agent.graph.tool.{
  AgentTool,
  AgentToolSpec,
  Operation,
  ToolContent,
  ToolOutcome,
  ToolResource,
  ToolSet
}
import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, ToolCall, ToolMessage }
import org.llm4s.samples.cookbook.ScriptedClient
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.collection.concurrent.TrieMap

/**
 * Shows allow, deny and ask permission rules with an in-memory file tool and a scripted model.
 *
 * Run with no API key or network access:
 * `sbt "samples/runMain org.llm4s.samples.agent.PermissionRulesExample"`
 */
object PermissionRulesExample {
  final case class WriteFileArgs(path: String, content: String) derives ReadWriter

  final case class PermissionRulesResult(
    decisions: Vector[String],
    deniedResult: String,
    files: Map[String, String],
    finalAnswer: String
  )

  private val SourcePath = "/src/A.scala"
  private val DeniedPath = "/etc/passwd"
  private val DeployPath = "/deploy/app.yaml"

  private val rules = Vector(
    PermissionRule.deny(Operation.Write, "/etc/**"),
    PermissionRule.ask(Operation.Write, "/deploy/**"),
    PermissionRule.allow(Operation.Write, "/src/**"),
    PermissionRule.allow(Operation.Invoke, "*")
  )

  private def writeFile(files: TrieMap[String, String]): AgentTool[WriteFileArgs] = {
    val spec = AgentToolSpec[WriteFileArgs](
      "write_file",
      "Writes text to an in-memory file map",
      Schema
        .`object`[WriteFileArgs]("A file write")
        .withRequiredField("path", Schema.string("The absolute path to write"))
        .withRequiredField("content", Schema.string("The file contents"))
    ).withResources(args => Vector(ToolResource(Operation.Write, args.path)))

    AgentTool(spec) { (args, _) =>
      files.put(args.path, args.content)
      ToolOutcome.Success(ToolContent.Json(ujson.Obj("path" -> args.path, "written" -> true)))
    }
  }

  private def scriptedClient: ScriptedClient = new ScriptedClient((_, callNumber) =>
    Right(
      if (callNumber == 1)
        AssistantMessage(
          None,
          Seq(
            ToolCall("write-source", "write_file", ujson.Obj("path" -> SourcePath, "content" -> "object A")),
            ToolCall("write-denied", "write_file", ujson.Obj("path" -> DeniedPath, "content" -> "not allowed")),
            ToolCall("write-deploy", "write_file", ujson.Obj("path" -> DeployPath, "content" -> "app: demo"))
          )
        )
      else AssistantMessage("The source and deployment files were written; the /etc write was denied.")
    )
  )

  def run(): Result[PermissionRulesResult] = {
    val files  = TrieMap.empty[String, String]
    val client = scriptedClient

    for {
      tools <- ToolSet.of(writeFile(files))
      agent <- Agent
        .builder("permission-rules-example", client)
        .withTools(tools)
        .withMiddleware(new PermissionMiddleware(rules))
        .build()
      parked <- agent.run("Write the source, system and deployment files.")
      approval <- parked.status match {
        case AgentStatus.Suspended(Vector((id, _)), Vector(), Vector(), Vector(), Vector()) => Right(id)
        case other => Left(ValidationError("permission rules example", s"expected one approval, got $other"))
      }
      completed <- agent.resume(parked.threadId, Map(parked.approve(approval)))
      answer <- completed.answer.toRight(
        ValidationError("permission rules example", s"expected a final answer, got ${completed.status}")
      )
      toolResults = client.calls.lastOption.toVector.flatMap { case (conversation, _) =>
        conversation.messages.collect { case message: ToolMessage => message.content }
      }
      denied <- toolResults
        .find(_.contains(s"Denied: write $DeniedPath"))
        .toRight(ValidationError("permission rules example", "the model did not receive the denied tool result"))
      allowedWriteObserved  = files.contains(SourcePath) && toolResults.exists(_.contains(SourcePath))
      approvedWriteObserved = files.contains(DeployPath) && toolResults.exists(_.contains(DeployPath))
    } yield PermissionRulesResult(
      decisions = Vector(
        Option.when(allowedWriteObserved)(s"allowed write $SourcePath"),
        Option.when(!files.contains(DeniedPath) && denied.nonEmpty)(s"denied write $DeniedPath"),
        Option.when(approvedWriteObserved)(s"asked write $DeployPath -> approved")
      ).flatten,
      deniedResult = denied,
      files = files.toMap,
      finalAnswer = answer
    )
  }

  def main(args: Array[String]): Unit =
    run() match {
      case Right(result) =>
        result.decisions.foreach(println)
        println(s"model saw: ${result.deniedResult}")
        println(s"files: ${result.files.toSeq.sortBy(_._1).mkString(", ")}")
        println(s"final answer: ${result.finalAnswer}")
      case Left(error) =>
        println(s"Permission rules example failed: ${error.formatted}")
        sys.exit(1)
    }
}
