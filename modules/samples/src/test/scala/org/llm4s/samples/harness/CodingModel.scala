package org.llm4s.samples.harness

import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

/**
 * The coding example's scripted model. As [[ResearchModel]], it answers from the last message of the conversation it
 * is sent, so the reply to a replayed call is the reply the lost call got; every step's tool calls have ids of their
 * own.
 */
object CodingModel {

  /** The check the agent runs, as the workspace's `AGENTS.md` names it. */
  val CheckCommand: String = "grep -c Hello src/Greeter.scala"

  /** A query whose answer puts a credential in a file and the answer: the output guardrail blocks it. */
  val LeakQuery: String = "Put the deploy key in the source so CI can use it."

  val FixedSource: String = CodingWorkspace.BrokenSource.replace("Helo,", "Hello,")

  val TestSource: String =
    """object GreeterSpec {
      |  assert(Greeter.greeting("world") == "Hello, world")
      |}
      |""".stripMargin

  val Answer: String = "Fixed the greeting in /src/Greeter.scala, added /src/GreeterSpec.scala, and the check passed."

  private val usage = TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120)

  private def call(id: String, name: String, args: (String, ujson.Value)*): ToolCall =
    ToolCall(id, name, ujson.Obj.from(args))

  private def todos(statuses: (String, String)*): ujson.Value =
    ujson.Arr.from(statuses.map((content, status) => ujson.Obj("content" -> content, "status" -> status)))

  private def calls(cs: ToolCall*): Reply = Reply.toolCalls(cs*).withUsage(usage)

  /** The reply to `conversation`, or `None` for a conversation the script does not expect. */
  def reply(conversation: Conversation): Option[Reply] =
    conversation.messages.lastOption.flatMap {
      case u: UserMessage if u.content == CodingWorkspace.Query =>
        Some(
          calls(
            call(
              "plan",
              "write_todos",
              "todos" -> todos(
                "Read the source"  -> "in_progress",
                "Fix the greeting" -> "pending",
                "Add a test"       -> "pending",
                "Run the check"    -> "pending"
              )
            )
          )
        )
      case t: ToolMessage if t.toolCallId == "plan" =>
        Some(calls(call("read", "read_file", "path" -> CodingWorkspace.SourcePath)))
      case t: ToolMessage if t.toolCallId == "read" =>
        // one message, three calls: the fix and the test, and a write outside /src the rules deny
        Some(
          calls(
            call(
              "fix",
              "edit_file",
              "path"       -> CodingWorkspace.SourcePath,
              "old_string" -> "Helo,",
              "new_string" -> "Hello,"
            ),
            call("test", "write_file", "path"   -> "/src/GreeterSpec.scala", "content" -> TestSource),
            call("readme", "write_file", "path" -> "/README.md", "content"             -> "Fixed.")
          )
        )
      case t: ToolMessage if t.toolCallId == "readme" =>
        Some(calls(call("check", "execute", "command" -> CheckCommand)))
      case t: ToolMessage if t.toolCallId == "check" =>
        Some(
          calls(
            call(
              "done",
              "write_todos",
              "todos" -> todos(
                "Read the source"  -> "done",
                "Fix the greeting" -> "done",
                "Add a test"       -> "done",
                "Run the check"    -> "done"
              )
            )
          )
        )
      case t: ToolMessage if t.toolCallId == "done" => Some(Reply.text(Answer).withUsage(usage))
      case u: UserMessage if u.content == LeakQuery =>
        Some(calls(call("leak-read", "read_file", "path" -> CodingWorkspace.SourcePath)))
      case t: ToolMessage if t.toolCallId == "leak-read" =>
        Some(
          calls(
            call("leak-src", "write_file", "path" -> "/src/Deploy.scala", "content" -> "object Deploy"),
            call("leak-note", "write_file", "path" -> s"${CodingWorkspace.NotesMount}/key.md", "content" -> "the key"),
            call("leak-plan", "write_todos", "todos" -> todos("Add the deploy key" -> "in_progress"))
          )
        )
      case t: ToolMessage if t.toolCallId == "leak-plan" =>
        Some(Reply.text("Added AKIAIOSFODNN7EXAMPLE to /src/Deploy.scala.").withUsage(usage))
      case _ => None
    }

  /** A fresh client over the script: one per "process". */
  def client(): ScriptedLLMClient =
    ScriptedLLMClient.respondingTo(Function.unlift(reply)).withChunkSize(16)
}
