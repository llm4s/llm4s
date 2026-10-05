package org.llm4s.testkit

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }

class FakeTokenExchangeServerSpec extends AnyWordSpec with Matchers:

  private val http = HttpClient.newHttpClient()

  private def post(url: String, body: String, headers: (String, String)*): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body))
    headers.foreach((k, v) => builder.header(k, v))
    http.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  private val form = "grant_type=g&subject_token=abc&subject_token_type=x"

  "FakeTokenExchangeServer" should {
    "issue t1, t2 for RFC 8693 exchanges and record the form" in FakeTokenExchangeServer.withServer { fake =>
      val encoded =
        "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&subject_token=abc&subject_token_type=x"
      val r1 = post(
        fake.baseUrl + FakeTokenExchangeServer.TokenPath,
        encoded,
        "Content-Type" -> "application/x-www-form-urlencoded"
      )
      r1.statusCode shouldBe 200
      r1.body should include("\"access_token\":\"t1\"")
      fake.exchanges.head("subject_token") shouldBe "abc"
      fake.exchanges.head("grant_type") shouldBe "urn:ietf:params:oauth:grant-type:token-exchange"
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, encoded).body should include("t2")
      fake.issuedTokens shouldBe Seq("t1", "t2")
    }

    "accept only the latest token on the chat endpoint" in FakeTokenExchangeServer.withServer { fake =>
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, form)
      post(
        fake.baseUrl + FakeTokenExchangeServer.ChatPath,
        "{}",
        "Authorization" -> "Bearer t1"
      ).statusCode shouldBe 200
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, form)
      post(
        fake.baseUrl + FakeTokenExchangeServer.ChatPath,
        "{}",
        "Authorization" -> "Bearer t1"
      ).statusCode shouldBe 401
      post(
        fake.baseUrl + FakeTokenExchangeServer.ChatPath,
        "{}",
        "Authorization" -> "Bearer t2"
      ).statusCode shouldBe 200
      fake.apiAuthorizations shouldBe Seq("Bearer t1", "Bearer t1", "Bearer t2")
    }

    "refuse an API call before any token has been issued" in FakeTokenExchangeServer.withServer { fake =>
      post(
        fake.baseUrl + FakeTokenExchangeServer.ChatPath,
        "{}",
        "Authorization" -> "Bearer t1"
      ).statusCode shouldBe 401
    }

    "reject the next N API calls when told to" in FakeTokenExchangeServer.withServer { fake =>
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, "subject_token=abc")
      fake.rejectNextApiCalls(1)
      post(
        fake.baseUrl + FakeTokenExchangeServer.ChatPath,
        "{}",
        "Authorization" -> "Bearer t1"
      ).statusCode shouldBe 401
      post(
        fake.baseUrl + FakeTokenExchangeServer.ChatPath,
        "{}",
        "Authorization" -> "Bearer t1"
      ).statusCode shouldBe 200
    }

    "refuse a subject token the validator rejects" in FakeTokenExchangeServer.withServer { fake =>
      fake.setSubjectValidator(token => Either.cond(token == "good", (), "bad audience"))
      val rejected = post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, "subject_token=bad")
      rejected.statusCode shouldBe 400
      rejected.body should include("bad audience")
      fake.issuedTokens shouldBe empty
    }

    "refuse a blank subject token by default" in FakeTokenExchangeServer.withServer { fake =>
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, "grant_type=g").statusCode shouldBe 400
    }

    "report the configured expires_in" in FakeTokenExchangeServer.withServer { fake =>
      fake.setExpiresIn(45)
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, form).body should include("\"expires_in\":45")
    }

    "serve a streaming chat completion when the request asks for one" in FakeTokenExchangeServer.withServer { fake =>
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, form)
      val reply = post(
        fake.baseUrl + FakeTokenExchangeServer.OpenAIChatPath,
        """{"stream":true}""",
        "Authorization" -> "Bearer t1"
      )
      reply.statusCode shouldBe 200
      reply.headers.firstValue("Content-Type").orElse("") should include("text/event-stream")
    }

    "serve a model list on the models endpoint" in FakeTokenExchangeServer.withServer { fake =>
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, form)
      val request = HttpRequest
        .newBuilder(URI.create(fake.baseUrl + FakeTokenExchangeServer.ModelsPath))
        .header("Authorization", "Bearer t1")
        .GET()
        .build()
      http.send(request, HttpResponse.BodyHandlers.ofString()).body should include("fake-model")
    }

    "serve the Anthropic jwt-bearer grant and messages" in FakeTokenExchangeServer.withServer { fake =>
      val grant =
        """{"grant_type":"urn:ietf:params:oauth:grant-type:jwt-bearer","assertion":"abc","federation_rule_id":"fdrl_1","organization_id":"org"}"""
      post(
        fake.baseUrl + FakeTokenExchangeServer.AnthropicTokenPath,
        grant,
        "Content-Type" -> "application/json"
      ).body should include("t1")
      fake.exchanges.head("assertion") shouldBe "abc"
      post(
        fake.baseUrl + FakeTokenExchangeServer.AnthropicMessagesPath,
        "{}",
        "Authorization" -> "Bearer t1"
      ).body should include("\"type\":\"message\"")
    }

    "treat a malformed Anthropic grant body as having no assertion" in FakeTokenExchangeServer.withServer { fake =>
      post(fake.baseUrl + FakeTokenExchangeServer.AnthropicTokenPath, "not json").statusCode shouldBe 400
    }

    "rethrow a failing test's exception after stopping the server" in {
      val error =
        intercept[IllegalStateException](FakeTokenExchangeServer.withServer(_ => throw IllegalStateException("boom")))
      error.getMessage shouldBe "boom"
    }
  }

  "TestJwt.es256" should {
    "produce a three-part compact JWT carrying the claims" in {
      val jwt   = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      val parts = jwt.split('.')
      parts.length shouldBe 3
      val payload = new String(java.util.Base64.getUrlDecoder.decode(parts(1)))
      payload should (include("spiffe://llm4s.test/app").and(include("databricks")))
    }

    "write the numeric dates as JSON numbers, which a JWT validator requires" in {
      val payload = ujson.read(new String(java.util.Base64.getUrlDecoder.decode(TestJwt.es256("s", "a").split('.')(1))))
      payload("exp").num should be > payload("iat").num
      payload("iat").numOpt shouldBe defined
    }

    "give each token a unique jti" in {
      TestJwt.es256("s", "a") should not be TestJwt.es256("s", "a")
    }
  }
