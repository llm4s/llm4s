package org.llm4s.samples.java

import org.llm4s.javaapi.{ FakeLlmClient, LlmErrorKind }
import org.llm4s.samples.java.JavaOfflineExample.TicketClassifier
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*

class JavaOfflineExampleSpec extends AnyFlatSpec with Matchers {

  "TicketClassifier" should "return each recognized category" in {
    val fake   = FakeLlmClient.replying("BILLING", "BUG", "OTHER")
    val client = fake.client()
    try {
      val classifier = new TicketClassifier(client)
      List("BILLING", "BUG", "OTHER").foreach { category =>
        val result = classifier.classify(s"A ticket for $category")
        result.isSuccess shouldBe true
        result.get() shouldBe category
      }
    } finally client.close()
  }

  it should "fall back for an unknown reply, preserve a provider failure, and record every prompt" in {
    val fake = FakeLlmClient
      .builder()
      .reply("BILLING")
      .reply("not sure")
      .failWith(LlmErrorKind.RATE_LIMIT, "slow down")
      .build()
    val client = fake.client()
    try {
      val classifier = new TicketClassifier(client)
      val billing    = classifier.classify("Please refund my duplicate charge.")
      val unknown    = classifier.classify("I have a question about my account.")
      val limited    = classifier.classify("The application crashes when I sign in.")

      billing.isSuccess shouldBe true
      billing.get() shouldBe "BILLING"
      unknown.isSuccess shouldBe true
      unknown.get() shouldBe "OTHER"
      limited.isFailure shouldBe true
      limited.getError().getKind shouldBe LlmErrorKind.RATE_LIMIT

      val instruction = "Classify this support ticket as BILLING, BUG, or OTHER. Reply with only the label.\n\nTicket: "
      fake.callCount() shouldBe 3
      fake.prompts().asScala.toList shouldBe List(
        instruction + "Please refund my duplicate charge.",
        instruction + "I have a question about my account.",
        instruction + "The application crashes when I sign in."
      )
    } finally client.close()
  }
}
