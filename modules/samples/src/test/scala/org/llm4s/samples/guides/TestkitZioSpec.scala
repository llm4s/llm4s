package org.llm4s.samples.guides

import org.llm4s.samples.guides.TestingWithTestkitGuideSpec.temperatureTool
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.zio.LLMClientZ
import zio.{ Exit, ZIO, ZLayer }
import zio.test.*

/**
 * The ZIO Test example of `docs/guide/testing-with-the-testkit.md`: a `ZLayer` that provides a [[ScriptedLLMClient]]
 * and the `LLMClientZ` over it, and fails the test when, at the end, a scripted reply was never asked for.
 *
 * The page's drift test, [[TestingWithTestkitGuideSpec]], fails if a block of the page is not found here or in the
 * other specs it reads. Change the page and this spec together.
 */
object TestkitZioSpec extends ZIOSpecDefault {

  def scripted(replies: Reply*): ZLayer[Any, Nothing, ScriptedLLMClient & LLMClientZ] =
    ZLayer.scoped(
      ZIO.acquireReleaseExit(ZIO.succeed(ScriptedLLMClient.sequence(replies*))) {
        case (client, Exit.Success(_)) =>
          ZIO.fromEither(client.assertExhausted()).orDieWith(unused => new AssertionError(unused))
        case _ => ZIO.unit // the test already failed: keep its own error
      }
    ) >+> ZLayer.fromFunction((client: ScriptedLLMClient) => LLMClientZ(client))

  def spec = suite("ScriptedLLMClient in ZIO Test")(
    test("the layer fails a test that leaves scripted replies unused") {
      for {
        exit <- ZIO.scoped(scripted(Reply.text("It is 21 degrees in Oslo.")).build).exit
      } yield assertTrue(
        exit.causeOption
          .flatMap(_.dieOption)
          .exists(_.getMessage.contains("""reply 1: text "It is 21 degrees in Oslo.""""))
      )
    },
    test("the packing assistant calls the tool, then answers") {
      for {
        client <- ZIO.service[ScriptedLLMClient]
        tool   <- ZIO.fromEither(temperatureTool)
        agent  <- ZIO.serviceWithZIO[LLMClientZ](_.agent("packing-assistant")(_.withTools(new ToolRegistry(Seq(tool)))))
        run    <- agent.run("What should I pack for Oslo today?")
      } yield assertTrue(
        run.answer.contains("It is 21 degrees in Oslo: pack light layers."),
        client.calls.head.options.tools.map(_.name) == Seq("get_temperature")
      )
    }.provide(
      scripted(
        Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
        Reply.text("It is 21 degrees in Oslo: pack light layers.")
      )
    )
  )
}
