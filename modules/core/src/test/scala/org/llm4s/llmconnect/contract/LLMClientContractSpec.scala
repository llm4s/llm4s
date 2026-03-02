package org.llm4s.llmconnect.contract

import org.llm4s.testutil.MockLLMClients
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Verifies that the existing mock LLMClient implementations
 * satisfy the [[LLMClientContractBehaviors]] contract.
 */
class LLMClientContractSpec extends AnyWordSpec with Matchers with LLMClientContractBehaviors {

  "SimpleMock" should {
    llmClientContract(() => new MockLLMClients.SimpleMock("test response"))
  }

  "MultiResponseMock" should {
    llmClientContract(() => new MockLLMClients.MultiResponseMock(Seq("response-1", "response-2")))
  }
}
