package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.model.Citation
import org.llm4s.llmconnect.spi.ProviderConfigKey
import org.llm4s.types.Result

import scala.util.Try

/**
 * The sources a Gemini or Vertex AI answer was grounded in, read from a candidate's `groundingMetadata`, which
 * the API returns when a request enables a grounding tool (Google Search, Google Maps, or Vertex AI Search /
 * a RAG corpus). Each `groundingChunks` entry becomes one [[Citation]]:
 *
 *  - `web` (Google Search) and `maps` (Google Maps): its `uri` and `title`;
 *  - `retrievedContext` (Vertex AI Search, RAG): its `uri`, `title` and, as the cited text, the retrieved `text`.
 *
 * The URL is kept exactly as returned: for Google Search grounding it is a `vertexaisearch.cloud.google.com`
 * redirect, not the page's own address. A chunk without a `uri` is skipped, and a source repeated with the same
 * URL, title and text is reported once, in the order the API listed them.
 *
 * No citation gets indices. The API's `groundingSupports` segments are UTF-8 byte offsets of the answer text a
 * source supports, which is neither a character position in [[org.llm4s.llmconnect.model.Completion.content]]
 * nor where an inline citation sits (what [[Citation.startIndex]] describes), so they are not carried over.
 */
private[provider] object GeminiGrounding {

  /** The section key that turns Google Search grounding on, for `gemini` and `vertexai` sections alike. */
  val GoogleSearchKey: String = "googleSearch"

  /** Declared without a default, so a section that does not set it keeps its extras unchanged; absent means off. */
  val GoogleSearchConfigKey: ProviderConfigKey = ProviderConfigKey(
    GoogleSearchKey,
    "true to ground answers in Google Search results (off when unset); their sources arrive on Completion.citations"
  )

  /** The section's `googleSearch` setting: `true` or `false` (any case), absent meaning `false`. */
  def googleSearch(providerName: String, section: NamedProviderConfig): Result[Boolean] =
    section.extra(GoogleSearchKey).map(_.trim.toLowerCase) match
      case None | Some("false") => Right(false)
      case Some("true")         => Right(true)
      case Some(other) =>
        Left(
          ConfigurationError(
            s"Configured provider '$providerName': $GoogleSearchKey must be true or false, got '$other'"
          )
        )

  /** The citations in `candidate`'s grounding metadata; empty when it has none or it cannot be read. */
  def citations(candidate: ujson.Value): List[Citation] =
    Try(candidate("groundingMetadata")("groundingChunks").arr.toList).toOption
      .getOrElse(Nil)
      .flatMap(citation)
      .distinct

  private def citation(chunk: ujson.Value): Option[Citation] =
    chunk.objOpt.flatMap { fields =>
      Seq("web", "retrievedContext", "maps").iterator
        .flatMap(fields.get)
        .flatMap(_.objOpt)
        .flatMap(source => string(source, "uri").map(uri => (source, uri)))
        .nextOption()
        .map { case (source, uri) =>
          Citation(url = uri, title = string(source, "title"), citedText = string(source, "text"))
        }
    }

  private def string(source: collection.Map[String, ujson.Value], key: String): Option[String] =
    source.get(key).flatMap(_.strOpt).filter(_.trim.nonEmpty)
}
