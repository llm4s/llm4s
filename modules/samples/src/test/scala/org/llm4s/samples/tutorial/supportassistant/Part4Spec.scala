package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.rag.RAG
import org.llm4s.samples.tutorial.supportassistant.solutions.Part4Solution
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Part 4 of the support-assistant tutorial (`docs/tutorials/support-assistant/part-4-help-centre.md`): its pipeline,
 * cited answer and help-centre tool, run against a scripted client and the stand-in embedding model; its page,
 * compared with the code word for word; and exercise 4, the solution and yours.
 */
class Part4Spec extends AnyFlatSpec with Matchers with TutorialSpecSupport {
  import Part4HelpCentre.*

  "Part 4's pipeline" should "ingest every article, one chunk each" in {
    withHelpCentre(ScriptedLLMClient.sequence())(rag => Right((rag.documentCount, rag.chunkCount))) shouldBe
      Right((articles.size, articles.size))
  }

  it should "retrieve the delivery article first for a delivery question" in {
    val passages = withHelpCentre(ScriptedLLMClient.sequence())(_.query(question))
    passages.map(_.head.metadata.get("docId")) shouldBe Right(Some("delivery"))
  }

  "Part 4's cited answer" should "answer from the retrieved passages and name them as sources" in {
    val client = scripted()

    val answer = withHelpCentre(client)(answerFromHelpCentre(_, question))

    answer.map(_.text) shouldBe Right(scriptedAnswer)
    answer.map(_.sources.headOption) shouldBe Right(Some("delivery"))
    client.assertExhausted() shouldBe Right(())
    val call = client.lastCall.get
    call.messages.head.content shouldBe helpCentrePrompt
    call.lastUserText.get should include("[1] " + articles("delivery"))
  }

  "Part 4's help tool" should "return the passages for a query, with their article" in {
    val passages = withHelpCentre(ScriptedLLMClient.sequence()) { rag =>
      helpTool(rag).flatMap(
        _.execute(ujson.Obj("question" -> "change my email address")).left.map(e => fail(e.getMessage))
      )
    }
    passages.map(json => json.arr.head("article").str) shouldBe Right("account")
  }

  "Part 4's agent" should "search the help centre and answer from the passage" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("search_help_centre", """{"question": "express delivery"}"""),
      Reply.text("Express delivery arrives the next working day.")
    )

    val answer = withHelpCentre(client)(rag => helpAgent(client, rag).flatMap(_.run(question)))

    answer.map(_.answer) shouldBe Right(Some("Express delivery arrives the next working day."))
    client.calls.head.options.tools.map(_.name).toSet shouldBe
      Set("lookup_order", "refund_policy", "search_help_centre")
    val toolOutput = client.calls.last.messages.filter(_.role == MessageRole.Tool).map(_.content)
    toolOutput.head should include("next working day")
  }

  "Part 4's page" should "show the code of Part4HelpCentre, in the same order, word for word" in {
    val code = regions("Part4HelpCentre.scala")
    code should have size 4
    pageBlocks("part-4-help-centre.md") shouldBe code
  }

  /** Exercise 4: the articles behind a question, best first, each once, and no model call. */
  private def checkSources(sources: (RAG, String) => Result[Seq[String]]): Unit = {
    val client = ScriptedLLMClient.sequence()
    val found  = withHelpCentre(client)(rag => sources(rag, question)).fold(e => fail(e.message), identity)
    found.headOption shouldBe Some("delivery")
    found.distinct shouldBe found
    client.callCount shouldBe 0
  }

  "Exercise 4's solution" should "name the articles behind a question" in {
    checkSources(Part4Solution.sources)
  }

  "Your exercise 4 (Part4Exercise.sources)" should "name the articles behind a question" in {
    yourExercise(checkSources(Part4Exercise.sources))
  }
}
