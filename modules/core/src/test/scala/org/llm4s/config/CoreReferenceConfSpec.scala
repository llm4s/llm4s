package org.llm4s.config

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Core's `reference.conf` binds only what core reads (#2806): a block read by one module ships in
 * that module's `reference.conf`.
 */
class CoreReferenceConfSpec extends AnyWordSpec with Matchers {

  private val reference = ConfigSource.defaultReference.config().fold(e => fail(e.prettyPrint()), identity)

  "core's reference.conf" should {
    "not bind llm4s.workspace, which only llm4s-workspace-client reads" in {
      reference.hasPathOrNull("llm4s.workspace") shouldBe false
    }

    "still bind the blocks core reads" in {
      reference.hasPath("llm4s.exchangeLogging.enabled") shouldBe true
      reference.hasPath("llm4s.tracing.mode") shouldBe true
    }
  }
}
