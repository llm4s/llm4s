package org.llm4s.samples.guides

/**
 * The programs of `docs/guide/effect-systems.md`: one program, a streamed completion and then a one-tool agent run,
 * with an error branch and a timeout, in plain `Result`, cats-effect, ZIO and Pekko. Each `// snippet:` region is a
 * Scala block of the page, word for word; [[EffectSystemsGuideSpec]] runs the four programs and compares the regions
 * with the page.
 *
 * Each program is in an object of its own, so that its imports are the ones the page shows for it (cats-effect's and
 * Pekko's `LLMException` are different classes). The shared values are this object's, as the page's first block.
 */
object EffectSystemsGuide {

  // snippet: setup
  import org.llm4s.agent.Agent
  import org.llm4s.llmconnect.LLMClient
  import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
  import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
  import org.llm4s.types.Result
  import scala.concurrent.duration.FiniteDuration

  val greeting: Conversation = Conversation(Seq(UserMessage("Say hello in five words.")))
  val question: String       = "What should I pack for Oslo?"

  val temperature: Result[ToolFunction[Map[String, Any], Double]] =
    ToolBuilder[Map[String, Any], Double](
      "get_temperature",
      "Current temperature in a city, in Celsius.",
      Schema.`object`[Map[String, Any]]("Parameters").withProperty(Schema.property("city", Schema.string("City name")))
    ).withHandler(params => params.getString("city").map(_ => 21.0)).buildSafe()
  // end snippet

  object Plain {
    // snippet: plain
    import java.util.concurrent.{ FutureTask, TimeUnit }
    import scala.util.Try

    /** `body` on a virtual thread; `None` once `timeout` passes, and the thread is interrupted, cancelling the call. */
    def withinTime[A](timeout: FiniteDuration)(body: => A): Option[A] = {
      val call = new FutureTask[A](() => body)
      Thread.startVirtualThread(call)
      val result = Try(call.get(timeout.toNanos, TimeUnit.NANOSECONDS)).toOption
      if (result.isEmpty) call.cancel(true)
      result
    }

    def plainProgram(client: LLMClient, timeout: FiniteDuration): String = {
      def program: Result[String] = {
        val reply = new StringBuilder
        for {
          _      <- client.streamComplete(greeting, CompletionOptions(), _.content.foreach(reply.append))
          tool   <- temperature
          agent  <- Agent.builder("assistant", client).withTools(new ToolRegistry(Seq(tool))).build()
          result <- agent.run(question)
        } yield s"$reply | ${result.answer.getOrElse("")}"
      }
      withinTime(timeout)(program).fold("timed out")(_.fold(error => s"failed: ${error.message}", identity))
    }
    // end snippet
  }

  object CatsEffect {
    // snippet: cats-effect
    import cats.effect.IO
    import org.llm4s.effect.cats.{ LLMClientIO, LLMException }

    def catsProgram(client: LLMClient, timeout: FiniteDuration): IO[String] = {
      val llm = LLMClientIO[IO](client)
      val program = for {
        reply  <- llm.streamComplete(greeting).map(_.content.getOrElse("")).compile.string
        tool   <- IO.fromEither(temperature.left.map(new LLMException(_)))
        agent  <- llm.agent("assistant")(_.withTools(new ToolRegistry(Seq(tool))))
        result <- agent.run(question)
      } yield s"$reply | ${result.answer.getOrElse("")}"
      program.timeoutTo(timeout, IO.pure("timed out")).recover { case e: LLMException => s"failed: ${e.error.message}" }
    }
    // end snippet
  }

  object Zio {
    // snippet: zio
    import org.llm4s.zio.LLMClientZ
    import zio.{ Duration, UIO, ZIO }

    def zioProgram(client: LLMClient, timeout: FiniteDuration): UIO[String] = {
      val llm = LLMClientZ(client)
      val program = for {
        reply  <- llm.streamComplete(greeting).map(_.content.getOrElse("")).runFold("")(_ + _)
        tool   <- ZIO.fromEither(temperature)
        agent  <- llm.agent("assistant")(_.withTools(new ToolRegistry(Seq(tool))))
        result <- agent.run(question)
      } yield s"$reply | ${result.answer.getOrElse("")}"
      program.timeout(Duration.fromScala(timeout)).fold(e => s"failed: ${e.message}", _.getOrElse("timed out"))
    }
    // end snippet
  }

  object Pekko {
    // snippet: pekko
    import java.util.concurrent.TimeoutException
    import org.apache.pekko.actor.ActorSystem
    import org.apache.pekko.stream.scaladsl.Sink
    import org.llm4s.agent.graph.ThreadId
    import org.llm4s.pekko.{ AgentStreamItem, LLMClientPekko, LLMException }
    import scala.concurrent.Future

    def pekkoProgram(client: LLMClient, timeout: FiniteDuration)(using system: ActorSystem): Future[String] = {
      import system.dispatcher
      val llm = LLMClientPekko(client)
      temperature.flatMap(tool => llm.agent("assistant")(_.withTools(new ToolRegistry(Seq(tool))))) match {
        case Left(error) => Future.successful(s"failed: ${error.message}")
        case Right(agent) =>
          val reply = llm.streamComplete(greeting).map(_.content.getOrElse("")).fold("")(_ + _)
          val answer = agent.stream(ThreadId("pick-your-stack"), question).collect {
            case AgentStreamItem.Done(result) => result.answer.getOrElse("")
          }
          reply
            .concatLazy(answer)
            .completionTimeout(timeout)
            .runWith(Sink.seq)
            .map(_.mkString(" | "))
            .recover {
              case _: TimeoutException => "timed out"
              case e: LLMException     => s"failed: ${e.error.message}"
            }
      }
    }
    // end snippet
  }
}
