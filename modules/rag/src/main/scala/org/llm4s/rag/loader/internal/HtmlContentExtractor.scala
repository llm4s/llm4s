package org.llm4s.rag.loader.internal

import org.jsoup.Jsoup
import org.jsoup.nodes.{ Document => JsoupDocument, Element, TextNode }

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Utility for extracting clean text content and links from HTML.
 *
 * Uses JSoup for parsing and provides:
 * - Title extraction
 * - Main content extraction (removing nav, header, footer, etc.)
 * - Link extraction for crawling
 * - Clean text output suitable for RAG chunking
 */
object HtmlContentExtractor {

  /**
   * Result of extracting content from an HTML page.
   *
   * @param title Page title
   * @param content Clean text content
   * @param links Discovered links on the page
   * @param description Meta description if available
   */
  final case class ExtractionResult(
    title: String,
    content: String,
    links: Seq[String],
    description: Option[String] = None
  )

  // Elements to remove before content extraction
  private val elementsToRemove = Seq(
    "script",
    "style",
    "noscript",
    "nav",
    "header",
    "footer",
    "aside",
    "form",
    "iframe",
    "svg",
    "canvas",
    "[role=navigation]",
    "[role=banner]",
    "[role=contentinfo]",
    "[aria-hidden=true]",
    ".nav",
    ".navigation",
    ".header",
    ".footer",
    ".sidebar",
    ".menu",
    ".advertisement",
    ".ad",
    ".ads",
    ".cookie-notice",
    ".cookie-banner"
  )

  /**
   * Extract content and links from HTML.
   *
   * @param html Raw HTML content
   * @param baseUrl Base URL for resolving relative links
   * @return ExtractionResult with title, content, and links
   */
  def extract(html: String, baseUrl: String): ExtractionResult = {
    val doc = Jsoup.parse(html, baseUrl)

    // Extract metadata before removing elements
    val title       = extractTitle(doc)
    val description = extractDescription(doc)
    val links       = extractLinks(doc, baseUrl)

    // Remove non-content elements
    elementsToRemove.foreach(selector => Try(doc.select(selector).remove()).fold(_ => (), _ => ()))

    // Extract clean text content
    val content = extractContent(doc)

    ExtractionResult(
      title = title,
      content = content,
      links = links,
      description = description
    )
  }

  /**
   * Extract just the links from HTML (faster if only links needed).
   *
   * @param html Raw HTML content
   * @param baseUrl Base URL for resolving relative links
   * @return Sequence of absolute URLs
   */
  def extractLinksOnly(html: String, baseUrl: String): Seq[String] = {
    val doc = Jsoup.parse(html, baseUrl)
    extractLinks(doc, baseUrl)
  }

  /**
   * Extract page title.
   */
  private def extractTitle(doc: JsoupDocument): String = {
    // Try <title> first
    val titleTag = doc.title()
    if (titleTag.nonEmpty) return titleTag

    // Fall back to first <h1>
    val h1 = Option(doc.select("h1").first())
    if (h1.isDefined) return h1.get.text()

    // Fall back to og:title
    val ogTitle = doc.select("meta[property=og:title]").attr("content")
    if (ogTitle.nonEmpty) return ogTitle

    ""
  }

  /**
   * Extract meta description.
   */
  private def extractDescription(doc: JsoupDocument): Option[String] = {
    val desc = doc.select("meta[name=description]").attr("content")
    if (desc.nonEmpty) Some(desc)
    else {
      val ogDesc = doc.select("meta[property=og:description]").attr("content")
      if (ogDesc.nonEmpty) Some(ogDesc) else None
    }
  }

  /**
   * Extract all links from the document.
   */
  private def extractLinks(doc: JsoupDocument, @annotation.unused baseUrl: String): Seq[String] =
    doc
      .select("a[href]")
      .asScala
      .map(_.attr("abs:href"))
      .filter(_.nonEmpty)
      .filter(link => link.startsWith("http://") || link.startsWith("https://"))
      .toSeq
      .distinct

  /**
   * Extract clean text content from the document.
   */
  private def extractContent(doc: JsoupDocument): String = {
    // Try to find main content area
    val mainContent = findMainContent(doc)

    // Get text, preserving some structure
    val text = Option(mainContent).map(formatElement).getOrElse(formatElement(doc.body()))

    // Clean up whitespace
    text
      .replaceAll("\\s+", " ")      // Collapse whitespace
      .replaceAll(" ?\n ?", "\n")   // Clean around newlines
      .replaceAll("\n{3,}", "\n\n") // Max 2 consecutive newlines
      .trim
  }

  /**
   * Find the main content area of a page.
   */
  private def findMainContent(doc: JsoupDocument): Element = {
    // Priority order for main content containers
    val selectors = Seq(
      "main",
      "article",
      "[role=main]",
      ".main-content",
      ".content",
      "#content",
      "#main",
      ".post-content",
      ".entry-content"
    )

    selectors.iterator
      .map(doc.select)
      .find(_.size() == 1)
      .map(_.first())
      .orNull
  }

  /**
   * Format an element's content with some structure preservation.
   */
  private def formatElement(element: Element): String = {
    if (element == null) return ""

    val sb = new StringBuilder

    element.childNodes().asScala.foreach {
      case textNode: TextNode =>
        val text = textNode.text().trim
        if (text.nonEmpty) {
          sb.append(text)
          sb.append(" ")
        }

      case child: Element =>
        val tagName = child.tagName().toLowerCase

        tagName match {
          case "h1" | "h2" | "h3" | "h4" | "h5" | "h6" =>
            sb.append("\n\n")
            sb.append(child.text())
            sb.append("\n\n")

          case "p" | "div" | "section" =>
            val text = child.text().trim
            if (text.nonEmpty) {
              sb.append(text)
              sb.append("\n\n")
            }

          case "ul" | "ol" =>
            child.select("li").asScala.foreach { li =>
              sb.append("• ")
              sb.append(li.text())
              sb.append("\n")
            }
            sb.append("\n")

          case "br" =>
            sb.append("\n")

          case "pre" | "code" =>
            sb.append("\n```\n")
            sb.append(child.text())
            sb.append("\n```\n")

          case "blockquote" =>
            sb.append("\n> ")
            sb.append(child.text().replaceAll("\n", "\n> "))
            sb.append("\n\n")

          case "table" =>
            // Simple table representation
            child.select("tr").asScala.foreach { tr =>
              val cells = tr.select("td, th").asScala.map(_.text())
              sb.append(cells.mkString(" | "))
              sb.append("\n")
            }
            sb.append("\n")

          case _ =>
            // For other elements, just get text
            val text = child.text().trim
            if (text.nonEmpty) {
              sb.append(text)
              sb.append(" ")
            }
        }

      case _ => // Ignore other node types
    }

    // If no children, just get the text
    if (sb.isEmpty) {
      sb.append(element.text())
    }

    sb.toString()
  }
}
