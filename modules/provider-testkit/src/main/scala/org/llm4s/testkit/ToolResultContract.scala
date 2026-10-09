package org.llm4s.testkit

import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, ToolCall, ToolMessage, UserMessage }

/**
 * A chat API's wire format for tool calls and their results, as the tool-result contract reads a
 * request body.
 */
enum ToolMessageFormat:

  /**
   * OpenAI chat completions, which every OpenAI-compatible endpoint shares: an assistant message's
   * `tool_calls`, then one `role: "tool"` message per call, with its `tool_call_id`, straight after.
   */
  case OpenAIChat

  /**
   * Anthropic Messages: an assistant message's `tool_use` content blocks, then a user message that
   * opens with one `tool_result` block per call, with its `tool_use_id`.
   */
  case AnthropicMessages

/** One conversation the contract sends: a shape of tool calls and results an agent produces. */
final case class ToolResultCase(name: String, conversation: Conversation):

  /** The tool calls in the conversation; a request must carry each of them. */
  def toolCalls: Int = conversation.messages.collect { case a: AssistantMessage => a.toolCalls.size }.sum

  /** The tool results in the conversation; a request must carry each of them as a native result. */
  def toolResults: Int = conversation.messages.count(_.isInstanceOf[ToolMessage])

/**
 * The tool-result contract (design §5.3 of the typed agent runtime): every tool call a request
 * carries has exactly one result, straight after the message that made it, in the provider's own
 * format - and no result answers a call that is not there. OpenAI rejects a request that breaks
 * it; Anthropic rejects one too, which is why an Anthropic client must never be handed such a
 * conversation, rather than drop or rewrite what does not pair.
 *
 * [[violations]] reads a request body; [[cases]] are the conversations an agent's tool loop sends
 * after every outcome of a call - success, a tool error, a rejection or denial, an unknown tool, a
 * refused handoff batch, a handoff - including provider call ids reused across turns.
 * `ProviderModuleChecks.assertOneToolResultPerCall` sends each case through a client and checks
 * what reaches the server.
 */
object ToolResultContract:

  /** Every way `requestBody` breaks the contract in `format`, in message order; empty when it holds. */
  def violations(format: ToolMessageFormat, requestBody: ujson.Value): Vector[String] =
    val messages = messagesOf(requestBody)
    format match
      case ToolMessageFormat.OpenAIChat        => openAIViolations(messages)
      case ToolMessageFormat.AnthropicMessages => anthropicViolations(messages)

  /** The tool calls `requestBody` carries in `format`. */
  def toolCallCount(format: ToolMessageFormat, requestBody: ujson.Value): Int =
    messagesOf(requestBody).map(m => callIds(format, m).size).sum

  /** The native tool results `requestBody` carries in `format` (a result sent as text is not one). */
  def toolResultCount(format: ToolMessageFormat, requestBody: ujson.Value): Int =
    messagesOf(requestBody).map(m => resultIds(format, m).size).sum

  /**
   * The conversations the contract sends, each a valid conversation that ends where the next model
   * call begins. Error results carry `{"error": ...}`, as the agent's tool loop writes them.
   */
  val cases: Vector[ToolResultCase] =
    def call(id: String, name: String, args: ujson.Value = ujson.Obj("query" -> "llm4s")) = ToolCall(id, name, args)
    def error(message: String) = ujson.Obj("error" -> message).render()
    Vector(
      ToolResultCase(
        "one call and its result",
        Conversation(
          Seq(
            UserMessage("Look up llm4s."),
            AssistantMessage(contentOpt = None, toolCalls = Seq(call("call_a1", "lookup"))),
            ToolMessage("""{"found":true}""", "call_a1")
          )
        )
      ),
      ToolResultCase(
        "a parallel batch: success, tool error, rejection and denial, in call order",
        Conversation(
          Seq(
            UserMessage("Check everything."),
            AssistantMessage(
              "Checking.",
              Seq(
                call("call_b1", "lookup"),
                call("call_b2", "fetch"),
                call("call_b3", "delete_all", ujson.Obj()),
                call("call_b4", "send_mail")
              )
            ),
            ToolMessage("found", "call_b1"),
            ToolMessage(error("Tool 'fetch' failed: connection reset"), "call_b2"),
            ToolMessage(error("Rejected: not today"), "call_b3"),
            ToolMessage(error("Denied: mail is not allowed"), "call_b4")
          )
        )
      ),
      ToolResultCase(
        "an unknown tool and invalid arguments",
        Conversation(
          Seq(
            UserMessage("Do it."),
            AssistantMessage(
              contentOpt = None,
              toolCalls = Seq(call("call_c1", "no_such_tool"), call("call_c2", "lookup", ujson.Obj("limit" -> 500)))
            ),
            ToolMessage(error("Unknown tool 'no_such_tool'"), "call_c1"),
            ToolMessage(error("Invalid arguments for 'lookup': $.limit: 500 is above maximum 100"), "call_c2")
          )
        )
      ),
      ToolResultCase(
        "provider call ids reused across turns",
        Conversation(
          Seq(
            UserMessage("First question."),
            AssistantMessage(contentOpt = None, toolCalls = Seq(call("call_0", "lookup"))),
            ToolMessage("first", "call_0"),
            AssistantMessage("First answer."),
            UserMessage("Second question."),
            AssistantMessage(contentOpt = None, toolCalls = Seq(call("call_0", "lookup"), call("call_1", "fetch"))),
            ToolMessage("second", "call_0"),
            ToolMessage("third", "call_1")
          )
        )
      ),
      ToolResultCase(
        "a handoff, then the target's own call",
        Conversation(
          Seq(
            UserMessage("I want a refund."),
            AssistantMessage(
              contentOpt = None,
              toolCalls = Seq(call("call_d1", "handoff_to_billing", ujson.Obj("reason" -> "refund")))
            ),
            ToolMessage("Transferred to billing", "call_d1"),
            AssistantMessage(contentOpt = None, toolCalls = Seq(call("call_d2", "refund"))),
            ToolMessage("refunded", "call_d2")
          )
        )
      ),
      ToolResultCase(
        "a refused handoff batch, every call answered",
        Conversation(
          Seq(
            UserMessage("Refund and look up."),
            AssistantMessage(
              contentOpt = None,
              toolCalls = Seq(call("call_e1", "handoff_to_billing"), call("call_e2", "lookup"))
            ),
            ToolMessage(error("A handoff must be the only tool call in its message"), "call_e1"),
            ToolMessage(error("A handoff must be the only tool call in its message"), "call_e2"),
            AssistantMessage(contentOpt = None, toolCalls = Seq(call("call_e3", "lookup"))),
            ToolMessage("found", "call_e3")
          )
        )
      )
    )

  // ---- reading a body ----

  private def messagesOf(body: ujson.Value): Vector[ujson.Value] =
    body.objOpt.flatMap(_.get("messages")).flatMap(_.arrOpt).map(_.toVector).getOrElse(Vector.empty)

  private def role(message: ujson.Value): String =
    message.objOpt.flatMap(_.get("role")).flatMap(_.strOpt).getOrElse("")

  /** Content blocks; a string content has none. */
  private def blocks(message: ujson.Value): Vector[ujson.Value] =
    message.objOpt.flatMap(_.get("content")).flatMap(_.arrOpt).map(_.toVector).getOrElse(Vector.empty)

  private def blockType(block: ujson.Value): String =
    block.objOpt.flatMap(_.get("type")).flatMap(_.strOpt).getOrElse("")

  private def str(value: ujson.Value, field: String): String =
    value.objOpt.flatMap(_.get(field)).flatMap(_.strOpt).getOrElse("")

  private def callIds(format: ToolMessageFormat, message: ujson.Value): Vector[String] =
    format match
      case ToolMessageFormat.OpenAIChat =>
        if role(message) != "assistant" then Vector.empty
        else
          message.objOpt
            .flatMap(_.get("tool_calls"))
            .flatMap(_.arrOpt)
            .map(_.toVector.map(str(_, "id")))
            .getOrElse(Vector.empty)
      case ToolMessageFormat.AnthropicMessages =>
        if role(message) != "assistant" then Vector.empty
        else blocks(message).filter(blockType(_) == "tool_use").map(str(_, "id"))

  private def resultIds(format: ToolMessageFormat, message: ujson.Value): Vector[String] =
    format match
      case ToolMessageFormat.OpenAIChat =>
        if role(message) == "tool" then Vector(str(message, "tool_call_id")) else Vector.empty
      case ToolMessageFormat.AnthropicMessages =>
        if role(message) != "user" then Vector.empty
        else blocks(message).filter(blockType(_) == "tool_result").map(str(_, "tool_use_id"))

  /** Exactly one result for each call, none for anything else, and no call id blank or repeated. */
  private def paired(where: String, calls: Vector[String], results: Vector[String]): Vector[String] =
    val blank = Option.when(calls.exists(_.isEmpty))(s"$where: a tool call has no id").toVector
    val repeated =
      calls.filter(_.nonEmpty).groupBy(identity).collect { case (id, n) if n.size > 1 => id }.toVector.sorted
    val counted = calls.distinct.filter(_.nonEmpty).flatMap { id =>
      results.count(_ == id) match
        case 1 => None
        case 0 => Some(s"$where: tool call '$id' has no result straight after it")
        case n => Some(s"$where: tool call '$id' has $n results")
    }
    val stray = results.filterNot(calls.contains).distinct.map(id => s"$where: a tool result for '$id' answers no call")
    blank ++ repeated.map(id => s"$where: tool call id '$id' is used more than once") ++ counted ++ stray

  private def openAIViolations(messages: Vector[ujson.Value]): Vector[String] =
    val format = ToolMessageFormat.OpenAIChat
    messages.indices.toVector.flatMap { i =>
      val calls = callIds(format, messages(i))
      if calls.nonEmpty then
        val run = messages.drop(i + 1).takeWhile(role(_) == "tool")
        paired(s"message $i", calls, run.flatMap(resultIds(format, _)))
      else if role(messages(i)) == "tool" then
        // a tool message counts only in the run straight after an assistant message with calls
        messages.take(i).reverseIterator.dropWhile(role(_) == "tool").nextOption() match
          case Some(m) if callIds(format, m).nonEmpty => Vector.empty
          case _ => Vector(s"message $i: a tool message follows no assistant message with tool_calls")
      else Vector.empty
    }

  private def anthropicViolations(messages: Vector[ujson.Value]): Vector[String] =
    val format = ToolMessageFormat.AnthropicMessages
    // tool_result blocks must open their user message: none may follow another kind of block
    val order = messages.indices.toVector.flatMap { i =>
      val kinds = blocks(messages(i)).map(blockType)
      val late  = kinds.dropWhile(_ == "tool_result").contains("tool_result")
      Option.when(role(messages(i)) == "user" && late)(s"message $i: a tool_result block follows another block")
    }
    val pairing = messages.indices.toVector.flatMap { i =>
      val calls = callIds(format, messages(i))
      if calls.nonEmpty then
        val next = messages.lift(i + 1).filter(role(_) == "user")
        paired(s"message $i", calls, next.toVector.flatMap(resultIds(format, _)))
      else
        val results = resultIds(format, messages(i))
        val answers = i > 0 && callIds(format, messages(i - 1)).nonEmpty
        Option
          .when(results.nonEmpty && !answers)(s"message $i: tool_result blocks follow no message with tool_use blocks")
          .toVector
    }
    order ++ pairing
