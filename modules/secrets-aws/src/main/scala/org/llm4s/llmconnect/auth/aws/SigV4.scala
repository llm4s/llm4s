package org.llm4s.llmconnect.auth.aws

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneOffset }
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 (`AWS4-HMAC-SHA256`) for one request, written out so that the module needs no AWS SDK.
 * It follows AWS's "Create a signed AWS API request" steps and is checked against the signatures AWS publishes for
 * them. The URL's path is taken as already encoded, which holds for the `/` that the JSON-protocol services use.
 */
private[aws] object SigV4:

  val Algorithm = "AWS4-HMAC-SHA256"

  private val hex        = HexFormat.of()
  private val amzDateFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

  /** `20150830T123600Z`: the `X-Amz-Date` form of `at`. */
  def amzDate(at: Instant): String = amzDateFmt.format(at)

  def sha256Hex(bytes: Array[Byte]): String = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def hmac(key: Array[Byte], data: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))

  /** `host`, with the port when it is not the scheme's default: the `Host` header an HTTP client sends for `uri`. */
  def hostOf(uri: URI): String =
    val defaultPort = if uri.getScheme.equalsIgnoreCase("https") then 443 else 80
    if uri.getPort == -1 || uri.getPort == defaultPort then uri.getHost else s"${uri.getHost}:${uri.getPort}"

  /**
   * The headers to add to a request so that AWS accepts it: `Authorization`, `X-Amz-Date` and, for temporary
   * credentials, `X-Amz-Security-Token`. `headers` are the request's other headers, all of them signed; `Host` is
   * signed from the URL and must not be among them, since the HTTP client sets it.
   */
  def sign(
    method: String,
    uri: URI,
    headers: Map[String, String],
    payload: Array[Byte],
    credentials: AwsCredentials,
    region: String,
    service: String,
    at: Instant
  ): Map[String, String] =
    val date      = amzDate(at)
    val added     = Map("X-Amz-Date" -> date) ++ credentials.sessionToken.map(t => "X-Amz-Security-Token" -> t.reveal)
    val signature = signatureOf(method, uri, headers ++ added, payload, credentials, region, service, at)
    val scope     = s"${date.take(8)}/$region/$service/aws4_request"
    val authorization = s"$Algorithm Credential=${credentials.accessKeyId}/$scope, " +
      s"SignedHeaders=${signedHeaders(headers ++ added)}, Signature=$signature"
    added + ("Authorization" -> authorization)

  /** The `;`-joined, lower-cased, sorted names of `headers` and `host`. */
  def signedHeaders(headers: Map[String, String]): String =
    (headers.keySet.map(_.toLowerCase) + "host").toSeq.sorted.mkString(";")

  /** The hex signature of a request whose headers, other than `Host`, are `headers` (`X-Amz-Date` among them). */
  def signatureOf(
    method: String,
    uri: URI,
    headers: Map[String, String],
    payload: Array[Byte],
    credentials: AwsCredentials,
    region: String,
    service: String,
    at: Instant
  ): String =
    val date      = amzDate(at)
    val day       = date.take(8)
    val canonical = canonicalRequest(method, uri, headers, payload)
    val toSign =
      Seq(Algorithm, date, s"$day/$region/$service/aws4_request", sha256Hex(canonical.getBytes(StandardCharsets.UTF_8)))
        .mkString("\n")
    val key = Seq(day, region, service, "aws4_request")
      .foldLeft(s"AWS4${credentials.secretAccessKey.reveal}".getBytes(StandardCharsets.UTF_8))(hmac)
    hex.formatHex(hmac(key, toSign))

  private def canonicalRequest(method: String, uri: URI, headers: Map[String, String], payload: Array[Byte]): String =
    val path = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
    val query = Option(uri.getRawQuery).toSeq
      .flatMap(_.split('&'))
      .filter(_.nonEmpty)
      .map { pair =>
        val (k, v) = pair.span(_ != '=')
        (k, v.drop(1))
      }
      .sorted
      .map((k, v) => s"$k=$v")
      .mkString("&")
    val all = headers.map((k, v) => k.toLowerCase -> v.trim.replaceAll(" +", " ")) + ("host" -> hostOf(uri))
    val canonicalHeaders = all.toSeq.sortBy(_._1).map((k, v) => s"$k:$v\n").mkString
    Seq(method, path, query, canonicalHeaders, signedHeaders(headers), sha256Hex(payload)).mkString("\n")
