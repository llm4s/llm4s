package org.llm4s.testkit

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result

/**
 * An [[org.llm4s.llmconnect.LLMClient]] that answers from a script, for testing code that calls LLM4S without a
 * model, a network or an API key.
 *
 * Two ways to script it:
 *
 *  - [[ScriptedLLMClient.sequence]] answers the first call with the first reply, the second call with the second,
 *    and so on. This suits an agent loop: a tool call, then the final answer.
 *  - [[ScriptedLLMClient.respondingTo]] chooses the reply from the conversation, so the answer does not depend on
 *    how many calls came before.
 *
 * {{{
 * val client = ScriptedLLMClient.sequence(
 *   Reply.toolCall("get_weather", ujson.Obj("city" -> "Paris")),
 *   Reply.text("It is sunny in Paris.")
 * )
 * // ... run the code under test with `client` ...
 * client.callCount          // 2
 * client.calls.head.lastUserText
 * }}}
 *
 * Every request is recorded ([[calls]]), whether it came through `complete` or `streamComplete`. A call the script
 * has no reply for does not hang or throw: it returns a `Left(ValidationError)` that names the call and shows the
 * last message, so a test that makes more calls than it scripted fails with a message that says why.
 *
 * `streamComplete` replays the same reply as chunks: the text in pieces of at most [[chunkSize]] characters, then
 * each tool call, then a final chunk that carries the finish reason (`stop`, or `tool_calls` when the reply calls
 * tools).
 *
 * The client is thread-safe: calls from several threads are recorded one at a time and each gets its own position
 * in the script. It reports a context window of [[getContextWindow]] tokens and reserves [[getReserveCompletion]]
 * for the answer, which [[withContextWindow]] and [[withReserveCompletion]] change.
 */
final class ScriptedLLMClient private (
  respond: (Int, Conversation) => Either[String, Reply],
  contextWindow: Int,
  reserveCompletion: Int,
  val chunkSize: Int
) extends LLMClient {

  private val lock                           = new Object
  private var recorded: Vector[RecordedCall] = Vector.empty

  /** Every request received so far, oldest first. */
  def calls: Vector[RecordedCall] = lock.synchronized(recorded)

  /** The number of requests received so far. */
  def callCount: Int = lock.synchronized(recorded.size)

  /** The most recent request, if there was one. */
  def lastCall: Option[RecordedCall] = lock.synchronized(recorded.lastOption)

  /** A fresh client with the same script and no recorded calls, reporting another context window. */
  def withContextWindow(tokens: Int): ScriptedLLMClient =
    new ScriptedLLMClient(respond, tokens, reserveCompletion, chunkSize)

  /** A fresh client with the same script and no recorded calls, reserving another number of tokens for the answer. */
  def withReserveCompletion(tokens: Int): ScriptedLLMClient =
    new ScriptedLLMClient(respond, contextWindow, tokens, chunkSize)

  /** A fresh client with the same script and no recorded calls, streaming text in chunks of this size (at least 1). */
  def withChunkSize(characters: Int): ScriptedLLMClient =
    new ScriptedLLMClient(respond, contextWindow, reserveCompletion, math.max(1, characters))

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    answer(conversation, options, streamed = false).map { case (_, completion) => completion }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    answer(conversation, options, streamed = true).map { case (index, completion) =>
      chunksOf(index, completion).foreach(onChunk)
      completion
    }

  override def getContextWindow(): Int = contextWindow

  override def getReserveCompletion(): Int = reserveCompletion

  /** Records the request and picks its reply under one lock, so concurrent callers never share a position. */
  private def answer(
    conversation: Conversation,
    options: CompletionOptions,
    streamed: Boolean
  ): Result[(Int, Completion)] = {
    val (index, scripted) = lock.synchronized {
      val position = recorded.size
      recorded = recorded :+ RecordedCall(conversation, options, streamed)
      (position, respond(position, conversation))
    }
    scripted match {
      case Left(reason) =>
        Left(
          ValidationError(
            "scripted reply",
            s"no reply is scripted for call ${index + 1}: $reason; ${describeLast(conversation)}"
          )
        )
      case Right(reply) =>
        reply.failure match {
          case Some(error) => Left(error)
          case None        => Right((index, completionOf(index, reply)))
        }
    }
  }

  private def completionOf(index: Int, reply: Reply): Completion = {
    val calls = reply.toolCalls.zipWithIndex.map { case (call, position) =>
      if (call.id.isEmpty) call.copy(id = s"call-${index + 1}-${position + 1}") else call
    }
    Completion(
      id = s"scripted-${index + 1}",
      created = 0L,
      content = reply.content,
      model = reply.model,
      message = AssistantMessage(
        contentOpt = Some(reply.content).filter(_.nonEmpty),
        toolCalls = calls
      ),
      toolCalls = calls,
      usage = reply.usage
    )
  }

  private def chunksOf(index: Int, completion: Completion): List[StreamedChunk] = {
    val id    = s"scripted-${index + 1}"
    val text  = completion.content.grouped(chunkSize).toList.map(piece => StreamedChunk(id, Some(piece)))
    val tools = completion.toolCalls.map(call => StreamedChunk(id, None, toolCall = Some(call)))
    val last = StreamedChunk(
      id,
      None,
      finishReason = Some(if (completion.toolCalls.isEmpty) "stop" else "tool_calls")
    )
    text ++ tools ++ List(last)
  }

  private def describeLast(conversation: Conversation): String =
    conversation.messages.lastOption match {
      case Some(message) => s"the last message was ${message.role}: \"${ScriptedLLMClient.preview(message.content)}\""
      case None          => "the conversation has no messages"
    }
}

object ScriptedLLMClient {

  private val DefaultContextWindow     = 128000
  private val DefaultReserveCompletion = 4096
  private val DefaultChunkSize         = 16
  private val PreviewLength            = 80

  /**
   * A client that answers call 1 with the first reply, call 2 with the second, and so on. A call past the end of
   * the script returns a `Left(ValidationError)` that names the call.
   */
  def sequence(replies: Reply*): ScriptedLLMClient = {
    val script = replies.toVector
    create { (index, _) =>
      script.lift(index).toRight(s"the script has ${script.size} ${if (script.size == 1) "reply" else "replies"}")
    }
  }

  /**
   * A client that chooses each reply from the conversation. A conversation no case matches returns a
   * `Left(ValidationError)` that names the call.
   *
   * {{{
   * ScriptedLLMClient.respondingTo {
   *   case c if c.messages.lastOption.exists(_.content.contains("capital")) => Reply.text("Paris")
   * }
   * }}}
   */
  def respondingTo(rules: PartialFunction[Conversation, Reply]): ScriptedLLMClient =
    create((_, conversation) => rules.lift(conversation).toRight("no rule matched the conversation"))

  /** A client that gives the same reply to every call. */
  def always(reply: Reply): ScriptedLLMClient = create((_, _) => Right(reply))

  private def create(respond: (Int, Conversation) => Either[String, Reply]): ScriptedLLMClient =
    new ScriptedLLMClient(respond, DefaultContextWindow, DefaultReserveCompletion, DefaultChunkSize)

  private[testkit] def preview(text: String): String = {
    val oneLine = text.replace('\n', ' ')
    if (oneLine.length > PreviewLength) oneLine.take(PreviewLength - 3) + "..." else oneLine
  }
}
