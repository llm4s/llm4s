package org.llm4s.llmconnect.smoke

import com.sun.net.httpserver.{ HttpExchange, HttpServer }

import java.net.{ Inet6Address, InetAddress, InetSocketAddress }
import java.nio.charset.StandardCharsets

/**
 * How a [[FakeOpenAIServer]] behaves. The default is a well-behaved OpenAI-compatible server; each flag turns off
 * one thing a real provider might get wrong, so that the contract's checks can be shown to fail for it.
 *
 * @param honourSystem            follow a `system` (or `developer`) message; when false it answers as if there was none
 * @param systemReplyExact        when honouring the system message, reply with exactly the word it asks for; when
 *                                false it wraps the word in prose
 * @param keepHistory             answer from earlier turns; when false it forgets everything but the last message
 * @param keepAssistantTurns      read the assistant turns in the history; when false it reads only the other turns
 * @param useToolResult           answer from a tool message's content; when false it ignores the tool result
 * @param toolArgumentsValid      send tool-call arguments that are JSON; when false they are cut off mid-object
 * @param toolArgumentsInSchema   send only the properties the tool declares; when false it adds an undeclared one
 * @param streamToolCalls         stream a tool call as a tool call; when false it streams prose instead
 * @param includeUsage            report token usage, also on streams
 * @param streamUsageNeedsOptIn   on a stream, report usage only to a request that asks with
 *                                `stream_options.include_usage` (OpenAI); when false it always does (OpenRouter)
 * @param honourResponseFormat    answer a `response_format` request with JSON; when false it answers in prose
 * @param structuredMatchesSchema the JSON it answers with matches the schema; when false the field types are wrong
 * @param structuredHasRequestedValues the JSON it answers with carries the values the prompt asked for; when false
 *                                the shape is right but the values are not
 * @param structuredOnlyDeclared  the JSON it answers with has only the schema's properties; when false it adds one
 * @param structuredBare          answer with the JSON document alone; when false it wraps it in prose
 * @param reasoningContent       send a `reasoning_content` field, as DeepSeek's reasoner does
 */
final case class FakeBehaviour(
  honourSystem: Boolean = true,
  systemReplyExact: Boolean = true,
  keepHistory: Boolean = true,
  keepAssistantTurns: Boolean = true,
  useToolResult: Boolean = true,
  toolArgumentsValid: Boolean = true,
  toolArgumentsInSchema: Boolean = true,
  streamToolCalls: Boolean = true,
  includeUsage: Boolean = true,
  streamUsageNeedsOptIn: Boolean = false,
  honourResponseFormat: Boolean = true,
  structuredMatchesSchema: Boolean = true,
  structuredHasRequestedValues: Boolean = true,
  structuredOnlyDeclared: Boolean = true,
  structuredBare: Boolean = true,
  reasoningContent: Boolean = true
)

/**
 * A local OpenAI-compatible `/chat/completions` server with JDK classes only (no test-kit dependency, so no build
 * change), replying the way the contract's prompts call for: to the system-message prompt, the multi-turn prompt,
 * the tool prompt (a tool call, then an answer carrying the tool's result), the structured-output prompt, and
 * anything else. It answers both plain and SSE-streamed requests, splitting a streamed tool call's arguments
 * across several deltas the way OpenAI does.
 *
 * Used only by `SmokeContractOfflineSpec`, to run the real checks and real clients without a network.
 */
final class FakeOpenAIServer(initial: FakeBehaviour = FakeBehaviour()) extends AutoCloseable {

  @volatile var behaviour: FakeBehaviour = initial

  private val server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)

  server.createContext("/", exchange => handle(exchange))
  server.start()

  /** The root a client's `baseUrl` should be set to. */
  def baseUrl: String = {
    // The address actually bound: the loopback address may be IPv6 (`::1`), which a URL needs in brackets.
    val address = server.getAddress
    val host = address.getAddress match {
      case v6: Inet6Address => s"[${v6.getHostAddress}]"
      case other            => other.getHostAddress
    }
    s"http://$host:${address.getPort}"
  }

  override def close(): Unit = server.stop(0)

  private def lastUserText(messages: Seq[ujson.Value]): String =
    messages.reverse.find(_("role").str == "user").flatMap(_.obj.get("content")).flatMap(_.strOpt).getOrElse("")

  private def textOf(message: ujson.Value): String =
    message.obj.get("content").flatMap(_.strOpt).getOrElse("")

  /** What the model says, for a request that is not a tool call. */
  private def reply(body: ujson.Value, behaviour: FakeBehaviour): String = {
    val messages = body("messages").arr.toSeq
    val last     = lastUserText(messages)
    val history  = if (behaviour.keepAssistantTurns) messages else messages.filterNot(_("role").str == "assistant")
    val earlier  = if (behaviour.keepHistory) history.map(textOf).mkString(" ") else last
    val system   = messages.filter(m => Set("system", "developer").contains(m("role").str)).map(textOf)
    if (messages.exists(_("role").str == "tool"))
      if (behaviour.useToolResult) s"The code is ${textOf(messages.reverse.find(_("role").str == "tool").get)}."
      else "I could not find the code."
    else if (last.contains("What is the capital of France"))
      if (behaviour.honourSystem && system.exists(_.contains("PINEAPPLE")))
        if (behaviour.systemReplyExact) "PINEAPPLE" else "The answer is PINEAPPLE."
      else "The capital of France is Paris."
    else if (last.contains("favourite number"))
      if (earlier.contains("7342")) "7342" else "I do not know your favourite number."
    else if (last == SmokeChecks.StructuredPrompt)
      // The prompt asks for no format: only `response_format` makes the answer JSON, as with a real model.
      if (behaviour.honourResponseFormat && body.obj.contains("response_format")) {
        val document =
          if (!behaviour.structuredMatchesSchema) """{"color":5,"count":"three"}"""
          else if (!behaviour.structuredOnlyDeclared) """{"color":"blue","count":3,"extra":true}"""
          else if (behaviour.structuredHasRequestedValues) """{"color":"blue","count":3}"""
          else """{"color":"red","count":9}"""
        if (behaviour.structuredBare) document else s"Here you go: $document"
      } else "The colour is blue and the count is 3."
    else "Hi"
  }

  private def wantsTool(body: ujson.Value): Option[String] = {
    val messages = body("messages").arr.toSeq
    val calls    = lastUserText(messages).contains("get_secret_code") && !messages.exists(_("role").str == "tool")
    if (calls) body.obj.get("tools").flatMap(_.arr.headOption).map(_("function")("name").str) else None
  }

  private def usageJson(behaviour: FakeBehaviour): ujson.Obj = {
    val usage = ujson.Obj("prompt_tokens" -> 12, "completion_tokens" -> 3, "total_tokens" -> 15)
    if (behaviour.reasoningContent) usage("completion_tokens_details") = ujson.Obj("reasoning_tokens" -> 2)
    usage
  }

  private def usage(behaviour: FakeBehaviour): Option[(String, ujson.Value)] =
    if (behaviour.includeUsage) Some("usage" -> usageJson(behaviour)) else None

  private def completionJson(message: ujson.Obj, finish: String, behaviour: FakeBehaviour): ujson.Obj = {
    val json = ujson.Obj(
      "id"      -> "chatcmpl-fake",
      "object"  -> "chat.completion",
      "created" -> 1,
      "model"   -> "fake-model",
      "choices" -> ujson.Arr(ujson.Obj("index" -> 0, "message" -> message, "finish_reason" -> finish))
    )
    usage(behaviour).foreach { case (key, value) => json(key) = value }
    json
  }

  private def chunkJson(delta: ujson.Obj, finish: Option[String]): ujson.Obj =
    ujson.Obj(
      "id"      -> "chatcmpl-fake",
      "object"  -> "chat.completion.chunk",
      "created" -> 1,
      "model"   -> "fake-model",
      "choices" -> ujson.Arr(
        ujson.Obj("index" -> 0, "delta" -> delta, "finish_reason" -> finish.fold[ujson.Value](ujson.Null)(ujson.Str(_)))
      )
    )

  private def send(exchange: HttpExchange, contentType: String, text: String): Unit = {
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(200, bytes.length.toLong)
    val out = exchange.getResponseBody
    out.write(bytes)
    out.close()
  }

  private def sse(events: Seq[ujson.Value]): String =
    (events.map(event => s"data: ${ujson.write(event)}\n\n") :+ "data: [DONE]\n\n").mkString

  private def toolArguments(behaviour: FakeBehaviour): String =
    if (!behaviour.toolArgumentsValid) """{"topic":"""
    else if (!behaviour.toolArgumentsInSchema) """{"topic":"vault","extra":true}"""
    else """{"topic":"vault"}"""

  private def streamedToolCall(name: String, behaviour: FakeBehaviour): Seq[ujson.Value] = {
    // Split where OpenAI splits, so that some pieces (`":"`) are valid JSON on their own and would lose their
    // quotes if a client parsed each piece before handing it on.
    val pieces =
      if (behaviour.toolArgumentsValid && behaviour.toolArgumentsInSchema) Seq("{\"", "topic", "\":\"", "vault", "\"}")
      else toolArguments(behaviour).grouped(4).toSeq
    val first = chunkJson(
      ujson.Obj(
        "role" -> "assistant",
        "tool_calls" -> ujson.Arr(
          ujson.Obj(
            "index"    -> 0,
            "id"       -> "call_1",
            "type"     -> "function",
            "function" -> ujson.Obj("name" -> name, "arguments" -> "")
          )
        )
      ),
      None
    )
    // As OpenAI does, the continuations carry the call's index and no id.
    val rest = pieces.map { piece =>
      chunkJson(
        ujson.Obj("tool_calls" -> ujson.Arr(ujson.Obj("index" -> 0, "function" -> ujson.Obj("arguments" -> piece)))),
        None
      )
    }
    (first +: rest) :+ chunkJson(ujson.Obj(), Some("tool_calls"))
  }

  private def streamedText(text: String, behaviour: FakeBehaviour): Seq[ujson.Value] = {
    val (head, tail) = text.splitAt(text.length / 2)
    val reasoning =
      if (behaviour.reasoningContent) Seq(chunkJson(ujson.Obj("reasoning_content" -> "Let me think."), None)) else Nil
    reasoning ++ Seq(
      chunkJson(ujson.Obj("role" -> "assistant", "content" -> head), None),
      chunkJson(ujson.Obj("content" -> tail), None),
      chunkJson(ujson.Obj(), Some("stop"))
    )
  }

  private def handle(exchange: HttpExchange): Unit = {
    val behaviour = this.behaviour
    val body      = ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
    val streaming = body.obj.get("stream").exists(_.bool)
    val wantsUsage = body.obj
      .get("stream_options")
      .flatMap(_.obj.get("include_usage"))
      .exists(_.bool)
    val tool = wantsTool(body)
    if (streaming) {
      val events =
        tool match {
          case Some(name) if behaviour.streamToolCalls => streamedToolCall(name, behaviour)
          case Some(_) => streamedText("I would call the tool, but will not.", behaviour)
          case None    => streamedText(reply(body, behaviour), behaviour)
        }
      val usageChunk =
        if (behaviour.includeUsage && (wantsUsage || !behaviour.streamUsageNeedsOptIn))
          Seq(
            ujson.Obj(
              "id"      -> "chatcmpl-fake",
              "object"  -> "chat.completion.chunk",
              "created" -> 1,
              "model"   -> "fake-model",
              "choices" -> ujson.Arr(),
              "usage"   -> usageJson(behaviour)
            )
          )
        else Nil
      send(exchange, "text/event-stream", sse(events ++ usageChunk))
    } else {
      val json = tool match {
        case Some(name) =>
          completionJson(
            ujson.Obj(
              "role"    -> "assistant",
              "content" -> ujson.Null,
              "tool_calls" -> ujson.Arr(
                ujson.Obj(
                  "id"       -> "call_1",
                  "type"     -> "function",
                  "function" -> ujson.Obj("name" -> name, "arguments" -> toolArguments(behaviour))
                )
              )
            ),
            "tool_calls",
            behaviour
          )
        case None =>
          val message = ujson.Obj("role" -> "assistant", "content" -> reply(body, behaviour))
          if (behaviour.reasoningContent) message("reasoning_content") = "Let me think."
          completionJson(message, "stop", behaviour)
      }
      send(exchange, "application/json", ujson.write(json))
    }
  }
}
