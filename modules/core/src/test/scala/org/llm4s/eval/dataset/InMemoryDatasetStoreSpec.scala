package org.llm4s.eval.dataset

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InMemoryDatasetStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var store: InMemoryDatasetStore = _

  override def beforeEach(): Unit =
    store = InMemoryDatasetStore()

  "InMemoryDatasetStore" should "create a dataset and return its ID" in {
    val id = store.create(
      name = "test-dataset",
      description = "A test dataset",
      inputSchema = Some(ujson.Obj("type" -> ujson.Str("string"))),
      outputSchema = None,
      tags = Set("test")
    )

    val dataset = store.getDataset(id)
    dataset shouldBe defined
    dataset.get.name shouldBe "test-dataset"
    dataset.get.description shouldBe "A test dataset"
    dataset.get.tags shouldBe Set("test")
  }

  it should "create dataset with zero examples" in {
    val id       = store.create("empty-dataset", "No examples")
    val examples = store.getExamples(id, ExampleSelector.All)
    examples shouldBe empty
  }

  it should "add examples and retrieve them" in {
    val datasetId = store.create("test-dataset", "Test")

    val exId1 = store.addExample(
      datasetId,
      input = ujson.Obj("query" -> ujson.Str("hello")),
      referenceOutput = Some(ujson.Str("hi")),
      tags = Set("greeting"),
      metadata = Map("source" -> "test")
    )

    val exId2 = store.addExample(
      datasetId,
      input = ujson.Obj("query" -> ujson.Str("goodbye")),
      referenceOutput = Some(ujson.Str("bye")),
      tags = Set("farewell")
    )

    val examples = store.getExamples(datasetId, ExampleSelector.All)
    examples should have size 2
    (examples.map(_.id) should contain).allOf(exId1, exId2)
  }

  it should "accumulate examples in insertion order" in {
    val datasetId = store.create("ordered-dataset", "Test")

    val id1 = store.addExample(datasetId, ujson.Str("first"))
    val id2 = store.addExample(datasetId, ujson.Str("second"))
    val id3 = store.addExample(datasetId, ujson.Str("third"))

    val examples = store.getExamples(datasetId, ExampleSelector.All)
    examples.map(_.id) shouldBe List(id1, id2, id3)
  }

  it should "filter examples by tags" in {
    val datasetId = store.create("tagged-dataset", "Test")

    store.addExample(datasetId, ujson.Str("qa1"), tags = Set("qa", "test"))
    store.addExample(datasetId, ujson.Str("rag1"), tags = Set("rag", "test"))
    store.addExample(datasetId, ujson.Str("qa2"), tags = Set("qa"))

    val qaExamples = store.getExamples(datasetId, ExampleSelector.ByTags(Set("qa")))
    qaExamples should have size 2

    val ragExamples = store.getExamples(datasetId, ExampleSelector.ByTags(Set("rag")))
    ragExamples should have size 1
  }

  it should "filter examples by IDs" in {
    val datasetId = store.create("id-dataset", "Test")

    val id1 = store.addExample(datasetId, ujson.Str("first"))
    val id2 = store.addExample(datasetId, ujson.Str("second"))
    store.addExample(datasetId, ujson.Str("third"))

    val examples = store.getExamples(datasetId, ExampleSelector.ByIds(Set(id1, id2)))
    examples should have size 2
    (examples.map(_.id) should contain).allOf(id1, id2)
  }

  it should "return empty list for empty tag filter" in {
    val datasetId = store.create("tag-dataset", "Test")
    store.addExample(datasetId, ujson.Str("data"), tags = Set("test"))

    val examples = store.getExamples(datasetId, ExampleSelector.ByTags(Set.empty))
    examples shouldBe empty
  }

  it should "create snapshot that is unaffected by later additions" in {
    val datasetId = store.create("snapshot-dataset", "Test")

    store.addExample(datasetId, ujson.Str("first"))
    store.addExample(datasetId, ujson.Str("second"))

    val snapshotId = store.createSnapshot(datasetId)

    store.addExample(datasetId, ujson.Str("third"))

    val snapshot = store.getSnapshot(snapshotId)
    snapshot shouldBe defined
    snapshot.get.examples should have size 2
    snapshot.get.datasetId shouldBe datasetId
  }

  it should "retrieve snapshot by ID" in {
    val datasetId = store.create("snapshot-dataset", "Test")
    store.addExample(datasetId, ujson.Str("data"))

    val snapshotId = store.createSnapshot(datasetId)
    val snapshot   = store.getSnapshot(snapshotId)

    snapshot shouldBe defined
    snapshot.get.snapshotId shouldBe snapshotId
  }

  it should "delete existing dataset and return true" in {
    val datasetId = store.create("to-delete", "Test")
    store.getDataset(datasetId) shouldBe defined

    val result = store.delete(datasetId)
    result shouldBe true
    store.getDataset(datasetId) shouldBe None
  }

  it should "return false when deleting non-existent dataset" in {
    val result = store.delete(DatasetId("nonexistent"))
    result shouldBe false
  }

  it should "delete snapshots when dataset is deleted" in {
    val datasetId = store.create("delete-with-snapshots", "Test")
    store.addExample(datasetId, ujson.Str("data"))
    val snapshotId = store.createSnapshot(datasetId)

    store.delete(datasetId)

    store.getSnapshot(snapshotId) shouldBe None
  }

  it should "list all datasets" in {
    store.create("dataset-1", "First")
    store.create("dataset-2", "Second")
    store.create("dataset-3", "Third")

    val datasets = store.listDatasets()
    datasets should have size 3
    (datasets.map(_.name) should contain).allOf("dataset-1", "dataset-2", "dataset-3")
  }

  it should "return empty list when no datasets exist" in {
    val datasets = store.listDatasets()
    datasets shouldBe empty
  }

  it should "import JSONL with all valid lines" in {
    val datasetId = store.create("import-dataset", "Test")
    val lines = Iterator(
      """{"id":"ex1","input":"q1","referenceOutput":"a1","tags":["qa"],"metadata":{}}""",
      """{"id":"ex2","input":"q2","referenceOutput":"a2","tags":[],"metadata":{}}""",
      """{"id":"ex3","input":"q3","referenceOutput":null,"tags":[],"metadata":{}}"""
    )

    val (imported, skipped) = store.importJsonl(datasetId, lines)
    imported shouldBe 3
    skipped shouldBe 0

    val examples = store.getExamples(datasetId, ExampleSelector.All)
    examples should have size 3
  }

  it should "import JSONL skipping malformed lines" in {
    val datasetId = store.create("import-dataset", "Test")
    val lines = Iterator(
      """{"id":"ex1","input":"q1","referenceOutput":"a1","tags":[],"metadata":{}}""",
      "not valid json",
      """{"id":"ex2","input":"q2","referenceOutput":"a2","tags":[],"metadata":{}}"""
    )

    val (imported, skipped) = store.importJsonl(datasetId, lines)
    imported shouldBe 2
    skipped shouldBe 1
  }

  it should "import empty iterator" in {
    val datasetId           = store.create("empty-import", "Test")
    val (imported, skipped) = store.importJsonl(datasetId, Iterator.empty)
    imported shouldBe 0
    skipped shouldBe 0
  }

  it should "export to JSONL" in {
    val datasetId = store.create("export-dataset", "Test")
    store.addExample(datasetId, ujson.Str("q1"), Some(ujson.Str("a1")), Set("qa"), Map("k" -> "v"))
    store.addExample(datasetId, ujson.Str("q2"), None, Set.empty, Map.empty)

    val lines = store.exportJsonl(datasetId).toList
    lines should have size 2
  }

  it should "round-trip via JSONL export and import" in {
    val datasetId1 = store.create("source-dataset", "Test")
    store.addExample(
      datasetId1,
      ujson.Obj("query" -> ujson.Str("hello")),
      Some(ujson.Str("hi")),
      Set("greeting"),
      Map("lang" -> "en")
    )
    store.addExample(datasetId1, ujson.Obj("query" -> ujson.Str("goodbye")), None, Set.empty, Map.empty)

    val exportedLines = store.exportJsonl(datasetId1).toList

    val datasetId2 = store.create("target-dataset", "Test")
    store.importJsonl(datasetId2, exportedLines.iterator)

    val sourceExamples = store.getExamples(datasetId1, ExampleSelector.All)
    val targetExamples = store.getExamples(datasetId2, ExampleSelector.All)

    targetExamples should have size sourceExamples.size
    targetExamples.map(_.input) shouldBe sourceExamples.map(_.input)
    targetExamples.map(_.referenceOutput) shouldBe sourceExamples.map(_.referenceOutput)
    targetExamples.map(_.tags) shouldBe sourceExamples.map(_.tags)
  }
}
