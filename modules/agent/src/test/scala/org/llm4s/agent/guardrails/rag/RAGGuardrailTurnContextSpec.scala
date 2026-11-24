package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.{ Agent, AgentStatus, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.AgentFixture.*
import org.llm4s.agent.graph.GraphError
import org.llm4s.agent.graph.GraphTestSupport.testModelContext
import org.llm4s.agent.graph.middleware.{ GuardrailMiddleware, ModelContext }
import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/**
 * A RAG guardrail inside an agent (#2781): `RAGGuardrail.withTurnContext` gives it the turn's retrieved context when
 * `GuardrailMiddleware` validates the final answer.
 */
class RAGGuardrailTurnContextSpec extends AnyFlatSpec with Matchers {

  final case class Found(passage: String) derives ReadWriter

  private val Passage = "Refunds are accepted within 30 days of purchase."

  /** A retrieval tool: whatever the query, it returns [[Passage]]. */
  private def searchTool(name: String = "search"): ToolFunction[Found] =
    ToolBuilder[Found](
      name,
      "Searches the documents",
      Schema.`object`[Map[String, Any]]("Search parameters").withRequiredField("query", Schema.string("The query"))
    ).withHandler(_ => Right(Found(Passage))).buildSafe().fold(e => fail(s"tool did not build: $e"), identity)

  private def searching(tool: String = "search"): Completion =
    CompletionFixture.withToolCall(tool, ujson.Obj("query" -> "refund window"), callId = "c1")

  private val Grounded =
    """SCORE: 0.95
      |GROUNDED: YES
      |UNGROUNDED_CLAIMS: NONE
      |EXPLANATION: Supported.""".stripMargin

  private val Ungrounded =
    """SCORE: 0.1
      |GROUNDED: NO
      |UNGROUNDED_CLAIMS: 90 days
      |EXPLANATION: The context says 30 days.""".stripMargin

  private val Relevant =
    """OVERALL_SCORE: 0.9
      |CHUNK_SCORES: 0.9
      |EXPLANATION: Relevant.""".stripMargin

  private val Attributed =
    """HAS_ATTRIBUTIONS: YES
      |ATTRIBUTION_SCORE: 0.9
      |CITED_SOURCES: policy.md
      |UNCITED_CLAIMS: NONE
      |EXPLANATION: Cited.""".stripMargin

  private def judge(replies: String*): ScriptedLLMClient =
    ScriptedLLMClient.of(replies.map(CompletionFixture.withUsage(_, prompt = 100, completion = 7))*)

  private val fromSearch: Seq[Message] => Option[RAGContext] =
    turn => Some(RAGContext.fromToolResults(turn, Set("search")))

  private def agentWith(client: ScriptedLLMClient, output: org.llm4s.agent.guardrails.OutputGuardrail*): Agent =
    built(
      Agent
        .builder("rag", client)
        .withTools(ToolRegistry.of(Seq(searchTool(), searchTool("other"))).toOption.get)
        .withMiddleware(new GuardrailMiddleware(Seq.empty, output))
    )

  private def judgePrompt(judge: ScriptedLLMClient): String =
    judge.sent.head.collect { case u: UserMessage => u.content }.mkString

  "RAGGuardrail.withTurnContext" should "give the judge the turn's query and its retrieval tool's result" in {
    val model     = ScriptedLLMClient.of(searching(), CompletionFixture.simple("Refunds within 30 days."))
    val grounding = judge(Grounded)
    val agent     = agentWith(model, RAGGuardrail.withTurnContext(GroundingGuardrail(grounding))(fromSearch))

    val result = agent.run("What is the refund window?").value

    result.status shouldBe AgentStatus.Completed("Refunds within 30 days.")
    grounding.callCount shouldBe 1
    val prompt = judgePrompt(grounding)
    prompt should include("What is the refund window?")
    prompt should include(Passage)
    prompt should include("Refunds within 30 days.")
  }

  it should "block the turn when the answer is not grounded in what the turn retrieved" in {
    val model = ScriptedLLMClient.of(searching(), CompletionFixture.simple("Refunds within 90 days."))
    val agent = agentWith(model, RAGGuardrail.withTurnContext(GroundingGuardrail(judge(Ungrounded)))(fromSearch))

    val result = agent.run("What is the refund window?").value

    result.status match {
      case AgentStatus.Blocked(guardrail, Some(reason)) =>
        guardrail shouldBe "GroundingGuardrail"
        reason should include("not sufficiently grounded")
      case other => fail(s"expected Blocked, got $other")
    }
    result.messages shouldBe empty
  }

  it should "count the judge's call in the thread's usage" in {
    val model     = ScriptedLLMClient.of(searching(), CompletionFixture.simple("Refunds within 30 days."))
    val grounding = judge(Grounded)
    val agent     = agentWith(model, RAGGuardrail.withTurnContext(GroundingGuardrail(grounding))(fromSearch))

    val usage = agent.run("What is the refund window?").value.usage

    usage.requestCount shouldBe 3
    usage.inputTokens shouldBe (15 + 10 + 100)
    usage.outputTokens shouldBe (5 + 20 + 7)
  }

  it should "use only this turn's results, not an earlier turn's" in {
    val model = ScriptedLLMClient.of(
      searching(),
      CompletionFixture.simple("Refunds within 30 days."),
      CompletionFixture.simple("You are welcome.")
    )
    val seen = new java.util.concurrent.CopyOnWriteArrayList[Seq[Message]]()
    val agent = agentWith(
      model,
      RAGGuardrail.withTurnContext(GroundingGuardrail(judge(Grounded))) { turn =>
        seen.add(turn)
        Option.when(turn.exists { case _: ToolMessage => true; case _ => false })(
          RAGContext.fromToolResults(turn, Set("search"))
        )
      }
    )

    val first  = agent.run("What is the refund window?").value
    val second = agent.continueConversation(first, "Thanks").value

    // no retrieval in the second turn: `None`, so the answer passes without a judge call (one was scripted)
    second.status shouldBe AgentStatus.Completed("You are welcome.")
    seen.get(0).head shouldBe UserMessage("What is the refund window?")
    seen.get(0).last shouldBe AssistantMessage("Refunds within 30 days.")
    seen.get(0).count { case _: ToolMessage => true; case _ => false } shouldBe 1
    seen.get(1) shouldBe Seq(UserMessage("Thanks"), AssistantMessage("You are welcome."))
  }

  it should "apply the guardrail's own rule to a turn that retrieved nothing" in {
    val blocking = agentWith(
      ScriptedLLMClient.of(CompletionFixture.simple("Refunds within 30 days.")),
      RAGGuardrail.withTurnContext(GroundingGuardrail(judge()))(fromSearch)
    )
    blocking.run("What is the refund window?").value.status match {
      case AgentStatus.Blocked(_, Some(reason)) => reason should include("no retrieved chunks")
      case other                                => fail(s"expected Blocked, got $other")
    }

    val warning = agentWith(
      ScriptedLLMClient.of(CompletionFixture.simple("Refunds within 30 days.")),
      RAGGuardrail.withTurnContext(GroundingGuardrail(judge(), 0.7, GuardrailAction.Warn))(fromSearch)
    )
    warning.run("What is the refund window?").value.status shouldBe AgentStatus.Completed("Refunds within 30 days.")
  }

  it should "run a preset's RAG guardrails after its output guardrails" in {
    val model = ScriptedLLMClient.of(searching(), CompletionFixture.simple("Refunds within 30 days."))
    // the standard preset: grounding, then context relevance
    val judged = judge(Grounded, Relevant)
    val config = RAGGuardrails.standard(judged)
    val output = config.outputGuardrailsWithContext(fromSearch)

    output.map(_.name) shouldBe Seq("PIIMasker", "GroundingGuardrail", "ContextRelevanceGuardrail")
    agentWith(model, output*).run("What is the refund window?").value.status shouldBe
      AgentStatus.Completed("Refunds within 30 days.")
    judged.callCount shouldBe 2
  }

  it should "check source attribution with the sources the caller's function names" in {
    val model = ScriptedLLMClient.of(searching(), CompletionFixture.simple("Refunds within 30 days [policy.md]."))
    val cites = judge(Attributed)
    val guardrail = RAGGuardrail.withTurnContext(SourceAttributionGuardrail(cites)) { turn =>
      val found = RAGContext.fromToolResults(turn, Set("search"))
      Some(RAGContext.withSources(found.query, found.retrievedChunks, found.retrievedChunks.map(_ => "policy.md")))
    }

    agentWith(model, guardrail).run("What is the refund window?").value.status shouldBe
      AgentStatus.Completed("Refunds within 30 days [policy.md].")
    judgePrompt(cites) should include("policy.md")
  }

  it should "keep the guardrail's name and description, and pass when validated without a hook" in {
    val grounding = GroundingGuardrail(judge())
    val wrapped   = RAGGuardrail.withTurnContext(grounding)(fromSearch)

    wrapped.name shouldBe grounding.name
    wrapped.description shouldBe grounding.description
    wrapped.validate("anything") shouldBe Right("anything")
  }

  it should "fail the check in a graph whose state has no message history" in {
    val wrapped = RAGGuardrail.withTurnContext(GroundingGuardrail(judge()))(fromSearch)

    wrapped.validate("anything", testModelContext()) shouldBe a[Left[GraphError.UnknownStateKey, ?]]
  }

  "RAGContext.fromToolResults" should "take the query from the user message and one chunk per named tool's result" in {
    val turn = Seq(
      UserMessage("q"),
      AssistantMessage(
        None,
        Seq(
          ToolCall(ToolCallId("c1"), "search", ujson.Obj()),
          ToolCall(ToolCallId("c2"), "calculator", ujson.Obj()),
          ToolCall(ToolCallId("c3"), "lookup", ujson.Obj())
        )
      ),
      ToolMessage("first", ToolCallId("c1")),
      ToolMessage("42", ToolCallId("c2")),
      ToolMessage("second", ToolCallId("c3")),
      AssistantMessage("answer")
    )

    val context = RAGContext.fromToolResults(turn, Set("search", "lookup"))

    context.query shouldBe "q"
    context.retrievedChunks shouldBe Seq("first", "second")
    context.sources shouldBe empty
  }

  it should "have no chunks, and an empty query, for a turn without them" in {
    RAGContext.fromToolResults(Seq(AssistantMessage("answer")), Set("search")) shouldBe RAGContext("", Seq.empty)
  }

  /** A RAG guardrail with no model of its own: it records the hook it was given. */
  final private class Recording extends RAGGuardrail {
    var contexts                                = Vector.empty[RAGContext]
    def validate(value: String): Result[String] = Right(value)
    def validateWithContext(output: String, context: RAGContext): Result[String] = {
      contexts = contexts :+ context
      Right(output)
    }
    val name = "Recording"
  }

  "A RAG guardrail that calls no model" should "be validated in a hook as it is outside one" in {
    val recording = new Recording
    val context   = RAGContext("q", Seq("chunk"))

    recording.validateWithContext("out", context, testModelContext()) shouldBe Right("out")
    recording.contexts shouldBe Vector(context)
  }

  "RAGGuardrail.all" should "pass the hook to each guardrail, so every judge call is counted" in {
    val hooks = new java.util.concurrent.CopyOnWriteArrayList[ModelContext]()
    final class Hooked extends RAGGuardrail {
      def validate(value: String): Result[String]                                  = Right(value)
      def validateWithContext(output: String, context: RAGContext): Result[String] = Right(output)
      override def validateWithContext(output: String, context: RAGContext, hook: ModelContext): Result[String] = {
        hooks.add(hook)
        Right(output + "!")
      }
      val name = "Hooked"
    }
    val hook = testModelContext()

    RAGGuardrail.all(Seq(new Hooked, new Hooked)).validateWithContext("out", RAGContext("q", Seq("c")), hook) shouldBe
      Right("out!!")
    hooks.size shouldBe 2
    hooks.forEach(h => h shouldBe theSameInstanceAs(hook))
  }
}
