package org.llm4s.repl

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.{ Conversation, Message, SystemMessage, ToolMessage, UserMessage }
import org.llm4s.schema.{ completeStructuredOf, SchemaOf }
import org.llm4s.toolapi.{ ToolCallRequest, ToolRegistry }
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec

/** The work behind the helpers, returning `Result`; the helpers decide how to print and what to return. */
private[repl] object Engine {

  /** The system message `extract` sends when the session has no system prompt of its own. */
  val ExtractPrompt: String =
    "Extract the requested fields from the user's text. Answer with JSON that matches the schema."

  /**
   * Sends `prompt` after the conversation so far, streaming the answer to the session's output. When the model calls
   * tools from `tools`, each call and its result are printed and sent back, up to the session's `maxToolSteps` model
   * calls. The question, the answer and the tool messages are added to the history only when the whole turn succeeds.
   */
  def ask(prompt: String, tools: ToolRegistry, session: Session): Result[String] =
    session.client.flatMap { client =>
      val out     = session.out
      val prior   = session.systemPrompt.map(SystemMessage(_)).toVector ++ session.conversationSoFar
      val options = if (tools.tools.isEmpty) session.options else session.options.withTools(tools.tools)

      @tailrec
      def loop(turn: Vector[Message], step: Int): Result[(String, Vector[Message])] = {
        val printed = new AtomicBoolean(false)
        val reply = client.streamComplete(
          Conversation(prior ++ turn),
          options,
          chunk =>
            chunk.content.filter(_.nonEmpty).foreach { text =>
              out.print(text)
              out.flush()
              printed.set(true)
            }
        )
        reply match {
          case Left(error) =>
            if (printed.get) out.println()
            Left(error)
          case Right(completion) =>
            if (!printed.get && completion.content.nonEmpty) out.print(completion.content)
            if (printed.get || completion.content.nonEmpty) out.println()
            val answered = turn :+ completion.message
            if (completion.toolCalls.isEmpty || tools.tools.isEmpty) {
              // a tool call with no tools to run it is shown, not run
              completion.toolCalls.foreach(call => out.println(Render.toolCall(call)))
              Right((completion.content, answered))
            } else if (step >= session.maxToolSteps) {
              Left(
                LLMError.processingFailed(
                  "ask",
                  s"the model was still calling tools after ${session.maxToolSteps} model calls; " +
                    "Session.withMaxToolSteps raises the limit"
                )
              )
            } else {
              val results = completion.toolCalls.map { call =>
                out.println(Render.toolCall(call))
                val content = tools.execute(ToolCallRequest(call.name, call.arguments)) match {
                  case Right(json) =>
                    val rendered = json.render()
                    out.println(Render.toolResult(rendered))
                    rendered
                  case Left(failure) =>
                    out.println(Render.toolFailure(failure.getFormattedMessage))
                    ujson.Obj("error" -> ujson.Str(failure.getFormattedMessage)).render()
                }
                ToolMessage(content, call.id)
              }
              loop(answered ++ results, step + 1)
            }
        }
      }

      loop(Vector(UserMessage(prompt)), 1).map { case (answer, turn) =>
        session.append(turn)
        answer
      }
    }

  /**
   * Asks the model for an `A` read from `text`, with the schema derived from `A`. The call is not added to the
   * history, since its JSON answer is no use to a later question.
   */
  def extract[A](text: String, session: Session)(using SchemaOf[A], upickle.default.Reader[A]): Result[A] =
    session.client.flatMap { client =>
      val system = SystemMessage(session.systemPrompt.getOrElse(ExtractPrompt))
      client.completeStructuredOf[A](Conversation(Seq(system, UserMessage(text))), session.options)
    }

  /** Prints a failure and wraps the result for a REPL. */
  def report[A](result: Result[A], session: Session): Outcome[A] =
    result match {
      case Right(value) => Outcome.Done(value)
      case Left(error) =>
        session.out.println(Render.error(error))
        Outcome.Failed(error)
    }
}
