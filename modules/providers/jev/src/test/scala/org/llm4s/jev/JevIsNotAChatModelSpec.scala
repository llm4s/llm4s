package org.llm4s.jev

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, StreamedChunk }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Jev is a decision model, not a chat model: the module keeps its own contract and does not adapt
 * Jev to `LLMClient`, `Completion` or the provider SPI.
 */
class JevIsNotAChatModelSpec extends AnyFlatSpec with Matchers {

  "The Jev client" should "not be an LLMClient" in {
    classOf[LLMClient].isAssignableFrom(classOf[JevClient]) shouldBe false
  }

  it should "offer no completion or streaming operation" in {
    val names = classOf[JevClient].getMethods.map(_.getName).toSet
    names should contain("evaluate")
    names.filter(n => n.toLowerCase.contains("complet") || n.toLowerCase.contains("stream")) shouldBe empty
    classOf[JevClient].getMethods.map(_.getReturnType).toSet should not contain classOf[StreamedChunk]
  }

  "A Jev response" should "not be a Completion" in {
    classOf[Completion].isAssignableFrom(classOf[JevResponse]) shouldBe false
  }

  "The module" should "register no chat or embedding provider" in {
    val registry = ProviderRegistry.discover()
    val ids      = registry.ids ++ registry.embeddingIds
    ids.filter(id => id.toLowerCase.contains("jev") || id.toLowerCase.contains("typesafe")) shouldBe empty
  }
}
