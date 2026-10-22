package org.llm4s.llmconnect.provider

import com.anthropic.models.messages.{ CitationsSearchResultLocation, CitationsWebSearchResultLocation, ContentBlock }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.model.Citation
import org.llm4s.llmconnect.spi.ProviderConfigKey
import org.llm4s.types.Result

import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * Anthropic's web search tool and the citations it produces. With `webSearch = true` in an `anthropic` section,
 * every request carries the server-side `web_search` tool; the model decides whether to search, and each text block
 * it writes from a search result lists that result in its `citations`. Each such citation becomes one [[Citation]]:
 *
 *  - `web_search_result_location`: its `url`, `title` and `cited_text`;
 *  - `search_result_location` (search results the application passed in): its `source` as the URL, `title` and
 *    `cited_text`.
 *
 * Document citations (`char_location`, `page_location`, `content_block_location`) name a document, not a URL, and
 * are skipped. A source cited twice with the same URL, title and text is reported once, in the order the answer
 * cites them. No citation gets indices: Anthropic attaches a citation to a text block, not to a position in it.
 */
private[provider] object AnthropicWebSearch {

  /** The section key that turns web search on. */
  val WebSearchKey: String = "webSearch"

  /** Declared without a default, so a section that does not set it keeps its extras unchanged; absent means off. */
  val WebSearchConfigKey: ProviderConfigKey = ProviderConfigKey(
    WebSearchKey,
    "true to let the model search the web (off when unset); the sources it cites arrive on Completion.citations"
  )

  /** The section's `webSearch` setting: `true` or `false` (any case), absent meaning `false`. */
  def webSearch(providerName: String, section: NamedProviderConfig): Result[Boolean] =
    section.extra(WebSearchKey).map(_.trim.toLowerCase) match
      case None | Some("false") => Right(false)
      case Some("true")         => Right(true)
      case Some(other) =>
        Left(
          ConfigurationError(
            s"Configured provider '$providerName': $WebSearchKey must be true or false, got '$other'",
            List(WebSearchKey)
          )
        )

  /** The citation one text-citation location describes, or `None` for a document citation. */
  def citation(
    web: Optional[CitationsWebSearchResultLocation],
    search: Optional[CitationsSearchResultLocation]
  ): Option[Citation] =
    web.toScala
      .map(w => Citation(url = w.url(), title = w.title().toScala, citedText = Option(w.citedText())))
      .orElse(
        search.toScala.map(s =>
          Citation(url = s.source(), title = s.title().toScala, citedText = Option(s.citedText()))
        )
      )
      .filter(_.url.nonEmpty)

  /** Every citation of a response's text blocks, each source once, in the order the answer cites them. */
  def citations(blocks: List[ContentBlock]): List[Citation] =
    blocks
      .filter(_.isText)
      .flatMap(_.asText().citations().toScala.map(_.asScala.toList).getOrElse(Nil))
      .flatMap(c => citation(c.webSearchResultLocation(), c.searchResultLocation()))
      .distinct
}
