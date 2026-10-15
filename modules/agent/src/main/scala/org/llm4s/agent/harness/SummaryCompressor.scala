package org.llm4s.agent.harness

import org.llm4s.agent.graph.middleware.ModelContext
import org.llm4s.annotation.Experimental
import org.llm4s.context.{ ConversationTokenCounter, HistoryCompressor }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  ContentPart,
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
  def compress(
    previous: Option[String],
    messages: Vector[Message],
    maxTokens: Int,
    context: ModelContext
  ): Result[String]

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
    require(
      maxInputTokens >= MinInputTokens,
      s"SummaryCompressor.model: maxInputTokens must be at least $MinInputTokens, was $maxInputTokens"
    )
    new Model(client, options, maxInputTokens)

  private val DigestHeader = "[HISTORY_SUMMARY]"

  /** The most characters the marker on a cut transcript line takes. */
  private val CutMarkerChars = 48

  /** The least `maxInputTokens` of a model compressor: a cut line keeps 16 characters beside its marker. */
  val MinInputTokens: Int = 16

  /**
   * A digest summary is its media lines (`Media: ...`) first, then the digest. The media lines name every media part
   * the summary covers, since `HistoryCompressor` keeps only what its patterns extract; they come first and take at
   * most half the bound, so the cut a bound makes falls on the digest, never on them.
   */
  final private class Digest(counter: ConversationTokenCounter) extends SummaryCompressor:
    def compress(
      previous: Option[String],
      messages: Vector[Message],
      maxTokens: Int,
      context: ModelContext
    ): Result[String] =
      val budget = maxTokens.toLong * 4
      // only lines in exactly the formats this compressor writes are media lines; any other line is summary prose
      val (oldMedia, oldDigest) =
        previous.fold(Vector.empty[String])(_.linesIterator.toVector).partition(l => mediaCounts(l).isDefined)
      val media = mediaText(oldMedia ++ mediaLine(messages), budget / 2)
      val room  = math.max(0L, budget - media.fold(0L)(_.length.toLong + 1))
      val old   = oldDigest.mkString("\n").trim
      if maxTokens < MinSummaryTokens then
        Left(
          ValidationError("maxTokens", s"a digest summary needs at least $MinSummaryTokens tokens, was $maxTokens")
        )
      else
        digestOf(messages, maxTokens)
          .flatMap { added =>
            val extended = Vector(old, added).filter(_.nonEmpty).mkString("\n")
            if old.isEmpty || extended.length.toLong <= room then Right(extended)
            else digestOf(UserMessage(old) +: messages, math.max(1L, room / 4).toInt)
          }
          .map(body => (media.toVector :+ SummarizationMiddleware.cut(body, room)).filter(_.nonEmpty).mkString("\n"))

    /**
     * The digest of `messages`. Media parts are first named by kind in their message's text, so `HistoryCompressor`
     * digests every block (it returns a block with media unchanged); and any message it still returns as it is - a
     * block it kept, or input it found already digested - joins the digest as its transcript line, never dropped.
     */
    private def digestOf(messages: Vector[Message], maxTokens: Int): Result[String] =
      HistoryCompressor
        .compressToDigest(messages.map(textOnly), counter, capTokens = maxTokens, keepLastK = 0)
        .map(_.map {
          case SystemMessage(c) if c.startsWith(DigestHeader) => c.stripPrefix(DigestHeader).trim
          case kept                                           => rendered(kept)
        })
        .map(_.filter(_.nonEmpty).mkString("\n"))

  /**
   * The least bound a summary may have: a digest summary's counted media line (at most about 60 characters) always
   * fits in half of it, whole.
   */
  val MinSummaryTokens: Int = 32

  private val MediaPrefix = "Media: "

  /** The media parts of `messages`, named by kind, as one media line. */
  private def mediaLine(messages: Vector[Message]): Option[String] =
    val media = messages.flatMap {
      case u: UserMessage => u.parts.filter(ContentPart.isMedia)
      case t: ToolMessage => t.parts.filter(ContentPart.isMedia)
      case _              => Vector.empty
    }
    Option.when(media.nonEmpty)(media.map(m => s"[${m.kind}]").mkString(MediaPrefix, ", ", ""))

  /**
   * `lines` (media lines) as one media text within `max` characters: as they are when they fit, else one counted line
   * adding up every kind, which fits whole in half of a [[MinSummaryTokens]] bound.
   */
  private def mediaText(lines: Vector[String], max: Long): Option[String] =
    val joined = lines.mkString("\n")
    if lines.isEmpty then None
    else if joined.length.toLong <= max then Some(joined)
    else Some(counted(lines.flatMap(mediaCounts).foldLeft(Map.empty[String, Long])(add)))

  /** The prefix of a media line that counts parts rather than naming them. */
  private val CountedPrefix = s"${MediaPrefix}counted "

  private val Kind = "(image|document)"

  /** A named line: `Media: [image (url, image/png)], [document (inline, application/pdf)]`. */
  private val Named = s"""\\Q$MediaPrefix\\E\\[$Kind \\([^()\\[\\]]*\\)\\](?:, \\[$Kind \\([^()\\[\\]]*\\)\\])*""".r

  /** A counted line: `Media: counted 3 image, 1 document`. */
  private val Counted = s"""\\Q$CountedPrefix\\E\\d{1,18} $Kind(?:, \\d{1,18} $Kind)*""".r

  private val NamedKind   = s"""\\[$Kind """.r
  private val CountedKind = s"""(\\d{1,18}) $Kind""".r

  /**
   * How many parts of each kind `line` records, when it is a media line in exactly a format this compressor writes:
   * one per named part, or a counted line's counts. `None` for any other line, which is prose and kept as such.
   */
  private def mediaCounts(line: String): Option[Map[String, Long]] =
    if Counted.matches(line) then
      Some(
        CountedKind
          .findAllMatchIn(line.stripPrefix(CountedPrefix))
          .map(m => Map(m.group(2) -> m.group(1).toLong))
          .foldLeft(Map.empty[String, Long])(add)
      )
    else if Named.matches(line) then
      Some(NamedKind.findAllMatchIn(line).map(m => Map(m.group(1) -> 1L)).foldLeft(Map.empty[String, Long])(add))
    else None

  private def add(a: Map[String, Long], b: Map[String, Long]): Map[String, Long] =
    // saturating: a count never wraps
    b.foldLeft(a) { (acc, kv) =>
      val sofar = acc.getOrElse(kv._1, 0L)
      acc.updated(kv._1, if Long.MaxValue - sofar < kv._2 then Long.MaxValue else sofar + kv._2)
    }

  private def counted(counts: Map[String, Long]): String =
    counts.toVector.sortBy(_._1).map((kind, n) => s"$n $kind").mkString(CountedPrefix, ", ", "")

  final private class Model(client: LLMClient, options: CompletionOptions, maxInputTokens: Int)
      extends SummaryCompressor:

    def compress(
      previous: Option[String],
      messages: Vector[Message],
      maxTokens: Int,
      context: ModelContext
    ): Result[String] =
      val accounted = context.accounted(client)
      chunks(messages.map(rendered))
        .foldLeft[Result[Option[String]]](Right(previous)) { (acc, chunk) =>
          acc.flatMap { summary =>
            accounted
              .complete(
                Conversation(Seq(SystemMessage(prompt(maxTokens)), UserMessage(input(summary, chunk)))),
                options.withMaxTokens(maxTokens)
              )
              .map(c => Some(SummarizationMiddleware.clamp(c.message.content.trim, maxTokens)))
          }
        }
        .map(_.getOrElse(""))

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
       |Drop pleasantries and repetition. Stay within about ${math.max(
        1L,
        maxTokens.toLong * 3 / 4
      )} words.""".stripMargin
      .replace('\n', ' ')

  /** One message of the transcript a model compressor is sent. */
  private def rendered(message: Message): String = message match
    case a: AssistantMessage =>
      val calls = a.toolCalls.map(c => s"[called ${c.name}(${c.arguments.render()}) as ${c.id}]")
      (Option(a.content).filter(_.nonEmpty).toVector ++ calls).mkString("assistant: ", " ", "")
    case t: ToolMessage => s"tool result for ${t.toolCallId}: ${withMedia(t.parts)}"
    case u: UserMessage => s"user: ${withMedia(u.parts)}"
    case other          => s"${other.role.toString.toLowerCase}: ${other.content}"

  /** `message` with each media part named by its kind in its text: what a digest can record of it. */
  private def textOnly(message: Message): Message = message match
    case u: UserMessage if u.parts.exists(ContentPart.isMedia) => UserMessage(withMedia(u.parts))
    case t: ToolMessage if t.parts.exists(ContentPart.isMedia) => ToolMessage(withMedia(t.parts), t.toolCallId)
    case other                                                 => other

  /** The text of `parts`, each media part named by its kind in place, never sent as bytes. */
  private def withMedia(parts: Vector[ContentPart]): String =
    parts
      .map {
        case ContentPart.Text(text) => text
        case media                  => s"[${media.kind}]"
      }
      .mkString(" ")
