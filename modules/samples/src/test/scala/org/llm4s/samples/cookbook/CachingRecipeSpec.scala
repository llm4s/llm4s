package org.llm4s.samples.cookbook

import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Conversation, UserMessage }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the caching recipe against its scripted client and counts the calls that reach it. */
class CachingRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {

  "CachingRecipe.askAll" should "answer a repeated question from the cache, without a model call" in {
    val client = CachingRecipe.script

    val answers = CachingRecipe.askAll(client, CachingRecipe.questions).value

    client.calls should have size 2
    answers shouldBe Seq("(model call 1) We open at nine.", "(model call 2) Yes, we ship to Canada.", answers.head)
  }

  it should "call the model for a question that only shares most of its words with a cached one" in {
    val client = CachingRecipe.script

    CachingRecipe.askAll(client, Seq("When does the office open?", "When does the office close?")).value

    client.calls should have size 2
  }

  it should "not share a cache between two calls of askAll, since each builds its own" in {
    val client = CachingRecipe.script

    CachingRecipe.askAll(client, Seq("When does the office open?")).value
    CachingRecipe.askAll(client, Seq("When does the office open?")).value

    client.calls should have size 2
  }

  "CachingRecipe.cached" should "miss when the same prompt is sent with different options" in {
    val client = new ScriptedClient((_, call) => Right(AssistantMessage(s"answer $call")))
    val cache  = CachingRecipe.cached(client).value
    val prompt = Conversation(Seq(UserMessage("When does the office open?")))

    cache.complete(prompt, CompletionOptions()).value.content shouldBe "answer 1"
    cache.complete(prompt, CompletionOptions()).value.content shouldBe "answer 1"
    cache.complete(prompt, CompletionOptions().withTemperature(0.0)).value.content shouldBe "answer 2"
  }

  "CachingRecipe.demo" should "show the third answer coming from the first model call" in {
    CachingRecipe.demo(CachingRecipe.script).value.linesIterator.toList.last should endWith(
      "(model call 1) We open at nine."
    )
  }
}
