package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  MiddlewareId,
  ModelContext,
  ModelRequest,
  ModelResponse,
  PromptSection
}
import org.llm4s.llmconnect.model.{ Message, SystemMessage, UserMessage }
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference

/**
 * A model wrapper is given the system prompt as a field of its own (#2795, item 8): `ModelRequest.systemPrompt` is the
 * assembled prompt, and `ModelRequest.messages` is the history alone, so a wrapper that rewrites or prunes the
 * messages cannot drop the prompt by accident.
 */
class ModelRequestSystemPromptSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private def answers(texts: String*): ScriptedLLMClient =
    ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  /** Applies `rewrite` to every model request, remembering the request it was given. */
  private class Rewriting(rewrite: ModelRequest => ModelRequest) extends AgentMiddleware:
    val id: MiddlewareId = MiddlewareId("rewriting")
    val seen             = new AtomicReference[Option[ModelRequest]](None)
    override def wrapModelCall(request: ModelRequest, context: ModelContext)(
      next: ModelRequest => Result[ModelResponse]
    ): Result[ModelResponse] =
      seen.set(Some(request))
      next(rewrite(request))

  private val styled = new AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("house-style")
    override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
      Right(Vector(PromptSection("style", "Answer in one sentence.")))
  }

  private def systemOf(messages: Vector[Message]): Vector[String] =
    messages.collect { case s: SystemMessage => s.content }

  "ModelRequest" should "carry the assembled system prompt apart from the messages" in {
    val client  = answers("ok")
    val wrapper = new Rewriting(identity)
    built(Agent.builder("assistant", client).withSystemPrompt("You are helpful.").withMiddleware(styled, wrapper))
      .run("hello")
      .value

    val request = wrapper.seen.get.get
    request.systemPrompt shouldBe Some("You are helpful.\n\nAnswer in one sentence.")
    request.messages shouldBe Vector(UserMessage("hello"))
    request.sentMessages shouldBe Vector(
      SystemMessage("You are helpful.\n\nAnswer in one sentence."),
      UserMessage("hello")
    )
    client.sent.head shouldBe request.sentMessages
  }

  it should "have no system prompt when the agent has none and no middleware adds a section" in {
    val client  = answers("ok")
    val wrapper = new Rewriting(identity)
    built(Agent.builder("assistant", client).withMiddleware(wrapper)).run("hello").value

    wrapper.seen.get.get.systemPrompt shouldBe None
    systemOf(client.sent.head) shouldBe empty
  }

  it should "keep the system prompt when a wrapper replaces every message" in {
    val client  = answers("ok")
    val wrapper = new Rewriting(_.withMessages(Vector(UserMessage("rewritten"))))
    built(Agent.builder("assistant", client).withSystemPrompt("You are helpful.").withMiddleware(wrapper))
      .run("hello")
      .value

    client.sent.head shouldBe Vector(SystemMessage("You are helpful."), UserMessage("rewritten"))
  }

  it should "send the system prompt a wrapper sets, and none when a wrapper removes it" in {
    val changed = answers("ok")
    built(
      Agent
        .builder("assistant", changed)
        .withSystemPrompt("You are helpful.")
        .withMiddleware(new Rewriting(r => r.withSystemPrompt(r.systemPrompt.fold("Be brief.")(_ + " Be brief."))))
    ).run("hello").value
    systemOf(changed.sent.head) shouldBe Vector("You are helpful. Be brief.")

    val removed = answers("ok")
    built(
      Agent
        .builder("assistant", removed)
        .withSystemPrompt("You are helpful.")
        .withMiddleware(new Rewriting(_.withSystemPrompt(None)))
    ).run("hello").value
    systemOf(removed.sent.head) shouldBe empty
  }

  it should "send no system message for a blank prompt" in {
    ModelRequest(Vector(UserMessage("q"))).withSystemPrompt("  ").sentMessages shouldBe Vector(UserMessage("q"))
    ModelRequest(Vector(UserMessage("q"))).systemPrompt shouldBe None
    ModelRequest(Vector(UserMessage("q")), systemPrompt = Some("p")).sentMessages.head shouldBe SystemMessage("p")
  }
}
