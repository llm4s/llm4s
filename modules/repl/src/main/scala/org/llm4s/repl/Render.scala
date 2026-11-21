package org.llm4s.repl

import org.llm4s.error.{ LLMError, TroubleshootingLinks }
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, TokenUsage, ToolCall, ToolMessage }

import java.util.Locale
import scala.concurrent.duration.FiniteDuration

/** How the REPL helpers print values: plain text, no colour codes, the same on every terminal. */
private[repl] object Render {

  /** The longest tool argument or result shown inline; longer ones end in `...`. */
  val PreviewLength: Int = 200

  /** The error's kind (its class name), for one-line summaries. */
  def errorKind(error: LLMError): String = error.getClass.getSimpleName

  /**
   * `error: <kind>: <message>`, the message carrying the troubleshooting hint and link that llm4s adds to
   * configuration, authentication, rate-limit and not-found errors. Never a stack trace.
   */
  def error(error: LLMError): String = {
    val annotated = TroubleshootingLinks.annotate(error)
    val code      = annotated.code.map(c => s" [$c]").getOrElse("")
    s"error: ${errorKind(annotated)}$code: ${annotated.message}"
  }

  /** `12 prompt + 8 completion = 20 tokens`, with thinking and cache counts when the provider reports them. */
  def usage(usage: TokenUsage): String = {
    val extras = List(
      usage.thinkingTokens.map(n => s"$n thinking"),
      usage.cachedTokens.map(n => s"$n cached"),
      usage.cacheCreationTokens.map(n => s"$n written to cache")
    ).flatten
    val base = s"${usage.promptTokens} prompt + ${usage.completionTokens} completion = ${usage.totalTokens} tokens"
    if (extras.isEmpty) base else s"$base (${extras.mkString(", ")})"
  }

  /** `$0.000120`, in US dollars. */
  def cost(usd: Double): String = String.format(Locale.ROOT, "$%.6f", Double.box(usd))

  /** `312 ms` */
  def latency(duration: FiniteDuration): String = s"${duration.toMillis} ms"

  /** `-> get_weather({"city":"Paris"})` */
  def toolCall(call: ToolCall): String = s"-> ${call.name}(${preview(call.arguments.render())})"

  /** `<- {"temperature":21}` */
  def toolResult(result: String): String = s"<- ${preview(result)}"

  /** `<- error: ...` for a tool that failed. */
  def toolFailure(message: String): String = s"<- error: ${preview(message)}"

  /** One message of a transcript: its role, its text, and an assistant message's tool calls on lines of their own. */
  def message(message: Message): String =
    message match {
      case assistant: AssistantMessage =>
        val text  = assistant.contentOpt.filter(_.nonEmpty).map(t => s"assistant: $t").toList
        val calls = assistant.toolCalls.map(call => s"assistant: ${toolCall(call)}")
        (text ++ calls).mkString("\n")
      case tool: ToolMessage => s"tool: ${toolResult(tool.content)}"
      case other             => s"${other.role.name}: ${other.content}"
    }

  /** A conversation as a transcript, one message after another. */
  def transcript(messages: Seq[Message]): String =
    if (messages.isEmpty) "(no messages yet)" else messages.map(message).mkString("\n")

  private def preview(text: String): String = {
    val oneLine = text.replace('\n', ' ')
    if (oneLine.length > PreviewLength) oneLine.take(PreviewLength - 3) + "..." else oneLine
  }
}
