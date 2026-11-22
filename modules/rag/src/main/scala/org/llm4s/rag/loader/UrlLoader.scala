package org.llm4s.rag.loader

import org.llm4s.error.NetworkError
import org.llm4s.rag.loader.internal.PinnedHttpClient
import org.llm4s.util.Redaction
import org.llm4s.security.Secret

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*

/**
 * Load documents from URLs.
 *
 * Supports HTTP/HTTPS URLs with configurable timeouts and headers.
 * Includes ETag-based version detection for efficient sync operations.
 *
 * @param urls URLs to load
 * @param headers HTTP headers to send with requests
 * @param timeout Connection and read timeout
 * @param metadata Additional metadata to attach
 * @param retryCount Number of retry attempts for failed requests
 * @param urlPolicy Which URLs may be fetched, checked for each URL and every redirect hop, and the addresses the
 *                  loader connects to (default: [[UrlPolicy.default]], public addresses only)
 */
final case class UrlLoader(
  urls: Seq[String],
  headers: Map[String, String] = Map.empty,
  timeout: FiniteDuration = 30.seconds,
  metadata: Map[String, String] = Map.empty,
  retryCount: Int = 2,
  urlPolicy: UrlPolicy = UrlPolicy.default
) extends DocumentLoader {

  /** The case class's form, with each credential-named header's value (`Authorization`, `x-api-key`, ...) masked. */
  override def toString: String = {
    val shownHeaders = headers.map { case (name, value) =>
      name -> (if (Redaction.isCredentialName(name)) "***" else value)
    }
    s"UrlLoader(${urls.map(Redaction.url)},$shownHeaders,$timeout,$metadata,$retryCount,$urlPolicy)"
  }

  // Every failure names its document (the URL is the id): the URL is still in the list, so
  // RAG.sync keeps its indexed version until it is taken out of the list.
  def load(): Iterator[LoadResult] = urls.iterator.map(loadUrl)

  private def loadUrl(urlString: String): LoadResult =
    PinnedHttpClient.get(urlString, requestHeaders, timeout, urlPolicy) match {
      case Left(error) => LoadResult.failure(urlString, error, documentId = urlString)
      case Right(resp) if resp.status != 200 =>
        LoadResult.failure(
          urlString,
          NetworkError(s"HTTP ${resp.status}: ${resp.reason}", None, "http"),
          documentId = urlString
        )
      case Right(resp) =>
        val content     = resp.body
        val contentType = resp.header("Content-Type").getOrElse("text/plain")
        val etag        = resp.header("ETag")
        val lastMod     = resp.header("Last-Modified").flatMap(parseHttpDate)

        val version = DocumentVersion(
          contentHash = DocumentVersion.fromContent(content).contentHash,
          timestamp = lastMod,
          etag = etag
        )

        LoadResult.success(
          Document(
            id = urlString,
            content = content,
            metadata = metadata ++ Map(
              "source"       -> urlString,
              "url"          -> urlString,
              "content_type" -> contentType
            ) ++ etag.map("etag" -> _).toMap
              ++ lastMod.map(m => "lastModified" -> m.toString).toMap,
            hints = Some(detectHints(urlString, contentType)),
            version = Some(version)
          )
        )
    }

  /** The caller's headers, with the default `User-Agent` unless they set one. */
  private def requestHeaders: Seq[(String, String)] =
    if (headers.keys.exists(_.equalsIgnoreCase("User-Agent"))) headers.toSeq
    else ("User-Agent" -> "LLM4S-RAG/1.0") +: headers.toSeq

  /** An HTTP-date (`Last-Modified`) as epoch milliseconds, as `HttpURLConnection.getLastModified` gave it. */
  private def parseHttpDate(value: String): Option[Long] =
    scala.util
      .Try(ZonedDateTime.parse(value.trim, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant.toEpochMilli)
      .toOption
      .filter(_ > 0)

  private def detectHints(url: String, contentType: String): DocumentHints =
    if (contentType.contains("markdown") || url.endsWith(".md")) {
      DocumentHints.markdown
    } else if (contentType.contains("html")) {
      DocumentHints.prose
    } else {
      DocumentHints.prose
    }

  override def estimatedCount: Option[Int] = Some(urls.size)

  def description: String = s"UrlLoader(${urls.size} URLs)"

  /** Add a URL */
  def withUrl(url: String): UrlLoader =
    copy(urls = urls :+ url)

  /** Add headers */
  def withHeaders(h: Map[String, String]): UrlLoader =
    copy(headers = headers ++ h)

  /** Set timeout */
  def withTimeout(timeout: FiniteDuration): UrlLoader =
    copy(timeout = timeout)

  /** Set the policy every URL and redirect hop is checked against */
  def withUrlPolicy(policy: UrlPolicy): UrlLoader =
    copy(urlPolicy = policy)

  /** Set retry count */
  def withRetries(n: Int): UrlLoader =
    copy(retryCount = n)
}

object UrlLoader {

  def apply(url: String): UrlLoader =
    UrlLoader(Seq(url))

  def apply(urls: String*): UrlLoader =
    UrlLoader(urls.toSeq)

  /** Create with authorization header */
  def withAuth(urls: Seq[String], token: Secret): UrlLoader =
    UrlLoader(urls, headers = Map("Authorization" -> s"Bearer ${token.reveal}"))

  /** Create with custom user agent */
  def withUserAgent(urls: Seq[String], userAgent: String): UrlLoader =
    UrlLoader(urls, headers = Map("User-Agent" -> userAgent))
}
