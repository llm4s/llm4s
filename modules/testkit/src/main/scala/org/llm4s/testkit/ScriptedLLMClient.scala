package org.llm4s.testkit

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result
import java.time.Instant

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
 *   Reply.toolCall("get_weather", """{"city": "Paris"}"""),
 *   Reply.text("It is sunny in Paris.")
 * )
 * // ... run the code under test with `client` ...
 * client.callCount          // 2
 * client.calls.head.lastUserText
 * }}}
 *
 * A reply the code under test never asked for usually means it took a wrong branch. [[assertExhausted]] returns a
 * `Left` naming every reply of a [[ScriptedLLMClient.sequence]] script that no call used, so a test (or a fixture of
 * any test framework) can fail on it; [[unusedReplies]] and [[remaining]] give the same information as values.
 *
 * Every request is recorded ([[calls]]), whether it came through `complete` or `streamComplete`. A call the script
 * has no reply for does not hang or throw: it returns a `Left(`[[UnscriptedCallError]]`)` that names the call and
 * shows the last message, so a test that makes more calls than it scripted fails with a message that says why.
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
  script: Vector[Reply],
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

  /**
   * The replies of a [[ScriptedLLMClient.sequence]] script that no call has used yet, in script order. A client from
   * [[ScriptedLLMClient.respondingTo]] or [[ScriptedLLMClient.always]] has no fixed script, so for it this is always
   * empty.
   */
  def unusedReplies: Vector[Reply] = lock.synchronized(script.drop(recorded.size))

  /** The number of [[unusedReplies]]: 0 once every scripted reply has been used. */
  def remaining: Int = unusedReplies.size

  /**
   * `Right(())` when every reply of the script was used, otherwise a `Left` whose message names each unused reply:
   *
   * {{{
   * 1 of 2 scripted replies was not used (the code under test made 1 call):
   *   reply 2: text "It is 21 degrees in Oslo."
   * }}}
   *
   * Call it at the end of a test, or from a test framework's fixture, to fail a test whose code under test asked
   * for fewer replies than were scripted. Always `Right(())` for a client without a fixed script ([[unusedReplies]]).
   */
  def assertExhausted(): Either[String, Unit] = {
    val (made, unused) = lock.synchronized((recorded.size, script.drop(recorded.size)))
    if (unused.isEmpty) Right(())
    else {
      val verb  = if (unused.size == 1) "was" else "were"
      val calls = if (made == 1) "call" else "calls"
      val heading =
        s"${unused.size} of ${script.size} scripted replies $verb not used (the code under test made $made $calls):"
      val lines = unused.zipWithIndex.map { case (reply, offset) =>
        s"  reply ${made + offset + 1}: ${ScriptedLLMClient.describe(reply)}"
      }
      Left((heading +: lines).mkString("\n"))
    }
  }

  /** A fresh client with the same script and no recorded calls, reporting another context window. */
  def withContextWindow(tokens: Int): ScriptedLLMClient =
    new ScriptedLLMClient(respond, script, tokens, reserveCompletion, chunkSize)

  /** A fresh client with the same script and no recorded calls, reserving another number of tokens for the answer. */
  def withReserveCompletion(tokens: Int): ScriptedLLMClient =
    new ScriptedLLMClient(respond, script, contextWindow, tokens, chunkSize)

  /** A fresh client with the same script and no recorded calls, streaming text in chunks of this size (at least 1). */
  def withChunkSize(characters: Int): ScriptedLLMClient =
    new ScriptedLLMClient(respond, script, contextWindow, reserveCompletion, math.max(1, characters))

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
        Left(UnscriptedCallError(index + 1, reason, describeLast(conversation)))
      case Right(reply) =>
        reply.failure match {
          case Some(error) => Left(error)
          case None        => Right((index, completionOf(index, reply)))
        }
    }
  }

  private def completionOf(index: Int, reply: Reply): Completion = {
    val calls = reply.toolCalls.zipWithIndex.map { case (call, position) =>
      if (call.id.isEmpty) call.withId(s"call-${index + 1}-${position + 1}") else call
    }
    Completion(
      id = s"scripted-${index + 1}",
      created = Instant.EPOCH,
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

  private def describeLast(conversation: Conversation): Option[String] =
    conversation.messages.lastOption.map(message =>
      s"${message.role}: \"${ScriptedLLMClient.preview(message.content)}\""
    )
}

object ScriptedLLMClient {

  private val DefaultContextWindow     = 128000
  private val DefaultReserveCompletion = 4096
  private val DefaultChunkSize         = 16
  private val PreviewLength            = 80

  /**
   * A client that answers call 1 with the first reply, call 2 with the second, and so on. A call past the end of
   * the script returns a `Left(`[[UnscriptedCallError]]`)` that names the call.
   */
  def sequence(replies: Reply*): ScriptedLLMClient = {
    val script = replies.toVector
    create(
      (index, _) =>
        script.lift(index).toRight(s"the script has ${script.size} ${if (script.size == 1) "reply" else "replies"}"),
      script
    )
  }

  /**
   * A client that chooses each reply from the conversation. A conversation no case matches returns a
   * `Left(`[[UnscriptedCallError]]`)` that names the call.
   *
   * The rules run under the client's lock, so they must not block on another call to the same client, and an
   * exception a rule throws is not caught: it propagates from `complete` or `streamComplete`.
   *
   * {{{
   * ScriptedLLMClient.respondingTo {
   *   case c if c.messages.lastOption.exists(_.content.contains("capital")) => Reply.text("Paris")
   * }
   * }}}
   */
  def respondingTo(rules: PartialFunction[Conversation, Reply]): ScriptedLLMClient =
    create((_, conversation) => rules.lift(conversation).toRight("no rule matched the conversation"), Vector.empty)

  /** A client that gives the same reply to every call. */
  def always(reply: Reply): ScriptedLLMClient = create((_, _) => Right(reply), Vector.empty)

  private def create(respond: (Int, Conversation) => Either[String, Reply], script: Vector[Reply]): ScriptedLLMClient =
    new ScriptedLLMClient(respond, script, DefaultContextWindow, DefaultReserveCompletion, DefaultChunkSize)

  /** One line saying what a reply would have returned, for [[ScriptedLLMClient.assertExhausted]]. */
  private def describe(reply: Reply): String =
    reply.failure match {
      case Some(error) => s"failure ${error.getClass.getSimpleName}: ${preview(error.message)}"
      case None =>
        val text = Option.when(reply.content.nonEmpty || reply.toolCalls.isEmpty)(s"text \"${preview(reply.content)}\"")
        val tools = reply.toolCalls.map(call => s"tool call ${call.name}(${preview(call.arguments.render())})")
        (text.toList ++ tools).mkString(", ")
    }

  private[testkit] def preview(text: String): String = {
    val oneLine = text.replace('\n', ' ')
    if (oneLine.length > PreviewLength) oneLine.take(PreviewLength - 3) + "..." else oneLine
  }
}
