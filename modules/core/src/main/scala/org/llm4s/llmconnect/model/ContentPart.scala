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
 *  - '''Inline bytes are bounded''' by [[ContentPart.MaxInlineBytes]] (5 MiB), also when a part is
 *    read from JSON. An agent thread's every checkpoint is a full snapshot of its state, so
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

  /**
   * Text. A message whose every part is blank text is refused by `Message.validate`. A `null` text is
   * read as empty when the part is built, so no code that reads a part - validation, a codec, a
   * provider's encoder - has to guard against it.
   */
  final case class Text private (text: String) extends ContentPart {
    def kind: String = "text"
  }

  object Text {

    /** A text part; `null` is read as empty. */
    def apply(text: String): Text = new Text(if (text == null) "" else text)
  }

  /** An image; build one with [[ContentPart.image]]. */
  final case class Image private[model] (source: MediaSource, mediaType: ImageMediaType) extends ContentPart {
    def kind: String = s"image (${source.kind}, ${mediaType.mimeType})"
  }

  /** A document - a PDF, text, Markdown, HTML or CSV file; build one with [[ContentPart.document]]. */
  final case class Document private[model] (source: MediaSource, mediaType: DocumentMediaType) extends ContentPart {
    def kind: String = s"document (${source.kind}, ${mediaType.mimeType})"
  }

  /**
   * The bound on an inline part's bytes: 5 MiB. One bound for every part, wherever it is built - a smart
   * constructor or the codec reading stored or imported JSON - so no part over it can exist.
   */
  val MaxInlineBytes: Int = 5 * 1024 * 1024

  /** An image from `source`, checked as described on [[ContentPart]]. */
  def image(source: MediaSource, mediaType: ImageMediaType): Result[Image] =
    notNull(mediaType).flatMap(_ => checked(source, MaxInlineBytes)).map(new Image(_, mediaType))

  /** A document from `source`, checked as described on [[ContentPart]]. */
  def document(source: MediaSource, mediaType: DocumentMediaType): Result[Document] =
    notNull(mediaType).flatMap(_ => checked(source, MaxInlineBytes)).map(new Document(_, mediaType))

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
   * `parts` with `f` applied to each text part, in place, and every media part kept as it is: for
   * code that rewrites a message's text - a compressor - and must leave each text beside the media it
   * describes.
   */
  private[llm4s] def mapText(parts: Vector[ContentPart])(f: String => String): Vector[ContentPart] =
    parts.map {
      case Text(text) => Text(f(text))
      case media      => media
    }

  /**
   * The tokens a media part is estimated to cost a model, for context budgets: a message's text view leaves its
   * media out, so a counter that read only the text would let images and documents overflow the window. The
   * true cost is the provider's and depends on the image's size or the document's pages; these err high, which
   * only prunes early:
   *  - an image, [[EstimatedImageTokens]] (a large image on Anthropic; more than OpenAI's high-detail cost);
   *  - an inline text document (plain text, Markdown, HTML, CSV), its bytes over four;
   *  - an inline PDF, its bytes over 32, at least [[EstimatedDocumentTokens]];
   *  - a document at a URL or in a provider file, whose size is unknown here, [[EstimatedReferencedDocumentTokens]]:
   *    a large document's cost, since nothing here bounds it; a caller that knows a document is small and is short of
   *    room can send it inline, where its size is counted.
   * Text parts are 0: they are counted by the tokenizer.
   */
  private[llm4s] def estimatedTokens(part: ContentPart): Int = part match {
    case _: Text                                             => 0
    case _: Image                                            => EstimatedImageTokens
    case Document(inline: MediaSource.Inline, MediaType.Pdf) => math.max(EstimatedDocumentTokens, inline.length / 32)
    case Document(inline: MediaSource.Inline, _)             => inline.length / 4
    case _: Document                                         => EstimatedReferencedDocumentTokens
  }

  /** What [[estimatedTokens]] gives an image. */
  private[llm4s] val EstimatedImageTokens: Int = 1600

  /**
   * What [[estimatedTokens]] gives a referenced document, whose size is unknown: 50,000 tokens, about a 25-page PDF
   * on the costlier providers. Unknown size errs high.
   */
  private[llm4s] val EstimatedReferencedDocumentTokens: Int = 50000

  /** The least [[estimatedTokens]] gives an inline PDF. */
  private[llm4s] val EstimatedDocumentTokens: Int = 3000

  /** Whether `part` is an image or a document. */
  def isMedia(part: ContentPart): Boolean = !part.isInstanceOf[Text]

  private def notNull(mediaType: MediaType): Result[Unit] =
    if (mediaType == null) Left(ValidationError("mediaType", "a media part needs a media type")) else Right(())

  // a null anywhere in the source is refused here, so no part that reaches validation, a codec or a provider holds one
  private def checked(source: MediaSource, maxInlineBytes: Int): Result[MediaSource] =
    source match {
      case null                  => Left(ValidationError("source", "a media part needs a source"))
      case MediaSource.Url(null) => Left(ValidationError("source", "a media URL needs a URI"))
      case i: MediaSource.Inline if i.bytes.asInstanceOf[AnyRef] == null =>
        Left(ValidationError("source", "inline media needs its bytes"))
      case MediaSource.ProviderFile(provider, id) if provider == null || id == null =>
        Left(ValidationError("source", "a provider file needs a provider and an id"))
      case inline: MediaSource.Inline if inline.length == 0 =>
        Left(ValidationError("source", "inline media has no bytes"))
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

  private def valid(part: Result[ContentPart]): ContentPart =
    part.fold(e => throw new IllegalArgumentException(s"invalid content part: ${e.message}"), identity)

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
        case "text" => Text(json("text").str)
        // read through the smart constructors: stored or imported JSON is checked as a built part is
        case "image" =>
          valid(image(readSource(json("source")), mediaType(json, "image") { case i: ImageMediaType => i }))
        case "document" =>
          valid(document(readSource(json("source")), mediaType(json, "document") { case d: DocumentMediaType => d }))
        case other => throw new IllegalArgumentException(s"unknown content part '$other'")
      }
  )
}
