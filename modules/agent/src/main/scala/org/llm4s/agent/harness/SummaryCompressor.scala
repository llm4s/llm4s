package org.llm4s.agent.harness

import org.llm4s.agent.graph.middleware.ModelContext
import org.llm4s.annotation.Experimental
import org.llm4s.context.{ ConversationTokenCounter, HistoryCompressor }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  Conversation,
  Message,
  SystemMessage,
  ToolMessage,
  UserMessage
}
import org.llm4s.types.Result

/**
 * Writes the summary a [[SummarizationMiddleware]] keeps (design §4.34).
 *
 * `compress` returns a summary of `previous` - the summary so far, if any - followed by `messages`, the messages
 * after it, never those it already covers: a summary is extended, not recomputed. It should stay within `maxTokens`
 * estimated tokens at four characters a token; the middleware cuts what it returns to that bound either way.
 *
 * A compressor that calls a model must call it through `context.accounted(client)`, so the call is counted in the
 * thread's usage, also when the model step later fails. Its result is pure state: when the model step runs again
 * (`recover`), it is called again.
 */
@Experimental
trait SummaryCompressor:
  def compress(previous: Option[String], messages: Vector[Message], maxTokens: Int, context: ModelContext): Result[String]

@Experimental
object SummaryCompressor:

  /** The default for a model compressor's `maxInputTokens`. */
  val DefaultMaxInputTokens: Int = 16000

  /**
   * Core's `HistoryCompressor` digest, with no model call: each user-assistant exchange becomes a line of the
   * identifiers, decisions, errors and outcomes found in it. An extension appends the new messages' digest to the
   * summary; when that would be over `maxTokens`, the summary and the new messages are digested together, which
   * `HistoryCompressor` consolidates within the cap.
   */
  def digest(counter: ConversationTokenCounter): SummaryCompressor = new Digest(counter)

  /**
   * A summary the model writes, through `context.accounted(client)`: given the summary so far and a transcript of the
   * new messages, it returns the extended summary, asked to stay within `maxTokens` (`options`' `maxTokens` is set to
   * it). A transcript over `maxInputTokens` (four characters a token) is folded in chunks, one call each, each
   * extending the summary the last call produced; a single message larger than a chunk is cut, and says so.
   */
  def model(
    client: LLMClient,
    options: CompletionOptions = CompletionOptions(),
    maxInputTokens: Int = DefaultMaxInputTokens
  ): SummaryCompressor =
    require(maxInputTokens >= 1, s"SummaryCompressor.model: maxInputTokens must be at least 1, was $maxInputTokens")
    new Model(client, options, maxInputTokens)

  private val DigestHeader = "[HISTORY_SUMMARY]"

  /** The most characters the marker on a cut transcript line takes. */
  private val CutMarkerChars = 48

  final private class Digest(counter: ConversationTokenCounter) extends SummaryCompressor:
    def compress(
      previous: Option[String],
      messages: Vector[Message],
      maxTokens: Int,
      context: ModelContext
    ): Result[String] =
      digestOf(messages, maxTokens).flatMap { added =>
        previous.filter(_.nonEmpty) match
          case None => Right(added)
          case Some(old) =>
            val extended = if added.isEmpty then old else s"$old\n$added"
            if extended.length.toLong <= maxTokens.toLong * 4 then Right(extended)
            else digestOf(UserMessage(old) +: messages, maxTokens)
      }

    private def digestOf(messages: Vector[Message], maxTokens: Int): Result[String] =
      HistoryCompressor
        .compressToDigest(messages, counter, capTokens = maxTokens, keepLastK = 0)
        .map(_.collect { case SystemMessage(c) if c.startsWith(DigestHeader) => c.stripPrefix(DigestHeader).trim })
        .map(_.filter(_.nonEmpty).mkString("\n"))

  final private class Model(client: LLMClient, options: CompletionOptions, maxInputTokens: Int)
      extends SummaryCompressor:

    def compress(
      previous: Option[String],
      messages: Vector[Message],
      maxTokens: Int,
      context: ModelContext
    ): Result[String] =
      val accounted = context.accounted(client)
      chunks(messages.map(rendered)).foldLeft[Result[Option[String]]](Right(previous)) { (acc, chunk) =>
        acc.flatMap { summary =>
          accounted
            .complete(
              Conversation(Seq(SystemMessage(prompt(maxTokens)), UserMessage(input(summary, chunk)))),
              options.withMaxTokens(maxTokens)
            )
            .map(c => Some(SummarizationMiddleware.clamp(c.message.content.trim, maxTokens)))
        }
      }.map(_.getOrElse(""))

    private def maxChars: Long = maxInputTokens.toLong * 4

    /** The transcript lines, grouped into chunks of at most `maxChars`; a line longer than that is cut. */
    private def chunks(lines: Vector[String]): Vector[Vector[String]] =
      val cut = lines.map { line =>
        if line.length.toLong <= maxChars then line
        else
          // the marker is at most CutMarkerChars, so a cut line fits its chunk whenever the chunk can hold a marker
          val kept = SummarizationMiddleware.cut(line, maxChars - CutMarkerChars)
          s"$kept ... [cut: ${line.length - kept.length} more characters]"
      }
      cut.foldLeft(Vector.empty[Vector[String]]) { (acc, line) =>
        acc.lastOption match
          case Some(last) if last.iterator.map(_.length.toLong + 1).sum + line.length <= maxChars =>
            acc.init :+ (last :+ line)
          case _ => acc :+ Vector(line)
      }

    private def input(summary: Option[String], chunk: Vector[String]): String =
      val old = summary.filter(_.nonEmpty).fold("(none yet)")(identity)
      s"Summary so far:\n$old\n\nNew messages:\n${chunk.mkString("\n")}"

  private def prompt(maxTokens: Int): String =
    s"""You keep a running summary of a conversation between a user and an AI agent that uses tools. You are given the
       |summary so far and the messages that followed it. Reply with the updated summary only: the summary so far
       |extended with what the new messages add. Keep every fact the agent may need to continue: the user's goals and
       |constraints, decisions made, identifiers, file paths, URLs, error messages and the results of tool calls.
       |Drop pleasantries and repetition. Stay within about ${math.max(1L, maxTokens.toLong * 3 / 4)} words.""".stripMargin
      .replace('\n', ' ')

  /** One message of the transcript a model compressor is sent. */
  private def rendered(message: Message): String = message match
    case a: AssistantMessage =>
      val calls = a.toolCalls.map(c => s"[called ${c.name}(${c.arguments.render()}) as ${c.id}]")
      (Option(a.content).filter(_.nonEmpty).toVector ++ calls).mkString("assistant: ", " ", "")
    case t: ToolMessage => s"tool result for ${t.toolCallId}: ${t.content}"
    case u: UserMessage => s"user: ${u.content}"
    case other          => s"${other.role.toString.toLowerCase}: ${other.content}"
