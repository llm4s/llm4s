package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.net.URI
import scala.util.Try

/**
 * Checks a configured `baseUrl` when its section is loaded (#2347).
 *
 * A base URL used to be an opaque string: `localhost:11434`, `htp://host` or a value with a space
 * in it loaded fine and failed only on the first call, with whatever the client's HTTP library
 * made of it. It must now be an absolute `http` or `https` URI with a host, so a broken value is
 * reported against the key that holds it.
 */
private[llm4s] object BaseUrlValidation:

  private val Schemes = Set("http", "https")

  /**
   * `value` if it is an absolute `http(s)` URI with a host, else a [[ConfigurationError]] naming
   * `path` and the value, with any credentials in it redacted.
   *
   *  @param path  the config key the value was read from, e.g. `llm4s.providers.<name>.baseUrl`
   *  @param value the configured base URL, already trimmed
   */
  def validate(path: String, value: String): Result[String] =
    problem(value) match
      case None => Right(value)
      case Some(reason) =>
        Left(
          ConfigurationError(
            s"$path must be an absolute http or https URL with a host, e.g. https://api.example.com/v1; " +
              s"got '${shown(value)}' ($reason)"
          )
        )

  /** Why `value` is not an acceptable base URL, or `None` when it is. */
  private def problem(value: String): Option[String] =
    if value.trim.isEmpty then Some("it is blank")
    else
      Try(new URI(value)).toEither match
        case Left(_) => Some("it is not a valid URI")
        case Right(uri) =>
          Option(uri.getScheme).map(_.toLowerCase) match
            case None                             => Some("it has no scheme")
            case Some(scheme) if !Schemes(scheme) => Some(s"scheme '$scheme' is not http or https")
            case Some(_) if !hasHost(uri)         => Some("it has no host")
            case Some(_)                          => None

  /**
   * Whether `uri` names a host. `URI.getHost` is `null` for a host name it does not accept as a
   * server-based authority - one with an underscore, as Docker Compose service names often have -
   * so the authority is read directly too, rather than refusing a host the HTTP clients reach.
   */
  private def hasHost(uri: URI): Boolean =
    Option(uri.getHost).exists(_.nonEmpty) ||
      Option(uri.getRawAuthority).exists { authority =>
        val hostAndPort = authority.substring(authority.lastIndexOf('@') + 1)
        val host =
          if hostAndPort.startsWith("[") then hostAndPort
          else hostAndPort.takeWhile(_ != ':')
        host.nonEmpty
      }

  /**
   * `value` fit for an error message. A URL that parses is shown without its userinfo, query or
   * fragment (`Redaction.url`); one that does not is shown pattern-redacted, unless it holds an
   * `@`, which may end a `user:password` that no pattern recognises without a scheme.
   */
  private def shown(value: String): String =
    Redaction.url(value) match
      case Unparsed if value.contains('@') => Unparsed
      case Unparsed                        => Redaction.redact(value)
      case redacted                        => redacted

  /** What `Redaction.url` returns for a value that is not an absolute URL with an authority. */
  private val Unparsed = "***"
