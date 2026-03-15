package org.llm4s.imagegeneration.provider

import org.llm4s.http.{ HttpRawResponse, HttpResponse, Llm4sHttpClient, MultipartPart }

import scala.util.Try

trait HttpClient {
  def post(url: String, headers: Map[String, String], data: String, timeout: Int): Try[HttpResponse]
  def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: Int): Try[HttpResponse]
  def postMultipart(
    url: String,
    headers: Map[String, String],
    data: Seq[MultipartPart],
    timeout: Int
  ): Try[HttpResponse]
  def get(url: String, headers: Map[String, String], timeout: Int): Try[HttpResponse]

  /** POST with a string body and return raw bytes, bypassing charset decoding. */
  def postRaw(url: String, headers: Map[String, String], data: String, timeout: Int): Try[HttpRawResponse]
}

object HttpClient {
  def create(): HttpClient = new SimpleHttpClient(Llm4sHttpClient.create())

  def apply(): HttpClient = create()
}

class SimpleHttpClient(llm4sClient: Llm4sHttpClient) extends HttpClient {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  override def post(url: String, headers: Map[String, String], data: String, timeout: Int): Try[HttpResponse] = Try {
    logger.debug(s"POST $url")
    llm4sClient.post(url = url, headers = headers, body = data, timeout = timeout)
  }

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: Int
  ): Try[HttpResponse] =
    Try {
      logger.debug(s"POST (bytes) $url")
      llm4sClient.postBytes(url = url, headers = headers, data = data, timeout = timeout)
    }

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    data: Seq[MultipartPart],
    timeout: Int
  ): Try[HttpResponse] = Try {
    logger.debug(s"POST (multipart) $url")
    llm4sClient.postMultipart(url = url, headers = headers, parts = data, timeout = timeout)
  }

  override def get(url: String, headers: Map[String, String], timeout: Int): Try[HttpResponse] = Try {
    logger.debug(s"GET $url")
    llm4sClient.get(url = url, headers = headers, timeout = timeout)
  }

  override def postRaw(url: String, headers: Map[String, String], data: String, timeout: Int): Try[HttpRawResponse] =
    Try {
      logger.debug(s"POST (raw bytes) $url")
      llm4sClient.postRaw(url = url, headers = headers, body = data, timeout = timeout)
    }
}
