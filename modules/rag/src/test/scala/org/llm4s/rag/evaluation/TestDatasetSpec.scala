package org.llm4s.rag.evaluation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class TestDatasetSpec extends AnyFlatSpec with Matchers {

  "TestDataset" should "parse JSON with all fields" in {
    val json =
      """{
        |  "name": "test_dataset",
        |  "metadata": {"source": "manual", "version": "1.0"},
        |  "samples": [
        |    {
        |      "question": "What is the capital of France?",
        |      "answer": "Paris is the capital of France.",
        |      "contexts": ["Paris is the capital.", "Paris is in France."],
        |      "ground_truth": "The capital is Paris.",
        |      "metadata": {"category": "geography"}
        |    }
        |  ]
        |}""".stripMargin

    val result = TestDataset.fromJson(json)

    result.isRight shouldBe true
    val dataset = result.toOption.get

    dataset.name shouldBe "test_dataset"
    dataset.metadata should contain("source" -> "manual")
    dataset.metadata should contain("version" -> "1.0")
    dataset.samples.size shouldBe 1

    val sample = dataset.samples.head
    sample.question shouldBe "What is the capital of France?"
    sample.answer shouldBe "Paris is the capital of France."
    sample.contexts.size shouldBe 2
    sample.groundTruth shouldBe Some("The capital is Paris.")
    sample.metadata should contain("category" -> "geography")
  }

  it should "parse JSON with minimal fields" in {
    val json =
      """{
        |  "samples": [
        |    {
        |      "question": "Question",
        |      "answer": "Answer",
        |      "contexts": ["Context"]
        |    }
        |  ]
        |}""".stripMargin

    val result = TestDataset.fromJson(json)

    result.isRight shouldBe true
    val dataset = result.toOption.get

    dataset.name shouldBe "unnamed"
    dataset.metadata shouldBe empty
    dataset.samples.size shouldBe 1

    val sample = dataset.samples.head
    sample.groundTruth shouldBe None
    sample.metadata shouldBe empty
  }

  it should "parse JSON with null ground_truth" in {
    val json =
      """{
        |  "samples": [
        |    {
        |      "question": "Q",
        |      "answer": "A",
        |      "contexts": ["C"],
        |      "ground_truth": null
        |    }
        |  ]
        |}""".stripMargin

    val result = TestDataset.fromJson(json)

    result.isRight shouldBe true
    result.toOption.get.samples.head.groundTruth shouldBe None
  }

  it should "convert dataset to JSON" in {
    val dataset = TestDataset(
      name = "my_dataset",
      samples = Seq(
        EvalSample(
          question = "Q1",
          answer = "A1",
          contexts = Seq("C1"),
          groundTruth = Some("GT1"),
          metadata = Map("key" -> "value")
        )
      ),
      metadata = Map("author" -> "test")
    )

    val json = TestDataset.toJson(dataset)

    json should include("my_dataset")
    json should include("Q1")
    json should include("A1")
    json should include("C1")
    json should include("GT1")
    json should include("author")
    json should include("key")
  }

  it should "filter samples with ground truth" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(
        EvalSample("Q1", "A1", Seq("C1"), Some("GT1")),
        EvalSample("Q2", "A2", Seq("C2"), None),
        EvalSample("Q3", "A3", Seq("C3"), Some("GT3"))
      )
    )

    val filtered = dataset.withGroundTruth

    filtered.samples.size shouldBe 2
    filtered.samples.forall(_.groundTruth.isDefined) shouldBe true
  }

  it should "filter samples without ground truth" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(
        EvalSample("Q1", "A1", Seq("C1"), Some("GT1")),
        EvalSample("Q2", "A2", Seq("C2"), None)
      )
    )

    val filtered = dataset.withoutGroundTruth

    filtered.samples.size shouldBe 1
    filtered.samples.forall(_.groundTruth.isEmpty) shouldBe true
  }

  it should "take first n samples" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(
        EvalSample("Q1", "A1", Seq("C1")),
        EvalSample("Q2", "A2", Seq("C2")),
        EvalSample("Q3", "A3", Seq("C3"))
      )
    )

    val taken = dataset.take(2)

    taken.samples.size shouldBe 2
    taken.samples.map(_.question) shouldBe Seq("Q1", "Q2")
  }

  it should "sample random subset" in {
    val dataset = TestDataset(
      name = "test",
      samples = (1 to 10).map(i => EvalSample(s"Q$i", s"A$i", Seq(s"C$i")))
    )

    val sampled = dataset.sample(3, seed = 42)

    sampled.samples.size shouldBe 3
    // Same seed should give same results
    val sampled2 = dataset.sample(3, seed = 42)
    sampled.samples.map(_.question) shouldBe sampled2.samples.map(_.question)
  }

  it should "add metadata" in {
    val dataset = TestDataset.empty("test")

    val withMetadata = dataset.withMetadata("key", "value")

    withMetadata.metadata should contain("key" -> "value")
  }

  it should "create empty dataset" in {
    val dataset = TestDataset.empty("empty_test")

    dataset.name shouldBe "empty_test"
    dataset.samples shouldBe empty
    dataset.metadata shouldBe empty
  }

  it should "create single sample dataset" in {
    val dataset = TestDataset.single(
      question = "Q",
      answer = "A",
      contexts = Seq("C1", "C2"),
      groundTruth = Some("GT")
    )

    dataset.name shouldBe "single"
    dataset.samples.size shouldBe 1
    dataset.samples.head.question shouldBe "Q"
    dataset.samples.head.groundTruth shouldBe Some("GT")
  }

  it should "save and load dataset from file" in {
    val dataset = TestDataset(
      name = "file_test",
      samples = Seq(
        EvalSample("Q1", "A1", Seq("C1"), Some("GT1"))
      )
    )

    val tempFile = Files.createTempFile("test_dataset", ".json")
    try {
      val saveResult = TestDataset.save(dataset, tempFile.toString)
      saveResult.isRight shouldBe true

      val loadResult = TestDataset.fromJsonFile(tempFile.toString)
      loadResult.isRight shouldBe true

      val loaded = loadResult.toOption.get
      loaded.name shouldBe "file_test"
      loaded.samples.size shouldBe 1
      loaded.samples.head.question shouldBe "Q1"
    } finally Files.deleteIfExists(tempFile)
  }

  it should "fail gracefully for invalid JSON" in {
    val invalidJson = "not valid json at all"

    val result = TestDataset.fromJson(invalidJson)

    result.isLeft shouldBe true
  }

  it should "fail gracefully for missing file" in {
    val result = TestDataset.fromJsonFile("/nonexistent/path/file.json")

    result.isLeft shouldBe true
  }

  it should "round-trip JSON conversion" in {
    val original = TestDataset(
      name = "roundtrip",
      samples = Seq(
        EvalSample("Q1", "A1", Seq("C1", "C2"), Some("GT1"), Map("k1" -> "v1")),
        EvalSample("Q2", "A2", Seq("C3"), None)
      ),
      metadata = Map("version" -> "1.0")
    )

    val json   = TestDataset.toJson(original)
    val result = TestDataset.fromJson(json)

    result.isRight shouldBe true
    val loaded = result.toOption.get

    loaded.name shouldBe original.name
    loaded.samples.size shouldBe original.samples.size
    loaded.samples.head.question shouldBe original.samples.head.question
    loaded.samples.head.groundTruth shouldBe original.samples.head.groundTruth
    loaded.metadata shouldBe original.metadata
  }
}
