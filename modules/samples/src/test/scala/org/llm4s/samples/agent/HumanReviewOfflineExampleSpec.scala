package org.llm4s.samples.agent

import org.llm4s.samples.agent.HumanReviewOfflineExample.Payment
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class HumanReviewOfflineExampleSpec extends AnyFunSuite with Matchers {
  private val result = HumanReviewOfflineExample.run().fold(error => fail(error.formatted), identity)

  test("the run waits on all three payments before any is made") {
    result.pending should have size 3
    result.pending.foreach(_ should include("payments need review"))
  }

  test("only the approved payment and the edited payment are made, the edited one with the new amount") {
    result.payments should contain theSameElementsAs Vector(Payment("alice", 20), Payment("bob", 50))
    result.payments.map(_.to) should not contain "mallory"
  }

  test("the model sees Rejected: <reason> for the rejected payment") {
    result.modelSaw should have size 3
    result.modelSaw.count(_.contains("Rejected: not on the payee list")) shouldBe 1
  }

  test("each decision is reported") {
    result.decisions should contain theSameElementsAs Vector(
      "approved send_payment(alice, 20)",
      "rejected send_payment(mallory, 1000): not on the payee list",
      "edited amount 500 -> 50"
    )
  }

  test("the model answers after the resume") {
    result.finalAnswer should include("Rejected: not on the payee list")
  }
}
