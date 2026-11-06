package org.llm4s.prompt

// The build compiles with -source:3.3, which turns off named tuple values and field access by name; Scala 3.7's
// default source level, which users have, has them.
import scala.language.`3.7`

import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.prompt.Fixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object TemplateFixtures {
  final case class Triage(ticket: Ticket, email: Untrusted[String])
}

class PromptTemplateSpec extends AnyFlatSpec with Matchers {

  private val ticket = Ticket("T-42", "ACME", Priority.High)

  "A PromptTemplate" should "render a case class of parameters" in {
    val triage = PromptTemplate[TemplateFixtures.Triage](p => prompt"Ticket ${p.ticket.id} says: ${p.email}")
    triage(TemplateFixtures.Triage(ticket, Untrusted.text("refund"))).messages shouldBe Vector(
      UserMessage(
        "Ticket T-42 says: <untrusted id=\"u1\">\nrefund\n</untrusted>\n\n" + Prompt.UntrustedNotice
      )
    )
  }

  it should "render a named tuple of parameters" in {
    val triage = PromptTemplate[(ticket: Ticket, email: Untrusted[String])] { p =>
      prompt"Priority ${p.ticket.priority}: ${p.email}"
    }
    triage((ticket = ticket, email = Untrusted.text("hi"))).text should startWith(
      "Priority High: <untrusted id=\"u1\">\nhi\n</untrusted>"
    )
  }

  it should "be reusable" in {
    val greet = PromptTemplate[String](name => prompt"Hello $name")
    greet("Ann").text shouldBe "Hello Ann"
    greet("Bo").text shouldBe "Hello Bo"
  }
}
