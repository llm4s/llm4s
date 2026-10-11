package org.llm4s.testkit

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.{ TokenUsage, ToolCall }

import scala.util.Try

/**
 * What one scripted call to a [[ScriptedLLMClient]] returns: a text answer, one or more tool calls, or a failure.
 *
 * Build one with [[Reply.text]], [[Reply.toolCall]], [[Reply.toolCalls]] or [[Reply.failure]], and adjust it with
 * the `with*` methods:
 *
 * {{{
 * Reply.text("Paris").withUsage(TokenUsage(promptTokens = 12, completionTokens = 1, totalTokens = 13))
 * Reply.toolCall("get_weather", """{"city": "Paris"}""")
 * Reply.failure(RateLimitError("openai"))
 * }}}
 *
 * A reply that carries a failure is returned as a `Left`; every other reply becomes a `Completion`.
 *
 * Tool-call arguments are written as a JSON string, so the kit's own factories need no JSON library in the test.
 * [[Reply.toolCalls]] and [[withToolCalls]] take core's `ToolCall` as it is, for a test that already builds them.
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

  /**
   * This reply with one more tool call after the ones it already makes, so that one answer can call several tools.
   *
   * {{{
   * Reply.toolCall("get_weather", """{"city": "Oslo"}""").withToolCall("get_time", """{"zone": "CET"}""")
   * }}}
   *
   * @param name the name of the tool, as registered with the agent
   * @param argumentsJson the arguments the model passes, as a JSON object
   * @param id the id of the call; when `None` the client numbers it
   * @throws IllegalArgumentException if `argumentsJson` is not a JSON object
   */
  def withToolCall(name: String, argumentsJson: String = "{}", id: Option[String] = None): Reply =
    copy(toolCalls = toolCalls :+ Reply.parsedToolCall(name, argumentsJson, id))

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
   * {{{
   * Reply.toolCall("get_weather", """{"city": "Paris"}""")
   * }}}
   *
   * The arguments are checked here, when the script is written: text that is not a JSON object is a mistake in the
   * test, so it throws at once, naming the tool and the text, instead of surfacing later as a confusing failure of
   * the code under test.
   *
   * @param name the name of the tool, as registered with the agent
   * @param argumentsJson the arguments the model passes, as a JSON object; `{}` when the tool takes none
   * @param id the id of the call; when `None` the client numbers it `call-<call number>-<position>`
   * @throws IllegalArgumentException if `argumentsJson` is not a JSON object
   */
  def toolCall(name: String, argumentsJson: String = "{}", id: Option[String] = None): Reply =
    apply(toolCalls = List(parsedToolCall(name, argumentsJson, id)))

  /**
   * An answer that makes several tool calls at once, built from core's `ToolCall`. Without a JSON library at hand,
   * chain [[Reply.withToolCall]] onto [[Reply.toolCall]] instead.
   */
  def toolCalls(calls: ToolCall*): Reply = apply(toolCalls = calls.toList)

  /** A call that fails with `error`, for example a `RateLimitError` or a `TimeoutError`. */
  def failure(error: LLMError): Reply = apply(failure = Some(error))

  private def parsedToolCall(name: String, argumentsJson: String, id: Option[String]): ToolCall =
    Try(ujson.read(argumentsJson)).toOption match {
      case Some(arguments: ujson.Obj) => ToolCall(id.getOrElse(""), name, arguments)
      case Some(_) =>
        throw new IllegalArgumentException(
          s"the arguments of tool call '$name' must be a JSON object, but were: $argumentsJson"
        )
      case None =>
        throw new IllegalArgumentException(s"the arguments of tool call '$name' are not valid JSON: $argumentsJson")
    }
}
