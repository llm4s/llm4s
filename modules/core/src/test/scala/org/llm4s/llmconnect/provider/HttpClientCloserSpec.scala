package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.http.HttpClient

/**
 * Tests for [[HttpClientCloser]], which safely closes JDK `HttpClient`
 * via reflection (JDK 21+) or no-ops on older JDKs.
 */
class HttpClientCloserSpec extends AnyFlatSpec with Matchers {

  "HttpClientCloser.tryClose" should "not throw on a default HttpClient" in {
    val httpClient = HttpClient.newHttpClient()
    noException should be thrownBy {
      HttpClientCloser.tryClose(httpClient)
    }
  }

  it should "be safe to call multiple times" in {
    val httpClient = HttpClient.newHttpClient()
    noException should be thrownBy {
      HttpClientCloser.tryClose(httpClient)
      HttpClientCloser.tryClose(httpClient)
    }
  }
}
