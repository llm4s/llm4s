package org.llm4s.samples.cookbook

import org.llm4s.agent.graph.{ GraphError, GraphRuntime, RunResult }
import org.llm4s.error.{ CancelledError, ExecutionError }
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, MessageRole, SystemMessage }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }

/** Runs the multi-agent graph recipe against scripted clients, with no network and no key. */
class MultiAgentGraphRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {
  import MultiAgentGraphRecipe.{ EditorPrompt, OptimistPrompt, SkepticPrompt, question }

  private def systemPrompt(conversation: Conversation): String =
    conversation.messages.collectFirst { case s: SystemMessage => s.content }.getOrElse("")

  private def editorCalls(client: ScriptedClient): Vector[Conversation] =
    client.calls.map(_._1).filter(c => systemPrompt(c) == EditorPrompt)

  "MultiAgentGraphRecipe.run" should "collect the views in task order, whichever specialist answers first" in {
    // the optimist is first in task order but answers last: it waits for the skeptic's reply
    val skepticAnswered = new CountDownLatch(1)
    val client = new ScriptedClient((conversation, _) =>
      systemPrompt(conversation) match {
        case OptimistPrompt =>
          skepticAnswered.await(5, TimeUnit.SECONDS): Unit
          Right(AssistantMessage("for it"))
        case SkepticPrompt =>
          skepticAnswered.countDown()
          Right(AssistantMessage("against it"))
        case _ => Right(AssistantMessage("adopt it, carefully"))
      }
    )

    val review = MultiAgentGraphRecipe.run(client, question).value

    review.views shouldBe Vector(View("optimist", "for it"), View("skeptic", "against it"))
    review.answer shouldBe "adopt it, carefully"
  }

  it should "run the editor once, after both specialists, with both views" in {
    val client = MultiAgentGraphRecipe.script

    MultiAgentGraphRecipe.run(client, question).value

    val editor = editorCalls(client)
    editor should have size 1
    val brief = editor.head.messages.filter(_.role == MessageRole.User).last.content
    brief should include(question)
    brief should include("optimist:")
    brief should include("skeptic:")
    // the editor is the last call: both specialist calls came before it
    systemPrompt(client.calls.last._1) shouldBe EditorPrompt
  }

  "MultiAgentGraphRecipe.runTraced" should "commit one checkpoint per superstep: brief, both specialists, editor" in {
    val (_, events) = MultiAgentGraphRecipe.runTraced(MultiAgentGraphRecipe.script, question).value

    val steps = events.dropWhile(_ == "RunStarted")
    steps.take(2) shouldBe Vector("TaskCompleted@brief", "CheckpointCommitted")
    // the specialists share a superstep, so they complete in either order before its one commit
    steps.slice(2, 4).toSet shouldBe Set("TaskCompleted@optimist", "TaskCompleted@skeptic")
    steps.drop(4) shouldBe Vector("CheckpointCommitted", "TaskCompleted@editor", "CheckpointCommitted", "RunCompleted")
  }

  "A specialist that fails" should "fail the run before the editor is asked" in {
    val client = new ScriptedClient((conversation, _) =>
      systemPrompt(conversation) match {
        case SkepticPrompt => Left(ExecutionError("the skeptic is unavailable", "complete"))
        case _             => Right(AssistantMessage("for it"))
      }
    )

    MultiAgentGraphRecipe.run(client, question).isLeft shouldBe true
    editorCalls(client) shouldBe empty
  }

  "Cancelling the graph run" should "cancel the specialist agent it is waiting on" in {
    val entered     = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    val client = new ScriptedClient((conversation, _) =>
      systemPrompt(conversation) match {
        case SkepticPrompt =>
          entered.countDown()
          CancelledError.catchInterrupt(Thread.sleep(60_000)) match {
            case Left(e) =>
              interrupted.countDown()
              Thread.currentThread().interrupt()
              Left(CancelledError("model call", Some(e)))
            case Right(_) => Right(AssistantMessage("never interrupted"))
          }
        case _ => Right(AssistantMessage("for it"))
      }
    )

    val handle = MultiAgentGraphRecipe.start(GraphRuntime.inMemory(), client, question).value
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    handle.cancel()

    handle.await().value match {
      case RunResult.Failed(_, _: GraphError.Cancelled) => succeed
      case other                                        => fail(s"expected a cancelled run, got $other")
    }
    // the skeptic agent's own turn was cancelled too, not left sleeping
    interrupted.await(10, TimeUnit.SECONDS) shouldBe true
  }

  "The demo" should "print the views and the editor's answer" in {
    val text = MultiAgentGraphRecipe.demo(MultiAgentGraphRecipe.script).value
    text should include("optimist")
    text should include("skeptic")
    text should include("Editor:")
  }
}
