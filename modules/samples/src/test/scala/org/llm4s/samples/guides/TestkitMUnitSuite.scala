package org.llm4s.samples.guides

import munit.{ FailException, FunSuite, TestOptions }
import org.llm4s.samples.guides.TestingWithTestkitGuideSpec.packingAdvice
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

import java.util.concurrent.ExecutionException
import scala.concurrent.{ ExecutionContext, Future }

/**
 * The MUnit example of `docs/guide/testing-with-the-testkit.md`: a `FunFixture` that hands each test a fresh
 * [[ScriptedLLMClient]] and fails the test when a scripted reply was never asked for.
 *
 * The page's drift test, [[TestingWithTestkitGuideSpec]], fails if a block of the page is not found here or in the
 * other specs it reads. Change the page and this suite together.
 */
class TestkitMUnitSuite extends FunSuite {

  def scripted(replies: Reply*): FunFixture[ScriptedLLMClient] =
    FunFixture(
      setup = _ => ScriptedLLMClient.sequence(replies*),
      teardown = client => client.assertExhausted().left.foreach(unused => fail(unused))
    )

  scripted(
    Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
    Reply.text("It is 21 degrees in Oslo: pack light layers.")
  ).test("the packing assistant calls the tool, then answers") { client =>
    assertEquals(packingAdvice(client, "Oslo"), Right("It is 21 degrees in Oslo: pack light layers."))
    assertEquals(client.calls.head.options.tools.map(_.name), Seq("get_temperature"))
  }

  test("the fixture fails a test that leaves scripted replies unused") {
    given ExecutionContext = munitExecutionContext
    val fixture            = scripted(Reply.text("It is 21 degrees in Oslo."))

    for {
      client <- fixture.setup(TestOptions("leaves a reply unused"))
      boxed  <- Future.unit.flatMap(_ => fixture.teardown(client)).failed
    } yield {
      // A `Future` boxes an `Error` such as munit's `FailException` in an `ExecutionException`; munit unboxes it.
      val failure = if (boxed.isInstanceOf[ExecutionException]) boxed.getCause else boxed
      assert(failure.isInstanceOf[FailException], failure)
      assert(failure.getMessage.contains("1 of 1 scripted replies was not used"), failure.getMessage)
      assert(failure.getMessage.contains("""reply 1: text "It is 21 degrees in Oslo.""""), failure.getMessage)
    }
  }
}
