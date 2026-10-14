package org.llm4s.llmconnect.provider

import org.llm4s.error.{ UnsupportedContentPartError, ValidationError }
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
   * builds its request in steps that cannot fail, so it checks the conversation first. It asks only
   * `isDefinedAt`: nothing is encoded, so inline media is not base64-encoded twice per request.
   */
  def check(
    provider: String,
    messages: Seq[Message],
    user: PartialFunction[ContentPart, Any],
    tool: PartialFunction[ContentPart, Any]
  ): Result[Unit] =
    messages.iterator
      .map {
        case um: UserMessage => covered(provider, um.role, um.parts, user)
        case tm: ToolMessage => covered(provider, tm.role, tm.parts, tool)
        case _               => Right(())
      }
      .collectFirst { case Left(error) => Left(error) }
      .getOrElse(Right(()))

  private def covered(
    provider: String,
    role: MessageRole,
    parts: Seq[ContentPart],
    encode: PartialFunction[ContentPart, Any]
  ): Result[Unit] =
    parts.find(!encode.isDefinedAt(_)).fold[Result[Unit]](Right(()))(part => Left(unsupported(provider, part, role)))

  /**
   * Whether `uri` is a web URL (`http` or `https`): the only URLs a provider that fetches media itself can
   * read. A `gs://` or `s3://` URL sent to one would pass the check and fail only after the request is sent.
   */
  def isWebUrl(uri: java.net.URI): Boolean =
    Option(uri.getScheme).exists(s => s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"))

  /**
   * `Right` when every inline part of `messages` is within the provider's own limit for it: `limit` gives the
   * most inline bytes `provider` takes for a part, or `None` for no limit of its own. A provider's limit can be
   * lower than `ContentPart.MaxInlineBytes`, so a part llm4s accepts would otherwise fail only at the provider.
   */
  def checkInlineLimits(provider: String, messages: Seq[Message], limit: ContentPart => Option[Long]): Result[Unit] =
    messages.iterator
      .flatMap(partsOf)
      .map(part => (part, ContentPart.inlineBytes(Vector(part)), limit(part)))
      .collectFirst {
        case (part, size, Some(max)) if size > max =>
          Left(
            ValidationError(
              "parts",
              s"$provider takes at most $max bytes for an inline ${part.kind}; this one has $size"
            )
          )
      }
      .getOrElse(Right(()))

  /** `Right` when the inline parts of `messages`, base64-encoded, total at most `maxEncodedBytes`: a limit on a whole request. */
  def checkInlineTotal(provider: String, messages: Seq[Message], maxEncodedBytes: Long): Result[Unit] = {
    val encoded = messages.flatMap(partsOf).map(p => base64Length(ContentPart.inlineBytes(Vector(p)))).sum
    if (encoded <= maxEncodedBytes) Right(())
    else
      Left(
        ValidationError(
          "parts",
          s"$provider takes at most $maxEncodedBytes bytes of inline media in one request; this one has $encoded, base64-encoded"
        )
      )
  }

  /** The length of `bytes` bytes once base64-encoded. */
  def base64Length(bytes: Long): Long = (bytes + 2) / 3 * 4

  private def partsOf(message: Message): Vector[ContentPart] = message match {
    case um: UserMessage => um.parts
    case tm: ToolMessage => tm.parts
    case _               => Vector.empty
  }

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
