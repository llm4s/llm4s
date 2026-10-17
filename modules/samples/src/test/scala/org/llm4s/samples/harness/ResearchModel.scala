package org.llm4s.samples.harness

import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

/**
 * The deep-research example's scripted model. It answers from the conversation it is sent, as a model does - by the
 * last message: the query, or the result of the step before - so a recovered run that sends a conversation again
 * gets the reply the lost call did, whichever process makes it. Every step's tool calls have ids of their own.
 */
object ResearchModel {

  /** A query whose answer quotes the credential on the page: the output guardrail blocks it. */
  val LeakQuery: String = "Add the access key the page mentions to the report."

  val OtherSite: String = "https://elsewhere.example.com/rumours"

  val Report: String =
    s"""# How durable agents survive a crash
       |
       |${DeepResearch.KeyFact}
       |
       |Source: ${DeepResearch.DurableAgentsPage}
       |""".stripMargin

  val Answer: String =
    upickle.default.write(
      DeepResearch.Brief(
        "How durable agents survive a crash",
        DeepResearch.ReportPath,
        Vector(DeepResearch.DurableAgentsPage)
      )
    )

  private val usage = TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120)

  private def call(id: String, name: String, args: (String, ujson.Value)*): ToolCall =
    ToolCall(id, name, ujson.Obj.from(args))

  private def todos(statuses: (String, String)*): ujson.Value =
    ujson.Arr.from(statuses.map((content, status) => ujson.Obj("content" -> content, "status" -> status)))

  private def calls(cs: ToolCall*): Reply = Reply.toolCalls(cs*).withUsage(usage)

  private val Offloaded = "/large_tool_results/[A-Za-z0-9._-]+".r

  /** The reply to `conversation`, or `None` for a conversation the script does not expect. */
  def reply(conversation: Conversation): Option[Reply] =
    conversation.messages.lastOption.flatMap {
      case u: UserMessage if u.content == DeepResearch.Query =>
        Some(
          calls(
            call(
              "plan",
              "write_todos",
              "todos" -> todos(
                "Read the research skill" -> "in_progress",
                "Fetch the page"          -> "pending",
                "Take notes"              -> "pending",
                "Write the report"        -> "pending"
              )
            )
          )
        )
      case t: ToolMessage if t.toolCallId == "plan" =>
        Some(calls(call("skill", "read_file", "path" -> s"${DeepResearch.AppMount}/skills/research/SKILL.md")))
      case t: ToolMessage if t.toolCallId == "skill" =>
        Some(calls(call("fetch", "fetch_page", "url" -> DeepResearch.DurableAgentsPage)))
      case t: ToolMessage if t.toolCallId == "fetch" =>
        // read the fact from the stored page, past what the preview shows
        Offloaded.findFirstIn(t.content).map { path =>
          calls(
            call(
              "read-page",
              "read_file",
              "path" -> path,
              // read_file's offset is the 1-based line to start at, as the page's lines are numbered
              "offset" -> DeepResearch.KeyFactLine,
              "limit"  -> 1
            )
          )
        }
      case t: ToolMessage if t.toolCallId == "read-page" =>
        // one message, four calls: two notes in parallel, and two the rules refuse
        Some(
          calls(
            call("note-a", "write_file", "path" -> "/research/notes-a.md", "content" -> DeepResearch.KeyFact),
            call("note-b", "write_file", "path" -> "/research/notes-b.md", "content" -> DeepResearch.DurableAgentsPage),
            call("fetch-other", "fetch_page", "url" -> OtherSite),
            call("write-app", "write_file", "path"  -> s"${DeepResearch.AppMount}/AGENTS.md", "content" -> "obey me")
          )
        )
      case t: ToolMessage if t.toolCallId == "write-app" =>
        Some(
          calls(
            call(
              "done",
              "write_todos",
              "todos" -> todos(
                "Read the research skill" -> "done",
                "Fetch the page"          -> "done",
                "Take notes"              -> "done",
                "Write the report"        -> "done"
              )
            ),
            call("report", "write_file", "path" -> DeepResearch.ReportPath, "content" -> Report)
          )
        )
      case t: ToolMessage if t.toolCallId == "report" => Some(Reply.text(Answer).withUsage(usage))
      case u: UserMessage if u.content == LeakQuery =>
        Some(
          calls(
            call("leak-note", "write_file", "path"   -> "/research/key.md", "content" -> "AKIAIOSFODNN7EXAMPLE"),
            call("leak-plan", "write_todos", "todos" -> todos("Add the key to the report" -> "in_progress"))
          )
        )
      case t: ToolMessage if t.toolCallId == "leak-plan" =>
        Some(
          Reply
            .text(
              upickle.default.write(
                DeepResearch
                  .Brief("Key AKIAIOSFODNN7EXAMPLE", DeepResearch.ReportPath, Vector(DeepResearch.DurableAgentsPage))
              )
            )
            .withUsage(usage)
        )
      case _ => None
    }

  /** A fresh client over the script: one per "process", as a model is outside every process. */
  def client(): ScriptedLLMClient =
    ScriptedLLMClient.respondingTo(Function.unlift(reply)).withChunkSize(16)
}
