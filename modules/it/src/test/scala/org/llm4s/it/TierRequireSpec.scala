package org.llm4s.it

import org.llm4s.it.tags.Local
import org.scalatest.exceptions.{ TestCanceledException, TestFailedException }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `Tier.require` is what keeps a `@Cloud` suite from reporting success while testing nothing: it
 * must be a no-op with the dependency, cancel without strict mode and fail with it. Both outcomes
 * are asserted against whichever mode this process runs in.
 */
@Local
class TierRequireSpec extends AnyFlatSpec with Matchers {

  "Tier.require" should "do nothing when the dependency is available" in {
    noException should be thrownBy Tier.require(available = true, "key not set")
  }

  it should "cancel (skip) when it is missing, or fail when LLM4S_IT_STRICT=true, naming what is missing" in {
    if (Tier.strict) {
      val e = the[TestFailedException] thrownBy Tier.require(available = false, "FOO_KEY not set")
      e.getMessage should include("FOO_KEY not set")
      e.getMessage should include("LLM4S_IT_STRICT=true")
    } else {
      val e = the[TestCanceledException] thrownBy Tier.require(available = false, "FOO_KEY not set")
      e.getMessage should include("FOO_KEY not set")
    }
  }
}
