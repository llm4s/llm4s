package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ SystemMessage, UserMessage }
import org.llm4s.samples.tutorial.supportassistant.solutions.Part1Solution
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Part 1 of the support-assistant tutorial (`docs/tutorials/support-assistant/part-1-first-call.md`): its code, run
 * against a scripted client; its page, compared with the code word for word; and exercise 1, the solution and yours.
 */
class Part1Spec extends AnyFlatSpec with Matchers with TutorialSpecSupport {
  import Part1FirstCall.*

  "Part 1's ask" should "send the instructions and the question, and return the model's answer" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Two working days."))

    ask(client, "How long does delivery take?") shouldBe Right("Two working days.")
    client.lastCall.map(_.messages) shouldBe Some(
      Seq(SystemMessage(instructions), UserMessage("How long does delivery take?"))
    )
  }

  "Part 1's askStreaming" should "hand every piece of the answer to onText, and return the whole answer" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Orders to London arrive in two working days.")).withChunkSize(5)
    val received = Vector.newBuilder[String]

    val result = askStreaming(client, question)(text => received += text)

    result shouldBe Right("Orders to London arrive in two working days.")
    received.result().size should be > 1
    received.result().mkString shouldBe "Orders to London arrive in two working days."
  }

  "Part 1's demo" should "run end to end against its scripted client" in {
    val client = scripted()
    ask(client, question).isRight shouldBe true
    askStreaming(client, question)(_ => ()).isRight shouldBe true
    client.assertExhausted() shouldBe Right(())
  }

  "Part 1's page" should "show the code of Part1FirstCall, in the same order, word for word" in {
    val code = regions("Part1FirstCall.scala")
    code should have size 3
    pageBlocks("part-1-first-call.md") shouldBe code
  }

  /** Exercise 1: the system message ends with "Answer in <language>." and the answer is the model's. */
  private def checkAskIn(askIn: (LLMClient, String, String) => Result[String]): Unit = {
    val client = ScriptedLLMClient.sequence(Reply.text("Votre colis arrive demain."))
    askIn(client, "Where is my parcel?", "French") shouldBe Right("Votre colis arrive demain.")
    client.lastCall.map(_.messages) shouldBe Some(
      Seq(SystemMessage(s"$instructions Answer in French."), UserMessage("Where is my parcel?"))
    )
  }

  "Exercise 1's solution" should "ask for an answer in the given language" in {
    checkAskIn(Part1Solution.askIn)
  }

  "Your exercise 1 (Part1Exercise.askIn)" should "ask for an answer in the given language" in {
    yourExercise(checkAskIn(Part1Exercise.askIn))
  }
}
