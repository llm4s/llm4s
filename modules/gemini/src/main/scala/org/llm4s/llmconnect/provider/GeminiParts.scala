package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ ContentPart, Message }
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.types.Result

/**
 * The content parts the Gemini API and Vertex AI take, in the `parts` of a `contents` entry, shared by
 * [[GeminiClient]] and [[VertexAIClient]], which speak the same request format.
 *
 * A user message's text parts are `text` parts; an image or document is `inlineData` (`mimeType`, base64
 * `data`) when inline, and `fileData` (`mimeType`, `fileUri`) for a reference the client resolves to a URI.
 * Each client passes its own `fileUri`, because the two take different references: the Gemini API a web
 * URL or a file uploaded through its Files API, Vertex AI a Cloud Storage object. A tool result is a
 * `functionResponse`, whose `response.result` is its text: media in one is refused. Any other part fails
 * the request with an [[org.llm4s.error.UnsupportedContentPartError]] before anything is sent.
 */
private[provider] object GeminiParts {

  /** Resolves a reference to the URI sent as `fileData.fileUri`; `None` refuses it. */
  type FileUri = MediaSource => Option[String]

  /** The `parts` entry for each user-message part a client with `fileUri` sends. */
  def user(fileUri: FileUri): PartialFunction[ContentPart, ujson.Value] = {
    case ContentPart.Text(text)                                                    => ujson.Obj("text" -> text)
    case ContentPart.Image(source, mt) if ImageTypes(mt) && sends(fileUri, source) => encode(fileUri, source, mt)
    case ContentPart.Document(source, mt) if sends(fileUri, source)                => encode(fileUri, source, mt)
  }

  /** The image formats Gemini and Vertex AI take that llm4s names: PNG, JPEG and WebP (HEIC and HEIF have no `MediaType`). */
  val ImageTypes: Set[org.llm4s.media.ImageMediaType] = Set(MediaType.Png, MediaType.Jpeg, MediaType.WebP)

  /** `Right` when a client with `fileUri` can send every part of `messages`. */
  def check(provider: String, messages: Seq[Message], fileUri: FileUri): Result[Unit] =
    PartEncoding
      .check(provider, messages, user(fileUri), PartEncoding.textOnly)
      .flatMap(_ => PartEncoding.checkRequestSize(provider, messages, MaxRequestBytes))

  /**
   * Gemini and Vertex AI take a request of at most 20 MB, its text and inline data together: each part is
   * within `ContentPart.MaxInlineBytes`, but several may not be together, so the whole request is bounded too.
   */
  val MaxRequestBytes: Long = 20000000L

  /**
   * The Gemini API's references: a web URL (`http`/`https`), or a file uploaded to `provider` through the
   * Files API - by its `uri`, or by its name (`files/abc`, or `abc`), resolved against `baseUrl` as the
   * Files API forms the `uri` (`<baseUrl>/files/abc`).
   */
  def geminiFileUri(provider: String, baseUrl: String): FileUri = {
    case MediaSource.Url(uri) if PartEncoding.isWebUrl(uri) => Some(uri.toString)
    case MediaSource.ProviderFile(owner, id) if owner == provider =>
      if (id.startsWith("https://")) Some(id)
      else Some(s"${baseUrl.stripSuffix("/")}/${if (id.startsWith("files/")) id else s"files/$id"}")
    case _ => None
  }

  /** Vertex AI's references: a Cloud Storage object (`gs://`). It has no file store of its own. */
  val vertexFileUri: FileUri = {
    case MediaSource.Url(uri) if Option(uri.getScheme).exists(_.equalsIgnoreCase("gs")) => Some(uri.toString)
    case _                                                                              => None
  }

  private def sends(fileUri: FileUri, source: MediaSource): Boolean = source match {
    case _: MediaSource.Inline => true
    case reference             => fileUri(reference).isDefined
  }

  private def encode(fileUri: FileUri, source: MediaSource, mediaType: MediaType): ujson.Value = source match {
    case inline: MediaSource.Inline =>
      ujson.Obj("inlineData" -> ujson.Obj("mimeType" -> mediaType.mimeType, "data" -> PartEncoding.base64(inline)))
    case reference =>
      ujson.Obj(
        "fileData" -> ujson.Obj("mimeType" -> mediaType.mimeType, "fileUri" -> fileUri(reference).getOrElse(""))
      )
  }
}
