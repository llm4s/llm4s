package org.llm4s.llmconnect.provider

import java.net.http.HttpClient
import scala.util.Try

/** Safely closes JDK `HttpClient` via reflection. No-ops on JDK versions before 21. */
private[provider] object HttpClientCloser {

  def tryClose(httpClient: HttpClient): Unit =
    Try {
      val closeMethod = httpClient.getClass.getMethod("close")
      closeMethod.invoke(httpClient)
    }.recover { case _: NoSuchMethodException => () }
}
