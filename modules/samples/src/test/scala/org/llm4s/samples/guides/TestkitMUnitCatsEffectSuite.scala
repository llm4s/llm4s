package org.llm4s.samples.guides

import cats.effect.{ IO, Resource }
import munit.CatsEffectSuite
import org.llm4s.effect.cats.{ LLMClientIO, LLMException }
import org.llm4s.samples.guides.TestingWithTestkitGuideSpec.temperatureTool
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.ToolRegistry

/**
 * The munit-cats-effect example of `docs/guide/testing-with-the-testkit.md`: a `Resource` that releases a
 * [[ScriptedLLMClient]] by failing when a scripted reply was never asked for, used as a `ResourceFunFixture`, and an
 * agent built through `LLMClientIO`.
 *
 * The page's drift test, [[TestingWithTestkitGuideSpec]], fails if a block of the page is not found here or in the
 * other specs it reads. Change the page and this suite together.
 */
class TestkitMUnitCatsEffectSuite extends CatsEffectSuite {

  def scripted(replies: Reply*): Resource[IO, ScriptedLLMClient] =
    Resource.make(IO(ScriptedLLMClient.sequence(replies*)))(client =>
      // `defer`: check when the release runs, after the test, not when the release is built
      IO.defer(IO.fromEither(client.assertExhausted().left.map(unused => new AssertionError(unused))))
    )

  val packing = ResourceFunFixture(
    scripted(
      Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
      Reply.text("It is 21 degrees in Oslo: pack light layers.")
    )
  )

  packing.test("the packing assistant calls the tool, then answers") { client =>
    for {
      tool  <- IO.fromEither(temperatureTool.left.map(new LLMException(_)))
      agent <- LLMClientIO[IO](client).agent("packing-assistant")(_.withTools(new ToolRegistry(Seq(tool))))
      run   <- agent.run("What should I pack for Oslo today?")
    } yield {
      assertEquals(run.answer, Some("It is 21 degrees in Oslo: pack light layers."))
      assertEquals(client.calls.head.options.tools.map(_.name), Seq("get_temperature"))
    }
  }

  test("the resource fails a test that leaves scripted replies unused") {
    scripted(Reply.text("It is 21 degrees in Oslo.")).use(_ => IO.unit).attempt.map { outcome =>
      val message = outcome.left.toOption.map(_.getMessage).getOrElse("")
      assert(outcome.isLeft, "the resource was released without an error")
      assert(message.contains("""reply 1: text "It is 21 degrees in Oslo.""""), message)
    }
  }
}
