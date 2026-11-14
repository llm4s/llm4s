package org.llm4s.samples.java

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*

class JavaAiServiceExampleSpec extends AnyFlatSpec with Matchers {

  "JavaAiServiceExample" should "answer every method from the scripted model, with templates rendered" in {
    val model  = JavaAiServiceExample.scriptedModel()
    val client = model.client()
    try {
      JavaAiServiceExample.run(JavaAiServiceExample.assistant(client)).asScala.toList shouldBe List(
        "answer: Open Settings, choose Security, then Reset password.",
        "sentiment: NEGATIVE",
        "ticket: Charged twice in March (priority 1)",
        "memory: Your name is Ada."
      )
      model.prompts().asScala.toList shouldBe List(
        "How do I reset my password?",
        "Classify the sentiment of: The app keeps crashing and nobody answers my emails.",
        "I was charged twice in March.",
        "Hi, I am Ada.",
        "What is my name?"
      )
    } finally client.close()
  }
}
