package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  BoundaryResult,
  GuardrailMiddleware,
  MiddlewareId,
  ModelContext
}
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.builtin.{ JSONValidator, LengthCheck }
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

/**
 * `AgentBuilder.withInputGuardrails` and `withOutputGuardrails` (#2785): the Stable way to attach guardrails. They
 * block as a `GuardrailMiddleware` does, append on every call, run innermost in the agent's stack, and run alongside a
 * `GuardrailMiddleware` given to `withMiddleware`.
 */
class AgentBuilderGuardrailsSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private def answers(texts: String*): ScriptedLLMClient =
    ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  /** A guardrail of both kinds that appends `+tag` to whatever it checks. */
  private def tagging(tag: String): InputGuardrail & OutputGuardrail = new InputGuardrail with OutputGuardrail {
    val name: String                             = s"tag-$tag"
    def validate(value: String): Result[String]  = Right(s"$value+$tag")
    override def transform(text: String): String = text
  }

  private def lastQuery(client: ScriptedLLMClient): String =
    client.sent.last.collect { case u: UserMessage => u.content }.last

  "withInputGuardrails" should "block a query before the model sees it" in {
    val client = answers("never sent")
    val result = built(Agent.builder("assistant", client).withInputGuardrails(LengthCheck(10, 100))).run("Short").value

    result.status shouldBe a[AgentStatus.Blocked]
    result.status.asInstanceOf[AgentStatus.Blocked].guardrail shouldBe "LengthCheck"
    result.messages shouldBe empty
    client.callCount shouldBe 0
  }

  it should "append on a second call, running every guardrail in order" in {
    val client = answers("ok")
    built(Agent.builder("assistant", client).withInputGuardrails(tagging("a")).withInputGuardrails(tagging("b")))
      .run("q")
      .value
    lastQuery(client) shouldBe "q+a+b"
  }

  "withOutputGuardrails" should "block an answer, keeping nothing of the turn" in {
    val client = answers("not json")
    val result = built(Agent.builder("assistant", client).withOutputGuardrails(JSONValidator())).run("Give JSON").value

    result.status shouldBe a[AgentStatus.Blocked]
    result.status.asInstanceOf[AgentStatus.Blocked].guardrail shouldBe "JSONValidator"
    result.messages shouldBe empty
    client.callCount shouldBe 1
  }

  it should "append on a second call, and store the last guardrail's result as the answer" in {
    val result = built(
      Agent.builder("assistant", answers("x")).withOutputGuardrails(tagging("a")).withOutputGuardrails(tagging("b"))
    ).run("q").value
    result.answer shouldBe Some("x+a+b")
  }

  "The builder's guardrails" should "admit the query before any beforeAgent, and check the answer before any afterAgent" in {
    val seen = ArrayBuffer.empty[String]
    val recorder = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("recorder")
      override def beforeAgent(text: String, context: ModelContext): Result[BoundaryResult] =
        seen += s"before:$text"
        Right(BoundaryResult(text))
      override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
        seen += s"after:$answer"
        Right(BoundaryResult(answer))
    }
    val client = answers("x")
    built(
      Agent
        .builder("assistant", client)
        .withInputGuardrails(tagging("in"))
        .withOutputGuardrails(tagging("out"))
        .withMiddleware(recorder)
    ).run("q").value

    // input guardrails run at admission (#2854), so every beforeAgent sees the query they passed on
    seen.toVector shouldBe Vector("before:q+in", "after:x+out")
    lastQuery(client) shouldBe "q+in"
  }

  they should "run alongside a GuardrailMiddleware given to withMiddleware, which runs outside them" in {
    val client = answers("x")
    val result = built(
      Agent
        .builder("assistant", client)
        .withInputGuardrails(tagging("builder"))
        .withOutputGuardrails(tagging("builder"))
        .withMiddleware(new GuardrailMiddleware(Seq(tagging("own")), Seq(tagging("own"))))
    ).run("q").value

    lastQuery(client) shouldBe "q+own+builder"
    result.answer shouldBe Some("x+builder+own")
  }

  they should "install nothing when none is given" in {
    val client                   = answers()
    def version(b: AgentBuilder) = built(b).loop.graph.version
    version(Agent.builder("assistant", client).withInputGuardrails().withOutputGuardrails()) shouldBe
      version(Agent.builder("assistant", client))
    version(Agent.builder("assistant", client).withInputGuardrails(LengthCheck(1, 10))) should not be
      version(Agent.builder("assistant", client))
  }
}
