package org.llm4s.llmconnect.provider

/** Wire JSON of OpenAI's Responses API: the SDK throws on access to a missing required field, so a reply is whole. */
object OpenAIResponsesFixtures:

  /** Input 9 (cached 0), output 6 (reasoning 2), total 15. */
  val Usage: String =
    """{"input_tokens":9,"input_tokens_details":{"cached_tokens":0},"output_tokens":6,""" +
      """"output_tokens_details":{"reasoning_tokens":2},"total_tokens":15}"""

  /** One output message item of one `output_text` part. */
  def message(text: String): String =
    val quoted = ujson.Str(text).render()
    s"""{"type":"message","id":"msg_1","status":"completed","role":"assistant","content":[""" +
      s"""{"type":"output_text","text":$quoted,"annotations":[]}]}"""

  /** A full `response` object whose `output` is `output` (an item, or items joined with commas). */
  def reply(output: String, status: String = "completed", extra: String = ""): String =
    val more = if extra.isEmpty then "" else s",$extra"
    s"""{"id":"resp_1","object":"response","created_at":1700000000,"status":"$status","model":"gpt-4o",""" +
      s""""output":[$output],"usage":$Usage,"parallel_tool_calls":true,"tool_choice":"auto","tools":[],""" +
      s""""error":null,"incomplete_details":null$more}"""
