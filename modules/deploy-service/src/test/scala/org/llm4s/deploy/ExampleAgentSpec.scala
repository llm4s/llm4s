package org.llm4s.deploy

import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.llmconnect.model.*
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/** The demo model and its `pause` tool (design 4.60). */
class ExampleAgentSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private val options = CompletionOptions()

  "DemoModel" should "ask for the pause tool on a new turn, then echo the user's message after its result" in {
    val model = new DemoModel(1.second)
    val first = model.complete(Conversation(Seq(SystemMessage("s"), UserMessage("hello"))), options).toOption.get
    first.toolCalls.map(_.name) shouldBe List(ExampleAgent.PauseTool)
    first.content shouldBe ""

    val call   = first.toolCalls.head
    val answer = Conversation(Seq(UserMessage("hello"), first.message, ToolMessage("""{"paused":1000}""", call.id)))
    val second = model.complete(answer, options).toOption.get
    second.toolCalls shouldBe empty
    second.content shouldBe "Echo: hello"
  }

  it should "answer at once with no tool delay, and stream the same completion" in {
    val model  = new DemoModel(Duration.Zero)
    val chunks = new AtomicReference[Vector[StreamedChunk]](Vector.empty)
    val reply  = model.streamComplete(Conversation(Seq(UserMessage("hi"))), options, c => chunks.updateAndGet(_ :+ c))
    reply.map(_.content) shouldBe Right("Echo: hi")
    model.complete(Conversation(Seq.empty), options).map(_.content) shouldBe Right("Echo: ")
    model.getContextWindow() should be > model.getReserveCompletion()
  }

  "the pause tool" should "wait its delay, and end early with an error when interrupted" in {
    val tool = ExampleAgent.pauseTool(10.millis)
    tool.execute(ExampleAgent.NoArgs(), null) match {
      case ToolOutcome.Success(ToolContent.Json(json), _) => json("paused").num shouldBe 10
      case other                                          => fail(s"unexpected $other")
    }

    val slow = ExampleAgent.pauseTool(1.minute)
    Thread.currentThread().interrupt()
    slow.execute(ExampleAgent.NoArgs(), null) shouldBe ToolOutcome.Error("the pause was interrupted")
    Thread.interrupted() shouldBe true
  }

  "client" should "build the demo model, and load the default provider for the provider model" in {
    ExampleAgent.client(AgentServiceConfig.default).map(_.getClass) shouldBe Right(classOf[DemoModel])
    // whether a default provider is configured here or not, the answer is a Result, never a throw
    noException should be thrownBy ExampleAgent.client(AgentServiceConfig.default.copy(model = AgentModel.Provider))
  }
}
