package org.llm4s.samples.guides

// The page shows these imports as one block: keep them together and in this order.
import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.agent.guardrails.rag._
import org.llm4s.toolapi.ToolRegistry

import org.llm4s.agent.AgentStatus
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/**
 * The code of the "RAG Guardrails" section of `docs/guide/agents/guardrails.md`, compiled and run.
 *
 * Every `scala` block of that section is a run of lines of THIS file, word for word (indentation aside): the last
 * test reads the page and fails if a block is not found here, so the section cannot name a constructor parameter or a
 * factory the guardrails do not have (#2781). Change the section and this spec together.
 */
@org.llm4s.tags.DocsSpec
class RagGuardrailsGuideSpec extends AnyFlatSpec with Matchers with EitherValues {
  import RagGuardrailsGuideSpec.*
  import TestingWithTestkitGuideSpec.{ normalised, read, repositoryRoot, scalaBlocks }

  private def searching = Reply.toolCall("search_docs", """{"query": "refund window"}""")

  "The direct call of the guide" should "validate an answer against a context the caller built" in {
    val judge = ScriptedLLMClient.sequence(Reply.text(Grounded))

    val context = RAGContext.withSources(
      query = "What is the refund window?",
      chunks = Seq("Refunds are accepted within 30 days of purchase."),
      sources = Seq("refund-policy.md")
    )

    val grounding = new GroundingGuardrail(llmClient = judge, threshold = 0.8)

    // Right(answer) when the judge scores it at or above the threshold, Left(ValidationError) otherwise
    val checked = grounding.validateWithContext("You can get a refund within 30 days.", context)

    checked shouldBe Right("You can get a refund within 30 days.")
    judge.calls.head.lastUserText.getOrElse("") should include("Refunds are accepted within 30 days of purchase.")
    // without a context there is nothing to check against, so `validate` passes and calls no judge
    grounding.validate("Refunds within 90 days.") shouldBe Right("Refunds within 90 days.")
    judge.callCount shouldBe 1
  }

  "The agent example of the guide" should "check the answer against the turn's search results" in {
    val client = ScriptedLLMClient.sequence(searching, Reply.text("You can get a refund within 30 days."))
    val judge  = ScriptedLLMClient.sequence(Reply.text(Grounded))

    // The turn's context: its user message is the query, each result of the "search_docs" tool is a chunk
    val grounded = RAGGuardrail.withTurnContext(GroundingGuardrail.balanced(judge)) { turn =>
      Some(RAGContext.fromToolResults(turn, Set("search_docs")))
    }

    val agent = ToolRegistry.of(Seq(searchDocs)).flatMap { tools =>
      Agent
        .builder("support", client)
        .withTools(tools)
        .withOutputGuardrails(grounded)
        .build()
    }

    val result = agent.flatMap(_.run("What is the refund window?"))

    result.value.status shouldBe AgentStatus.Completed("You can get a refund within 30 days.")
    val prompt = judge.calls.head.lastUserText.getOrElse("")
    prompt should include("What is the refund window?")
    prompt should include("Refunds are accepted within 30 days of purchase.")
  }

  it should "block an answer the search results do not support" in {
    val client = ScriptedLLMClient.sequence(searching, Reply.text("You can get a refund within 90 days."))
    val judge  = ScriptedLLMClient.sequence(Reply.text(Ungrounded))
    val grounded = RAGGuardrail.withTurnContext(GroundingGuardrail.balanced(judge)) { turn =>
      Some(RAGContext.fromToolResults(turn, Set("search_docs")))
    }
    val agent = ToolRegistry.of(Seq(searchDocs)).flatMap { tools =>
      Agent
        .builder("support", client)
        .withTools(tools)
        .withOutputGuardrails(grounded)
        .build()
    }

    agent.flatMap(_.run("What is the refund window?")).value.status match {
      case AgentStatus.Blocked(guardrail, _) => guardrail shouldBe "GroundingGuardrail"
      case other                             => fail(s"expected Blocked, got $other")
    }
  }

  "The presets of the guide" should "each be a GuardrailConfig, usable on the agent builder" in {
    val client = ScriptedLLMClient.sequence(searching, Reply.text("You can get a refund within 30 days."))
    val judge  = ScriptedLLMClient.sequence(Reply.text(Grounded), Reply.text(Relevant))

    val minimal    = RAGGuardrails.minimal           // no LLM calls
    val standard   = RAGGuardrails.standard(judge)
    val strict     = RAGGuardrails.strict(judge, allowedTopics = Seq("refunds", "shipping"))
    val monitoring = RAGGuardrails.monitoring(judge) // warns, never blocks

    val inputs = standard.inputGuardrails
    val outputs =
      standard.outputGuardrailsWithContext(turn => Some(RAGContext.fromToolResults(turn, Set("search_docs"))))

    minimal.ragGuardrails shouldBe empty
    standard.ragGuardrails.map(_.name) shouldBe Seq("GroundingGuardrail", "ContextRelevanceGuardrail")
    strict.ragGuardrails.map(_.name) should contain("SourceAttributionGuardrail")
    strict.inputGuardrails.map(_.name) should contain("TopicBoundaryGuardrail")
    monitoring.ragGuardrails should have size 3

    val agent = ToolRegistry.of(Seq(searchDocs)).flatMap { tools =>
      Agent
        .builder("support", client)
        .withTools(tools)
        .withInputGuardrails(inputs*)
        .withOutputGuardrails(outputs*)
        .build()
    }
    agent.flatMap(_.run("What is the refund window?")).value.status shouldBe
      AgentStatus.Completed("You can get a refund within 30 days.")
    judge.callCount shouldBe 2
  }

  "The individual guardrails of the guide" should "take the parameters the page names" in {
    val judge = ScriptedLLMClient.sequence()

    // Is the answer supported by the retrieved chunks? strictMode fails on any unsupported claim, whatever the score
    val grounding = new GroundingGuardrail(
      llmClient = judge,
      threshold = 0.7,
      onFail = GuardrailAction.Block,
      strictMode = false
    )

    // Are the retrieved chunks relevant to the query? A chunk scoring at least `threshold` is relevant
    val relevance = new ContextRelevanceGuardrail(
      llmClient = judge,
      threshold = 0.5,
      minRelevantRatio = 0.5,
      onFail = GuardrailAction.Block
    )

    // Does the answer cite its sources?
    val attribution = new SourceAttributionGuardrail(
      llmClient = judge,
      requireAttributions = true,
      minAttributionScore = 0.5,
      onFail = GuardrailAction.Block
    )

    // Is the query about an allowed topic? An input guardrail: it needs no RAGContext
    val topicBoundary = new TopicBoundaryGuardrail(
      llmClient = judge,
      allowedTopics = Seq("programming", "software engineering"),
      threshold = 0.5,
      onFail = GuardrailAction.Block
    )

    // the values the page shows are the defaults
    val defaultGrounding = new GroundingGuardrail(judge)
    (grounding.threshold, grounding.onFail, grounding.strictMode) shouldBe
      (defaultGrounding.threshold, defaultGrounding.onFail, defaultGrounding.strictMode)
    val defaultRelevance = new ContextRelevanceGuardrail(judge)
    (relevance.threshold, relevance.minRelevantRatio, relevance.onFail) shouldBe
      (defaultRelevance.threshold, defaultRelevance.minRelevantRatio, defaultRelevance.onFail)
    val defaultAttribution = new SourceAttributionGuardrail(judge)
    (attribution.requireAttributions, attribution.minAttributionScore, attribution.onFail) shouldBe
      (defaultAttribution.requireAttributions, defaultAttribution.minAttributionScore, defaultAttribution.onFail)
    val defaultTopics = new TopicBoundaryGuardrail(judge, Seq("programming", "software engineering"))
    (topicBoundary.threshold, topicBoundary.onFail) shouldBe (defaultTopics.threshold, defaultTopics.onFail)
    judge.callCount shouldBe 0
  }

  "The RAG Guardrails section of the page" should "show only code that this spec contains" in {
    val root = repositoryRoot.getOrElse(fail("no docs/guide above the working directory"))
    val page = read(root.resolve("docs/guide/agents/guardrails.md"))
    val spec = "\n" + normalised(
      read(root.resolve("modules/samples/src/test/scala/org/llm4s/samples/guides/RagGuardrailsGuideSpec.scala"))
    ) + "\n"

    val section = page.linesIterator.dropWhile(_ != "## RAG Guardrails").drop(1).takeWhile(!_.startsWith("## "))
    val blocks  = scalaBlocks(section.mkString("\n"))
    blocks should have size 5
    blocks.foreach { block =>
      withClue(s"This block of the section is not in the spec, line for line:\n$block\n") {
        spec.contains("\n" + normalised(block) + "\n") shouldBe true
      }
    }
  }
}

object RagGuardrailsGuideSpec {

  final case class Passages(passages: Seq[String]) derives ReadWriter

  /** The agent's retrieval tool: whatever the query, it finds the refund policy. */
  val searchDocs: ToolFunction[Passages] =
    ToolBuilder[Passages](
      "search_docs",
      "Searches the product documentation.",
      Schema.`object`[Map[String, Any]]("Parameters").withProperty(Schema.property("query", Schema.string("The query")))
    ).withHandler(_ => Right[Nothing, Passages](Passages(Seq("Refunds are accepted within 30 days of purchase."))))
      .buildSafe()
      .fold(error => throw new IllegalStateException(error.message), identity)

  val Grounded: String =
    """SCORE: 0.95
      |GROUNDED: YES
      |UNGROUNDED_CLAIMS: NONE
      |EXPLANATION: Supported by the context.""".stripMargin

  val Ungrounded: String =
    """SCORE: 0.1
      |GROUNDED: NO
      |UNGROUNDED_CLAIMS: 90 days
      |EXPLANATION: The context says 30 days.""".stripMargin

  val Relevant: String =
    """OVERALL_SCORE: 0.9
      |CHUNK_SCORES: 0.9
      |EXPLANATION: Relevant.""".stripMargin
}
