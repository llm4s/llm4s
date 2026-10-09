package org.llm4s.javaapi

import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolContext, ToolOutcome, ToolSet }
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, ToolCall }
import org.llm4s.toolapi.Schema
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import StreamFixtures.*

/**
 * Agents whose turns suspend, for the pending-interrupt specs: `deploy` needs approval (an
 * `ApprovalMiddleware` for it alone, giving the reason `deploying <text>`) and records each run in
 * `ran`; `confirm` asks `really <text>?` and answers with the reply's `ok`.
 */
private[javaapi] object SuspensionFixtures {

  final case class Text(text: String) derives ReadWriter
  final case class Confirm(prompt: String) derives ReadWriter
  final case class Confirmed(ok: Boolean) derives ReadWriter

  private def spec(name: String): AgentToolSpec[Text] =
    AgentToolSpec[Text](
      name,
      s"The $name tool",
      Schema.`object`[Text](name).withRequiredField("text", Schema.string("Text"))
    )

  private val confirm = new AgentTool.Asking[Text, Confirm, Confirmed](spec("confirm")) {
    def execute(args: Text, context: ToolContext): ToolOutcome = ask(Confirm(s"really ${args.text}?"))
    def resume(args: Text, question: Confirm, answer: Confirmed, context: ToolContext): ToolOutcome =
      ToolOutcome.Success(ujson.Str(s"${question.prompt} ${answer.ok}"))
  }

  def call(id: String, name: String, text: String): ToolCall = ToolCall(id, name, ujson.Obj("text" -> text))

  def calling(calls: ToolCall*): Completion =
    Completion("turn", 0L, "", "m", AssistantMessage(None, calls), calls.toList)

  /** A model answering each call with the next of `replies`, then `done`. */
  def scripted(replies: Either[LLMError, Completion]*): Scripted = {
    val next    = new AtomicInteger(0)
    def reply() = replies.lift(next.getAndIncrement()).getOrElse(Right(completion("done")))
    new Scripted(_ => reply(), () => reply())
  }

  /** An agent over `client` with `deploy` and `confirm`, `deploy` recording its runs in `ran`. */
  def agentOver(client: Scripted, ran: CopyOnWriteArrayList[String] = new CopyOnWriteArrayList()): JAgent = {
    val deploy = AgentTool(spec("deploy")) { (args, _) =>
      ran.add(args.text)
      ToolOutcome.Success(ujson.Str(s"deployed ${args.text}"))
    }
    val tools = ToolSet.of(deploy, confirm).fold(e => throw new AssertionError(e.message), identity)
    jAgentOf(client)(
      _.withTools(tools).withMiddleware(
        new ApprovalMiddleware(r => Option.when(r.spec.name == "deploy")(s"deploying ${r.call.arguments("text").str}"))
      )
    )
  }
}
