package org.llm4s.portable.demo

import com.raquo.laminar.api.L._
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.scalajs.dom

import scala.concurrent.ExecutionContext

/**
 * A Laminar page that streams a reply from a local Ollama (or the application's own backend proxy) into the page,
 * using llm4s-core's own `Conversation`, `CompletionOptions`, `StreamedChunk` and `Completion` on Scala.js.
 * Steps to run it: docs/design/portable-spike.md.
 */
object ChatPage {
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  def main(args: Array[String]): Unit = {
    val baseUrl = Var("http://localhost:11434/v1")
    val model   = Var("llama3")
    val prompt  = Var("Why is the sky blue? Answer in two sentences.")
    val reply   = Var("")
    val status  = Var("")
    val busy    = Var(false)

    def send(): Unit = {
      reply.set("")
      status.set("streaming...")
      busy.set(true)
      val conversation = Conversation(Seq(SystemMessage("You are concise."), UserMessage(prompt.now())))
      FetchStreaming
        .streamComplete(baseUrl.now(), model.now(), conversation, CompletionOptions().withMaxTokens(256)) { chunk =>
          chunk.content.foreach(text => reply.update(_ + text))
        }
        .foreach { result =>
          busy.set(false)
          status.set(result.fold(e => s"error: ${e.message}", c => s"done; usage: ${c.usage.fold("n/a")(_.toString)}"))
        }
    }

    def field(name: String, v: Var[String]) =
      p(label(s"$name ", input(size := 50, controlled(value <-- v.signal, onInput.mapToValue --> v))))

    val app = div(
      h1("llm4s on Scala.js: a streamed reply"),
      p("Talks to an OpenAI-compatible endpoint with no API key: a local Ollama, or your own backend proxy."),
      field("Base URL", baseUrl),
      field("Model", model),
      p(textArea(rows := 3, cols := 60, controlled(value <-- prompt.signal, onInput.mapToValue --> prompt))),
      button("Send", disabled <-- busy.signal, onClick --> (_ => send())),
      pre(idAttr := "reply", styleAttr := "white-space: pre-wrap", child.text <-- reply.signal),
      p(idAttr   := "status", child.text <-- status.signal)
    )
    renderOnDomContentLoaded(dom.document.getElementById("app"), app)
  }
}
