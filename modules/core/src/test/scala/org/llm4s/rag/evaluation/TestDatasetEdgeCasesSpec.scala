package org.llm4s.rag.evaluation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TestDatasetEdgeCasesSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // TestDataset.filter with custom predicate
  // =========================================================================

  "TestDataset.filter" should "apply custom predicate" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(
        EvalSample("Q1", "A1", Seq("C1"), metadata = Map("category" -> "science")),
        EvalSample("Q2", "A2", Seq("C2"), metadata = Map("category" -> "history")),
        EvalSample("Q3", "A3", Seq("C3"), metadata = Map("category" -> "science"))
      )
    )

    val filtered = dataset.filter(_.metadata.get("category").contains("science"))
    filtered.samples should have size 2
    filtered.samples.map(_.question) shouldBe Seq("Q1", "Q3")
  }

  it should "return empty dataset when no samples match" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(EvalSample("Q1", "A1", Seq("C1")))
    )

    val filtered = dataset.filter(_ => false)
    filtered.samples shouldBe empty
    filtered.name shouldBe "test"
  }

  it should "return all samples when all match" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(EvalSample("Q1", "A1", Seq("C1")), EvalSample("Q2", "A2", Seq("C2")))
    )

    val filtered = dataset.filter(_ => true)
    filtered.samples should have size 2
  }

  // =========================================================================
  // TestDataset.sample edge cases
  // =========================================================================

  "TestDataset.sample" should "return all samples when n exceeds dataset size" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(EvalSample("Q1", "A1", Seq("C1")), EvalSample("Q2", "A2", Seq("C2")))
    )

    val sampled = dataset.sample(10, seed = 42)
    sampled.samples should have size 2
  }

  it should "return empty for n=0" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(EvalSample("Q1", "A1", Seq("C1")))
    )

    val sampled = dataset.sample(0, seed = 42)
    sampled.samples shouldBe empty
  }

  it should "produce different results with different seeds" in {
    val dataset = TestDataset(
      name = "test",
      samples = (1 to 20).map(i => EvalSample(s"Q$i", s"A$i", Seq(s"C$i")))
    )

    val s1 = dataset.sample(5, seed = 1)
    val s2 = dataset.sample(5, seed = 2)

    s1.samples.map(_.question) should not be s2.samples.map(_.question)
  }

  // =========================================================================
  // TestDataset.take edge cases
  // =========================================================================

  "TestDataset.take" should "handle n larger than dataset" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(EvalSample("Q1", "A1", Seq("C1")))
    )

    val taken = dataset.take(100)
    taken.samples should have size 1
  }

  it should "handle n=0" in {
    val dataset = TestDataset(
      name = "test",
      samples = Seq(EvalSample("Q1", "A1", Seq("C1")))
    )

    val taken = dataset.take(0)
    taken.samples shouldBe empty
  }

  // =========================================================================
  // TestDataset.create factory method
  // =========================================================================

  "TestDataset.create" should "create dataset from name and samples" in {
    val samples = Seq(EvalSample("Q1", "A1", Seq("C1")))
    val dataset = TestDataset.create("my-dataset", samples)
    dataset.name shouldBe "my-dataset"
    dataset.samples shouldBe samples
    dataset.metadata shouldBe empty
  }

  // =========================================================================
  // TestDataset.toJson edge cases
  // =========================================================================

  "TestDataset.toJson" should "handle dataset without ground truth" in {
    val dataset = TestDataset(
      name = "no-gt",
      samples = Seq(EvalSample("Q1", "A1", Seq("C1")))
    )

    val json = TestDataset.toJson(dataset)
    (json should not).include("ground_truth")
  }

  it should "handle dataset without metadata" in {
    val dataset = TestDataset(
      name = "no-meta",
      samples = Seq(EvalSample("Q1", "A1", Seq("C1")))
    )

    val json = TestDataset.toJson(dataset)
    (json should not).include("\"metadata\"")
  }

  it should "handle empty samples" in {
    val dataset = TestDataset.empty("empty")
    val json    = TestDataset.toJson(dataset)
    json should include("\"samples\"")
    json should include("[]")
  }

  it should "produce compact JSON when pretty=false" in {
    val dataset = TestDataset(
      name = "compact",
      samples = Seq(EvalSample("Q1", "A1", Seq("C1")))
    )

    val json = TestDataset.toJson(dataset, pretty = false)
    (json should not).include("\n  ")
  }

  // =========================================================================
  // TestDataset.fromJson edge cases
  // =========================================================================

  "TestDataset.fromJson" should "handle multiple samples" in {
    val json =
      """{
        |  "name": "multi",
        |  "samples": [
        |    {"question": "Q1", "answer": "A1", "contexts": ["C1"]},
        |    {"question": "Q2", "answer": "A2", "contexts": ["C2", "C3"]},
        |    {"question": "Q3", "answer": "A3", "contexts": ["C4"], "ground_truth": "GT3"}
        |  ]
        |}""".stripMargin

    val result = TestDataset.fromJson(json)
    result.isRight shouldBe true
    val dataset = result.toOption.get
    dataset.samples should have size 3
    dataset.samples(2).groundTruth shouldBe Some("GT3")
  }

  it should "fail for missing samples key" in {
    val json   = """{"name": "bad"}"""
    val result = TestDataset.fromJson(json)
    result.isLeft shouldBe true
  }

  it should "fail for empty string" in {
    val result = TestDataset.fromJson("")
    result.isLeft shouldBe true
  }

  // =========================================================================
  // TestDataset.withMetadata chaining
  // =========================================================================

  "TestDataset.withMetadata" should "support chaining" in {
    val dataset = TestDataset
      .empty("test")
      .withMetadata("key1", "val1")
      .withMetadata("key2", "val2")

    dataset.metadata should have size 2
    dataset.metadata("key1") shouldBe "val1"
    dataset.metadata("key2") shouldBe "val2"
  }

  it should "overwrite existing keys" in {
    val dataset = TestDataset
      .empty("test")
      .withMetadata("key", "old")
      .withMetadata("key", "new")

    dataset.metadata("key") shouldBe "new"
  }

  // =========================================================================
  // TestDataset.save error handling
  // =========================================================================

  "TestDataset.save" should "return Left for invalid path" in {
    val dataset = TestDataset.empty("test")
    val result  = TestDataset.save(dataset, "/nonexistent/deeply/nested/dir/file.json")
    result.isLeft shouldBe true
  }

  // =========================================================================
  // Round-trip with sample metadata
  // =========================================================================

  "TestDataset" should "round-trip samples with metadata and ground truth" in {
    val original = TestDataset(
      name = "roundtrip",
      samples = Seq(
        EvalSample("Q1", "A1", Seq("C1", "C2"), Some("GT1"), Map("src" -> "test")),
        EvalSample("Q2", "A2", Seq("C3"), None, Map.empty)
      ),
      metadata = Map("version" -> "2.0", "author" -> "unit-test")
    )

    val json   = TestDataset.toJson(original)
    val result = TestDataset.fromJson(json)

    result.isRight shouldBe true
    val loaded = result.toOption.get

    loaded.name shouldBe "roundtrip"
    loaded.metadata shouldBe Map("version" -> "2.0", "author" -> "unit-test")
    loaded.samples should have size 2
    loaded.samples(0).metadata shouldBe Map("src" -> "test")
    loaded.samples(1).groundTruth shouldBe None
    loaded.samples(1).metadata shouldBe empty
  }
}
