package org.llm4s.speech

import org.llm4s.error.{ AuthenticationError, ConfigurationError, NetworkError, RateLimitError }
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
import org.llm4s.speech.cloud.{
  AzureSTTClient,
  AzureTTSClient,
  CloudSpeechError,
  ElevenLabsTTSClient,
  OpenAISTTClient,
  OpenAITTSClient
}
import org.llm4s.speech.stt.STTOptions
import org.llm4s.speech.tts.TTSOptions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Mock-based integration tests for all cloud TTS and STT providers.
 * Runs under `sbt test` with no external dependencies or API keys.
 */
class CloudSpeechProviderIntegrationSpec extends AnyFlatSpec with Matchers {

  // Minimal valid MP3 magic bytes string (UTF-8 encoding of binary-safe content for mock)
  private val fakeAudioBytes: String  = "FAKE_AUDIO_BYTES_FOR_TESTING"
  private val sttJsonResponse: String = """{"text": "hello world"}"""

  // ===== OpenAITTSClient =====

  "OpenAITTSClient.synthesize" should "return non-empty Array[Byte] for a 200 OK response" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = OpenAITTSClient.forTest("test-key", mock)

    val result = client.synthesize("hello", TTSOptions())

    result.isRight shouldBe true
    result.toOption.get.data should not be empty
  }

  it should "use the provided voice in the request body" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = OpenAITTSClient.forTest("test-key", mock)

    client.synthesize("hello", TTSOptions(voice = Some("nova")))

    mock.lastBody.get should include("nova")
  }

  it should "default to 'alloy' voice when no voice option is set" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = OpenAITTSClient.forTest("test-key", mock)

    client.synthesize("hello", TTSOptions())

    mock.lastBody.get should include("alloy")
  }

  it should "set Authorization header with Bearer token" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = OpenAITTSClient.forTest("sk-test-key-123", mock)

    client.synthesize("hello", TTSOptions())

    mock.lastHeaders.get("Authorization") shouldBe "Bearer sk-test-key-123"
  }

  it should "map HTTP 401 to AuthenticationError" in {
    val mock401 = new MockHttpClient(HttpResponse(401, """{"error": "invalid api key"}"""))
    val client  = OpenAITTSClient.forTest("bad-key", mock401)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "map HTTP 403 to AuthenticationError" in {
    val mock403 = new MockHttpClient(HttpResponse(403, """{"error": "forbidden"}"""))
    val client  = OpenAITTSClient.forTest("bad-key", mock403)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError" in {
    val mock429 = new MockHttpClient(HttpResponse(429, """{"error": "rate limit exceeded"}"""))
    val client  = OpenAITTSClient.forTest("test-key", mock429)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[RateLimitError]
  }

  it should "map network exception to NetworkError" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = OpenAITTSClient.forTest("test-key", failing)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[NetworkError]
  }

  it should "have the correct provider name" in {
    val client = OpenAITTSClient("test-key")
    client.name shouldBe "openai-tts"
  }

  // ===== ElevenLabsTTSClient =====

  "ElevenLabsTTSClient.synthesize" should "return non-empty Array[Byte] for a 200 OK response" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = ElevenLabsTTSClient.forTest("test-key", mock)

    val result = client.synthesize("hello", TTSOptions())

    result.isRight shouldBe true
    result.toOption.get.data should not be empty
  }

  it should "include the voice ID in the request URL" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = ElevenLabsTTSClient.forTest("test-key", mock, voiceId = "voice-123")

    client.synthesize("hello", TTSOptions())

    mock.lastUrl.get should include("voice-123")
  }

  it should "set xi-api-key header" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = ElevenLabsTTSClient.forTest("el-test-key-xyz", mock)

    client.synthesize("hello", TTSOptions())

    mock.lastHeaders.get("xi-api-key") shouldBe "el-test-key-xyz"
  }

  it should "map HTTP 401 to AuthenticationError" in {
    val mock401 = new MockHttpClient(HttpResponse(401, """{"detail": "invalid_api_key"}"""))
    val client  = ElevenLabsTTSClient.forTest("bad-key", mock401)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError" in {
    val mock429 = new MockHttpClient(HttpResponse(429, """{"detail": "rate_limit"}"""))
    val client  = ElevenLabsTTSClient.forTest("test-key", mock429)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[RateLimitError]
  }

  it should "map network exception to NetworkError" in {
    val failing = new FailingHttpClient(new java.net.ConnectException("connection refused"))
    val client  = ElevenLabsTTSClient.forTest("test-key", failing)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[NetworkError]
  }

  it should "have the correct provider name" in {
    val client = ElevenLabsTTSClient("test-key")
    client.name shouldBe "elevenlabs-tts"
  }

  // ===== AzureTTSClient =====

  "AzureTTSClient.synthesize" should "return non-empty Array[Byte] for a 200 OK response" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = AzureTTSClient.forTest("test-sub-key", "eastus", mock)

    val result = client.synthesize("hello", TTSOptions())

    result.isRight shouldBe true
    result.toOption.get.data should not be empty
  }

  it should "include SSML with the text in the request body" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = AzureTTSClient.forTest("test-sub-key", "eastus", mock)

    client.synthesize("hello world", TTSOptions())

    mock.lastBody.get should include("hello world")
  }

  it should "set Ocp-Apim-Subscription-Key header" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = AzureTTSClient.forTest("my-sub-key-123", "eastus", mock)

    client.synthesize("hello", TTSOptions())

    mock.lastHeaders.get("Ocp-Apim-Subscription-Key") shouldBe "my-sub-key-123"
  }

  it should "use a custom voice when provided in TTSOptions" in {
    val mock   = new MockHttpClient(HttpResponse(200, fakeAudioBytes))
    val client = AzureTTSClient.forTest("test-key", "eastus", mock)

    client.synthesize("hi", TTSOptions(voice = Some("en-US-GuyNeural")))

    mock.lastBody.get should include("en-US-GuyNeural")
  }

  it should "map HTTP 401 to AuthenticationError" in {
    val mock401 = new MockHttpClient(HttpResponse(401, "Unauthorized"))
    val client  = AzureTTSClient.forTest("bad-key", "eastus", mock401)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError" in {
    val mock429 = new MockHttpClient(HttpResponse(429, "Too Many Requests"))
    val client  = AzureTTSClient.forTest("test-key", "eastus", mock429)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[RateLimitError]
  }

  it should "map network exception to NetworkError" in {
    val failing = new FailingHttpClient(new java.io.IOException("timeout"))
    val client  = AzureTTSClient.forTest("test-key", "eastus", failing)

    val result = client.synthesize("hello", TTSOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[NetworkError]
  }

  it should "have the correct provider name" in {
    val client = AzureTTSClient("sub-key", "eastus")
    client.name shouldBe "azure-tts"
  }

  // ===== OpenAISTTClient =====

  "OpenAISTTClient.transcribe" should "return non-empty String for a 200 OK response" in {
    val mock   = new MockHttpClient(HttpResponse(200, sttJsonResponse))
    val client = OpenAISTTClient.forTest("test-key", mock)
    val input  = AudioInput.BytesAudio(Array[Byte](0x52, 0x49, 0x46, 0x46), 16000)

    val result = client.transcribe(input, STTOptions())

    result.isRight shouldBe true
    result.toOption.get.text should not be empty
    result.toOption.get.text shouldBe "hello world"
  }

  it should "parse the transcription text from JSON response" in {
    val responseJson = """{"text": "the quick brown fox"}"""
    val mock         = new MockHttpClient(HttpResponse(200, responseJson))
    val client       = OpenAISTTClient.forTest("test-key", mock)
    val input        = AudioInput.BytesAudio(Array[Byte](0, 1, 2, 3), 16000)

    val result = client.transcribe(input, STTOptions())

    result.toOption.get.text shouldBe "the quick brown fox"
  }

  it should "set Authorization header with Bearer token" in {
    val mock   = new MockHttpClient(HttpResponse(200, sttJsonResponse))
    val client = OpenAISTTClient.forTest("sk-abc-123", mock)
    val input  = AudioInput.BytesAudio(Array[Byte](0, 1, 2, 3), 16000)

    client.transcribe(input, STTOptions())

    mock.lastHeaders.get("Authorization") shouldBe "Bearer sk-abc-123"
  }

  it should "map HTTP 401 to AuthenticationError" in {
    val mock401 = new MockHttpClient(HttpResponse(401, """{"error": {"message": "invalid api key"}}"""))
    val client  = OpenAISTTClient.forTest("bad-key", mock401)
    val input   = AudioInput.BytesAudio(Array[Byte](0, 1, 2, 3), 16000)

    val result = client.transcribe(input, STTOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError" in {
    val mock429 = new MockHttpClient(HttpResponse(429, """{"error": {"message": "rate limit"}}"""))
    val client  = OpenAISTTClient.forTest("test-key", mock429)
    val input   = AudioInput.BytesAudio(Array[Byte](0, 1, 2, 3), 16000)

    val result = client.transcribe(input, STTOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[RateLimitError]
  }

  it should "map network exception to NetworkError" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = OpenAISTTClient.forTest("test-key", failing)
    val input   = AudioInput.BytesAudio(Array[Byte](0, 1, 2, 3), 16000)

    val result = client.transcribe(input, STTOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[NetworkError]
  }

  it should "have the correct provider name" in {
    val client = OpenAISTTClient("test-key")
    client.name shouldBe "openai-whisper"
  }

  it should "list supported audio formats" in {
    val client = OpenAISTTClient("test-key")
    client.supportedFormats should contain("audio/wav")
    client.supportedFormats should contain("audio/mp3")
  }

  // ===== AzureSTTClient =====

  "AzureSTTClient.transcribe" should "return non-empty String for a 200 OK response" in {
    val azureResponse = """{"DisplayText": "hello world", "RecognitionStatus": "Success"}"""
    val mock          = new MockHttpClient(HttpResponse(200, azureResponse))
    val client        = AzureSTTClient.forTest("test-sub-key", "eastus", mock)
    val input         = AudioInput.BytesAudio(Array[Byte](0x52, 0x49, 0x46, 0x46), 16000)

    val result = client.transcribe(input, STTOptions())

    result.isRight shouldBe true
    result.toOption.get.text should not be empty
    result.toOption.get.text shouldBe "hello world"
  }

  it should "set Ocp-Apim-Subscription-Key header" in {
    val azureResponse = """{"DisplayText": "test", "RecognitionStatus": "Success"}"""
    val mock          = new MockHttpClient(HttpResponse(200, azureResponse))
    val client        = AzureSTTClient.forTest("my-sub-key-456", "eastus", mock)
    val input         = AudioInput.BytesAudio(Array[Byte](0, 1, 2, 3), 16000)

    client.transcribe(input, STTOptions())

    mock.lastHeaders.get("Ocp-Apim-Subscription-Key") shouldBe "my-sub-key-456"
  }

  it should "map HTTP 401 to AuthenticationError" in {
    val mock401 = new MockHttpClient(HttpResponse(401, "Authentication failed"))
    val client  = AzureSTTClient.forTest("bad-key", "eastus", mock401)
    val input   = AudioInput.BytesAudio(Array[Byte](0, 1, 2, 3), 16000)

    val result = client.transcribe(input, STTOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError" in {
    val mock429 = new MockHttpClient(HttpResponse(429, "Rate limit exceeded"))
    val client  = AzureSTTClient.forTest("test-key", "eastus", mock429)
    val input   = AudioInput.BytesAudio(Array[Byte](0, 1, 2, 3), 16000)

    val result = client.transcribe(input, STTOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[RateLimitError]
  }

  it should "map network exception to NetworkError" in {
    val failing = new FailingHttpClient(new java.io.IOException("timeout"))
    val client  = AzureSTTClient.forTest("test-key", "eastus", failing)
    val input   = AudioInput.BytesAudio(Array[Byte](0, 1, 2, 3), 16000)

    val result = client.transcribe(input, STTOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[NetworkError]
  }

  it should "have the correct provider name" in {
    val client = AzureSTTClient("sub-key", "eastus")
    client.name shouldBe "azure-stt"
  }

  it should "list supported audio formats" in {
    val client = AzureSTTClient("sub-key", "eastus")
    client.supportedFormats should contain("audio/wav")
  }

  // ===== CloudSpeechError utility tests =====

  "CloudSpeechError.fromHttpStatus" should "return AuthenticationError for 401" in {
    val err = CloudSpeechError.fromHttpStatus(401, "openai-tts", "Unauthorized")
    err shouldBe an[AuthenticationError]
  }

  it should "return AuthenticationError for 403" in {
    val err = CloudSpeechError.fromHttpStatus(403, "openai-tts", "Forbidden")
    err shouldBe an[AuthenticationError]
  }

  it should "return RateLimitError for 429" in {
    val err = CloudSpeechError.fromHttpStatus(429, "openai-tts", "Too Many Requests")
    err shouldBe an[RateLimitError]
  }

  it should "return ServiceError for 500" in {
    import org.llm4s.error.ServiceError
    val err = CloudSpeechError.fromHttpStatus(500, "openai-tts", "Internal Server Error")
    err shouldBe an[ServiceError]
  }

  "CloudSpeechError.fromThrowable" should "return NetworkError wrapping the cause" in {
    val cause = new java.io.IOException("connection refused")
    val err   = CloudSpeechError.fromThrowable(cause, "https://api.openai.com")
    err shouldBe an[NetworkError]
    err.cause shouldBe Some(cause)
  }

  "CloudSpeechError.missingKey" should "return a ConfigurationError listing the key name" in {
    val err = CloudSpeechError.missingKey("elevenlabs-tts", "ELEVENLABS_API_KEY")
    err shouldBe an[ConfigurationError]
    err.missingKeys should contain("ELEVENLABS_API_KEY")
  }
}
