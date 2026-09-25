package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ToolExecutionConfigSpec extends AnyFlatSpec with Matchers {

  "ToolRetryPolicy.delayBeforeAttempt" should "return baseDelay for the first retry" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 100.millis, backoffFactor = 2.0)
    policy.delayBeforeAttempt(1) shouldBe 100.millis
  }

  it should "apply the backoff factor for later retries" in {
    val policy = ToolRetryPolicy(maxAttempts = 4, baseDelay = 100.millis, backoffFactor = 2.0)
    policy.delayBeforeAttempt(2) shouldBe 200.millis
    policy.delayBeforeAttempt(3) shouldBe 400.millis
  }

  it should "stay constant when backoffFactor is 1.0" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 50.millis, backoffFactor = 1.0)
    policy.delayBeforeAttempt(1) shouldBe 50.millis
    policy.delayBeforeAttempt(2) shouldBe 50.millis
  }
}
