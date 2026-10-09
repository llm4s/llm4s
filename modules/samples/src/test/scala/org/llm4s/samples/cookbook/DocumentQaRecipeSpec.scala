package org.llm4s.samples.cookbook

import org.llm4s.llmconnect.model.{ MessageRole, UserMessage }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the document Q&A recipe: retrieval picks the passage, and only that passage reaches the model. */
class DocumentQaRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {

  import DocumentQaRecipe.{ answer, handbook, keywordQuery }

  private def systemPrompt(client: ScriptedClient): String =
    client.calls.head._1.messages.find(_.role == MessageRole.System).map(_.content).getOrElse("")

  "DocumentQaRecipe.answer" should "answer from the passage that matches the question, and name its source" in {
    val client = DocumentQaRecipe.script

    val result = answer(client, handbook, "How many vacation days do employees get?").value

    result.text should include("25 days")
    result.sources shouldBe Seq("vacation")
  }

  it should "put only the best passage in the prompt, not the whole handbook" in {
    val client = DocumentQaRecipe.script

    answer(client, handbook, "How many vacation days do employees get?").value

    client.calls should have size 1
    val prompt = systemPrompt(client)
    prompt should include("25 days of paid vacation")
    (prompt should not).include("receipt")
    (prompt should not).include("Remote work")
  }

  it should "give the no-document answer for a question of only stop words, with no error and no model call" in {
    val client = DocumentQaRecipe.script

    // "What", "is" and "it" are all stop words or too short, so the keyword query is empty;
    // FTS5 rejects an empty MATCH, and the recipe must not reach it (Codex review, #1595).
    val result = answer(client, handbook, "What is it?").value

    result.text shouldBe "I could not find anything about that in the documents."
    result.sources shouldBe Seq.empty
    client.calls shouldBe empty
  }

  it should "pick a different passage for a different question" in {
    val client = DocumentQaRecipe.script

    val result = answer(client, handbook, "Do I need a receipt for expenses?").value

    result.sources shouldBe Seq("expenses")
    systemPrompt(client) should include("receipt")
    (systemPrompt(client) should not).include("vacation")
  }

  it should "say it found nothing, without calling the model, when no document matches" in {
    val client = DocumentQaRecipe.script

    val result = answer(client, handbook, "Who won the football match yesterday?").value

    result.sources shouldBe empty
    result.text should include("could not find")
    client.calls shouldBe empty
  }

  it should "send the question itself to the model as the user message" in {
    val client = DocumentQaRecipe.script

    answer(client, handbook, "How many vacation days do employees get?").value

    client.calls.head._1.messages.collect { case user: UserMessage => user.content } shouldBe
      Seq("How many vacation days do employees get?")
  }

  "DocumentQaRecipe.keywordQuery" should "turn a question into an OR of its content words" in {
    keywordQuery("How many vacation days do employees get?") shouldBe "\"vacation\" OR \"days\" OR \"employees\""
  }

  it should "keep punctuation out of the query" in {
    keywordQuery("Receipt? (expenses), please!") shouldBe "\"receipt\" OR \"expenses\" OR \"please\""
  }

  "DocumentQaRecipe.demo" should "print the answer and its source" in {
    val text = DocumentQaRecipe.demo(DocumentQaRecipe.script).value

    text should include("25 days")
    text should include("(source: vacation)")
  }
}
