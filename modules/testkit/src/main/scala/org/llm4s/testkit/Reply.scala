package org.llm4s.testkit

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.{ TokenUsage, ToolCall }

/**
 * What one scripted call to a [[ScriptedLLMClient]] returns: a text answer, one or more tool calls, or a failure.
 *
 * Build one with [[Reply.text]], [[Reply.toolCall]], [[Reply.toolCalls]] or [[Reply.failure]], and adjust it with
 * the `with*` methods:
 *
 * {{{
 * Reply.text("Paris").withUsage(TokenUsage(promptTokens = 12, completionTokens = 1, totalTokens = 13))
 * Reply.toolCall("get_weather", ujson.Obj("city" -> "Paris"))
 * Reply.failure(RateLimitError("openai"))
 * }}}
 *
 * A reply that carries a failure is returned as a `Left`; every other reply becomes a `Completion`.
 *
 * @param content the text of the answer; empty for a reply that only calls tools
 * @param toolCalls the tool calls the model makes; a call whose `id` is empty is given the id
 *                  `call-<call number>-<position>` when the client returns it
 * @param failure the error to return instead of a completion
 * @param model the model name reported in the completion
 * @param usage the token usage reported in the completion
 */
final case class Reply private (
  content: String,
  toolCalls: List[ToolCall],
  failure: Option[LLMError],
  model: String,
  usage: Option[TokenUsage]
) {

  /** This reply with another text. */
  def withContent(content: String): Reply = copy(content = content)

  /** This reply with other tool calls. */
  def withToolCalls(toolCalls: List[ToolCall]): Reply = copy(toolCalls = toolCalls)

  /** This reply with another model name in the completion. */
  def withModel(model: String): Reply = copy(model = model)

  /** This reply reporting the given token usage. */
  def withUsage(usage: TokenUsage): Reply = copy(usage = Some(usage))

  /** This reply reporting the given token usage, or none. */
  def withUsage(usage: Option[TokenUsage]): Reply = copy(usage = usage)

  /** True when this reply makes the call fail. */
  def isFailure: Boolean = failure.isDefined
}

object Reply {

  /** The model name a completion reports unless [[Reply.withModel]] changes it. */
  val DefaultModel: String = "scripted-model"

  /** Creates a [[Reply]]. The named arguments are the supported way to build one; the factories below cover the common cases. */
  def apply(
    content: String = "",
    toolCalls: List[ToolCall] = List.empty,
    failure: Option[LLMError] = None,
    model: String = DefaultModel,
    usage: Option[TokenUsage] = None
  ): Reply = new Reply(content, toolCalls, failure, model, usage)

  /** A plain text answer. */
  def text(content: String): Reply = apply(content = content)

  /**
   * An answer that calls one tool.
   *
   * @param name the name of the tool, as registered with the agent
   * @param arguments the arguments the model passes
   * @param id the id of the call; when empty the client numbers it
   */
  def toolCall(name: String, arguments: ujson.Value = ujson.Obj(), id: String = ""): Reply =
    apply(toolCalls = List(ToolCall(id, name, arguments)))

  /** An answer that makes several tool calls at once. */
  def toolCalls(calls: ToolCall*): Reply = apply(toolCalls = calls.toList)

  /** A call that fails with `error`, for example a `RateLimitError` or a `TimeoutError`. */
  def failure(error: LLMError): Reply = apply(failure = Some(error))
}
