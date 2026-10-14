package org.llm4s.llmconnect.provider

import org.llm4s.error.UnsupportedContentPartError
import org.llm4s.llmconnect.model.{ ContentPart, Message, MessageRole, ToolMessage, UserMessage }
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.types.Result

import java.util.Base64

/**
 * What every chat client needs to map [[ContentPart]]s: a client describes the parts it sends as
 * a partial function from part to its wire form, and a part it does not cover fails the request
 * with an [[UnsupportedContentPartError]] - never dropped, never sent as text.
 */
private[llm4s] object PartEncoding {

  /** The error for `part` in a `role` message sent to `provider`. */
  def unsupported(provider: String, part: ContentPart, role: MessageRole): UnsupportedContentPartError =
    UnsupportedContentPartError(provider, part.kind, role.name)

  /** `parts`, each encoded with `encode`; the first part `encode` does not cover fails the whole. */
  def encodeAll[A](provider: String, role: MessageRole, parts: Seq[ContentPart])(
    encode: PartialFunction[ContentPart, A]
  ): Result[Vector[A]] =
    parts.find(!encode.isDefinedAt(_)) match {
      case Some(part) => Left(unsupported(provider, part, role))
      case None       => Right(parts.toVector.map(encode))
    }

  /**
   * `Right` when `user` covers every part of every user message in `messages`, and `tool` every
   * part of every tool message; else the error for the first part one does not. For a client that
   * builds its request in steps that cannot fail, so it checks the conversation first.
   */
  def check(
    provider: String,
    messages: Seq[Message],
    user: PartialFunction[ContentPart, Any],
    tool: PartialFunction[ContentPart, Any]
  ): Result[Unit] =
    messages.iterator
      .map {
        case um: UserMessage => encodeAll(provider, um.role, um.parts)(user).map(_ => ())
        case tm: ToolMessage => encodeAll(provider, tm.role, tm.parts)(tool).map(_ => ())
        case _               => Right(())
      }
      .collectFirst { case Left(error) => Left(error) }
      .getOrElse(Right(()))

  /** Covers text parts only, as their text: for a role whose wire format takes nothing else. */
  val textOnly: PartialFunction[ContentPart, String] = { case ContentPart.Text(text) => text }

  /** The bytes, base64-encoded. */
  def base64(inline: MediaSource.Inline): String = Base64.getEncoder.encodeToString(bytes(inline))

  /** A `data:` URL carrying the bytes, as OpenAI's `image_url` and `file_data` take them. */
  def dataUrl(inline: MediaSource.Inline, mediaType: MediaType): String =
    s"data:${mediaType.mimeType};base64,${base64(inline)}"

  /** A copy of the bytes, for an SDK that takes an array. */
  def bytes(inline: MediaSource.Inline): Array[Byte] = inline.bytes.asInstanceOf[Array[Byte]].clone()

  /** Whether `source` is a file uploaded to `provider`. */
  def isFileOf(provider: String, source: MediaSource): Boolean = source match {
    case MediaSource.ProviderFile(owner, _) => owner == provider
    case _                                  => false
  }
}
