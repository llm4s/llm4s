package org.llm4s.repl

import org.llm4s.error.AuthenticationError
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ BufferedReader, ByteArrayOutputStream, PrintStream, StringReader }
import java.nio.charset.StandardCharsets

/** `chat()` fed scripted input lines, asserting the whole transcript it prints. */
class ReplChatSpec extends AnyFlatSpec with Matchers {

  private def run(session: Session, lines: String*): String = {
    val buffer = new ByteArrayOutputStream()
    given Session = session
      .withInput(new BufferedReader(new StringReader(lines.mkString("", "\n", "\n"))))
      .withOutput(new PrintStream(buffer, true, "UTF-8"))
    chat()
    buffer.toString(StandardCharsets.UTF_8).replace("\r\n", "\n")
  }

  "chat" should "answer each line, keep the history across lines, and leave on :q" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Hello! How can I help?"), Reply.text("Paris."))
    val transcript = run(
      Session.offline(client),
      "hi",
      "",
      "What is the capital of France?",
      ":history",
      ":q",
      "never read"
    )

    transcript shouldBe
      """Chatting with offline. :q leaves, :history shows the conversation, :reset forgets it.
        |you> llm> Hello! How can I help?
        |you> you> llm> Paris.
        |you> user: hi
        |assistant: Hello! How can I help?
        |user: What is the capital of France?
        |assistant: Paris.
        |you>""".stripMargin + " \n"
    client.calls(1).conversation.messages.map(_.content) shouldBe
      Seq("hi", "Hello! How can I help?", "What is the capital of France?")
  }

  it should "forget the conversation on :reset" in {
    val client     = ScriptedLLMClient.sequence(Reply.text("One."), Reply.text("Two."))
    val transcript = run(Session.offline(client), "first", ":reset", "second", ":history")

    transcript should include("(the conversation is forgotten)")
    transcript should include("user: second\nassistant: Two.")
    (transcript should not).include("user: first")
    client.calls(1).conversation.messages.map(_.content) shouldBe Seq("second")
  }

  it should "print a failed line's error and carry on" in {
    val transcript = run(
      Session.offline(Reply.failure(AuthenticationError("openai", "Invalid API key")), Reply.text("Recovered.")),
      "first",
      "second"
    )

    val lines = transcript.linesIterator.toList
    lines(1) should startWith("you> llm> error: AuthenticationError")
    transcript should include("you> llm> Recovered.")
    (transcript should not).include("\tat ")
  }

  it should "leave at the end of the input" in {
    run(Session.offline()) shouldBe
      """Chatting with offline, 0 scripted replies. :q leaves, :history shows the conversation, :reset forgets it.
        |you> you>""".stripMargin + " \n"
  }
}
