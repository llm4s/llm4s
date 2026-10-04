package org.llm4s.speech

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.speech.config.SpeechConfigLoader
import org.llm4s.speech.tts.TTSOptions
import org.llm4s.speech.tts.provider.ElevenLabsTTSClient
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for ElevenLabs text-to-speech.
 *
 * Requires `ELEVENLABS_API_KEY`, read through `SpeechConfigLoader` (the `llm4s.speech.elevenlabs`
 * block of `llm4s-speech`'s `reference.conf`). Uses ElevenLabs' premade "Rachel" voice, which is
 * available to every account; `ELEVENLABS_MODEL_ID` selects another model. Makes real, billed API
 * calls. Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class ElevenLabsSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val RachelVoiceId = "21m00Tcm4TlvDq8ikWAM"

  private val config = SpeechConfigLoader.tts(s"elevenlabs/$RachelVoiceId")

  private def client: ElevenLabsTTSClient = {
    Tier.require(config.isRight, "ELEVENLABS_API_KEY not set")
    new ElevenLabsTTSClient(config.value)
  }

  "ElevenLabs TTS" should "synthesize speech: 24 kHz 16-bit mono PCM of plausible length that saves as a valid WAV" in {
    val audio = client.synthesize(CloudSpeechSmoke.Phrase).value

    CloudSpeechSmoke.requireSpeechPcm(audio)
  }

  it should "use the voice id from TTSOptions" in {
    // An id that is not a voice must be refused by the service, not silently replaced by the default.
    val result = client.synthesize("Hello", TTSOptions(voice = Some("not-a-real-voice-id")))

    result.isLeft shouldBe true
  }

  it should "reject an invalid key with an authentication error" in {
    Tier.require(config.isRight, "ELEVENLABS_API_KEY not set")
    val rejected = new ElevenLabsTTSClient(config.value.copy(apiKey = "invalid-llm4s-smoke-test-key"))

    rejected.synthesize("Hello").left.value shouldBe an[AuthenticationError]
  }
}
