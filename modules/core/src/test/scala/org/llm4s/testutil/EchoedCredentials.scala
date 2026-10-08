package org.llm4s.testutil

import ch.qos.logback.classic.{ Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * A provider reply that echoes the request's credentials back, for the specs that check a client never logs one or
 * puts one into an error in the clear (#1674).
 *
 * The text carries a credential in each shape `Redaction` must catch: an `Authorization: Bearer` header, an
 * `api_key` JSON field and a `?key=` query parameter. The bearer token matches no vendor key pattern, so only the
 * header rule can catch it.
 */
object EchoedCredentials {

  val BearerToken: String = "ya29.bearer-secret-token-value"
  val ApiKeyValue: String = "s3cr3t-api-key-value"
  val GoogleKey: String   = "AIzaSyA1234567890abcdefghijklmnopqrstuv"

  val Secrets: Seq[String] = Seq(BearerToken, ApiKeyValue, GoogleKey)

  /** Plain text echoing all three credentials, one per line. */
  val Text: String =
    s"Authorization: Bearer $BearerToken\n" +
      s"""{"api_key": "$ApiKeyValue"}""" + "\n" +
      s"GET /v1/models?key=$GoogleKey"

  /** The same text as the `message` of a JSON error body, as most providers shape one. */
  val JsonError: String = ujson.Obj("error" -> ujson.Obj("message" -> Text)).render()

  /** The credentials that appear in `text`; empty when none leaked. */
  def leaked(text: String): Seq[String] = Secrets.filter(text.contains)

  /** Runs `f` and returns its result with the formatted message of every log event written meanwhile. */
  def logged[A](f: => A): (A, Seq[String]) = {
    val root     = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    root.addAppender(appender)
    val result = Try(f)
    root.detachAppender(appender)
    (result.get, appender.list.asScala.toSeq.map(_.getFormattedMessage))
  }
}
