package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ ContentPart, Message }
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.types.Result

/**
 * The content parts the Gemini API and Vertex AI take, in the `parts` of a `contents` entry, shared by
 * [[GeminiClient]] and [[VertexAIClient]], which speak the same request format.
 *
 * A user message's text parts are `text` parts; an image or document is `inlineData` (`mimeType`, base64
 * `data`) when inline, and `fileData` (`mimeType`, `fileUri`) at a URL or in a file uploaded to the client's
 * own provider, whose id is the `fileUri`. A tool result is a `functionResponse`, whose `response.result` is
 * its text: media in one is refused. Any other part fails the request with an
 * [[org.llm4s.error.UnsupportedContentPartError]] before anything is sent.
 */
private[provider] object GeminiParts {

  /** The `parts` entry for each user-message part `provider`'s client sends. */
  def user(provider: String): PartialFunction[ContentPart, ujson.Value] = {
    case ContentPart.Text(text)                                      => ujson.Obj("text" -> text)
    case ContentPart.Image(source, mt) if sends(provider, source)    => encode(source, mt)
    case ContentPart.Document(source, mt) if sends(provider, source) => encode(source, mt)
  }

  /** `Right` when `provider`'s client can send every part of `messages`. */
  def check(provider: String, messages: Seq[Message]): Result[Unit] =
    PartEncoding.check(provider, messages, user(provider), PartEncoding.textOnly)

  private def sends(provider: String, source: MediaSource): Boolean = source match {
    case MediaSource.ProviderFile(owner, _) => owner == provider
    case _                                  => true
  }

  private def encode(source: MediaSource, mediaType: MediaType): ujson.Value = source match {
    case inline: MediaSource.Inline =>
      ujson.Obj("inlineData" -> ujson.Obj("mimeType" -> mediaType.mimeType, "data" -> PartEncoding.base64(inline)))
    case MediaSource.Url(uri)            => fileData(uri.toString, mediaType)
    case MediaSource.ProviderFile(_, id) => fileData(id, mediaType)
  }

  private def fileData(uri: String, mediaType: MediaType): ujson.Value =
    ujson.Obj("fileData" -> ujson.Obj("mimeType" -> mediaType.mimeType, "fileUri" -> uri))
}
