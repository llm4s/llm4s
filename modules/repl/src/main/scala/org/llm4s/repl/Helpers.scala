package org.llm4s.repl

import org.llm4s.annotation.Experimental
import org.llm4s.schema.SchemaOf
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result

import scala.annotation.tailrec

// The helpers of `import org.llm4s.repl.*`, for the Scala CLI REPL, Metals worksheets and scripts. Each takes the
// `Session` from a context parameter. They are not for production code: they print to the console and keep the
// conversation in memory.

/**
 * Asks the model `prompt`, after the conversation so far, and streams the answer to the session's output as it
 * arrives. The question and answer are added to the [[history]].
 *
 * With `tools`, each tool call the model makes is run, printed (`-> name(arguments)`, then `<- result`) and sent back,
 * until the model answers in text.
 *
 * A failed call prints `error: <kind>: <message>`, with the troubleshooting hint llm4s attaches, and returns
 * [[Outcome.Failed]]; nothing is thrown. [[strict.ask]] returns a `Result` instead.
 *
 * @param prompt the question
 * @param tools  the tools the model may call; none by default
 * @return the answer's text, or the error
 */
def ask(prompt: String, tools: ToolRegistry = ToolRegistry.empty)(using session: Session): Outcome[String] =
  Engine.report(Engine.ask(prompt, tools, session), session)

/**
 * Asks the model for an `A` read from `text`, with the JSON schema derived from `A` (`completeStructuredOf`):
 *
 * {{{
 * case class Invoice(vendor: String, amount: Double, currency: String) derives SchemaOf, ReadWriter
 * val invoice = extract[Invoice]("ACME, 12.50 EUR, two widgets")
 * }}}
 *
 * The call is not added to the [[history]]. A failure is printed and returned as [[Outcome.Failed]].
 *
 * @param text the text to read the value from
 * @tparam A a case class or sum type that `derives SchemaOf` and has a uPickle `Reader`
 */
def extract[A](
  text: String
)(using session: Session, schema: SchemaOf[A], reader: upickle.default.Reader[A]): Outcome[A] =
  Engine.report(Engine.extract[A](text, session), session)

/**
 * A small read-eval loop over the session: each line read is asked with [[ask]], so the conversation carries on
 * from one line to the next. `:q` (or the end of the input) leaves, `:history` prints the conversation so far and
 * `:reset` forgets it.
 */
def chat()(using session: Session): Unit = {
  val out = session.out
  val in  = session.in
  out.println(s"Chatting with ${session.description}. :q leaves, :history shows the conversation, :reset forgets it.")

  @tailrec
  def loop(): Unit = {
    out.print("you> ")
    out.flush()
    Option(in.readLine()).map(_.trim) match {
      case None | Some(":q") => out.println()
      case Some("")          => loop()
      case Some(":history") =>
        history.show
        loop()
      case Some(":reset") =>
        session.reset()
        out.println("(the conversation is forgotten)")
        loop()
      case Some(line) =>
        out.print("llm> ")
        out.flush()
        ask(line)
        loop()
    }
  }

  loop()
}

/** The conversation so far: what [[ask]] and [[chat]] sent and received. `history.show` prints it. */
def history(using session: Session): History = new History(session.conversationSoFar, session.out)

/** The model, tokens, latency and estimated cost of the session's last successful model call, if there was one. */
def lastUsage(using session: Session): Option[CallStats] = session.lastCall

/**
 * The helpers for scripts: the same calls, returning `Result` so that a script can stop on the first failure with a
 * `for` comprehension. They print nothing on failure; [[strict.ask]] still streams the answer.
 */
@Experimental
object strict {

  /** [[org.llm4s.repl.ask]], returning `Left(error)` instead of printing it. */
  def ask(prompt: String, tools: ToolRegistry = ToolRegistry.empty)(using session: Session): Result[String] =
    Engine.ask(prompt, tools, session)

  /** [[org.llm4s.repl.extract]], returning `Left(error)` instead of printing it. */
  def extract[A](
    text: String
  )(using session: Session, schema: SchemaOf[A], reader: upickle.default.Reader[A]): Result[A] =
    Engine.extract[A](text, session)
}
