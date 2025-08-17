package org.llm4s.imageprocessing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{ Files, Paths }
import java.time.Instant

class ImageModelsTest extends AnyFlatSpec with Matchers {

  "ImageMetadata" should "create with default values" in {
    val metadata = ImageMetadata()
    metadata.originalPath shouldBe None
    metadata.createdAt should not be null
    metadata.processedAt should not be null
    metadata.operations shouldBe List.empty
    metadata.colorSpace shouldBe None
    metadata.dpi shouldBe None
    metadata.hasAlpha shouldBe false
    metadata.compressionRatio shouldBe None
  }

  it should "create with custom values" in {
    val now = Instant.now()
    val metadata = ImageMetadata(
      originalPath = Some("/path/to/image.jpg"),
      createdAt = now,
      processedAt = now,
      operations = List(ImageOperation.Resize(100, 100)),
      colorSpace = Some("RGB"),
      dpi = Some(300),
      hasAlpha = true,
      compressionRatio = Some(0.8)
    )

    metadata.originalPath shouldBe Some("/path/to/image.jpg")
    metadata.createdAt shouldBe now
    metadata.processedAt shouldBe now
    metadata.operations shouldBe List(ImageOperation.Resize(100, 100))
    metadata.colorSpace shouldBe Some("RGB")
    metadata.dpi shouldBe Some(300)
    metadata.hasAlpha shouldBe true
    metadata.compressionRatio shouldBe Some(0.8)
  }

  "ProcessedImage" should "create with valid data" in {
    val data           = Array[Byte](1, 2, 3, 4)
    val metadata       = ImageMetadata()
    val processedImage = ProcessedImage(data, ImageFormat.JPEG, 100, 100, metadata)

    processedImage.data shouldBe data
    processedImage.format shouldBe ImageFormat.JPEG
    processedImage.width shouldBe 100
    processedImage.height shouldBe 100
    processedImage.metadata shouldBe metadata
    processedImage.sizeInBytes shouldBe 4
  }

  it should "save to file successfully" in {
    val tempFile = Files.createTempFile("test", ".jpg")
    try {
      val data           = Array[Byte](1, 2, 3, 4)
      val metadata       = ImageMetadata()
      val processedImage = ProcessedImage(data, ImageFormat.JPEG, 100, 100, metadata)

      val result = processedImage.saveToFile(tempFile)
      result shouldBe Right(())
      Files.exists(tempFile) shouldBe true
      Files.readAllBytes(tempFile) shouldBe data
    } finally
      Files.deleteIfExists(tempFile)
  }

  it should "fail to save with invalid path" in {
    val data           = Array[Byte](1, 2, 3, 4)
    val metadata       = ImageMetadata()
    val processedImage = ProcessedImage(data, ImageFormat.JPEG, 100, 100, metadata)

    val result = processedImage.saveToFile(Paths.get("/invalid/path/that/does/not/exist/file.jpg"))
    result.isLeft shouldBe true
  }

  it should "prevent path traversal attacks" in {
    val data           = Array[Byte](1, 2, 3, 4)
    val metadata       = ImageMetadata()
    val processedImage = ProcessedImage(data, ImageFormat.JPEG, 100, 100, metadata)

    val maliciousPath = Paths.get("../../../etc/passwd")
    val result        = processedImage.saveToFile(maliciousPath)
    result.isLeft shouldBe true
  }

  "ImageAnalysisResult" should "create with all fields" in {
    val metadata = ImageMetadata()
    val objects  = List(DetectedObject("person", 0.9, BoundingBox(10, 10, 50, 100)))
    val emotions = List(DetectedEmotion("happy", 0.8))

    val result = ImageAnalysisResult(
      description = "A person in the image",
      confidence = 0.85,
      tags = List("person", "portrait"),
      objects = objects,
      emotions = emotions,
      text = Some("Hello World"),
      metadata = metadata
    )

    result.description shouldBe "A person in the image"
    result.confidence shouldBe 0.85
    result.tags shouldBe List("person", "portrait")
    result.objects shouldBe objects
    result.emotions shouldBe emotions
    result.text shouldBe Some("Hello World")
    result.metadata shouldBe metadata
  }

  "DetectedObject" should "create with bounding box" in {
    val obj = DetectedObject("car", 0.95, BoundingBox(100, 200, 300, 150))

    obj.label shouldBe "car"
    obj.confidence shouldBe 0.95
    obj.boundingBox.x shouldBe 100
    obj.boundingBox.y shouldBe 200
    obj.boundingBox.width shouldBe 300
    obj.boundingBox.height shouldBe 150
  }

  "DetectedEmotion" should "create with emotion and confidence" in {
    val emotion = DetectedEmotion("sad", 0.7)

    emotion.emotion shouldBe "sad"
    emotion.confidence shouldBe 0.7
  }

  "BoundingBox" should "create with coordinates" in {
    val box = BoundingBox(10, 20, 100, 200)

    box.x shouldBe 10
    box.y shouldBe 20
    box.width shouldBe 100
    box.height shouldBe 200
  }

  "ImageEmbedding" should "calculate cosine similarity" in {
    val vector1 = Array[Float](1.0f, 0.0f, 0.0f)
    val vector2 = Array[Float](1.0f, 0.0f, 0.0f)
    val vector3 = Array[Float](0.0f, 1.0f, 0.0f)

    val embedding1 = ImageEmbedding(vector1, 3, "test-model", "/path1", ImageMetadata())
    val embedding2 = ImageEmbedding(vector2, 3, "test-model", "/path2", ImageMetadata())
    val embedding3 = ImageEmbedding(vector3, 3, "test-model", "/path3", ImageMetadata())

    // Same vectors should have similarity of 1.0
    embedding1.cosineSimilarity(embedding2) shouldBe 1.0 +- 0.001

    // Orthogonal vectors should have similarity of 0.0
    embedding1.cosineSimilarity(embedding3) shouldBe 0.0 +- 0.001
  }

  it should "throw exception for different dimensions" in {
    val vector1 = Array[Float](1.0f, 0.0f)
    val vector2 = Array[Float](1.0f, 0.0f, 0.0f)

    val embedding1 = ImageEmbedding(vector1, 2, "test-model", "/path1", ImageMetadata())
    val embedding2 = ImageEmbedding(vector2, 3, "test-model", "/path2", ImageMetadata())

    an[IllegalArgumentException] should be thrownBy {
      embedding1.cosineSimilarity(embedding2)
    }
  }

  "ImageFormat" should "have correct string representations" in {
    ImageFormat.JPEG.toString shouldBe "JPEG"
    ImageFormat.PNG.toString shouldBe "PNG"
    ImageFormat.GIF.toString shouldBe "GIF"
    ImageFormat.WEBP.toString shouldBe "WEBP"
  }

  "ImageOperation" should "create resize operation" in {
    val resize = ImageOperation.Resize(100, 200)
    resize.width shouldBe 100
    resize.height shouldBe 200
  }

  it should "create crop operation" in {
    val crop = ImageOperation.Crop(10, 20, 100, 200)
    crop.x shouldBe 10
    crop.y shouldBe 20
    crop.width shouldBe 100
    crop.height shouldBe 200
  }

  it should "create rotate operation" in {
    val rotate = ImageOperation.Rotate(90.0)
    rotate.degrees shouldBe 90.0
  }

  it should "create blur operation" in {
    val blur = ImageOperation.Blur(5.0)
    blur.radius shouldBe 5.0
  }

  it should "create brightness adjustment" in {
    val brightness = ImageOperation.Brightness(50)
    brightness.level shouldBe 50
  }

  it should "create contrast adjustment" in {
    val contrast = ImageOperation.Contrast(25)
    contrast.level shouldBe 25
  }
}
