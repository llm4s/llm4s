package org.llm4s.portable

import org.llm4s.error.{ AuthenticationError, LLMError, ProcessingError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import java.time.Instant
import scala.util.Try

/**
 * The OpenAI chat-completions wire format (`POST {baseUrl}/chat/completions`), as pure functions over core's own
 * `Conversation`, `CompletionOptions`, `Completion` and `StreamedChunk`. No I/O and no platform API, so it runs
 * unchanged on the JVM, Scala.js and Scala Native; [[PortableChatClient]] and the browser's fetch reader in the
 * Laminar demo both drive it.
 *
 * Spike scope (#2338): text messages, tool definitions and tool calls, the sampling options, and streamed text and
 * tool-call deltas. Media parts, reasoning and response formats are refused or ignored, not mapped.
 */
object OpenAIChatProtocol {

  /** The request body for `model`; `stream` adds `stream_options.include_usage` so the last event carries usage. */
  def requestBody(
    model: String,
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean
  ): Result[ujson.Obj] =
    conversation.messages
      .foldLeft[Result[Vector[ujson.Value]]](Right(Vector.empty))((acc, m) =>
        acc.flatMap(ms => message(m).map(ms :+ _))
      )
      .map { messages =>
        val body = ujson.Obj(
          "model"       -> model,
          "messages"    -> ujson.Arr.from(messages),
          "temperature" -> options.temperature,
          "top_p"       -> options.topP,
          "stream"      -> stream
        )
        options.maxTokens.foreach(n => body("max_tokens") = n)
        if (options.presencePenalty != 0.0) body("presence_penalty") = options.presencePenalty
        if (options.frequencyPenalty != 0.0) body("frequency_penalty") = options.frequencyPenalty
        if (options.tools.nonEmpty) body("tools") = ujson.Arr.from(options.tools.map(_.toOpenAITool(strict = false)))
        if (stream) body("stream_options") = ujson.Obj("include_usage" -> true)
        body
      }

  private def message(m: Message): Result[ujson.Value] = m match {
    case s: SystemMessage => Right(ujson.Obj("role" -> "system", "content" -> s.content))
    case u: UserMessage   => textOnly(u.parts, "user").map(t => ujson.Obj("role" -> "user", "content" -> t))
    case t: ToolMessage =>
      textOnly(t.parts, "tool").map(c => ujson.Obj("role" -> "tool", "tool_call_id" -> t.toolCallId, "content" -> c))
    case a: AssistantMessage =>
      val obj = ujson.Obj("role" -> "assistant", "content" -> a.contentOpt.fold[ujson.Value](ujson.Null)(ujson.Str(_)))
      if (a.toolCalls.nonEmpty)
        obj("tool_calls") = ujson.Arr.from(a.toolCalls.map { c =>
          ujson.Obj(
            "id"       -> c.id,
            "type"     -> "function",
            "function" -> ujson.Obj("name" -> c.name, "arguments" -> ujson.write(c.arguments))
          )
        })
      Right(obj)
  }

  private def textOnly(parts: Vector[ContentPart], role: String): Result[String] =
    parts.find(ContentPart.isMedia) match {
      case Some(media) =>
        Left(ValidationError("messages", s"the portable spike sends text only; a $role message has ${media.kind}"))
      case None => Right(ContentPart.text(parts))
    }

  /** A non-streamed response body. */
  def decodeCompletion(body: String): Result[Completion] =
    parse(body).flatMap { json =>
      Try {
        val choice  = json("choices")(0)
        val msg     = choice("message")
        val content = msg.obj.get("content").flatMap(_.strOpt)
        val calls = msg.obj.get("tool_calls").flatMap(_.arrOpt).toList.flatten.map { c =>
          ToolCall(c("id").str, c("function")("name").str, arguments(c("function")("arguments")))
        }
        Completion(
          id = json.obj.get("id").flatMap(_.strOpt).getOrElse(""),
          created = created(json),
          content = content.getOrElse(""),
          model = json.obj.get("model").flatMap(_.strOpt).getOrElse(""),
          message = AssistantMessage(content, calls),
          toolCalls = calls,
          usage = json.obj.get("usage").flatMap(usage)
        )
      }.toEither.left.map(t => malformed(s"unexpected response shape: ${t.getMessage}"))
    }

  /** The error an unsuccessful HTTP status means. */
  def decodeError(status: Int, body: String, provider: String): LLMError = status match {
    case 401 | 403 => AuthenticationError(provider, errorMessage(body))
    case 429       => RateLimitError(provider)
    case _         => ServiceError(status, provider, errorMessage(body))
  }

  private def errorMessage(body: String): String =
    Try(ujson.read(body)("error")("message").str).getOrElse(body.take(500))

  private[portable] def parse(text: String): Result[ujson.Value] =
    Try(ujson.read(text)).toEither.left.map(t => malformed(s"not JSON: ${t.getMessage}"))

  private def malformed(message: String): LLMError = ProcessingError("openai-compatible-response", message)

  private def created(json: ujson.Value): Instant =
    json.obj.get("created").flatMap(_.numOpt).fold(Instant.EPOCH)(s => Instant.ofEpochSecond(s.toLong))

  private def arguments(raw: ujson.Value): ujson.Value = raw.strOpt match {
    case Some(text) if text.trim.isEmpty => ujson.Obj()
    case Some(text)                      => Try(ujson.read(text)).getOrElse(ujson.Str(text))
    case None                            => raw
  }

  private[portable] def usage(json: ujson.Value): Option[TokenUsage] =
    Try(
      TokenUsage(
        promptTokens = json("prompt_tokens").num.toInt,
        completionTokens = json("completion_tokens").num.toInt,
        totalTokens = json("total_tokens").num.toInt
      )
    ).toOption

  /**
   * Turns a streamed response into core's `StreamedChunk`s and, at the end, one `Completion`. Feed it the body's text
   * in whatever pieces the transport delivers; an SSE event split across pieces is reassembled. A tool call's
   * continuation deltas carry only its `index`; each chunk is given the id and name its index was first seen with.
   */
  final class StreamDecoder {
    private val sse                           = new SseParser
    private val text                          = new StringBuilder
    private var id                            = ""
    private var model                         = ""
    private var created                       = Instant.EPOCH
    private var finished: Option[String]      = None
    private var lastUsage: Option[TokenUsage] = None
    private val calls = scala.collection.mutable.LinkedHashMap.empty[Int, (String, String, StringBuilder)]
    private var failure: Option[LLMError] = None

    /** The chunks this piece of the body completes. After an error event or malformed data it returns none. */
    def feed(piece: String): Vector[StreamedChunk] =
      sse.feed(piece).flatMap(event)

    private def event(data: String): Vector[StreamedChunk] =
      if (failure.nonEmpty || data == "[DONE]") Vector.empty
      else
        parse(data) match {
          case Left(e) => failure = Some(e); Vector.empty
          case Right(json) if json.obj.contains("error") =>
            failure = Some(malformed(errorMessage(data))); Vector.empty
          case Right(json) =>
            json.obj.get("id").flatMap(_.strOpt).foreach(id = _)
            json.obj.get("model").flatMap(_.strOpt).foreach(model = _)
            if (created == Instant.EPOCH) created = OpenAIChatProtocol.created(json)
            json.obj.get("usage").flatMap(u => if (u.isNull) None else usage(u)).foreach(u => lastUsage = Some(u))
            json.obj.get("choices").flatMap(_.arrOpt).toVector.flatten.flatMap(choice)
        }

    private def choice(c: ujson.Value): Vector[StreamedChunk] = {
      val delta  = c.obj.get("delta").filterNot(_.isNull)
      val finish = c.obj.get("finish_reason").flatMap(_.strOpt)
      finish.foreach(f => finished = Some(f))
      val content = delta.flatMap(_.obj.get("content")).flatMap(_.strOpt).filter(_.nonEmpty)
      content.foreach(text.append)
      val toolChunks = delta.flatMap(_.obj.get("tool_calls")).flatMap(_.arrOpt).toVector.flatten.zipWithIndex.map {
        case (call, position) =>
          val index                      = call.obj.get("index").flatMap(_.numOpt).fold(position)(_.toInt)
          val fn                         = call.obj.get("function").filterNot(_.isNull)
          val fragment                   = fn.flatMap(_.obj.get("arguments")).flatMap(_.strOpt).getOrElse("")
          val (knownId, knownName, args) = calls.getOrElse(index, ("", "", new StringBuilder))
          val callId                     = call.obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty).getOrElse(knownId)
          val name = fn.flatMap(_.obj.get("name")).flatMap(_.strOpt).filter(_.nonEmpty).getOrElse(knownName)
          args.append(fragment)
          calls(index) = (callId, name, args)
          StreamedChunk(id = id, content = None, toolCall = Some(ToolCall(callId, name, ujson.Str(fragment))))
      }
      val textChunk = content.map(t => StreamedChunk(id = id, content = Some(t))).toVector
      val finishChunk =
        if (finish.nonEmpty && textChunk.isEmpty && toolChunks.isEmpty)
          Vector(StreamedChunk(id = id, content = None, finishReason = finish))
        else Vector.empty
      (textChunk ++ toolChunks).map(_.withFinishReason(finish)) ++ finishChunk
    }

    /** The finished reply: an error event, malformed data or a stream that never finished is an error. */
    def completion: Result[Completion] =
      failure.toLeft(()).flatMap { _ =>
        if (finished.isEmpty && text.isEmpty && calls.isEmpty) Left(malformed("the stream ended without a reply"))
        else {
          val toolCalls = calls.values.toList.map { case (callId, name, args) =>
            ToolCall(callId, name, arguments(ujson.Str(args.toString)))
          }
          val content = text.toString
          Right(
            Completion(
              id = id,
              created = created,
              content = content,
              model = model,
              message = AssistantMessage(Option(content).filter(_.nonEmpty), toolCalls),
              toolCalls = toolCalls,
              usage = lastUsage
            )
          )
        }
      }
  }
}
