package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

/**
 * A source a model cited in its answer, as a provider reports it: where the claim came from and,
 * when the provider says, where in the answer the inline citation sits. Provider-neutral: a
 * provider that does not report a field leaves it `None`, and nothing here is invented.
 *
 * Models that search the web by themselves report citations (OpenAI's Chat Completions search
 * models - `gpt-5-search-api`, since the `*-search-preview` models were retired on 2026-07-23 -
 * and OpenRouter's `:online` models, both as `url_citation` annotations). They arrive on
 * [[Completion.citations]]; a completion from any other model has none.
 *
 * @param url        The cited source's URL, exactly as the provider returned it.
 * @param title      The source's title, when the provider reports one.
 * @param citedText  A passage of the source, when the provider returns one with the citation
 *                   (OpenRouter's `content`). It describes the source, not the answer.
 * @param startIndex Where the inline citation starts, as the provider reports it: a position in
 *                   [[Completion.content]]. OpenAI documents the two indices as the first and
 *                   last character "of the URL citation in the message": they locate the
 *                   citation itself, and no provider documents them as the span of prose the
 *                   source supports, so do not highlight the text between them as the supported
 *                   claim. Whether `endIndex` is inclusive is also the provider's call: check its
 *                   convention before cutting `content` with it.
 * @param endIndex   Where the inline citation ends; see `startIndex`.
 */
@Stable
final case class Citation private (
  url: String,
  title: Option[String],
  citedText: Option[String],
  startIndex: Option[Int],
  endIndex: Option[Int]
) {
  def withUrl(url: String): Citation                     = copy(url = url)
  def withTitle(title: String): Citation                 = copy(title = Some(title))
  def withTitle(title: Option[String]): Citation         = copy(title = title)
  def withCitedText(citedText: String): Citation         = copy(citedText = Some(citedText))
  def withCitedText(citedText: Option[String]): Citation = copy(citedText = citedText)
  def withStartIndex(startIndex: Int): Citation          = copy(startIndex = Some(startIndex))
  def withStartIndex(startIndex: Option[Int]): Citation  = copy(startIndex = startIndex)
  def withEndIndex(endIndex: Int): Citation              = copy(endIndex = Some(endIndex))
  def withEndIndex(endIndex: Option[Int]): Citation      = copy(endIndex = endIndex)

  /** Whether the provider said where in the answer this citation sits (both indices present). */
  def hasSpan: Boolean = startIndex.isDefined && endIndex.isDefined
}

object Citation {

  /** Creates a [[Citation]]. Named arguments are the supported way to construct one. */
  def apply(
    url: String,
    title: Option[String] = None,
    citedText: Option[String] = None,
    startIndex: Option[Int] = None,
    endIndex: Option[Int] = None
  ): Citation =
    new Citation(url, title, citedText, startIndex, endIndex)
}
