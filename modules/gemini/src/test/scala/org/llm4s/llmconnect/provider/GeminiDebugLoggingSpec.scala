package org.llm4s.llmconnect.provider

import ch.qos.logback.classic.Level
import org.llm4s.http.{ HttpResponse, StubbedHttpCalls }
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ GeminiConfig, VertexAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.LocalProviderTestServer._
import org.llm4s.testutil.DebugMessageProbe.debugMessagesBuilt
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.FiniteDuration

/** The request body is redacted for a debug line only when that line is logged (#2631). */
final class GeminiDebugLoggingSpec extends AnyFlatSpec with Matchers with EitherValues with MockFactory {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val secret = "s3cr3tvalue0123456789"
  private val text   = s"callback https://hooks.test/cb?token=$secret"
  private val reply =
    """|{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},"finishReason":"STOP"}],
       | "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}""".stripMargin
  private val conversation = Conversation(Seq(UserMessage(text)))

  private def gemini(level: Level): Vector[String] = {
    var built = Vector.empty[String]
    withServer("/")(exchange => sendJsonResponse(exchange, 200, reply)) { baseUrl =>
      val client = new GeminiClient(
        GeminiConfig(
          apiKey = Secret("test-key"),
          model = "gemini-2.0-flash",
          baseUrl = baseUrl,
          contextWindow = 1048576,
          reserveCompletion = 8192
        )
      )
      val (result, messages) = debugMessagesBuilt(client.getClass.getName, level) {
        client.complete(conversation, CompletionOptions())
      }
      result.value.content shouldBe "Hello!"
      built = messages
    }
    built
  }

  private def vertex(level: Level): Vector[String] = {
    val http = stub[StubbedHttpCalls]
    (http.onPost _).when(*, *, *, *).onCall { (url: String, _: Map[String, String], _: String, _: FiniteDuration) =>
      Right {
        if (url.contains("oauth2.googleapis.com"))
          HttpResponse(200, """{"access_token":"ya29.test","expires_in":3600}""", Map.empty)
        else HttpResponse(200, reply, Map.empty)
      }
    }
    (http.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, """{"access_token":"ya29.test"}""", Map.empty)))
    val config = VertexAIConfig(
      projectId = "my-gcp-project",
      location = "us-central1",
      model = "gemini-2.0-flash",
      credentialFilePath = None,
      contextWindow = 1048576,
      reserveCompletion = 8192
    )
    val client =
      new VertexAIClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, http)
    val (result, built) = debugMessagesBuilt(client.getClass.getName, level)(client.complete(conversation))
    result.value.content shouldBe "Hello!"
    built
  }

  "GeminiClient" should "build no request body for its debug line when DEBUG is off" in {
    gemini(Level.INFO).filter(_.contains("Request body")) shouldBe empty
  }

  it should "still log the request body redacted when DEBUG is on" in {
    val bodies = gemini(Level.DEBUG).filter(_.contains("Request body"))
    bodies should have size 1
    (bodies.head should not).include(secret)
  }

  "VertexAIClient" should "build no request body for its debug line when DEBUG is off" in {
    vertex(Level.INFO).filter(_.contains("Request body")) shouldBe empty
  }

  it should "still log the request body redacted when DEBUG is on" in {
    val bodies = vertex(Level.DEBUG).filter(_.contains("Request body"))
    bodies should have size 1
    (bodies.head should not).include(secret)
  }
}
