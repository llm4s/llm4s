package org.llm4s.portable

import org.llm4s.llmconnect.model._
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }

import java.net.URI

/** The same values on every platform, so a spec's expected JSON is one string for the JVM, Scala.js and Native. */
object Fixtures {

  /** Non-ASCII text, including a character outside the Basic Multilingual Plane (a surrogate pair). */
  val nonAscii: String = "\u00e9t\u00e9 \ud83c\udf24"

  val weatherCall: ToolCall = ToolCall("call_1", "get_weather", ujson.Obj("city" -> "Paris", "days" -> 3))

  val image: ContentPart =
    ContentPart
      .image(MediaSource.Url(URI.create("https://example.com/cat.png")), MediaType.Png)
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** Every message kind, a tool call, a media part and non-ASCII text. */
  val conversation: Conversation = Conversation(
    Seq(
      SystemMessage("You are terse."),
      UserMessage(s"Weather in Paris? $nonAscii"),
      AssistantMessage(contentOpt = None, toolCalls = Seq(weatherCall)),
      ToolMessage("""{"temp":21.5}""", "call_1"),
      UserMessage(Vector(ContentPart.Text("And this picture?"), image)),
      AssistantMessage("Sunny, 21.5 C.")
    )
  )

  /** The text-only part of [[conversation]]: what the portable client can send. */
  val textConversation: Conversation = Conversation(conversation.messages.take(4))

  val weatherTool: ToolFunction[Map[String, Any], String] =
    ToolBuilder[Map[String, Any], String](
      "get_weather",
      "Current weather for a city",
      Schema
        .`object`[Map[String, Any]]("Weather query")
        .withRequiredField("city", Schema.string("City name"))
        .withRequiredField("days", Schema.integer("Days ahead"))
    ).withHandler(params => params.getString("city").map(c => s"Sunny in $c"))
      .buildSafe()
      .fold(e => throw new IllegalStateException(e.message), identity)
}
