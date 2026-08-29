package org.llm4s.eval.dataset

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DatasetModelSpec extends AnyFlatSpec with Matchers {

  "DatasetId.generate()" should "produce unique IDs" in {
    val id1 = DatasetId.generate()
    val id2 = DatasetId.generate()
    (id1.value should not).equal(id2.value)
  }

  it should "preserve wrapped string value" in {
    val id = DatasetId("test-id-123")
    id.value shouldBe "test-id-123"
  }

  "ExampleId.generate()" should "produce unique IDs" in {
    val id1 = ExampleId.generate()
    val id2 = ExampleId.generate()
    (id1.value should not).equal(id2.value)
  }

  it should "preserve wrapped string value" in {
    val id = ExampleId("example-id-456")
    id.value shouldBe "example-id-456"
  }

  "SnapshotId.generate()" should "produce unique IDs" in {
    val id1 = SnapshotId.generate()
    val id2 = SnapshotId.generate()
    (id1.value should not).equal(id2.value)
  }

  it should "preserve wrapped string value" in {
    val id = SnapshotId("snapshot-id-789")
    id.value shouldBe "snapshot-id-789"
  }

  "Example" should "be constructed with reference output" in {
    val example = Example[String, String](
      id = ExampleId.generate(),
      input = "question",
      referenceOutput = Some("answer"),
      tags = Set("qa"),
      metadata = Map("source" -> "test")
    )
    example.referenceOutput shouldBe Some("answer")
    example.tags shouldBe Set("qa")
    example.metadata shouldBe Map("source" -> "test")
  }

  it should "be constructed without reference output" in {
    val example = Example[String, String](
      id = ExampleId.generate(),
      input = "question",
      referenceOutput = None
    )
    example.referenceOutput shouldBe None
  }

  "Dataset" should "be created with no examples" in {
    val dataset = Dataset[String, String](
      id = DatasetId.generate(),
      name = "test-dataset",
      description = "A test dataset",
      examples = List.empty,
      createdAt = java.time.Instant.now()
    )
    dataset.examples.isEmpty shouldBe true
  }

  it should "be created with optional schemas absent" in {
    val dataset = Dataset[String, String](
      id = DatasetId.generate(),
      name = "test-dataset",
      description = "A test dataset",
      inputSchema = None,
      outputSchema = None,
      examples = List.empty,
      createdAt = java.time.Instant.now()
    )
    dataset.inputSchema shouldBe None
    dataset.outputSchema shouldBe None
  }

  it should "be created with schemas present" in {
    val inputSchema  = ujson.Obj("type" -> ujson.Str("string"))
    val outputSchema = ujson.Obj("type" -> ujson.Str("string"))
    val dataset = Dataset[String, String](
      id = DatasetId.generate(),
      name = "test-dataset",
      description = "A test dataset",
      inputSchema = Some(inputSchema),
      outputSchema = Some(outputSchema),
      examples = List.empty,
      createdAt = java.time.Instant.now()
    )
    dataset.inputSchema shouldBe Some(inputSchema)
    dataset.outputSchema shouldBe Some(outputSchema)
  }

  "DatasetSnapshot" should "hold examples at creation time" in {
    val examples = List(
      Example[String, String](
        id = ExampleId.generate(),
        input = "q1",
        referenceOutput = Some("a1")
      )
    )
    val snapshot = DatasetSnapshot[String, String](
      snapshotId = SnapshotId.generate(),
      datasetId = DatasetId.generate(),
      examples = examples,
      createdAt = java.time.Instant.now()
    )
    snapshot.examples shouldBe examples
  }

  "ExampleSelector.All" should "match every example" in {
    val selector: ExampleSelector = ExampleSelector.All
    val examples = List(
      Example[String, String](ExampleId("1"), "q1", None),
      Example[String, String](ExampleId("2"), "q2", None)
    )
    val filtered = selector match {
      case ExampleSelector.All => examples
      case _                   => List.empty
    }
    filtered shouldBe examples
  }

  "ExampleSelector.ByTags" should "filter by tag intersection" in {
    val selector = ExampleSelector.ByTags(Set("qa"))
    val examples = List(
      Example[String, String](ExampleId("1"), "q1", None, tags = Set("qa")),
      Example[String, String](ExampleId("2"), "q2", None, tags = Set("rag"))
    )
    val filtered = examples.filter(e => e.tags.intersect(selector.tags).nonEmpty)
    filtered should have size 1
    filtered.head.id shouldBe ExampleId("1")
  }

  it should "return empty list for empty tag set" in {
    val selector = ExampleSelector.ByTags(Set.empty)
    selector.tags.isEmpty shouldBe true
  }

  "ExampleSelector.ByIds" should "filter by ID membership" in {
    val selector = ExampleSelector.ByIds(Set(ExampleId("1")))
    val examples = List(
      Example[String, String](ExampleId("1"), "q1", None),
      Example[String, String](ExampleId("2"), "q2", None)
    )
    val filtered = examples.filter(e => selector.ids.contains(e.id))
    filtered should have size 1
    filtered.head.id shouldBe ExampleId("1")
  }
}
