package org.llm4s.prompt

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ Conversation, SystemMessage, ToolMessage, UserMessage }
import org.llm4s.prompt.Fixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PromptSpec extends AnyFlatSpec with Matchers {

  private val ticket = Ticket("T-42", "ACME", Priority.High)

  "prompt\"...\"" should "render each value by its Promptable" in {
    val count = 3
    prompt"Classify ticket ${ticket.id} (priority ${ticket.priority}), $count lines".text shouldBe
      "Classify ticket T-42 (priority High), 3 lines"
    prompt"Ticket: $ticket".text shouldBe "Ticket: Ticket(id: \"T-42\", customer: \"ACME\", priority: High)"
  }

  it should "be a user message, and system\"...\" a system message" in {
    prompt"Hello".toMessage shouldBe Right(UserMessage("Hello"))
    system"Be brief".toMessage shouldBe Right(SystemMessage("Be brief"))
  }

  it should "process escapes as s\"...\" does" in {
    prompt"a\tb\nc".text shouldBe "a\tb\nc"
  }

  it should "accept a template without values, and values at its edges" in {
    prompt"".text shouldBe ""
    val x = 1
    prompt"$x".text shouldBe "1"
    prompt"$x and $x".text shouldBe "1 and 1"
  }

  "++" should "join adjacent parts of the same role into one message, by a blank line" in {
    val p = system"You triage tickets." ++ system"Answer in one sentence." ++ prompt"Ticket ${ticket.id}" ++
      prompt"Is it urgent?"
    p.messages shouldBe Vector(
      SystemMessage("You triage tickets.\n\nAnswer in one sentence."),
      UserMessage("Ticket T-42\n\nIs it urgent?")
    )
    p.toConversation shouldBe Conversation.create(
      SystemMessage("You triage tickets.\n\nAnswer in one sentence."),
      UserMessage("Ticket T-42\n\nIs it urgent?")
    )
    p.text shouldBe "You triage tickets.\n\nAnswer in one sentence.\n\nTicket T-42\n\nIs it urgent?"
    p.toString shouldBe p.text
  }

  it should "keep alternating roles as separate messages" in {
    (prompt"a" ++ system"b" ++ prompt"c").messages.map(_.role.name) shouldBe Vector("user", "system", "user")
  }

  "toMessage" should "refuse an empty prompt, a blank one and one of several messages" in {
    (system"a" ++ prompt"b").toMessage match {
      case Left(e: ValidationError) => e.message should include("2 messages")
      case other                    => fail(s"expected a ValidationError, got $other")
    }
    prompt"   ".toMessage.isLeft shouldBe true
    Prompt.empty.toMessage match {
      case Left(e: ValidationError) => e.message should include("empty")
      case other                    => fail(s"expected a ValidationError, got $other")
    }
  }

  "toConversation" should "refuse a blank message" in {
    (system"You triage." ++ prompt"  ").toConversation.isLeft shouldBe true
  }

  "Untrusted values" should "be fenced and numbered across ++, with the notice once per message" in {
    val email = Untrusted.text("Hi, refund me.")
    val doc   = Untrusted("The <b>terms</b> & conditions")
    val p     = system"Triage." ++ prompt"Email: $email" ++ prompt"Terms: $doc"
    p.messages shouldBe Vector(
      SystemMessage("Triage."),
      UserMessage(
        "Email: <untrusted id=\"u1\">\nHi, refund me.\n</untrusted>\n\n" +
          "Terms: <untrusted id=\"u2\">\nThe &lt;b&gt;terms&lt;/b&gt; &amp; conditions\n</untrusted>\n\n" +
          Prompt.UntrustedNotice
      )
    )
  }

  it should "render a value of any Promptable type, then fence it" in {
    prompt"${Untrusted(ticket)}".text shouldBe
      "<untrusted id=\"u1\">\nTicket(id: \"T-42\", customer: \"ACME\", priority: High)\n</untrusted>\n\n" +
      Prompt.UntrustedNotice
    Untrusted(ticket).value shouldBe ticket
  }

  it should "fence a tool's result" in {
    val result = Untrusted.toolResult(ToolMessage("""{"weather":"<sunny>"}""", "call-1"))
    prompt"Tool said: $result".text shouldBe
      "Tool said: <untrusted id=\"u1\">\n{\"weather\":\"&lt;sunny&gt;\"}\n</untrusted>\n\n" + Prompt.UntrustedNotice
  }

  it should "add no notice to a message without a fence" in {
    val p = system"Rules." ++ prompt"Data: ${Untrusted.text("x")}"
    p.messages.head shouldBe SystemMessage("Rules.")
  }

  "stripMargin" should "strip the template's margin but never a value's" in {
    val table = "| a | b |\n  | 1 | 2 |"
    val p = prompt"""Classify:
                    |  $table
                    |done""".stripMargin
    p.text shouldBe "Classify:\n  | a | b |\n  | 1 | 2 |\ndone"
  }

  it should "strip the first line, and lines in parts after ++" in {
    (prompt"  |one" ++ system"""two
                               |three""").stripMargin.messages shouldBe Vector(
      UserMessage("one"),
      SystemMessage("two\nthree")
    )
  }

  it should "leave a line that does not start with a margin, and a literal mid-line after a value" in {
    val v = "x"
    prompt"""a
            no margin $v| kept""".stripMargin.text shouldBe "a\n            no margin x| kept"
  }

  "Prompt equality" should "be by content" in {
    prompt"a ${1}" shouldBe prompt"a ${1}"
    prompt"a ${1}".hashCode shouldBe prompt"a ${1}".hashCode
    prompt"a" should not be system"a"
    (prompt"a" should not).equal("a")
  }

  "Prompt.empty" should "be the identity of ++" in {
    (Prompt.empty ++ prompt"a") shouldBe prompt"a"
    (prompt"a" ++ Prompt.empty) shouldBe prompt"a"
    Prompt.empty.messages shouldBe empty
    Prompt.empty.toConversation shouldBe Right(Conversation.empty())
  }

  "Prompt.text" should "not show roles" in {
    (system"a" ++ prompt"b").text shouldBe "a\n\nb"
  }
}
