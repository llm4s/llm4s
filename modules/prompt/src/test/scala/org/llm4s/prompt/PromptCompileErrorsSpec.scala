package org.llm4s.prompt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.Error

/** What does not compile, and the message that says why. */
class PromptCompileErrorsSpec extends AnyFlatSpec with Matchers {

  private def messages(errors: List[Error]): String = errors.map(_.message).mkString("\n")

  "prompt\"...\"" should "refuse a value whose type has no Promptable, naming the type" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("""prompt"Summarise ${new Fixtures.Opaque}""""))
    errors should include("No Promptable[org.llm4s.prompt.Fixtures.Opaque]")
    errors should include("derives Promptable")
  }

  it should "refuse an LLMClient" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        """val client: org.llm4s.llmconnect.LLMClient = ???; prompt"Summarise $client""""
      )
    ) should include("No Promptable[org.llm4s.llmconnect.LLMClient]")
  }

  it should "refuse an Untrusted value whose content has no Promptable, naming the content's type" in {
    messages(scala.compiletime.testing.typeCheckErrors("""prompt"${Untrusted(new Fixtures.Opaque)}"""")) should include(
      "No Promptable[org.llm4s.prompt.Fixtures.Opaque]"
    )
  }

  it should "refuse a case class that does not derive Promptable" in {
    messages(
      scala.compiletime.testing.typeCheckErrors("""prompt"${Fixtures.HoldsOpaque(new Fixtures.Opaque)}"""")
    ) should include(
      "No Promptable[org.llm4s.prompt.Fixtures.HoldsOpaque]"
    )
  }

  it should "refuse an invalid escape" in {
    messages(scala.compiletime.testing.typeCheckErrors("""prompt"bad \q escape"""")) should include("Invalid escape")
  }

  it should "refuse system\"...\" alike" in {
    messages(scala.compiletime.testing.typeCheckErrors("""system"${new Fixtures.Opaque}"""")) should include(
      "No Promptable"
    )
  }

  "derives Promptable" should "refuse a field whose type has no Promptable, naming it" in {
    messages(scala.compiletime.testing.typeCheckErrors("Promptable.derived[Fixtures.HoldsOpaque]")) should include(
      "No Promptable[org.llm4s.prompt.Fixtures.Opaque]"
    )
  }

  "A PromptTemplate" should "refuse a placeholder that names no parameter, naming it" in {
    val errors = messages(
      scala.compiletime.testing.typeCheckErrors(
        """PromptTemplate[TemplateFixtures.Triage](p => prompt"Ticket ${p.tikcet} says ${p.email}")"""
      )
    )
    errors should include("tikcet")
    errors should include("is not a member of")
  }

  it should "compile when every placeholder names a parameter" in {
    scala.compiletime.testing.typeCheckErrors(
      """PromptTemplate[TemplateFixtures.Triage](p => prompt"Ticket ${p.ticket} says ${p.email}")"""
    ) shouldBe Nil
  }
}
