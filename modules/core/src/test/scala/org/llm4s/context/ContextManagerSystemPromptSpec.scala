package org.llm4s.context

import org.llm4s.llmconnect.model.*
import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The leading system messages are the conversation's instructions: history compression never digests them. */
class ContextManagerSystemPromptSpec extends AnyFlatSpec with Matchers {

  private val prompt = SystemMessage("You are the billing agent. Never quote a price in USD; reply in German.")
  private val second = SystemMessage("The customer is on the Gold plan.")

  private def turns(n: Int, words: Int): Seq[Message] =
    (1 to n).flatMap { i =>
      Seq(
        UserMessage(s"Question $i " + "lorem ipsum dolor " * words),
        AssistantMessage(s"Answer $i " + "sit amet " * words)
      )
    }

  private def managed(messages: Seq[Message], budget: ConversationTokenCounter => Int): (Seq[Message], Boolean) =
    inside(ConversationTokenCounter.o200k()) { case Right(counter) =>
      inside(ContextManager.create(counter).flatMap(_.manageContext(Conversation(messages), budget(counter)))) {
        case Right(result) =>
          val digested = result.steps.exists(s => s.stage == ContextStage.HistoryCompression && s.applied)
          (result.conversation.messages, digested)
      }
    }

  "manageContext" should "keep the system prompt first and verbatim when it digests the history" in {
    val (kept, digested) = managed(prompt +: turns(12, 20), _ => 1000)
    digested shouldBe true
    kept.headOption shouldBe Some(prompt)
    kept.drop(1).exists(HistorySummaryMarker.isDigest) shouldBe true
  }

  it should "keep every leading system message, in order" in {
    val (kept, digested) = managed(Seq(prompt, second) ++ turns(12, 20), _ => 1000)
    digested shouldBe true
    kept.take(2) shouldBe Seq(prompt, second)
  }

  it should "keep it when the conversation is one token over the budget" in {
    val messages         = prompt +: turns(8, 10)
    val (kept, digested) = managed(messages, _.countConversation(Conversation(messages)) - 1)
    digested shouldBe true
    kept.headOption shouldBe Some(prompt)
  }

  it should "fit a long system prompt, its digest and the newest turn in the budget" in {
    val long             = SystemMessage("Follow the house rules. " * 60)
    val messages         = long +: turns(12, 20)
    val (kept, digested) = managed(messages, _ => 1000)
    digested shouldBe true
    kept.headOption shouldBe Some(long)
    kept.lastOption shouldBe messages.lastOption
  }

  it should "give the digest less room the longer the system prompt is" in {
    inside(ConversationTokenCounter.o200k()) { case Right(counter) =>
      val long = SystemMessage("Follow the house rules. " * 100)
      val decisions = (1 to 80).flatMap(i =>
        Seq(UserMessage(s"We decided on plan number $i for the team."), AssistantMessage("Noted."))
      )
      def digestTokens(first: SystemMessage): Int = {
        val (kept, digested) = managed(first +: decisions, _ => 1000)
        digested shouldBe true
        kept.filter(HistorySummaryMarker.isDigest).map(counter.countMessage).sum
      }
      digestTokens(long) should be > 0
      digestTokens(long) should be < digestTokens(prompt)
    }
  }

  it should "drop the digest, not fail, when it leaves no room for the newest turn" in {
    Seq(840, 860, 880).foreach { words =>
      val long      = SystemMessage("rule " * words)
      val messages  = long +: (1 to 12).flatMap(i => Seq(UserMessage(s"Question $i"), AssistantMessage(s"Answer $i")))
      val (kept, _) = managed(messages, _ => 1000)
      kept.headOption shouldBe Some(long)
      kept.lastOption shouldBe messages.lastOption
    }
  }

  it should "report the steps of a dropped digest as not applied" in {
    inside(ConversationTokenCounter.o200k()) { case Right(counter) =>
      val long     = SystemMessage("rule " * 880)
      val messages = long +: (1 to 12).flatMap(i => Seq(UserMessage(s"Question $i"), AssistantMessage(s"Answer $i")))
      inside(ContextManager.create(counter).flatMap(_.manageContext(Conversation(messages), 1000))) {
        case Right(result) =>
          result.conversation.messages.exists(HistorySummaryMarker.isDigest) shouldBe false
          result.steps.map(s => s.stage -> s.applied) shouldBe Seq(
            ContextStage.ToolDeterministicCompaction -> false,
            ContextStage.HistoryCompression          -> false,
            ContextStage.LLMHistorySqueeze           -> false,
            ContextStage.FinalTokenTrim              -> true
          )
          result.totalTokensSaved shouldBe result.originalTokens - result.finalTokens
      }
    }
  }

  it should "fail when the system prompt exceeds the budget, or leaves no room for the newest turn" in {
    inside(ConversationTokenCounter.o200k()) { case Right(counter) =>
      def failure(messages: Seq[Message]): String =
        ContextManager.create(counter).flatMap(_.manageContext(Conversation(messages), 1000)) match {
          case Left(error) => error.message
          case Right(_)    => fail("the conversation does not fit the budget")
        }
      val short = (1 to 12).flatMap(i => Seq(UserMessage(s"Question $i"), AssistantMessage(s"Answer $i")))
      failure(SystemMessage("rule " * 2000) +: short) should include("Token budget exceeded")
      failure((SystemMessage("rule " * 800) +: short) :+ UserMessage("word " * 200)) should include(
        "does not fit beside the 1 pinned message(s)"
      )
    }
  }

  it should "not change its own result" in {
    val (once, _)  = managed(prompt +: turns(12, 20), _ => 1000)
    val (twice, _) = managed(once, _ => 1000)
    twice shouldBe once
  }

  it should "not digest a digest again when the conversation has grown" in {
    val (once, _)  = managed(prompt +: turns(12, 20), _ => 1000)
    val (again, _) = managed(once ++ turns(6, 20), _ => 1000)
    again.headOption shouldBe Some(prompt)
    again.filter(HistorySummaryMarker.isDigest) shouldBe once.filter(HistorySummaryMarker.isDigest)
  }

  it should "digest the history of a conversation without a system prompt as before" in {
    val (kept, digested) = managed(turns(12, 20), _ => 1000)
    digested shouldBe true
    kept.headOption.exists(HistorySummaryMarker.isDigest) shouldBe true
  }
}
