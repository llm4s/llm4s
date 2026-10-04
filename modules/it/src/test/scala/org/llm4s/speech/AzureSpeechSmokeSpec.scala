package org.llm4s.speech

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.speech.config.SpeechConfigLoader
import org.llm4s.speech.stt.provider.AzureSTTClient
import org.llm4s.speech.tts.TTSOptions
import org.llm4s.speech.tts.provider.AzureTTSClient
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for Azure AI Speech: text-to-speech, speech-to-text, and the round trip.
 *
 * Requires `AZURE_SPEECH_KEY` and `AZURE_SPEECH_REGION`, read through `SpeechConfigLoader` (the
 * `llm4s.speech.azure` block of `llm4s-speech`'s `reference.conf`). Makes real, billed API calls.
 * Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class AzureSpeechSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val Voice = "en-US-JennyNeural"

  private val ttsConfig = SpeechConfigLoader.tts(s"azure/$Voice")
  private val sttConfig = SpeechConfigLoader.stt("azure/en-US")

  private def tts: AzureTTSClient = {
    Tier.require(ttsConfig.isRight, "AZURE_SPEECH_KEY / AZURE_SPEECH_REGION not set")
    new AzureTTSClient(ttsConfig.value)
  }

  private def stt: AzureSTTClient = {
    Tier.require(sttConfig.isRight, "AZURE_SPEECH_KEY / AZURE_SPEECH_REGION not set")
    new AzureSTTClient(sttConfig.value)
  }

  "Azure TTS" should "synthesize speech: 24 kHz 16-bit mono PCM of plausible length that saves as a valid WAV" in {
    val audio = tts.synthesize(CloudSpeechSmoke.Phrase).value

    CloudSpeechSmoke.requireSpeechPcm(audio)
  }

  it should "use the voice from TTSOptions" in {
    val jenny = tts.synthesize(CloudSpeechSmoke.Phrase).value
    val guy   = tts.synthesize(CloudSpeechSmoke.Phrase, TTSOptions(voice = Some("en-US-GuyNeural"))).value

    CloudSpeechSmoke.requireSpeechPcm(guy)
    (guy.data should not).equal(jenny.data)
  }

  "Azure STT" should "transcribe the speech Azure TTS produced (round trip)" in {
    val wav = CloudSpeechSmoke.wav16k(tts.synthesize("Hello world").value)

    val transcription = stt.transcribe(AudioInput.BytesAudio(wav, 16000)).value

    transcription.text.toLowerCase should include("hello")
    transcription.language shouldBe Some("en-US")
  }

  it should "fail, not return text, for audio with no speech" in {
    val silence = new Array[Byte](16000 * 2 * 2) // two seconds of 16 kHz silence
    val wav =
      org.llm4s.speech.io.WavFileGenerator.createWavHeader(silence.length, AudioMeta(16000, 1, 16)).value ++ silence

    stt.transcribe(AudioInput.BytesAudio(wav, 16000)).isLeft shouldBe true
  }

  it should "reject an invalid key with an authentication error" in {
    Tier.require(sttConfig.isRight, "AZURE_SPEECH_KEY / AZURE_SPEECH_REGION not set")
    val rejected = new AzureSTTClient(sttConfig.value.copy(apiKey = "invalid-llm4s-smoke-test-key"))
    val wav      = CloudSpeechSmoke.wav16k(tts.synthesize("Hello").value)

    rejected.transcribe(AudioInput.BytesAudio(wav, 16000)).left.value shouldBe an[AuthenticationError]
  }
}
