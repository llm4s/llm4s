package org.llm4s.testkit

import org.llm4s.error.LLMError

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * End-to-end check that a credential a provider echoes back in its error body never reaches the
 * `LLMError` message. A client spec builds the client with `key` as its credential, points it at
 * the local server [[assertNotEchoed]] starts, and makes one call.
 */
object CredentialEchoChecks {

  /** An error body echoing `key` raw, inside a JSON string, JSON-escaped and URL-encoded. */
  def echoBody(key: String): String =
    s"""{"error":{"message":"Incorrect API key provided: $key","raw":"$key","url":"?key=${URLEncoder
        .encode(key, StandardCharsets.UTF_8)}","nested":"{\\"k\\":\\"$key\\"}"}}"""

  /**
   * Starts a local server answering every request with HTTP 401 and [[echoBody]], runs
   * `call(baseUrl)`, and fails unless the server saw a request and the call returns a `Left` whose message holds none of `key`'s forms.
   */
  def assertNotEchoed(key: String)(call: String => Either[LLMError, ?]): Unit = {
    val requests = new java.util.concurrent.atomic.AtomicInteger(0)
    val handler = (exchange: com.sun.net.httpserver.HttpExchange) => {
      requests.incrementAndGet(): Unit
      LocalProviderTestServer.sendJsonResponse(exchange, 401, echoBody(key))
    }
    LocalProviderTestServer.withServer("/")(handler) { baseUrl =>
      val result = call(baseUrl)
      if (requests.get() < 1)
        throw new AssertionError(s"the call never reached the server, so it proves nothing: $result")
      assertHidesKey(key, result)
    }
  }

  /**
   * Fails unless `result` is a `Left` whose message holds none of `key`'s forms. For a client with
   * no base URL, called over a stub HTTP client that answers 401 with [[echoBody]].
   */
  def assertHidesKey(key: String, result: Either[LLMError, ?]): Unit =
    result match {
      case Left(err) =>
        val forms = Seq(key, URLEncoder.encode(key, StandardCharsets.UTF_8))
        forms.foreach(f =>
          if (err.message.contains(f)) throw new AssertionError(s"credential echoed in: ${err.message}")
        )
      case Right(r) => throw new AssertionError(s"expected an error, got $r")
    }
}
