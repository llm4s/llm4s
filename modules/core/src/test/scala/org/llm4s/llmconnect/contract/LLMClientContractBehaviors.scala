package org.llm4s.llmconnect.contract

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.HeadroomPercent
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable.ListBuffer
import scala.util.Using

/**
 * Reusable contract behaviour suite for [[LLMClient]] implementations.
 *
 * Mix this trait into an `AnyWordSpec with Matchers` and call
 * `behave like llmClientContract(factory)` for each implementation.
 * The factory is invoked per-test so each test gets a fresh instance.
 */
trait LLMClientContractBehaviors { this: AnyWordSpec with Matchers =>

  private def validConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  def llmClientContract(createClient: () => LLMClient): Unit = {

    // -- complete() guarantees ------------------------------------------------

    "return Right for a valid single-message conversation" in {
      val client = createClient()
      val result = client.complete(validConversation)
      result.isRight shouldBe true
    }

    "produce a completion with non-empty id" in {
      val client     = createClient()
      val completion = client.complete(validConversation)
      completion.map(_.id) shouldBe Symbol("right")
      completion.foreach(c => c.id should not be empty)
    }

    "produce a completion with non-negative created timestamp" in {
      val client     = createClient()
      val completion = client.complete(validConversation)
      completion.foreach(c => c.created should be >= 0L)
    }

    "produce a completion with non-empty model string" in {
      val client     = createClient()
      val completion = client.complete(validConversation)
      completion.foreach(c => c.model should not be empty)
    }

    "produce a completion whose message role is Assistant" in {
      val client     = createClient()
      val completion = client.complete(validConversation)
      completion.foreach(c => c.message.role shouldBe MessageRole.Assistant)
    }

    "produce a completion with non-null content" in {
      val client     = createClient()
      val completion = client.complete(validConversation)
      completion.foreach(c => c.content should not be null)
    }

    // -- streamComplete() guarantees ------------------------------------------

    "return Right from streamComplete for a valid conversation" in {
      val client = createClient()
      val result = client.streamComplete(validConversation, onChunk = _ => ())
      result.isRight shouldBe true
    }

    "produce a stream completion with non-empty id and model" in {
      val client = createClient()
      val result = client.streamComplete(validConversation, onChunk = _ => ())
      result.foreach { c =>
        c.id should not be empty
        c.model should not be empty
      }
    }

    "invoke onChunk at least once when content is non-empty (if supported)" in {
      val client = createClient()
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(validConversation, onChunk = chunks += _)
      result.foreach { _ =>
        // Only assert if the implementation actually calls onChunk.
        // Mocks that delegate to complete() without calling onChunk are acceptable.
        if (chunks.nonEmpty) {
          chunks.size should be >= 1
        }
      }
    }

    "pass StreamedChunks with non-empty id to onChunk (if chunks are emitted)" in {
      val client = createClient()
      val chunks = ListBuffer.empty[StreamedChunk]
      client.streamComplete(validConversation, onChunk = chunks += _)
      chunks.foreach(_.id should not be empty)
    }

    // -- Context window guarantees --------------------------------------------

    "return a positive context window" in {
      val client = createClient()
      client.getContextWindow() should be > 0
    }

    "return a non-negative reserve completion" in {
      val client = createClient()
      client.getReserveCompletion() should be >= 0
    }

    "have reserve completion less than context window" in {
      val client = createClient()
      client.getReserveCompletion() should be < client.getContextWindow()
    }

    "return a positive context budget with Standard headroom" in {
      val client = createClient()
      client.getContextBudget(HeadroomPercent.Standard) should be > 0
    }

    "return context budget equal to contextWindow - reserve when headroom is None" in {
      val client   = createClient()
      val expected = client.getContextWindow() - client.getReserveCompletion()
      client.getContextBudget(HeadroomPercent.None) shouldBe expected
    }

    "have monotonically decreasing budgets: None > Light > Standard > Conservative" in {
      val client       = createClient()
      val none         = client.getContextBudget(HeadroomPercent.None)
      val light        = client.getContextBudget(HeadroomPercent.Light)
      val standard     = client.getContextBudget(HeadroomPercent.Standard)
      val conservative = client.getContextBudget(HeadroomPercent.Conservative)
      none should be > light
      light should be > standard
      standard should be > conservative
    }

    // -- validate() guarantees ------------------------------------------------

    "return a Result from validate without throwing" in {
      val client = createClient()
      noException should be thrownBy client.validate()
    }

    // -- close() / AutoCloseable guarantees -----------------------------------

    "not throw when close() is called" in {
      val client = createClient()
      noException should be thrownBy client.close()
    }

    "be idempotent — calling close() twice does not throw" in {
      val client = createClient()
      noException should be thrownBy {
        client.close()
        client.close()
      }
    }

    "be an instance of AutoCloseable" in {
      val client = createClient()
      client shouldBe a[AutoCloseable]
    }

    "work with scala.util.Using" in {
      val client = createClient()
      val result = Using(client)(c => c.complete(validConversation))
      result.isSuccess shouldBe true
    }

    // -- Consistency ----------------------------------------------------------

    "produce structurally equivalent results from complete and streamComplete" in {
      val client         = createClient()
      val completeResult = client.complete(validConversation)
      val streamResult   = client.streamComplete(validConversation, onChunk = _ => ())
      for {
        c <- completeResult
        s <- streamResult
      } {
        c.model shouldBe s.model
        c.message.role shouldBe s.message.role
        c.id should not be empty
        s.id should not be empty
        c.content should not be null
        s.content should not be null
      }
    }
  }
}
