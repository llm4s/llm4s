/*
rule = AccessorRenames
*/
package fix.v06

import org.llm4s.context.ConversationTokenCounter
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.types.HeadroomPercent

object AccessorRenamesTest {
  def promptBudget(client: LLMClient): Int = client.getContextWindow() - client.getReserveCompletion()

  def budget(client: LLMClient): Int = client.getContextBudget()

  def tight(client: LLMClient, headroom: HeadroomPercent): Int = client.getContextBudget(headroom)

  // a wrapper of your own: the definitions are renamed with the calls
  abstract class Halved(underlying: LLMClient) extends LLMClient {
    override def getContextWindow(): Int = underlying.getContextWindow() / 2

    def getReserveCompletion(): Int =
      underlying.getReserveCompletion()

    def left: Int = getContextWindow() - getReserveCompletion()
  }

  def halved(client: Halved): Int = client.getContextWindow()

  def total(counter: ConversationTokenCounter, conversation: Conversation): Int =
    counter.getTokenBreakdown(conversation).totalTokens

  // not an LLMClient's: a class that extends nothing keeps its own names
  class Window {
    def getContextWindow(): Int = 8192
  }

  def own(window: Window): Int = window.getContextWindow()
}
