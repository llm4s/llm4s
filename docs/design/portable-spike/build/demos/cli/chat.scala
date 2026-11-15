//> using scala 3.7.1
//> using platform native
//> using nativeVersion 0.5.12
//> using dep org.llm4s::llm4s-portable-spike::0.0.0-spike
//> using dep com.softwaremill.sttp.client4::core::4.0.27

// A Scala Native command-line chat over llm4s-core's own types and the portable OpenAI-compatible client, built by
// Scala CLI (or by `sbt cliDemo/nativeLink`). Steps to build and run it: docs/design/portable-spike.md.
package org.llm4s.portable.demo

import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.portable.PortableChatClient
import sttp.client4.curl.CurlBackend

object Chat {

  private val usage = "usage: llm4s-chat [--base-url URL] [--model NAME] PROMPT..."

  def main(args: Array[String]): Unit = {
    def option(name: String, default: String): String =
      args.sliding(2).collectFirst { case Array(`name`, v) => v }.getOrElse(default)
    val optionNames = Set("--base-url", "--model")
    val prompt = args.toList
      .foldLeft((List.empty[String], false)) { case ((words, skip), arg) =>
        if (skip) (words, false) else if (optionNames(arg)) (words, true) else (words :+ arg, false)
      }
      ._1
      .mkString(" ")
    if (prompt.isEmpty) {
      System.err.println(usage)
      sys.exit(2)
    }

    val client = new PortableChatClient(
      CurlBackend(),
      baseUrl = option("--base-url", "http://localhost:11434/v1"),
      model = option("--model", "llama3")
    )
    val conversation = Conversation(Seq(SystemMessage("You are concise."), UserMessage(prompt)))
    client.streamComplete(conversation, CompletionOptions().withMaxTokens(256)) { chunk =>
      chunk.content.foreach(text => print(text))
    } match {
      case Right(completion) =>
        println()
        completion.usage.foreach(u =>
          System.err.println(s"[${u.promptTokens} prompt + ${u.completionTokens} completion tokens]")
        )
      case Left(error) =>
        System.err.println(s"error: ${error.message}")
        sys.exit(1)
    }
  }
}
