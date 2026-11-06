package org.llm4s.ox

import org.llm4s.error.{ CancelledError, SimpleError, ValidationError }
import org.llm4s.llmconnect.model.{ Completion, Conversation, UserMessage }
import org.llm4s.ox.Clients.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalatest.concurrent.{ Signaler, ThreadSignaler, TimeLimitedTests }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Minutes, Span }

import java.util.concurrent.TimeUnit

/** `parComplete` and `raceProviders`. */
class FanOutSpec extends AnyFlatSpec with Matchers with TimeLimitedTests {

  override def timeLimit: Span               = Span(2, Minutes)
  override val defaultTestSignaler: Signaler = ThreadSignaler

  private def ask(text: String): Conversation = Conversation(Seq(UserMessage(text)))

  private def echoOf(conversation: Conversation): String =
    conversation.messages.lastOption.map(_.content).getOrElse("")

  private val echo: ScriptedLLMClient =
    ScriptedLLMClient.respondingTo { case c => Reply.text(s"re: ${echoOf(c)}") }

  private def completed(content: String): Result[Completion] =
    ScriptedLLMClient.always(Reply.text(content)).complete(conversation)

  "parComplete" should "return one result per conversation, in their order" in {
    val questions = (1 to 8).map(i => ask(s"q$i"))
    echo.parComplete(questions, parallelism = 3).map(_.map(_.content)) shouldBe
      (1 to 8).map(i => Right(s"re: q$i")).toVector
  }

  it should "run at most `parallelism` calls at once" in {
    val timed = new Timed(c => completed(echoOf(c)), delayMillis = 50)
    timed.parComplete((1 to 8).map(i => ask(s"q$i")), parallelism = 2).forall(_.isRight) shouldBe true
    timed.maxSeen.get() shouldBe 2
  }

  it should "treat a parallelism below 1 as 1" in {
    val timed = new Timed(c => completed(echoOf(c)), delayMillis = 10)
    timed.parComplete((1 to 4).map(i => ask(s"q$i")), parallelism = 0).size shouldBe 4
    timed.maxSeen.get() shouldBe 1
  }

  it should "keep the other results when one request fails" in {
    val error = SimpleError("refused")
    val client = ScriptedLLMClient.respondingTo {
      case c if echoOf(c) == "bad" => Reply.failure(error)
      case c                       => Reply.text(echoOf(c))
    }
    client.parComplete(Seq(ask("a"), ask("bad"), ask("c")), parallelism = 3).map(_.map(_.content)) shouldBe
      Vector(Right("a"), Left(error), Right("c"))
  }

  it should "return nothing for no conversations" in {
    echo.parComplete(Nil, parallelism = 4) shouldBe empty
  }

  "raceProviders" should "return the first success and interrupt the loser" in {
    val loser  = new Recording(new Parking())
    val winner = new Timed(_ => completed("fast"), delayMillis = 0)
    val raced  = raceProviders(loser, winner)(conversation)
    raced.map(_.content) shouldBe Right("fast")
    withClue("the losing call did not return after the race:") {
      loser.ended.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    }
    loser.outcome.get() match {
      case Some(Left(_: CancelledError)) => succeed
      case other                         => fail(s"the losing call was not cancelled: $other")
    }
    loser.thread.get().exists(_.isAlive) shouldBe false
  }

  it should "return a success over an earlier failure" in {
    val failing = ScriptedLLMClient.always(Reply.failure(SimpleError("down")))
    val slow    = new Timed(_ => completed("slow but fine"), delayMillis = 100)
    raceProviders(failing, slow)(conversation).map(_.content) shouldBe Right("slow but fine")
  }

  it should "return the first failure when every provider fails" in {
    val first  = SimpleError("first")
    val second = SimpleError("second")
    val early  = ScriptedLLMClient.always(Reply.failure(first))
    val late   = new Timed(_ => Left(second), delayMillis = 200)
    raceProviders(late, early)(conversation) shouldBe Left(first)
  }

  it should "refuse a race with no providers" in {
    raceProviders()(conversation) match {
      case Left(_: ValidationError) => succeed
      case other                    => fail(s"expected a ValidationError, got $other")
    }
  }
}
