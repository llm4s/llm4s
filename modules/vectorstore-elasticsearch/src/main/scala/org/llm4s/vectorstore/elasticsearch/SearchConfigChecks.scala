package org.llm4s.vectorstore.elasticsearch

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * The checks [[ElasticsearchConfig]] and [[org.llm4s.vectorstore.opensearch.OpenSearchConfig]] share: OpenSearch
 * forked from Elasticsearch 7.10 and keeps its URL and index-name rules.
 */
private[vectorstore] object SearchConfigChecks {

  private def isLoopback(host: String): Boolean =
    host.equalsIgnoreCase("localhost") || host == "[::1]" || {
      val octets = host.split('.')
      octets.length == 4 && octets(0) == "127" &&
      octets.forall(o => o.nonEmpty && o.forall(_.isDigit) && o.length <= 3 && o.toInt <= 255)
    }

  /**
   * An absolute `http` or `https` URL without user info, query or fragment; `https` when `hasCredentials`, unless the
   * host is loopback.
   */
  def checkBaseUrl(product: String, url: String, hasCredentials: Boolean): Result[Unit] = {
    def bad(why: String): Result[Unit] = Left(ConfigurationError(s"The $product baseUrl $why"))
    if (url.exists(c => c.isWhitespace || c.isControl)) bad("must not contain whitespace or control characters")
    else
      Try(new URI(url)).toOption match {
        case None => bad("is not a valid URL")
        case Some(uri) =>
          val scheme = Option(uri.getScheme).map(_.toLowerCase)
          val host   = Option(uri.getHost)
          if (host.isEmpty || scheme.isEmpty) bad("must be an absolute URL with a host")
          else if (!scheme.exists(s => s == "http" || s == "https")) bad("must be http or https")
          else if (uri.getRawUserInfo != null) bad("must not carry credentials; configure them as auth instead")
          else if (uri.getRawQuery != null || uri.getRawFragment != null) bad("must not have a query or a fragment")
          else if (scheme.contains("https") || !hasCredentials || host.exists(isLoopback)) Right(())
          else bad("must be https when credentials are configured, unless the host is loopback")
      }
  }

  private val ForbiddenIndexChars = "\\/*?\"<>|, #:"

  /** The index-name rules both products apply. */
  def checkIndex(product: String, index: String): Result[Unit] = {
    def bad(why: String): Result[Unit] = Left(ConfigurationError(s"The $product index name '$index' $why"))
    if (index.isEmpty) bad("is empty")
    else if (index == "." || index == "..") bad("is reserved")
    else if (index.exists(c => c.isUpper)) bad("must be lower case")
    else if (index.exists(c => ForbiddenIndexChars.contains(c) || c.isControl))
      bad(s"contains a character $product forbids (\\ / * ? \" < > | , # : or space)")
    else if ("-_+".contains(index.head)) bad("must not start with -, _ or +")
    else if (index.getBytes(StandardCharsets.UTF_8).length > 255) bad("is longer than 255 bytes")
    else Right(())
  }

  /** A whole number setting within `[1, max]`, when set. */
  def checkRange(name: String, value: Option[Int], max: Int): Result[Unit] =
    value match {
      case Some(n) if n < 1 || n > max => Left(ConfigurationError(s"$name must be between 1 and $max, got $n"))
      case _                           => Right(())
    }
}
