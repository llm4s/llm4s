package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.media.{ DocumentMediaType, ImageMediaType, MediaSource, MediaType }
import org.llm4s.types.Result
import upickle.default.{ readwriter, ReadWriter => RW }

import java.net.URI
import java.util.Base64

/**
 * One part of a user or tool message's content: text, an image, or a document.
 *
 * Text is built directly. An image or a document is built with [[ContentPart.image]] or
 * [[ContentPart.document]], which check its source and return a `Result`:
 *
 *  - '''Inline bytes are bounded''' by [[ContentPart.MaxInlineBytes]] (5 MiB) unless the caller
 *    gives its own bound. An agent thread's every checkpoint is a full snapshot of its state, so
 *    each retained checkpoint keeps a copy of every inline part - base64-encoded, a third larger -
 *    for as long as the message stays in the thread. Larger media belongs at a
 *    [[org.llm4s.media.MediaSource.Url]] or in a [[org.llm4s.media.MediaSource.ProviderFile]],
 *    which a message holds by reference.
 *  - '''A URL is absolute''', and not a `data:` URL: that is inline bytes by another name, and
 *    would escape the bound. Use [[org.llm4s.media.MediaSource.Inline]].
 *  - '''A provider file''' names a provider and an id, neither blank.
 *
 * Each provider sends the parts its API takes and fails the request with an
 * [[org.llm4s.error.UnsupportedContentPartError]] for any other; nothing is dropped or turned
 * into text. Which provider takes which parts is listed in `docs/guide/multimodal.md`.
 */
@Stable
sealed trait ContentPart extends Product with Serializable {

  /**
   * The part's kind, for errors and logs: `text`, or the category, source and media type of an image or
   * document, e.g. `image (url, image/png)` or `document (provider file openai, application/pdf)`.
   */
  def kind: String
}

object ContentPart {

  /** Text. A message whose every part is blank text is refused by `Message.validate`. */
  final case class Text(text: String) extends ContentPart {
    def kind: String = "text"
  }

  /** An image; build one with [[ContentPart.image]]. */
  final case class Image private[model] (source: MediaSource, mediaType: ImageMediaType) extends ContentPart {
    def kind: String = s"image (${source.kind}, ${mediaType.mimeType})"
  }

  /** A document - a PDF, text, Markdown, HTML or CSV file; build one with [[ContentPart.document]]. */
  final case class Document private[model] (source: MediaSource, mediaType: DocumentMediaType) extends ContentPart {
    def kind: String = s"document (${source.kind}, ${mediaType.mimeType})"
  }

  /** The default bound on an inline part's bytes: 5 MiB. */
  val MaxInlineBytes: Int = 5 * 1024 * 1024

  /** An image from `source`, checked as described on [[ContentPart]], with inline bytes bounded by [[MaxInlineBytes]]. */
  def image(source: MediaSource, mediaType: ImageMediaType): Result[Image] =
    image(source, mediaType, MaxInlineBytes)

  /** An image from `source`, checked as described on [[ContentPart]], with inline bytes bounded by `maxInlineBytes`. */
  def image(source: MediaSource, mediaType: ImageMediaType, maxInlineBytes: Int): Result[Image] =
    checked(source, maxInlineBytes).map(new Image(_, mediaType))

  /** A document from `source`, checked as described on [[ContentPart]], with inline bytes bounded by [[MaxInlineBytes]]. */
  def document(source: MediaSource, mediaType: DocumentMediaType): Result[Document] =
    document(source, mediaType, MaxInlineBytes)

  /** A document from `source`, checked as described on [[ContentPart]], with inline bytes bounded by `maxInlineBytes`. */
  def document(source: MediaSource, mediaType: DocumentMediaType, maxInlineBytes: Int): Result[Document] =
    checked(source, maxInlineBytes).map(new Document(_, mediaType))

  /** The text parts of `parts`, joined by newlines: a message's text view. Empty when there are none. */
  def text(parts: Seq[ContentPart]): String =
    parts.collect { case Text(t) => t }.mkString("\n")

  /** The number of inline bytes `parts` carry, in total. */
  def inlineBytes(parts: Seq[ContentPart]): Long =
    parts.map {
      case Image(inline: MediaSource.Inline, _)    => inline.length.toLong
      case Document(inline: MediaSource.Inline, _) => inline.length.toLong
      case _                                       => 0L
    }.sum

  /**
   * `parts` with its text parts replaced by one part, `text`, where the first of them was; the
   * media parts are kept as they are, in place. Unchanged when there is no text part. For code
   * that rewrites a message's text view - a compressor - and must not touch its media.
   */
  private[llm4s] def withText(parts: Vector[ContentPart], text: String): Vector[ContentPart] =
    parts.indexWhere(_.isInstanceOf[Text]) match {
      case -1 => parts
      case first =>
        parts.zipWithIndex.collect {
          case (_: Text, `first`)           => Text(text)
          case (media, _) if isMedia(media) => media
        }
    }

  /** Whether `part` is an image or a document. */
  def isMedia(part: ContentPart): Boolean = !part.isInstanceOf[Text]

  private def checked(source: MediaSource, maxInlineBytes: Int): Result[MediaSource] =
    source match {
      case _ if maxInlineBytes <= 0 =>
        Left(ValidationError("maxInlineBytes", s"must be positive, was $maxInlineBytes"))
      case inline: MediaSource.Inline if inline.length > maxInlineBytes =>
        Left(
          ValidationError(
            "source",
            s"an inline part of ${inline.length} bytes is over the bound of $maxInlineBytes bytes; every checkpoint " +
              "copies a message's inline bytes, so put larger media at a Url or a ProviderFile"
          )
        )
      case MediaSource.Url(uri) if !uri.isAbsolute =>
        Left(ValidationError("source", s"a media URL must be absolute, was '$uri'"))
      case MediaSource.Url(uri) if uri.getScheme.equalsIgnoreCase("data") =>
        Left(ValidationError("source", "a data: URL is inline bytes; use MediaSource.Inline, which is bounded"))
      case MediaSource.ProviderFile(provider, id) if provider.trim.isEmpty || id.trim.isEmpty =>
        Left(ValidationError("source", "a provider file needs a provider and an id"))
      case ok => Right(ok)
    }

  // ---- codec ----

  private def sourceJson(source: MediaSource): ujson.Value = source match {
    case MediaSource.Url(uri) => ujson.Obj("type" -> "url", "uri" -> uri.toString)
    case inline: MediaSource.Inline =>
      ujson.Obj("type" -> "inline", "data" -> Base64.getEncoder.encodeToString(inline.bytes.asInstanceOf[Array[Byte]]))
    case MediaSource.ProviderFile(provider, id) =>
      ujson.Obj("type" -> "providerFile", "provider" -> provider, "id" -> id)
  }

  private def readSource(json: ujson.Value): MediaSource = json("type").str match {
    case "url"          => MediaSource.Url(URI.create(json("uri").str))
    case "inline"       => MediaSource.Inline(IArray.unsafeFromArray(Base64.getDecoder.decode(json("data").str)))
    case "providerFile" => MediaSource.ProviderFile(json("provider").str, json("id").str)
    case other          => throw new IllegalArgumentException(s"unknown media source '$other'")
  }

  private def mediaType[T <: MediaType](json: ujson.Value, family: String)(pick: PartialFunction[MediaType, T]): T = {
    val mime = json("mediaType").str
    MediaType.fromMimeType(mime).collect(pick).getOrElse {
      throw new IllegalArgumentException(s"'$mime' is not a known $family media type")
    }
  }

  // stored parts were checked when they were built, and a thread must restore: the reader does not check again
  implicit val rw: RW[ContentPart] = readwriter[ujson.Value].bimap[ContentPart](
    {
      case Text(text) => ujson.Obj("type" -> "text", "text" -> text)
      case Image(source, mt) =>
        ujson.Obj("type" -> "image", "mediaType" -> mt.mimeType, "source" -> sourceJson(source))
      case Document(source, mt) =>
        ujson.Obj("type" -> "document", "mediaType" -> mt.mimeType, "source" -> sourceJson(source))
    },
    json =>
      json("type").str match {
        case "text"  => Text(json("text").str)
        case "image" => new Image(readSource(json("source")), mediaType(json, "image") { case i: ImageMediaType => i })
        case "document" =>
          new Document(readSource(json("source")), mediaType(json, "document") { case d: DocumentMediaType => d })
        case other => throw new IllegalArgumentException(s"unknown content part '$other'")
      }
  )
}
