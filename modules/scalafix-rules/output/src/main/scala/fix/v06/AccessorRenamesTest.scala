package fix.v06

import org.llm4s.context.ConversationTokenCounter
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.types.HeadroomPercent

object AccessorRenamesTest {
  def promptBudget(client: LLMClient): Int = client.contextWindow - client.reserveCompletion

  def budget(client: LLMClient): Int = client.contextBudget()

  def tight(client: LLMClient, headroom: HeadroomPercent): Int = client.contextBudget(headroom)

  // a wrapper of your own: the definitions are renamed with the calls
  abstract class Halved(underlying: LLMClient) extends LLMClient {
    override def contextWindow: Int = underlying.contextWindow / 2

    def reserveCompletion: Int =
      underlying.reserveCompletion

    def left: Int = contextWindow - reserveCompletion
  }

  def halved(client: Halved): Int = client.contextWindow

  def total(counter: ConversationTokenCounter, conversation: Conversation): Int =
    counter.tokenBreakdown(conversation).totalTokens

  // not an LLMClient's: a class that extends nothing keeps its own names
  class Window {
    def getContextWindow(): Int = 8192
  }

  def own(window: Window): Int = window.getContextWindow()
}
