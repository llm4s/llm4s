package org.llm4s.speech

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.speech.config.SpeechConfigLoader
import org.llm4s.speech.tts.TTSOptions
import org.llm4s.speech.tts.provider.OpenAITTSClient
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for OpenAI text-to-speech.
 *
 * Requires `OPENAI_API_KEY`, read through `SpeechConfigLoader` (the `llm4s.speech.openai` block of
 * `llm4s-speech`'s `reference.conf`) as an application would. Makes real, billed API calls.
 * Tier: `@Cloud` - `sbt testSmoke`. Never run by default; without a key it is skipped, or failed
 * under `LLM4S_IT_STRICT=true`.
 */
@Cloud
class OpenAITTSSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val config = SpeechConfigLoader.tts("openai/tts-1")

  private def client: OpenAITTSClient = {
    Tier.require(config.isRight, "OPENAI_API_KEY not set")
    new OpenAITTSClient(config.value)
  }

  "OpenAI TTS" should "synthesize speech: 24 kHz 16-bit mono PCM of plausible length that saves as a valid WAV" in {
    val audio = client.synthesize(CloudSpeechSmoke.Phrase, TTSOptions(voice = Some("alloy"))).value

    CloudSpeechSmoke.requireSpeechPcm(audio)
  }

  it should "honour the voice option: different voices give different audio" in {
    val alloy = client.synthesize(CloudSpeechSmoke.Phrase, TTSOptions(voice = Some("alloy"))).value
    val onyx  = client.synthesize(CloudSpeechSmoke.Phrase, TTSOptions(voice = Some("onyx"))).value

    CloudSpeechSmoke.requireSpeechPcm(onyx)
    (onyx.data should not).equal(alloy.data)
  }

  it should "speak faster when the speaking rate is raised" in {
    val normal = client.synthesize(CloudSpeechSmoke.Phrase, TTSOptions(speakingRate = Some(1.0))).value
    val fast   = client.synthesize(CloudSpeechSmoke.Phrase, TTSOptions(speakingRate = Some(2.0))).value

    fast.data.length.toDouble should be < (normal.data.length * 0.8)
  }

  it should "reject an invalid key with an authentication error" in {
    Tier.require(config.isRight, "OPENAI_API_KEY not set")
    val rejected = new OpenAITTSClient(config.value.copy(apiKey = "sk-invalid-llm4s-smoke-test"))

    rejected.synthesize("Hello").left.value shouldBe an[org.llm4s.error.AuthenticationError]
  }
}
