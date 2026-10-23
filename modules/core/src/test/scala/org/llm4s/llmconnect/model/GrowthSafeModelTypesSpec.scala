package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/**
 * `Conversation`, `SystemMessage`, `ToolCall`, `ResponseFormat.JsonSchema`, `EmbeddingVector`,
 * `MultimediaEmbeddingRequest` and the multimedia inputs are growth-prone (#2378): built through `apply`, changed
 * through `with*`, with a private constructor and `copy`.
 */
class GrowthSafeModelTypesSpec extends AnyFlatSpec with Matchers {

  "Conversation" should "replace its messages through withMessages" in {
    val conversation = Conversation(Seq(SystemMessage("s")))
    val replaced     = conversation.withMessages(Seq(UserMessage("u")))

    replaced shouldBe Conversation(Seq(UserMessage("u")))
    conversation.messages shouldBe Seq(SystemMessage("s"))
  }

  it should "keep its JSON" in {
    val conversation = Conversation(Seq(SystemMessage("s"), UserMessage("u")))
    read[Conversation](write(conversation)) shouldBe conversation
    ujson.read(write(Conversation(Seq(SystemMessage("s"))))).obj.keySet shouldBe Set("messages")
  }

  it should "still match positionally" in {
    (Conversation(Seq(SystemMessage("s"))) match { case Conversation(ms) => ms.size }) shouldBe 1
  }

  "SystemMessage" should "change its content through withContent" in {
    SystemMessage("a").withContent("b") shouldBe SystemMessage("b")
    SystemMessage("a").withContent("b").role shouldBe MessageRole.System
  }

  it should "keep its JSON" in {
    write(SystemMessage("hi")) shouldBe """{"$type":"SystemMessage","content":"hi"}"""
    read[SystemMessage]("""{"$type":"SystemMessage","content":"hi"}""") shouldBe SystemMessage("hi")
  }

  "ToolCall" should "change each field through its setter" in {
    val call    = ToolCall("1", "weather", ujson.Obj("city" -> "Paris"))
    val changed = call.withId("2").withName("time").withArguments(ujson.Obj("tz" -> "UTC"))

    changed shouldBe ToolCall("2", "time", ujson.Obj("tz" -> "UTC"))
    call.id shouldBe "1"
  }

  it should "keep its JSON" in {
    val call = ToolCall("1", "weather", ujson.Obj("city" -> "Paris"))
    write(call) shouldBe """{"id":"1","name":"weather","arguments":{"city":"Paris"}}"""
    read[ToolCall](write(call)) shouldBe call
  }

  "ResponseFormat.JsonSchema" should "default its name and strictness" in {
    val format = ResponseFormat.JsonSchema(ujson.Obj("type" -> "object"))
    format.name shouldBe "response"
    format.strict shouldBe true
  }

  it should "change each field through its setter" in {
    val format = ResponseFormat
      .JsonSchema(ujson.Obj())
      .withSchema(ujson.Obj("type" -> "string"))
      .withName("answer")
      .withStrict(false)

    format shouldBe ResponseFormat.JsonSchema(ujson.Obj("type" -> "string"), "answer", strict = false)
  }

  "EmbeddingVector" should "default its metadata" in {
    EmbeddingVector("id", Text, "m", 2, Array(1f, 2f)).meta shouldBe empty
  }

  it should "change each field through its setter" in {
    val values = Array(0.5f)
    val vector = EmbeddingVector("id", Text, "m", 2, Array(1f, 2f))
      .withId("other")
      .withModality(Image)
      .withModel("clip")
      .withDim(1)
      .withValues(values)
      .withMeta(Map("k" -> "v"))

    vector.id shouldBe "other"
    vector.modality shouldBe Image
    vector.model shouldBe "clip"
    vector.dim shouldBe 1
    vector.values shouldBe theSameInstanceAs(values)
    vector.meta shouldBe Map("k" -> "v")
  }

  "MultimediaEmbeddingRequest" should "default its metadata and change each field through its setter" in {
    val model   = EmbeddingModelConfig("m", 4)
    val request = MultimediaEmbeddingRequest(Seq(TextChunkInput(Seq("a"))), model, Text)
    request.meta shouldBe empty

    val other   = EmbeddingModelConfig("clip", 8)
    val image   = ImageInput(1, 1, 1, Array[Byte](1))
    val changed = request.withInputs(Seq(image)).withModel(other).withModality(Image).withMeta(Map("k" -> "v"))

    changed shouldBe MultimediaEmbeddingRequest(Seq(image), other, Image, Map("k" -> "v"))
  }

  "ImageInput" should "change each field through its setter" in {
    val data  = Array[Byte](1, 2, 3)
    val image = ImageInput(1, 1, 3, Array[Byte](0)).withWidth(4).withHeight(5).withChannels(1).withData(data)

    (image.width, image.height, image.channels) shouldBe ((4, 5, 1))
    image.data shouldBe theSameInstanceAs(data)
    image.bytesApprox shouldBe 3L
  }

  "AudioInput" should "change each field through its setter" in {
    val samples = Array(0.1f, 0.2f)
    val audio   = AudioInput(Array(0f), 8000).withSamples(samples).withSampleRate(16000)

    audio.samples shouldBe theSameInstanceAs(samples)
    audio.sampleRate shouldBe 16000
    audio.bytesApprox shouldBe 8L
  }

  "VideoInput" should "change each field through its setter" in {
    val frames = Seq(Array[Byte](1, 2), Array[Byte](3))
    val video = VideoInput(1, 1, 3, 24, Seq.empty)
      .withFrameWidth(2)
      .withFrameHeight(3)
      .withChannels(1)
      .withFps(30)
      .withFrames(frames)

    (video.frameWidth, video.frameHeight, video.channels, video.fps) shouldBe ((2, 3, 1, 30))
    video.frames shouldBe frames
    video.bytesApprox shouldBe 3L
  }

  "RawMediaInput" should "change each field through its setter" in {
    val data = Array[Byte](9)
    val raw  = RawMediaInput(Array.emptyByteArray, "image/png").withData(data).withMime("image/jpeg")

    raw.data shouldBe theSameInstanceAs(data)
    raw.mime shouldBe "image/jpeg"
    raw.bytesApprox shouldBe 1L
  }

  "The growth-prone model types" should "not expose their constructor or copy" in {
    assertTypeError("""new Conversation(Seq.empty)""")
    assertTypeError("""Conversation(Seq.empty).copy(messages = Seq.empty)""")
    assertTypeError("""new SystemMessage("s")""")
    assertTypeError("""SystemMessage("s").copy(content = "t")""")
    assertTypeError("""new ToolCall("1", "n", ujson.Obj())""")
    assertTypeError("""ToolCall("1", "n", ujson.Obj()).copy(id = "2")""")
    assertTypeError("""new ResponseFormat.JsonSchema(ujson.Obj(), "n", true)""")
    assertTypeError("""ResponseFormat.JsonSchema(ujson.Obj()).copy(strict = false)""")
    assertTypeError("""new EmbeddingVector("id", Text, "m", 1, Array(1f), Map.empty)""")
    assertTypeError("""new ImageInput(1, 1, 1, Array[Byte]())""")
    assertTypeError("""new AudioInput(Array(0f), 1)""")
    assertTypeError("""new VideoInput(1, 1, 1, 1, Seq.empty)""")
    assertTypeError("""new RawMediaInput(Array[Byte](), "m")""")
  }
}
