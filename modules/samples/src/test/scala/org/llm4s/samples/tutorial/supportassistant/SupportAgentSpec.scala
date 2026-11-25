package org.llm4s.samples.tutorial.supportassistant

// The test that part 6 of the support-assistant tutorial (`docs/tutorials/support-assistant/part-6-testing.md`)
// shows: the region below is a Scala block of the page, word for word, and `Part6Spec` compares the two.

// snippet: spec
import org.llm4s.agent.graph.GraphError
import org.llm4s.error.RateLimitError
import org.llm4s.samples.tutorial.supportassistant.Part5SafetyMemory.safeAgent
import org.llm4s.samples.tutorial.supportassistant.Part6Testing.fakeModel
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SupportAgentSpec extends AnyFlatSpec with Matchers {

  "The support agent" should "look the order up before it answers" in {
    val model = fakeModel()

    val result = safeAgent(model).flatMap(_.run("Where is my order A-1002?"))

    result.map(_.answer) shouldBe Right(Some("Your order is processing."))
    model.callCount shouldBe 2
    model.calls.head.options.tools.map(_.name) should contain("lookup_order")
  }

  it should "never send the customer's email address to the model" in {
    val model = fakeModel()

    safeAgent(model).flatMap(_.run("I'm jane@example.com. Where is A-1001?")).isRight shouldBe true

    (every(model.calls.flatMap(_.lastUserText)) should not).include("jane@example.com")
  }

  it should "fail, not answer, when the provider is down" in {
    val model = ScriptedLLMClient.sequence(Reply.failure(RateLimitError(ProviderId("openai"))))

    val result = safeAgent(model).flatMap(_.run("Where is my order A-1001?"))

    result.left.toOption should matchPattern { case Some(GraphError.NodeFailed(_, _, _: RateLimitError)) => }
  }
}
// end snippet
