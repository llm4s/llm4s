package org.llm4s.agent

import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AgentIdSpec extends AnyFlatSpec with Matchers with EitherValues {

  "AgentId.of" should "accept letters, digits, '_' and '-', up to 52 characters" in {
    AgentId.of("triage").value.value shouldBe "triage"
    AgentId.of("Agent_2-b").value.value shouldBe "Agent_2-b"
    AgentId.of("a" * 52).value.value shouldBe "a" * 52
  }

  it should "refuse an empty id, other characters and an id longer than 52 characters" in {
    Seq("", "has space", "slash/ed", "dot.ted", "ünï", "a" * 53).foreach { bad =>
      val refused = AgentId.of(bad).left.value
      withClue(bad) {
        refused shouldBe a[ValidationError]
        refused.message should include("agent.id")
      }
    }
  }

  it should "round-trip through JSON as a plain string" in {
    val id = AgentId.of("billing").value
    upickle.default.write(id) shouldBe "\"billing\""
    upickle.default.read[AgentId](upickle.default.write(id)) shouldBe id
  }
}
