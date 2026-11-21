package org.llm4s.llmconnect.provider

import ch.qos.logback.classic.Level
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.llm4s.testutil.DebugMessageProbe.debugMessagesBuilt
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The request and response bodies are redacted for a debug line only when that line is logged (#2631). */
final class OpenAICompatibleDebugLoggingSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val secret = "s3cr3tvalue0123456789"
  private val text   = s"callback https://hooks.test/cb?token=$secret"

  private def completeAgainstServer(level: Level): (String, Vector[String]) = {
    var outcome: (String, Vector[String]) = ("", Vector.empty)
    withServer("/")(exchange => sendJsonResponse(exchange, 200, openAICompletion(text))) { baseUrl =>
      val client = OpenAICompatibleClient(OpenAICompatibleConfig(model = "local-model", baseUrl = baseUrl)).value
      val (reply, built) = debugMessagesBuilt(client.getClass.getName, level) {
        client.complete(Conversation(Seq(UserMessage(text))), CompletionOptions())
      }
      outcome = (reply.value.content, built)
    }
    outcome
  }

  "OpenAICompatibleClient" should "build no body for its debug lines when DEBUG is off" in {
    val (content, built) = completeAgainstServer(Level.INFO)
    content shouldBe text
    built.filter(_.contains("body")) shouldBe empty
  }

  it should "still log both bodies redacted when DEBUG is on" in {
    val (_, built) = completeAgainstServer(Level.DEBUG)
    val bodies     = built.filter(_.contains("body"))
    bodies.exists(_.startsWith("Request body:")) shouldBe true
    bodies.exists(_.startsWith("Response body:")) shouldBe true
    bodies.foreach(line => (line should not).include(secret))
  }
}
