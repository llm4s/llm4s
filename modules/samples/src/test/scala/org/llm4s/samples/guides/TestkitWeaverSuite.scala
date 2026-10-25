package org.llm4s.samples.guides

import cats.effect.{ IO, Resource }
import org.llm4s.effect.cats.{ LLMClientIO, LLMException }
import org.llm4s.samples.guides.TestingWithTestkitGuideSpec.temperatureTool
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.ToolRegistry
import weaver.SimpleIOSuite

/**
 * The weaver example of `docs/guide/testing-with-the-testkit.md`: each test uses its own scripted `Resource`, whose
 * release fails when a scripted reply was never asked for, and builds the agent through `LLMClientIO`.
 *
 * The page's drift test, [[TestingWithTestkitGuideSpec]], fails if a block of the page is not found here or in the
 * other specs it reads. Change the page and this suite together.
 */
object TestkitWeaverSuite extends SimpleIOSuite {

  def scripted(replies: Reply*): Resource[IO, ScriptedLLMClient] =
    Resource.make(IO(ScriptedLLMClient.sequence(replies*)))(client =>
      // `defer`: check when the release runs, after the test, not when the release is built
      IO.defer(IO.fromEither(client.assertExhausted().left.map(unused => new AssertionError(unused))))
    )

  test("the packing assistant calls the tool, then answers") {
    scripted(
      Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
      Reply.text("It is 21 degrees in Oslo: pack light layers.")
    ).use { client =>
      for {
        tool  <- IO.fromEither(temperatureTool.left.map(new LLMException(_)))
        agent <- LLMClientIO[IO](client).agent("packing-assistant")(_.withTools(new ToolRegistry(Seq(tool))))
        run   <- agent.run("What should I pack for Oslo today?")
      } yield expect.all(
        run.answer.contains("It is 21 degrees in Oslo: pack light layers."),
        client.calls.head.options.tools.map(_.name) == Seq("get_temperature")
      )
    }
  }

  test("the resource fails a test that leaves scripted replies unused") {
    scripted(Reply.text("It is 21 degrees in Oslo.")).use(_ => IO.unit).attempt.map { outcome =>
      expect(outcome.left.exists(_.getMessage.contains("""reply 1: text "It is 21 degrees in Oslo."""")))
    }
  }
}
