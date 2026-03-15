package org.llm4s.agent.memory

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class MemoryStatsSpec extends AnyWordSpec with Matchers {

  "MemoryStats.empty" should {

    "return the zero/default state" in {
      val stats = MemoryStats.empty

      stats.totalMemories shouldBe 0L
      stats.byType shouldBe Map.empty
      stats.entityCount shouldBe 0L
      stats.conversationCount shouldBe 0L
      stats.embeddedCount shouldBe 0L
      stats.oldestMemory shouldBe None
      stats.newestMemory shouldBe None
    }

  }
}
