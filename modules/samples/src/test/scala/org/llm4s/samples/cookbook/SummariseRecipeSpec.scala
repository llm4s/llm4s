package org.llm4s.samples.cookbook

import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the map-reduce summary against its scripted client. */
class SummariseRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def instructions(client: ScriptedClient): Vector[String] =
    client.calls.map(_._1.messages.find(_.role == MessageRole.System).map(_.content).getOrElse(""))

  "SummariseRecipe.summarise" should "summarise each part, then the summaries, in one call each" in {
    val client = SummariseRecipe.script

    val summary = SummariseRecipe.summarise(client, SummariseRecipe.report).value

    summary.parts should be > 1
    client.calls should have size (summary.parts + 1).toLong
    instructions(client) shouldBe Vector.fill(summary.parts)(SummariseRecipe.MapPrompt) :+ SummariseRecipe.ReducePrompt
    summary.text should include("12 percent")
  }

  it should "give the reduce call every part's summary, numbered in document order" in {
    val client = SummariseRecipe.script

    val summary = SummariseRecipe.summarise(client, SummariseRecipe.report).value

    val reduceInput = client.calls.last._1.messages.filter(_.role == MessageRole.User).map(_.content).mkString
    (1 to summary.parts).foreach(i => reduceInput should include(s"$i. "))
    reduceInput.indexOf("northern warehouse") should be < reduceInput.indexOf("Late deliveries")
  }

  it should "send every part of the document to the model, and nothing twice" in {
    val client = SummariseRecipe.script

    SummariseRecipe.summarise(client, SummariseRecipe.report).value

    val mapInputs = client.calls.init.map(_._1.messages.filter(_.role == MessageRole.User).map(_.content).mkString)
    Seq("18,400 orders", "sorting hub", "second packing line").foreach { fact =>
      mapInputs.count(_.contains(fact)) shouldBe 1
    }
  }

  it should "skip the reduce call when the document fits in one part" in {
    val client = SummariseRecipe.script

    val summary = SummariseRecipe.summarise(client, "A short note. It has two sentences.").value

    summary.parts shouldBe 1
    client.calls should have size 1
    summary.text shouldBe "A short note."
  }

  it should "fail, without the reduce call, when a part cannot be summarised" in {
    val client = new ScriptedClient((_, call) =>
      if (call == 2) Left(ServiceError(503, "scripted", "unavailable")) else Right(AssistantMessage("ok"))
    )

    SummariseRecipe.summarise(client, SummariseRecipe.report).left.value shouldBe a[ServiceError]
    client.calls should have size 2
  }
}
