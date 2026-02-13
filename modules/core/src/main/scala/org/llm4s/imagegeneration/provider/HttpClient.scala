package org.llm4s.imagegeneration.provider

import scala.util.Try
import requests.Response

trait HttpClient {
  def post(url: String, headers: Map[String, String], data: String, timeout: Int): Try[Response]
  def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: Int): Try[Response]
  def postMultipart(url: String, headers: Map[String, String], data: requests.MultiPart, timeout: Int): Try[Response]
  def get(url: String, headers: Map[String, String], timeout: Int): Try[Response]
}

object HttpClient {
  def create(): HttpClient = new SimpleHttpClient()

  // For backward compatibility during refactoring if needed, though we aim to replace usage
  def apply(): HttpClient = new SimpleHttpClient()
}

class SimpleHttpClient extends HttpClient {
  private val logger                = org.slf4j.LoggerFactory.getLogger(getClass)
  private val defaultConnectTimeout = 10000

  override def post(url: String, headers: Map[String, String], data: String, timeout: Int): Try[Response] = Try {
    logger.debug(s"POST $url")
    requests.post(
      url = url,
      data = data,
      headers = headers,
      readTimeout = timeout,
      connectTimeout = defaultConnectTimeout
    )
  }

  override def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: Int): Try[Response] =
    Try {
      logger.debug(s"POST (bytes) $url")
      requests.post(
        url = url,
        data = data,
        headers = headers,
        readTimeout = timeout,
        connectTimeout = defaultConnectTimeout
      )
    }

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    data: requests.MultiPart,
    timeout: Int
  ): Try[Response] = Try {
    logger.debug(s"POST (multipart) $url")
    requests.post(
      url = url,
      data = data,
      headers = headers,
      readTimeout = timeout,
      connectTimeout = defaultConnectTimeout
    )
  }

  override def get(url: String, headers: Map[String, String], timeout: Int): Try[Response] = Try {
    logger.debug(s"GET $url")
    requests.get(
      url = url,
      headers = headers,
      readTimeout = timeout,
      connectTimeout = defaultConnectTimeout
    )
  }
}
