package org.llm4s.agent.graph.middleware

import org.llm4s.agent.CompletionFixture
import org.llm4s.agent.graph.{ StateKey, StateUpdate, ThreadState }
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome, ToolSuccess }
import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Every hook returns its state update the same way (#2795, item 3): the result is built with an update, read with
 * `update`, and added to with `addUpdate`, which applies after what is there - for a boundary hook's
 * `BoundaryResult`, a model wrapper's `ModelResponse` and a tool wrapper's `ToolOutcome` alike. None of them replaces
 * an update, so a wrapper cannot drop what the hooks or the tool inside it wrote.
 */
class HookResultVocabularySpec extends AnyFlatSpec with Matchers with EitherValues {

  private val notes: StateKey[Vector[String], String] = StateKey.appending[String]("vocabulary-notes")
  private val count: StateKey[Int, Int]               = StateKey.replace[Int]("vocabulary-count", 0)

  private def note(text: String): StateUpdate = StateUpdate.update(notes, text)

  /** What `update` leaves in a state registering both keys: the notes in the order applied, and the count. */
  private def applied(update: StateUpdate): (Vector[String], Int) =
    val state = ThreadState.empty(Map(notes.id -> notes, count.id -> count)).applyUpdate(update).value
    (state.get(notes).value, state.get(count).value)

  private val content = ToolContent.Json(ujson.Str("done"))

  "BoundaryResult" should "be built with an update and add to it with addUpdate, applied after" in {
    val result = BoundaryResult("text", note("first")).addUpdate(note("second")).addUpdate(StateUpdate.update(count, 2))

    result.text shouldBe "text"
    applied(result.update) shouldBe (Vector("first", "second"), 2)
    applied(BoundaryResult("text").update) shouldBe (Vector.empty, 0)
  }

  it should "keep its update when its text changes" in {
    applied(BoundaryResult("text", note("kept")).withText("changed").update)._1 shouldBe Vector("kept")
  }

  "ModelResponse" should "be built with an update and add to it with addUpdate, applied after" in {
    val completion = CompletionFixture.simple("answer")
    val response   = ModelResponse(completion, note("first")).addUpdate(note("second"))

    response.completion shouldBe completion
    applied(response.update)._1 shouldBe Vector("first", "second")
    applied(ModelResponse(completion).update)._1 shouldBe empty
  }

  it should "keep its update when its completion changes" in {
    val changed = ModelResponse(CompletionFixture.simple("a"), note("kept")).withCompletion(CompletionFixture.simple("b"))
    applied(changed.update)._1 shouldBe Vector("kept")
  }

  "ToolSuccess" should "be built with an update and add to it with addUpdate, applied after" in {
    val success = ToolSuccess(content, note("tool")).addUpdate(note("wrapper"))

    success.content shouldBe content
    applied(success.update)._1 shouldBe Vector("tool", "wrapper")
    applied(success.withContent(ToolContent.Json(ujson.Num(1))).update)._1 shouldBe Vector("tool", "wrapper")
  }

  "ToolOutcome.addUpdate" should "add to a Success's update, after the tool's own" in {
    val outcome = ToolOutcome.success(content, note("tool")).addUpdate(note("wrapper"))

    outcome match
      case ToolOutcome.Success(success) =>
        success.content shouldBe content
        applied(success.update)._1 shouldBe Vector("tool", "wrapper")
      case other => fail(s"expected a Success, got $other")
  }

  it should "leave every other outcome as it is: only a successful call commits an update" in {
    val others = Vector[ToolOutcome](
      ToolOutcome.Error("denied"),
      ToolOutcome.NeedsApproval("review"),
      ToolOutcome.Ask("which one?"),
      ToolOutcome.Fatal(ValidationError("tool", "broken"))
    )
    others.foreach(outcome => outcome.addUpdate(note("ignored")) shouldBe outcome)
  }
}
