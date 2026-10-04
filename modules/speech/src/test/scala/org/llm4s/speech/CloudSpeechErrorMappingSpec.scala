package org.llm4s.speech

import org.llm4s.error._
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.config.{ STTConfig, TTSConfig }
import org.llm4s.speech.stt.provider.{ AzureSTTClient, OpenAISTTClient }
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * The same HTTP failures, through every cloud client: status codes map as the chat providers' do
 * (via HttpErrorMapper) and transport failures from the HTTP layer pass through unchanged.
 */
class CloudSpeechErrorMappingSpec extends AnyFlatSpec with Matchers {

  private val tts = TTSConfig("p", "m", "v", "sk-secret-123", "https://tts.example", Some("eastus"))
  private val stt = STTConfig("p", "m", "sk-secret-123", "https://stt.example", Some("eastus"))
  private val wav = Array.fill[Byte](64)(1)

  private val clients: Seq[(String, Llm4sHttpClient => Result[Any])] = Seq(
    "OpenAITTSClient"     -> (h => new OpenAITTSClient(tts, h).synthesize("hello")),
    "ElevenLabsTTSClient" -> (h => new ElevenLabsTTSClient(tts, h).synthesize("hello")),
    "AzureTTSClient"      -> (h => new AzureTTSClient(tts, h).synthesize("hello")),
    "OpenAISTTClient"     -> (h => new OpenAISTTClient(stt, h).transcribe(AudioInput.BytesAudio(wav, 16000))),
    "AzureSTTClient"      -> (h => new AzureSTTClient(stt, h).transcribe(AudioInput.BytesAudio(wav, 16000)))
  )

  for ((name, call) <- clients) {

    s"$name" should "map HTTP 401 to AuthenticationError" in {
      val error = call(StubHttpClient.json(401, """{"error":{"message":"bad key"}}""")).left.toOption.get
      error shouldBe a[AuthenticationError]
    }

    it should "map HTTP 429 to RateLimitError carrying Retry-After" in {
      val http  = new StubHttpClient(status = 429, responseHeaders = Map("retry-after" -> Seq("7")))
      val error = call(http).left.toOption.get
      error shouldBe a[RateLimitError]
      error.asInstanceOf[RateLimitError].retryAfter shouldBe Some(7.seconds)
    }

    it should "map HTTP 400 to ValidationError and 500 to ServiceError" in {
      call(StubHttpClient.json(400, "bad request")).left.toOption.get shouldBe a[ValidationError]
      val service = call(StubHttpClient.json(500, "boom")).left.toOption.get
      service shouldBe a[ServiceError]
      service.asInstanceOf[ServiceError].httpStatus shouldBe 500
    }

    it should "pass a network failure through as NetworkError" in {
      val failure = NetworkError("connection refused", None, "https://tts.example")
      call(new StubHttpClient(failure = Some(failure))).left.toOption.get shouldBe failure
    }

    it should "pass a timeout through as TimeoutError" in {
      val failure = TimeoutError("timed out", 60.seconds, "POST https://tts.example")
      call(new StubHttpClient(failure = Some(failure))).left.toOption.get shouldBe failure
    }

    it should "never put the API key in an error" in {
      val error = call(StubHttpClient.json(401, "denied")).left.toOption.get
      (error.message should not).include("sk-secret-123")
      error.context.values.foreach(v => (v should not).include("sk-secret-123"))
    }
  }
}
