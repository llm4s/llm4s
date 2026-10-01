package org.llm4s.imagegeneration.provider

import org.llm4s.error.{ ExecutionError, LLMError, NetworkError, TimeoutError, UnknownError }
import org.llm4s.http.{ HttpRawResponse, HttpResponse, Llm4sHttpClient, MultipartPart }
import org.llm4s.types.Result

import java.io.IOException
import scala.concurrent.duration.*
import scala.util.{ Failure, Success, Try }

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

/**
 * [[HttpClient]] over the shared [[org.llm4s.http.Llm4sHttpClient]]. That client reports a
 * transport failure as a `Left`; this adapter turns it into a `Failure` holding an
 * the exception that caused it where the error carries one (as a connection failure or timeout
 * does), else an `IOException` with the error's message - so the image clients' `Try`-based
 * error handling sees what it saw when the client threw.
 */
class SimpleHttpClient(llm4sClient: Llm4sHttpClient) extends HttpClient {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  override def post(url: String, headers: Map[String, String], data: String, timeout: Int): Try[HttpResponse] = {
    logger.debug(s"POST $url")
    SimpleHttpClient.toTry(llm4sClient.post(url = url, headers = headers, body = data, timeout = timeout.millis))
  }

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: Int
  ): Try[HttpResponse] = {
    logger.debug(s"POST (bytes) $url")
    SimpleHttpClient.toTry(llm4sClient.postBytes(url = url, headers = headers, data = data, timeout = timeout.millis))
  }

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    data: Seq[MultipartPart],
    timeout: Int
  ): Try[HttpResponse] = {
    logger.debug(s"POST (multipart) $url")
    SimpleHttpClient.toTry(
      llm4sClient.postMultipart(url = url, headers = headers, parts = data, timeout = timeout.millis)
    )
  }

  override def get(url: String, headers: Map[String, String], timeout: Int): Try[HttpResponse] = {
    logger.debug(s"GET $url")
    SimpleHttpClient.toTry(llm4sClient.get(url = url, headers = headers, timeout = timeout.millis))
  }

  override def postRaw(url: String, headers: Map[String, String], data: String, timeout: Int): Try[HttpRawResponse] = {
    logger.debug(s"POST (raw bytes) $url")
    SimpleHttpClient.toTry(llm4sClient.postRaw(url = url, headers = headers, body = data, timeout = timeout.millis))
  }
}

private object SimpleHttpClient {

  private[provider] def toTry[A](result: Result[A]): Try[A] =
    result.fold(error => Failure(causeOf(error).getOrElse(new IOException(error.message))), Success(_))

  private def causeOf(error: LLMError): Option[Throwable] = error match {
    case e: NetworkError   => e.cause
    case e: TimeoutError   => e.cause
    case e: ExecutionError => e.cause
    case e: UnknownError   => Some(e.cause)
    case _                 => None
  }
}
