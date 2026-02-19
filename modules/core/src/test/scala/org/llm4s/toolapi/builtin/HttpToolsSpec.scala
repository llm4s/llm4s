package org.llm4s.toolapi.builtin

import scala.annotation.nowarn

import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.builtin.http._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HttpToolsSpec extends AnyFlatSpec with Matchers {

  "HttpConfig" should "block localhost by default" in {
    val config = HttpConfig()

    config.isDomainAllowed("localhost") shouldBe false
    config.isDomainAllowed("127.0.0.1") shouldBe false
    config.isDomainAllowed("0.0.0.0") shouldBe false
    config.isDomainAllowed("::1") shouldBe false
  }

  it should "allow external domains by default" in {
    val config = HttpConfig()

    config.isDomainAllowed("example.com") shouldBe true
    config.isDomainAllowed("api.example.com") shouldBe true
    config.isDomainAllowed("google.com") shouldBe true
  }

  it should "restrict to allowed domains when specified" in {
    val config = HttpConfig(allowedDomains = Some(Seq("api.example.com", "data.example.org")))

    config.isDomainAllowed("api.example.com") shouldBe true
    config.isDomainAllowed("data.example.org") shouldBe true
    config.isDomainAllowed("sub.api.example.com") shouldBe true // subdomain match
    config.isDomainAllowed("other.com") shouldBe false
  }

  it should "validate methods" in {
    // Default config now only allows GET and HEAD for security (read-only by default)
    val defaultConfig = HttpConfig()

    defaultConfig.isMethodAllowed("GET") shouldBe true
    defaultConfig.isMethodAllowed("HEAD") shouldBe true
    defaultConfig.isMethodAllowed("POST") shouldBe false   // Changed: secure default
    defaultConfig.isMethodAllowed("DELETE") shouldBe false // Changed: secure default

    // Use withWriteMethods for full HTTP method access
    val fullConfig = HttpConfig().withAllMethods

    fullConfig.isMethodAllowed("GET") shouldBe true
    fullConfig.isMethodAllowed("POST") shouldBe true
    fullConfig.isMethodAllowed("DELETE") shouldBe true

    val readOnlyConfig = HttpConfig.readOnly()

    readOnlyConfig.isMethodAllowed("GET") shouldBe true
    readOnlyConfig.isMethodAllowed("HEAD") shouldBe true
    readOnlyConfig.isMethodAllowed("POST") shouldBe false
    readOnlyConfig.isMethodAllowed("DELETE") shouldBe false
  }

  "HTTPTool" should "reject blocked domains" in {
    val config = HttpConfig()
    HTTPTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("url" -> "http://localhost:8080/test")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => err should include("not allowed"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  it should "reject disallowed methods" in {
    val config = HttpConfig.readOnly()
    HTTPTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("url" -> "https://example.com/api", "method" -> "POST")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => err should include("not allowed"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  it should "reject invalid URLs" in {
    val config = HttpConfig()
    HTTPTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("url" -> "not-a-valid-url")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => err should include("Invalid URL"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  it should "default to GET method" in {
    // We can't easily test actual HTTP requests without a server,
    // but we can verify the method handling logic
    val config = HttpConfig(allowedDomains = Some(Seq("nonexistent.test")))
    HTTPTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          // This will fail with connection error, but we can verify the method is accepted
          val params = ujson.Obj("url" -> "https://nonexistent.test/api")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              // Will fail due to connection, but should not fail due to method
              err => (err should not).include("method"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  "HttpConfig.readOnly" should "only allow read methods" in {
    val config = HttpConfig.readOnly()

    config.allowedMethods shouldBe Seq("GET", "HEAD")
    config.isMethodAllowed("GET") shouldBe true
    config.isMethodAllowed("POST") shouldBe false
    config.isMethodAllowed("PUT") shouldBe false
    config.isMethodAllowed("DELETE") shouldBe false
  }

  "HttpConfig.validateDomainWithSSRF" should "block internal IPs by default" in {
    val config = HttpConfig()

    config.validateDomainWithSSRF("10.0.0.1") shouldBe false
    config.validateDomainWithSSRF("192.168.1.10") shouldBe false
  }

  it should "allow internal IPs when explicitly enabled" in {
    val config = HttpConfig().withInternalIPsAllowed

    config.validateDomainWithSSRF("10.0.0.1") shouldBe true
  }

  "HttpConfig.unsafe" should "disable SSRF protection and allow write methods" in {
    val config = HttpConfig.unsafe

    config.blockInternalIPs shouldBe false
    config.blockedDomains shouldBe Seq.empty
    config.isMethodAllowed("POST") shouldBe true
    config.isMethodAllowed("DELETE") shouldBe true
  }

  "HttpConfig.restricted" should "limit to specified domains" in {
    val config = HttpConfig.restricted(Seq("api.trusted.com"))

    config.isDomainAllowed("api.trusted.com") shouldBe true
    config.isDomainAllowed("other.com") shouldBe false
  }

  // ============ Deprecated API tests ============

  "HTTPTool.create() (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = HTTPTool.create()
    tool.name shouldBe "http_request"
  }

  "HTTPTool.tool (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = HTTPTool.tool
    tool.name shouldBe "http_request"
  }

  "http.allTools (deprecated)" should "contain http_request" in {
    @nowarn("cat=deprecation") val tools = http.allTools
    tools.size shouldBe 1
    tools.head.name shouldBe "http_request"
  }
}
