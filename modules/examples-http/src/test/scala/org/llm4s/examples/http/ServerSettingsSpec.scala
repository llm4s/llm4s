package org.llm4s.examples.http

import cats.effect.unsafe.implicits.global
import org.llm4s.llmconnect.model.{ Conversation, SystemMessage, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ServerSettingsSpec extends AnyFlatSpec with Matchers {

  "ServerSettings.parse" should "default to the configured provider on 127.0.0.1:8080" in {
    ServerSettings.parse(Nil) shouldBe Right(ServerSettings("127.0.0.1", 8080, fake = false))
  }

  it should "read the host, the port and --fake in any order" in {
    ServerSettings.parse(List("--fake", "--port", "0", "--host", "0.0.0.0")) shouldBe
      Right(ServerSettings("0.0.0.0", 0, fake = true))
  }

  it should "refuse a port that is not one" in {
    ServerSettings.parse(List("--port", "http")).swap.getOrElse("") should include("--port must be a number")
    ServerSettings.parse(List("--port", "70000")).isLeft shouldBe true
  }

  it should "refuse an unknown or incomplete argument" in {
    ServerSettings.parse(List("--verbose")).swap.getOrElse("") should include("'--verbose'")
    ServerSettings.parse(List("--host")).isLeft shouldBe true
  }

  "The fake client" should "echo the last user message" in {
    val conversation = Conversation(Seq(SystemMessage("be brief"), UserMessage("first"), UserMessage("hello")))
    ServerSettings.fakeClient.complete(conversation).map(_.content) shouldBe Right("You said: hello")
  }

  it should "be what --fake serves" in {
    val answer = ServerSettings(fake = true).client
      .use(_.complete(Conversation(Seq(UserMessage("ping")))))
      .map(_.content)
      .unsafeRunSync()
    answer shouldBe "You said: ping"
  }

  it should "answer an empty conversation" in {
    ServerSettings.fakeClient.complete(Conversation(Seq.empty)).map(_.content) shouldBe Right("You said: ")
  }
}
