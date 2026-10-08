package org.llm4s.samples.cookbook

import org.llm4s.llmconnect.model.MessageRole
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the memory recipe: a recorded fact changes what the model is shown, and so what it answers. */
class MemoryRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {

  import MemoryRecipe.{ facts, question, recall }

  "MemoryRecipe.recall" should "show the model the fact the question is about, and get an answer that uses it" in {
    val client = MemoryRecipe.script

    val result = recall(client, facts, question).value

    result.reply shouldBe "You prefer Scala."
    result.context should include("Prefers Scala over Java")
    val system = client.calls.head._1.messages.find(_.role == MessageRole.System).map(_.content).getOrElse("")
    system should include("Prefers Scala over Java")
  }

  it should "leave out a recorded fact that has nothing to do with the question" in {
    val result = recall(MemoryRecipe.script, facts, question).value

    (result.context should not).include("Berlin")
  }

  it should "not know the answer when nothing was recorded, so it is the memory that made the difference" in {
    val result = recall(MemoryRecipe.script, Seq.empty, question).value

    result.reply shouldBe "I do not know yet."
    result.context shouldBe ""
  }

  // The in-memory store matches keywords, which is why the recipe's question repeats "Scala" and "Java". Swapping
  // in a store with semantic search would change this, and the recipe's text with it.
  it should "find nothing when the question shares no word with the fact (the in-memory store matches keywords)" in {
    val result = recall(MemoryRecipe.script, facts, "What do I like?").value

    result.context shouldBe ""
    result.reply shouldBe "I do not know yet."
  }

  it should "remember several facts, show the ones that match and rank the better match first" in {
    val several = Seq("Prefers Scala over Java", "Prefers sbt over Maven", "Works in the Berlin office")

    val result = recall(MemoryRecipe.script, several, "Which build tool do I prefer, sbt or Maven?").value

    (result.context should not).include("Berlin")
    result.context should include("Prefers sbt over Maven")
    // "prefer" and "maven" match the build-tool fact, only "prefer" matches the language fact
    result.context.indexOf("Prefers sbt over Maven") should be < result.context.indexOf("Prefers Scala over Java")
  }

  "MemoryRecipe.contentWords" should "drop punctuation and short words, which the store would match as substrings" in {
    MemoryRecipe.contentWords("Which language do I prefer, Scala or Java?") shouldBe "which language prefer scala java"
  }

  "MemoryRecipe.demo" should "print the answer and the context the model saw" in {
    val text = MemoryRecipe.demo(MemoryRecipe.script).value

    text should include("You prefer Scala.")
    text should include("context shown to the model")
  }
}
