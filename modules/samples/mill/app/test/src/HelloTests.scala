package hello

import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

// ScriptedLLMClient (llm4s-testkit) answers from a script: no model, no network, no API key.
class HelloTests extends munit.FunSuite {

  test("ask returns the model's answer") {
    val client = ScriptedLLMClient.always(Reply.text("Scala is a JVM language."))

    assertEquals(Hello.ask(client, "What is Scala?"), Right("Scala is a JVM language."))
  }

  test("ask sends the question as the user's message") {
    val client = ScriptedLLMClient.always(Reply.text("ok"))

    Hello.ask(client, "What is Scala?")

    assertEquals(client.callCount, 1)
    assertEquals(client.lastCall.flatMap(_.lastUserText), Some("What is Scala?"))
  }

  test("ask returns an error rather than throwing") {
    val client = ScriptedLLMClient.sequence() // no reply scripted: the call fails

    assert(Hello.ask(client, "What is Scala?").isLeft)
  }
}
