package org.llm4s.testing

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.testing.model.Interaction
import org.llm4s.types.Result

import java.nio.file.{ Files, Paths }

/**
 * Matching mode for PlaybackLLMClient.
 */
sealed trait MatchingMode
object MatchingMode {

  /**
   * Requires exact match of conversation and options.
   */
  case object Strict extends MatchingMode

  /**
   * Ignores whitespace differences and empty options fields.
   */
  case object Lenient extends MatchingMode

  /**
   * Only matches on message content, ignoring options entirely.
   */
  case object ContentOnly extends MatchingMode
}

/**
 * Replays recorded interactions for deterministic testing.
 *
 * @example
 * {{{
 * // Load recordings
 * val playback = PlaybackLLMClient.fromFile("recordings/test.json")
 *
 * // Use in tests
 * val result = playback.complete(conversation, options)
 *
 * // Use lenient matching for flexibility
 * val lenientPlayback = PlaybackLLMClient.fromFile("recordings/test.json", MatchingMode.Lenient)
 * }}}
 *
 * @param recordings List of recorded interactions
 * @param mode Matching mode for finding recorded responses
 */
class PlaybackLLMClient(
  recordings: List[Interaction],
  mode: MatchingMode = MatchingMode.Strict
) extends LLMClient {

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    findMatch(conversation, options) match {
      case Some(interaction) => Right(interaction.response)
      case None =>
        Left(
          ValidationError("conversation", s"No recorded interaction found. Mode: $mode, Recordings: ${recordings.size}")
        )
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    findMatch(conversation, options) match {
      case Some(interaction) =>
        onChunk(StreamedChunk(interaction.response.id, Some(interaction.response.content)))
        Right(interaction.response)
      case None =>
        Left(ValidationError("conversation", s"No recorded interaction found. Mode: $mode"))
    }

  private def findMatch(conversation: Conversation, options: CompletionOptions): Option[Interaction] =
    mode match {
      case MatchingMode.Strict =>
        recordings.find(i => i.conversation == conversation && i.options == options)

      case MatchingMode.Lenient =>
        recordings.find(i => normalizeConversation(i.conversation) == normalizeConversation(conversation))

      case MatchingMode.ContentOnly =>
        val inputContent = conversation.messages.map(_.content.trim).mkString("\n")
        recordings.find { i =>
          val recordedContent = i.conversation.messages.map(_.content.trim).mkString("\n")
          recordedContent == inputContent
        }
    }

  private def normalizeConversation(conversation: Conversation): List[String] =
    conversation.messages.map(_.content.trim.replaceAll("\\s+", " ")).toList

  /**
   * Get the number of recorded interactions.
   */
  def recordingCount: Int = recordings.size

  override def getContextWindow(): Int     = 4096
  override def getReserveCompletion(): Int = 1024
}

object PlaybackLLMClient {

  /**
   * Load recordings from a JSON file with strict matching.
   */
  def fromFile(path: String): PlaybackLLMClient =
    fromFile(path, MatchingMode.Strict)

  /**
   * Load recordings from a JSON file with specified matching mode.
   */
  def fromFile(path: String, mode: MatchingMode): PlaybackLLMClient = {
    import upickle.default._
    val jsonString = new String(Files.readAllBytes(Paths.get(path)))
    val recordings = read[List[Interaction]](jsonString)
    new PlaybackLLMClient(recordings, mode)
  }

  /**
   * Create from in-memory recordings.
   */
  def fromRecordings(recordings: List[Interaction], mode: MatchingMode = MatchingMode.Strict): PlaybackLLMClient =
    new PlaybackLLMClient(recordings, mode)
}
