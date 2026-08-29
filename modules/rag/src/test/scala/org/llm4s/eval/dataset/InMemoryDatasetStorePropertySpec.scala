package org.llm4s.eval.dataset

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class InMemoryDatasetStorePropertySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 50)

  // ---- generators ----

  val genNonEmptyString: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  // Exclude ujson.Null to avoid referenceOutput round-trip ambiguity
  val genJsonValue: Gen[ujson.Value] = Gen.oneOf(
    Gen.alphaStr.map(ujson.Str(_)),
    Gen.choose(-1000000L, 1000000L).map(n => ujson.Num(n.toDouble)),
    Gen.oneOf(true, false).map(ujson.Bool(_))
  )

  val genTags: Gen[Set[String]] = Gen.containerOf[Set, String](genNonEmptyString)

  def freshStore(): InMemoryDatasetStore = InMemoryDatasetStore()

  // ---- create / getDataset identity ----

  "InMemoryDatasetStore.create" should "return an ID whose corresponding dataset has matching name and description" in {
    forAll(genNonEmptyString, genNonEmptyString) { (name, desc) =>
      val store = freshStore()
      val id    = store.create(name, desc)
      val ds    = store.getDataset(id)
      ds shouldBe defined
      ds.get.id shouldBe id
      ds.get.name shouldBe name
      ds.get.description shouldBe desc
    }
  }

  it should "always return a dataset with zero examples immediately after creation" in {
    forAll(genNonEmptyString, genNonEmptyString) { (name, desc) =>
      val store = freshStore()
      val id    = store.create(name, desc)
      store.getExamples(id, ExampleSelector.All) shouldBe empty
    }
  }

  // ---- addExample: size monotonicity and insertion order ----

  "InMemoryDatasetStore.addExample" should "grow the example list by exactly one per call" in {
    forAll(Gen.choose(1, 20)) { n =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      (0 until n).foreach(_ => store.addExample(datasetId, ujson.Str("v")))
      store.getExamples(datasetId, ExampleSelector.All) should have size n.toLong
    }
  }

  it should "preserve insertion order across N additions" in {
    forAll(Gen.nonEmptyListOf(genJsonValue)) { inputs =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      val ids       = inputs.map(inp => store.addExample(datasetId, inp))
      val retrieved = store.getExamples(datasetId, ExampleSelector.All).map(_.id)
      retrieved shouldBe ids
    }
  }

  // ---- getExamples: selector invariants ----

  "InMemoryDatasetStore.getExamples(ByTags)" should "always return a subset of All" in {
    forAll(genTags) { filterTags =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      store.addExample(datasetId, ujson.Str("a"), tags = Set("x", "y"))
      store.addExample(datasetId, ujson.Str("b"), tags = Set("y", "z"))
      store.addExample(datasetId, ujson.Str("c"), tags = Set("w"))
      val all      = store.getExamples(datasetId, ExampleSelector.All)
      val filtered = store.getExamples(datasetId, ExampleSelector.ByTags(filterTags))
      all should contain allElementsOf filtered
    }
  }

  it should "return only examples that have at least one tag from the filter set" in {
    forAll(genTags.suchThat(_.nonEmpty)) { filterTags =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      store.addExample(datasetId, ujson.Str("a"), tags = Set("x", "y"))
      store.addExample(datasetId, ujson.Str("b"), tags = Set("y", "z"))
      store.addExample(datasetId, ujson.Str("c"), tags = Set("w"))
      val filtered = store.getExamples(datasetId, ExampleSelector.ByTags(filterTags))
      filtered.foreach(ex => ex.tags.intersect(filterTags) should not be empty)
    }
  }

  it should "return empty when the filter tag set is empty" in {
    forAll(Gen.nonEmptyListOf(genJsonValue)) { inputs =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      inputs.foreach(inp => store.addExample(datasetId, inp, tags = Set("tag")))
      store.getExamples(datasetId, ExampleSelector.ByTags(Set.empty)) shouldBe empty
    }
  }

  "InMemoryDatasetStore.getExamples(ByIds)" should "return exactly the examples whose IDs were requested" in {
    forAll(Gen.choose(2, 10)) { n =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      val allIds    = (0 until n).map(_ => store.addExample(datasetId, ujson.Str("v"))).toList
      val subset    = allIds.take(n / 2).toSet
      val result    = store.getExamples(datasetId, ExampleSelector.ByIds(subset))
      result.map(_.id).toSet shouldBe subset
    }
  }

  it should "return empty when an empty ID set is requested" in {
    forAll(Gen.choose(1, 5)) { n =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      (0 until n).foreach(_ => store.addExample(datasetId, ujson.Str("v")))
      store.getExamples(datasetId, ExampleSelector.ByIds(Set.empty)) shouldBe empty
    }
  }

  // ---- snapshot freezing ----

  "InMemoryDatasetStore.createSnapshot" should "freeze the example count at snapshot time" in {
    forAll(Gen.choose(1, 10), Gen.choose(1, 10)) { (before, after) =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      (0 until before).foreach(_ => store.addExample(datasetId, ujson.Str("v")))
      val snapId = store.createSnapshot(datasetId)
      (0 until after).foreach(_ => store.addExample(datasetId, ujson.Str("v2")))
      val snap = store.getSnapshot(snapId)
      snap shouldBe defined
      snap.get.examples should have size before.toLong
    }
  }

  it should "always associate the snapshot with the correct dataset ID" in {
    forAll(genNonEmptyString) { name =>
      val store     = freshStore()
      val datasetId = store.create(name, "test")
      store.addExample(datasetId, ujson.Str("v"))
      val snapId = store.createSnapshot(datasetId)
      store.getSnapshot(snapId).map(_.datasetId) shouldBe Some(datasetId)
    }
  }

  // ---- delete invariants ----

  "InMemoryDatasetStore.delete" should "return true for an existing dataset and false on repeat" in {
    forAll(genNonEmptyString) { name =>
      val store     = freshStore()
      val datasetId = store.create(name, "test")
      store.delete(datasetId) shouldBe true
      store.delete(datasetId) shouldBe false
    }
  }

  it should "remove all snapshots for the deleted dataset" in {
    forAll(Gen.choose(1, 5)) { numSnaps =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      store.addExample(datasetId, ujson.Str("v"))
      val snapIds = (0 until numSnaps).map(_ => store.createSnapshot(datasetId)).toList
      store.delete(datasetId)
      snapIds.foreach(sid => store.getSnapshot(sid) shouldBe None)
    }
  }

  // ---- listDatasets cardinality ----

  "InMemoryDatasetStore.listDatasets" should "grow by one for each create call" in {
    forAll(Gen.choose(1, 20)) { n =>
      val store = freshStore()
      (0 until n).foreach(i => store.create(s"ds$i", "test"))
      store.listDatasets() should have size n.toLong
    }
  }

  it should "shrink by one after a successful delete" in {
    forAll(Gen.choose(2, 10)) { n =>
      val store = freshStore()
      val ids   = (0 until n).map(i => store.create(s"ds$i", "test")).toList
      store.delete(ids.head)
      store.listDatasets() should have size (n - 1).toLong
    }
  }

  // ---- unknown dataset ID: List.empty fallbacks ----

  "InMemoryDatasetStore.getExamples" should "return empty list for an unknown dataset ID" in {
    forAll(genNonEmptyString) { id =>
      val store = freshStore()
      store.getExamples(DatasetId(id), ExampleSelector.All) shouldBe empty
    }
  }

  "InMemoryDatasetStore.addExample" should "reject unknown dataset IDs" in {
    forAll(genNonEmptyString) { id =>
      val store     = freshStore()
      val datasetId = DatasetId(id)
      an[IllegalArgumentException] should be thrownBy store.addExample(datasetId, ujson.Str("v"))
    }
  }

  "InMemoryDatasetStore.createSnapshot" should "reject unknown dataset IDs" in {
    forAll(genNonEmptyString) { id =>
      val store     = freshStore()
      val datasetId = DatasetId(id)
      an[IllegalArgumentException] should be thrownBy store.createSnapshot(datasetId)
    }
  }

  // ---- importJsonl count invariant ----

  "InMemoryDatasetStore.importJsonl" should "always satisfy: imported + skipped == total lines" in {
    forAll(Gen.choose(0, 20)) { totalLines =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      val lines = (0 until totalLines).map { i =>
        if (i % 3 == 2) "not valid json"
        else s"""{"id":"ex$i","input":"v$i","referenceOutput":null,"tags":[],"metadata":{}}"""
      }.iterator
      val (imported, skipped) = store.importJsonl(datasetId, lines)
      imported + skipped shouldBe totalLines
    }
  }

  it should "add exactly the number of imported examples to the dataset" in {
    forAll(Gen.choose(1, 10)) { n =>
      val store     = freshStore()
      val datasetId = store.create("ds", "test")
      val lines =
        (0 until n).map(i => s"""{"id":"ex$i","input":"v$i","referenceOutput":null,"tags":[],"metadata":{}}""").iterator
      val (imported, _) = store.importJsonl(datasetId, lines)
      store.getExamples(datasetId, ExampleSelector.All) should have size imported.toLong
    }
  }
}
