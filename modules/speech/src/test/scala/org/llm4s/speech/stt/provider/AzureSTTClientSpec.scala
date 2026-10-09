package org.llm4s.speech.stt.provider

import org.llm4s.speech.{ AudioInput, AudioMeta, StubHttpClient }
import org.llm4s.speech.config.STTConfig
import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.speech.stt.{ STTError, STTOptions }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class AzureSTTClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = STTConfig("azure", "en-US", "azure-key", "https://eastus.stt.speech.microsoft.com", Some("eastus"))

  private def wavAt(rate: Int): Array[Byte] = {
    val pcm = Array.fill[Byte](3200)(5)
    WavFileGenerator.createWavHeader(pcm.length, AudioMeta(rate, 1, 16)).toOption.get ++ pcm
  }

  private def recognised(text: String) =
    StubHttpClient.json(200, s"""{"RecognitionStatus":"Success","DisplayText":"$text","Offset":0,"Duration":1}""")

  "AzureSTTClient" should "POST the audio bytes and return the DisplayText" in {
    val audio  = wavAt(16000)
    val http   = recognised("Hello world.")
    val result = new AzureSTTClient(cfg, http).transcribe(AudioInput.BytesAudio(audio, 16000)).toOption.get

    result.text shouldBe "Hello world."
    result.language shouldBe Some("en-US")
    val request = http.only
    request.url shouldBe
      "https://eastus.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=en-US&format=simple"
    request.headers("Ocp-Apim-Subscription-Key") shouldBe "azure-key"
    request.headers("Content-Type") shouldBe "audio/wav; codecs=audio/pcm; samplerate=16000"
    request.body shouldBe audio
  }

  it should "take the sample rate from the WAV header, not a hard-coded 16 kHz" in {
    val http = recognised("hi")

    new AzureSTTClient(cfg, http).transcribe(AudioInput.BytesAudio(wavAt(44100), 8000))

    http.only.headers("Content-Type") shouldBe "audio/wav; codecs=audio/pcm; samplerate=44100"
  }

  it should "fall back to the declared sample rate for data without a WAV header, then to 16 kHz" in {
    val http = recognised("hi")
    val raw  = Array.fill[Byte](100)(1)

    new AzureSTTClient(cfg, http).transcribe(AudioInput.BytesAudio(raw, 8000))
    new AzureSTTClient(cfg, http).transcribe(AudioInput.StreamAudio(new java.io.ByteArrayInputStream(raw), 22050))

    http.requests(0).headers("Content-Type") should endWith("samplerate=8000")
    http.requests(1).headers("Content-Type") should endWith("samplerate=22050")
  }

  it should "read file input and use the language from STTOptions" in {
    val file = Files.createTempFile("llm4s-azure-stt-spec-", ".wav")
    try {
      val audio = wavAt(16000)
      Files.write(file, audio)
      val http = recognised("Bonjour")

      new AzureSTTClient(cfg, http).transcribe(AudioInput.FileAudio(file), STTOptions(language = Some("fr-FR")))

      http.only.url should include("language=fr-FR")
      http.only.body shouldBe audio
      http.only.headers("Content-Type") should endWith("samplerate=16000")
    } finally Files.deleteIfExists(file)
  }

  it should "map NoMatch, InitialSilenceTimeout and unknown statuses to ProcessingFailed" in {
    def run(status: String) =
      new AzureSTTClient(cfg, StubHttpClient.json(200, s"""{"RecognitionStatus":"$status"}"""))
        .transcribe(AudioInput.BytesAudio(wavAt(16000), 16000))
        .left
        .toOption
        .get

    run("NoMatch") shouldBe a[STTError.ProcessingFailed]
    run("NoMatch").message should include("no speech")
    run("InitialSilenceTimeout").message should include("silence")
    run("Error").message should include("status: Error")
  }

  it should "fail on a Success status with no text, and on a body that is not JSON" in {
    def run(body: String) =
      new AzureSTTClient(cfg, StubHttpClient.json(200, body))
        .transcribe(AudioInput.BytesAudio(wavAt(16000), 16000))
        .left
        .toOption
        .get

    run("""{"RecognitionStatus":"Success","DisplayText":""}""").message should include("empty transcription")
    run("<html>").message should include("Failed to parse Azure STT response")
  }

  it should "report its name and formats" in {
    val client = new AzureSTTClient(cfg, new StubHttpClient())
    client.name shouldBe "azure-stt"
    client.supportedFormats shouldBe List("audio/wav")
  }
}
