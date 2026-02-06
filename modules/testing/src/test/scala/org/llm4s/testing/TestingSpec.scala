package org.llm4s.testing

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model._
import org.llm4s.error.ValidationError
import org.llm4s.testing.model.Interaction
import java.nio.file.{ Files, Paths }

class MockLLMClientSpec extends AnyFunSpec with Matchers {

  private def createResponse(content: String): Completion = Completion(
    id = "mock-id",
    created = System.currentTimeMillis(),
    content = content,
    model = "mock-model",
    message = AssistantMessage(content)
  )

  describe("MockLLMClient") {
    it("should return the configured response") {
      val client = new MockLLMClient()
      val conv   = Conversation(Seq(UserMessage("Hello")))
      val resp   = createResponse("Hi there")

      client.whenExactly(conv, CompletionOptions())(resp)

      client.complete(conv).map(_.content) shouldBe Right("Hi there")
    }

    it("should return error when no expectation matches") {
      val client = new MockLLMClient()
      val conv   = Conversation(Seq(UserMessage("Hello")))

      client.complete(conv).isLeft shouldBe true
    }

    it("should support alwaysReturn for simple cases") {
      val client = new MockLLMClient()
      val resp   = createResponse("Always this")

      client.alwaysReturn(resp)

      client.complete(Conversation(Seq(UserMessage("Anything")))).map(_.content) shouldBe Right("Always this")
      client.complete(Conversation(Seq(UserMessage("Something else")))).map(_.content) shouldBe Right("Always this")
    }

    it("should support whenContains for partial matching") {
      val client = new MockLLMClient()
      val resp   = createResponse("Found hello!")

      client.whenContains("hello").thenReturn(resp)

      client.complete(Conversation(Seq(UserMessage("Say hello world")))).map(_.content) shouldBe Right("Found hello!")
      client.complete(Conversation(Seq(UserMessage("goodbye")))).isLeft shouldBe true
    }

    it("should support error simulation with thenFail") {
      val client = new MockLLMClient()
      val error  = ValidationError("test", "Simulated error")

      client.whenContains("fail").thenFail(error)

      val result = client.complete(Conversation(Seq(UserMessage("Please fail"))))
      result.isLeft shouldBe true
    }

    it("should support reset to clear expectations") {
      val client = new MockLLMClient()
      val resp   = createResponse("Response")

      client.alwaysReturn(resp)
      client.complete(Conversation(Seq(UserMessage("Test")))).isRight shouldBe true

      client.reset()
      client.complete(Conversation(Seq(UserMessage("Test")))).isLeft shouldBe true
    }
  }
}

class PlaybackLLMClientSpec extends AnyFunSpec with Matchers {

  private def createResponse(content: String): Completion = Completion(
    id = "playback-id",
    created = System.currentTimeMillis(),
    content = content,
    model = "mock-model",
    message = AssistantMessage(content)
  )

  describe("PlaybackLLMClient") {
    it("should replay recorded interactions with strict matching") {
      val conv        = Conversation(Seq(UserMessage("Test")))
      val resp        = createResponse("Recorded Response")
      val interaction = Interaction(conv, CompletionOptions(), resp)
      val playback    = PlaybackLLMClient.fromRecordings(List(interaction))

      playback.complete(conv).map(_.content) shouldBe Right("Recorded Response")
    }

    it("should fail when no match found in strict mode") {
      val conv        = Conversation(Seq(UserMessage("Test")))
      val resp        = createResponse("Response")
      val interaction = Interaction(conv, CompletionOptions(), resp)
      val playback    = PlaybackLLMClient.fromRecordings(List(interaction))

      val differentConv = Conversation(Seq(UserMessage("Different")))
      playback.complete(differentConv).isLeft shouldBe true
    }

    it("should match content only in ContentOnly mode") {
      val conv        = Conversation(Seq(UserMessage("Test")))
      val resp        = createResponse("Response")
      val interaction = Interaction(conv, CompletionOptions(), resp)
      val playback    = PlaybackLLMClient.fromRecordings(List(interaction), MatchingMode.ContentOnly)

      // Same content, different options should still match
      val differentOpts = CompletionOptions(temperature = 0.5)
      playback.complete(conv, differentOpts).map(_.content) shouldBe Right("Response")
    }

    it("should handle lenient matching with whitespace differences") {
      val conv        = Conversation(Seq(UserMessage("Test   message")))
      val resp        = createResponse("Response")
      val interaction = Interaction(conv, CompletionOptions(), resp)
      val playback    = PlaybackLLMClient.fromRecordings(List(interaction), MatchingMode.Lenient)

      val normalizedConv = Conversation(Seq(UserMessage("Test message")))
      playback.complete(normalizedConv).map(_.content) shouldBe Right("Response")
    }

    it("should report recording count") {
      val interactions = List(
        Interaction(Conversation(Seq(UserMessage("1"))), CompletionOptions(), createResponse("R1")),
        Interaction(Conversation(Seq(UserMessage("2"))), CompletionOptions(), createResponse("R2"))
      )
      val playback = PlaybackLLMClient.fromRecordings(interactions)
      playback.recordingCount shouldBe 2
    }
  }
}

class RecordingPlaybackIntegrationSpec extends AnyFunSpec with Matchers {

  private def createResponse(content: String): Completion = Completion(
    id = "integration-id",
    created = System.currentTimeMillis(),
    content = content,
    model = "mock-model",
    message = AssistantMessage(content)
  )

  describe("Recording and Playback Integration") {
    it("should record and replay interactions") {
      val mockBase = new MockLLMClient()
      val conv     = Conversation(Seq(UserMessage("Test")))
      val resp     = createResponse("Recorded Response")
      mockBase.whenExactly(conv, CompletionOptions())(resp)

      // Recording Phase
      val recordingClient = new RecordingLLMClient(mockBase)
      recordingClient.complete(conv).map(_.content) shouldBe Right("Recorded Response")

      val tempFile = Files.createTempFile("llm4s-recording", ".json").toString
      recordingClient.save(tempFile)

      // Playback Phase
      val playbackClient = PlaybackLLMClient.fromFile(tempFile)
      playbackClient.complete(conv).map(_.content) shouldBe Right("Recorded Response")

      // Cleanup
      Files.delete(Paths.get(tempFile))
    }

    it("should support scrubbing when saving") {
      val mockBase = new MockLLMClient()
      val conv     = Conversation(Seq(UserMessage("Use key sk-abc123def456ghi789jkl012mno345pqr678stu901vwx234")))
      val resp     = createResponse("Response")
      mockBase.alwaysReturn(resp)

      val recordingClient = new RecordingLLMClient(mockBase)
      recordingClient.complete(conv)

      val tempFile = Files.createTempFile("llm4s-scrubbed", ".json").toString
      recordingClient.saveWithScrubbing(tempFile, Scrubber.default)

      val savedContent = new String(Files.readAllBytes(Paths.get(tempFile)))
      (savedContent should not).include("sk-abc123")
      savedContent should include("[OPENAI_API_KEY]")

      Files.delete(Paths.get(tempFile))
    }

    it("should expose recorded interactions") {
      val mockBase = new MockLLMClient()
      mockBase.alwaysReturn(createResponse("Response"))

      val recorder = new RecordingLLMClient(mockBase)
      recorder.complete(Conversation(Seq(UserMessage("1"))))
      recorder.complete(Conversation(Seq(UserMessage("2"))))

      recorder.getRecordings.size shouldBe 2
    }

    it("should support clearing recordings") {
      val mockBase = new MockLLMClient()
      mockBase.alwaysReturn(createResponse("Response"))

      val recorder = new RecordingLLMClient(mockBase)
      recorder.complete(Conversation(Seq(UserMessage("Test"))))
      recorder.getRecordings.size shouldBe 1

      recorder.clear()
      recorder.getRecordings.size shouldBe 0
    }
  }
}
