package org.llm4s.llmconnect.spi

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Covers `ProviderFeatures`, the static declaration of what a provider's client implements.
 *
 * It follows the repository's growth-prone pattern: a companion `apply` carrying the defaults and
 * `with*` setters. The tests pin the flags' defaults, that each setter changes exactly its own flag
 * and leaves the original alone, and value equality. Whether the constructor and `copy` stay private
 * is not tested here: see the pull request description.
 */
class ProviderFeaturesSpec extends AnyWordSpec with Matchers:

  /** Every combination of the two flags, so a setter is checked against each starting point. */
  private val allCombinations: Seq[(Boolean, Boolean)] =
    for
      streaming   <- Seq(true, false)
      toolCalling <- Seq(true, false)
    yield (streaming, toolCalling)

  private def flags(features: ProviderFeatures): (Boolean, Boolean) =
    (features.streaming, features.toolCalling)

  "ProviderFeatures()" should {

    "declare both streaming and tool calling by default" in {
      val features = ProviderFeatures()

      features.streaming shouldBe true
      features.toolCalling shouldBe true
    }

    "equal ProviderFeatures.default, which also declares both" in {
      ProviderFeatures.default shouldBe ProviderFeatures()
      flags(ProviderFeatures.default) shouldBe ((true, true))
    }

    "change only streaming when streaming = false is named" in {
      flags(ProviderFeatures(streaming = false)) shouldBe ((false, true))
    }

    "change only tool calling when toolCalling = false is named" in {
      flags(ProviderFeatures(toolCalling = false)) shouldBe ((true, false))
    }

    "carry both flags as given, in the order the parameters are declared" in {
      flags(ProviderFeatures(true, false)) shouldBe ((true, false))
      flags(ProviderFeatures(false, true)) shouldBe ((false, true))
      flags(ProviderFeatures(false, false)) shouldBe ((false, false))
    }
  }

  "withStreaming" should {

    "change only streaming, from every starting point, to either value" in {
      for
        (streaming, toolCalling) <- allCombinations
        target                   <- Seq(true, false)
      do
        val start   = ProviderFeatures(streaming, toolCalling)
        val updated = start.withStreaming(target)

        flags(updated) shouldBe ((target, toolCalling))
    }

    "leave the value it was called on unchanged" in {
      val start = ProviderFeatures(streaming = true, toolCalling = false)

      start.withStreaming(false)

      flags(start) shouldBe ((true, false))
    }
  }

  "withToolCalling" should {

    "change only tool calling, from every starting point, to either value" in {
      for
        (streaming, toolCalling) <- allCombinations
        target                   <- Seq(true, false)
      do
        val start   = ProviderFeatures(streaming, toolCalling)
        val updated = start.withToolCalling(target)

        flags(updated) shouldBe ((streaming, target))
    }

    "leave the value it was called on unchanged" in {
      val start = ProviderFeatures(streaming = false, toolCalling = true)

      start.withToolCalling(false)

      flags(start) shouldBe ((false, true))
    }
  }

  "chained setters" should {

    "set both flags, whichever setter comes first" in {
      val streamingFirst   = ProviderFeatures.default.withStreaming(false).withToolCalling(false)
      val toolCallingFirst = ProviderFeatures.default.withToolCalling(false).withStreaming(false)

      flags(streamingFirst) shouldBe ((false, false))
      flags(toolCallingFirst) shouldBe ((false, false))
    }

    "let the last call on a flag win" in {
      val features = ProviderFeatures.default.withStreaming(false).withStreaming(true).withToolCalling(false)

      flags(features) shouldBe ((true, false))
    }

    "return an equal value when a flag is set to the value it already has" in {
      allCombinations.foreach { case (streaming, toolCalling) =>
        val start = ProviderFeatures(streaming, toolCalling)

        start.withStreaming(streaming) shouldBe start
        start.withToolCalling(toolCalling) shouldBe start
      }
    }
  }

  "equality" should {

    "hold for values built with the same flags, with the same hash code" in {
      allCombinations.foreach { case (streaming, toolCalling) =>
        val first  = ProviderFeatures(streaming, toolCalling)
        val second = ProviderFeatures(streaming = streaming, toolCalling = toolCalling)

        first shouldBe second
        first.hashCode shouldBe second.hashCode
      }
    }

    "hold between a value built directly and one reached through the setters" in {
      val built = ProviderFeatures(streaming = false, toolCalling = true)
      val set   = ProviderFeatures.default.withStreaming(false)

      set shouldBe built
      set.hashCode shouldBe built.hashCode
    }

    "distinguish values that differ in either flag" in {
      val base = ProviderFeatures.default

      base should not be base.withStreaming(false)
      base should not be base.withToolCalling(false)
      base.withStreaming(false) should not be base.withToolCalling(false)
    }

    "give the four combinations four distinct values in a set" in {
      val values = allCombinations.map { case (streaming, toolCalling) => ProviderFeatures(streaming, toolCalling) }

      values.toSet should have size 4
    }
  }
