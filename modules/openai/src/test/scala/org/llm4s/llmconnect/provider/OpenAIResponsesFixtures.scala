package org.llm4s.llmconnect.provider

/** Wire JSON of OpenAI's Responses API: the SDK throws on access to a missing required field, so a reply is whole. */
object OpenAIResponsesFixtures:

  /** Input 9 (cached 0), output 6 (reasoning 2), total 15. */
  val Usage: String =
    """{"input_tokens":9,"input_tokens_details":{"cached_tokens":0},"output_tokens":6,""" +
      """"output_tokens_details":{"reasoning_tokens":2},"total_tokens":15}"""

  /** One output message item of one `output_text` part. */
  def message(text: String, status: String = "completed"): String =
    val quoted = ujson.Str(text).render()
    s"""{"type":"message","id":"msg_1","status":"$status","role":"assistant","content":[""" +
      s"""{"type":"output_text","text":$quoted,"annotations":[]}]}"""

  /** A full `response` object whose `output` is `output` (an item, or items joined with commas). */
  def reply(output: String, status: String = "completed", extra: String = ""): String =
    // a key `extra` supplies replaces the default null, so the JSON holds each key once
    def default(key: String): String = if extra.contains(s"\"$key\":") then "" else s""","$key":null"""
    val more                         = if extra.isEmpty then "" else s",$extra"
    s"""{"id":"resp_1","object":"response","created_at":1700000000,"status":"$status","model":"gpt-4o",""" +
      s""""output":[$output],"usage":$Usage,"parallel_tool_calls":true,"tool_choice":"auto","tools":[]""" +
      s"""${default("error")}${default("incomplete_details")}$more}"""

  /** One `function_call` output item. */
  def functionCall(callId: String, name: String, arguments: String, status: String = "completed"): String =
    val quoted = ujson.Str(arguments).render()
    s"""{"type":"function_call","id":"fc_$callId","call_id":"$callId","name":"$name","arguments":$quoted,""" +
      s""""status":"$status"}"""

  /** The default `encrypted` of [[reasoningItem]]: `enc-<id>`. */
  val DefaultEncrypted: Option[String] = Some("")

  /** One `reasoning` output item: with `encrypted_content` unless `encrypted` is `None`, and one summary part per text. */
  def reasoningItem(id: String, encrypted: Option[String] = DefaultEncrypted, summary: Seq[String] = Nil): String =
    val parts = summary.map(t => s"""{"type":"summary_text","text":${ujson.Str(t).render()}}""").mkString(",")
    val enc = encrypted
      .map(e => if encrypted.eq(DefaultEncrypted) then "enc-" + id else e)
      .fold("")(e => s""","encrypted_content":${ujson.Str(e).render()}""")
    s"""{"type":"reasoning","id":"$id","summary":[$parts]$enc}"""

  /** One output message item of one `refusal` part. */
  def refusal(text: String): String =
    val quoted = ujson.Str(text).render()
    s"""{"type":"message","id":"msg_1","status":"completed","role":"assistant","content":[""" +
      s"""{"type":"refusal","refusal":$quoted}]}"""

  /** An `output_text` part with no annotations, or with `url_citation`s at the given `(start, end)` indices. */
  def textPart(text: String, citations: Seq[(Int, Int)] = Seq.empty): String =
    val quoted = ujson.Str(text).render()
    val notes = citations
      .map((s, e) =>
        s"""{"type":"url_citation","url":"https://example.com","title":"t","start_index":$s,"end_index":$e}"""
      )
      .mkString(",")
    s"""{"type":"output_text","text":$quoted,"annotations":[$notes]}"""

  /** A `refusal` part. */
  def refusalPart(text: String): String =
    s"""{"type":"refusal","refusal":${ujson.Str(text).render()}}"""

  /** One output message item holding the given parts. */
  def messageOf(parts: String*): String =
    s"""{"type":"message","id":"msg_1","status":"completed","role":"assistant","content":[${parts.mkString(",")}]}"""

  /**
   * An event stream as the Responses API sends it: `event: <type>` and `data: <json>` per event, each event numbered by
   * its position in `sequence_number`, and no `[DONE]`.
   */
  def sse(events: String*): String =
    events.zipWithIndex.map { (event, i) =>
      s"event: ${ujson.read(event)("type").str}\ndata: ${event.stripSuffix("}")},\"sequence_number\":$i}\n\n"
    }.mkString

  /** `response.created`, carrying the response in progress with no output yet. */
  def created(): String = s"""{"type":"response.created","response":${reply("", status = "in_progress")}}"""

  def textDelta(delta: String, itemId: String = "msg_1", outputIndex: Int = 0): String =
    s"""{"type":"response.output_text.delta","item_id":"$itemId","output_index":$outputIndex,"content_index":0,""" +
      s""""delta":${ujson.Str(delta).render()},"logprobs":[]}"""

  /** `response.output_item.added` opening a `function_call` item with no arguments yet. */
  def callAdded(callId: String, name: String, itemId: String, outputIndex: Int): String =
    s"""{"type":"response.output_item.added","output_index":$outputIndex,"item":{"type":"function_call",""" +
      s""""id":"$itemId","call_id":"$callId","name":"$name","arguments":"","status":"in_progress"}}"""

  def argumentsDelta(delta: String, itemId: String, outputIndex: Int): String =
    s"""{"type":"response.function_call_arguments.delta","item_id":"$itemId","output_index":$outputIndex,""" +
      s""""delta":${ujson.Str(delta).render()}}"""

  /** `response.reasoning_summary_part.added`: a summary part begins. */
  def summaryPartAdded(summaryIndex: Int, itemId: String = "rs_1"): String =
    s"""{"type":"response.reasoning_summary_part.added","item_id":"$itemId","output_index":0,""" +
      s""""summary_index":$summaryIndex,"part":{"type":"summary_text","text":""}}"""

  def summaryDelta(delta: String, itemId: String = "rs_1"): String =
    s"""{"type":"response.reasoning_summary_text.delta","item_id":"$itemId","output_index":0,"summary_index":0,""" +
      s""""delta":${ujson.Str(delta).render()}}"""

  def refusalDelta(delta: String): String =
    s"""{"type":"response.refusal.delta","item_id":"msg_1","output_index":0,"content_index":0,""" +
      s""""delta":${ujson.Str(delta).render()}}"""

  /** `response.completed` wrapping a full [[reply]]. */
  def completed(response: String): String = s"""{"type":"response.completed","response":$response}"""

  def incomplete(response: String): String = s"""{"type":"response.incomplete","response":$response}"""

  def failed(response: String): String = s"""{"type":"response.failed","response":$response}"""

  /** A stream's `error` event with its `error` object nested, the form the SDK's SSE handler throws on. */
  def nestedErrorEvent(code: String, message: String): String =
    s"""{"type":"error","error":{"code":"$code","message":${ujson.Str(message).render()}}}"""

  /** A stream's `error` event. */
  def errorEvent(code: String, message: String): String =
    s"""{"type":"error","code":"$code","message":${ujson.Str(message).render()},"param":null}"""
