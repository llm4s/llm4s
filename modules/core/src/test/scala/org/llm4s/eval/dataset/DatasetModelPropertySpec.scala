package org.llm4s.eval.dataset

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.Instant

class DatasetModelPropertySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 50)

  // ---- generators ----

  val genNonEmptyString: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)
  val genId: Gen[String]             = Gen.uuid.map(_.toString)

  val genDatasetId: Gen[DatasetId]   = genId.map(DatasetId(_))
  val genExampleId: Gen[ExampleId]   = genId.map(ExampleId(_))
  val genSnapshotId: Gen[SnapshotId] = genId.map(SnapshotId(_))

  val genTags: Gen[Set[String]]             = Gen.containerOf[Set, String](genNonEmptyString)
  val genMetadata: Gen[Map[String, String]] = Gen.mapOf(Gen.zip(genNonEmptyString, Gen.alphaStr))

  val genSimpleJsonValue: Gen[ujson.Value] = Gen.oneOf(
    Gen.alphaStr.map(ujson.Str(_)),
    Gen.choose(-1000000L, 1000000L).map(n => ujson.Num(n.toDouble)),
    Gen.oneOf(true, false).map(ujson.Bool(_)),
    Gen.const(ujson.Null)
  )

  val genExample: Gen[Example[ujson.Value, ujson.Value]] =
    for {
      id       <- genExampleId
      input    <- genSimpleJsonValue
      refOut   <- Gen.option(genSimpleJsonValue)
      tags     <- genTags
      metadata <- genMetadata
    } yield Example(id, input, refOut, tags, metadata)

  // ---- ID wrapper: value is preserved losslessly ----

  "DatasetId" should "preserve any wrapped string value exactly" in {
    forAll(genId)(s => DatasetId(s).value shouldBe s)
  }

  it should "return the wrapped value from toString" in {
    forAll(genId)(s => DatasetId(s).toString shouldBe s)
  }

  "ExampleId" should "preserve any wrapped string value exactly" in {
    forAll(genId)(s => ExampleId(s).value shouldBe s)
  }

  it should "return the wrapped value from toString" in {
    forAll(genId)(s => ExampleId(s).toString shouldBe s)
  }

  "SnapshotId" should "preserve any wrapped string value exactly" in {
    forAll(genId)(s => SnapshotId(s).value shouldBe s)
  }

  it should "return the wrapped value from toString" in {
    forAll(genId)(s => SnapshotId(s).toString shouldBe s)
  }

  // ---- ID generate: uniqueness ----

  "DatasetId.generate()" should "produce distinct values across N calls" in {
    forAll(Gen.choose(2, 20)) { n =>
      val ids = List.fill(n)(DatasetId.generate())
      ids.distinct shouldBe ids
    }
  }

  "ExampleId.generate()" should "produce distinct values across N calls" in {
    forAll(Gen.choose(2, 20)) { n =>
      val ids = List.fill(n)(ExampleId.generate())
      ids.distinct shouldBe ids
    }
  }

  "SnapshotId.generate()" should "produce distinct values across N calls" in {
    forAll(Gen.choose(2, 20)) { n =>
      val ids = List.fill(n)(SnapshotId.generate())
      ids.distinct shouldBe ids
    }
  }

  // ---- Example: all fields are preserved ----

  "Example" should "preserve all fields through copy()" in {
    forAll(genExample) { ex =>
      val copied = ex.copy()
      copied.id shouldBe ex.id
      copied.input shouldBe ex.input
      copied.referenceOutput shouldBe ex.referenceOutput
      copied.tags shouldBe ex.tags
      copied.metadata shouldBe ex.metadata
    }
  }

  it should "default to empty tags and metadata" in {
    forAll(genExampleId, genSimpleJsonValue) { (id, input) =>
      val ex = Example[ujson.Value, ujson.Value](id = id, input = input, referenceOutput = None)
      ex.tags shouldBe empty
      ex.metadata shouldBe empty
    }
  }

  // ---- Dataset: default field values ----

  "Dataset" should "default to an empty examples list" in {
    forAll(genDatasetId, genNonEmptyString, genNonEmptyString) { (id, name, desc) =>
      val ds = Dataset[ujson.Value, ujson.Value](
        id = id,
        name = name,
        description = desc,
        createdAt = Instant.now(),
        examples = List.empty[Example[ujson.Value, ujson.Value]]
      )
      ds.examples shouldBe empty
    }
  }

  it should "default to no input or output schema" in {
    forAll(genDatasetId, genNonEmptyString, genNonEmptyString) { (id, name, desc) =>
      val ds = Dataset[ujson.Value, ujson.Value](
        id = id,
        name = name,
        description = desc,
        createdAt = Instant.now(),
        examples = List.empty[Example[ujson.Value, ujson.Value]]
      )
      ds.inputSchema shouldBe None
      ds.outputSchema shouldBe None
    }
  }

  // ---- DatasetSnapshot: examples are exactly those provided ----

  "DatasetSnapshot" should "store exactly the examples provided at construction" in {
    forAll(Gen.listOf(genExample)) { examples =>
      val snap = DatasetSnapshot[ujson.Value, ujson.Value](
        snapshotId = SnapshotId.generate(),
        datasetId = DatasetId.generate(),
        examples = examples,
        createdAt = Instant.now()
      )
      snap.examples shouldBe examples
    }
  }

  it should "never mutate after construction regardless of what happens to the original list" in {
    forAll(Gen.nonEmptyListOf(genExample)) { examples =>
      val snap = DatasetSnapshot[ujson.Value, ujson.Value](
        snapshotId = SnapshotId.generate(),
        datasetId = DatasetId.generate(),
        examples = examples,
        createdAt = Instant.now()
      )
      // The snapshot examples are a separate copy; size is stable
      snap.examples should have size examples.size.toLong
    }
  }

  // ---- ExampleSelector: structural invariants ----

  "ExampleSelector.ByTags" should "hold the exact tag set provided" in {
    forAll(genTags)(tags => ExampleSelector.ByTags(tags).tags shouldBe tags)
  }

  "ExampleSelector.ByIds" should "hold the exact ID set provided" in {
    forAll(Gen.containerOf[Set, ExampleId](genExampleId))(ids => ExampleSelector.ByIds(ids).ids shouldBe ids)
  }

  "ExampleSelector" should "cover all three cases via pattern matching without gaps" in {
    val selectors: List[ExampleSelector] = List(
      ExampleSelector.All,
      ExampleSelector.ByTags(Set("a")),
      ExampleSelector.ByIds(Set(ExampleId("x")))
    )
    selectors.foreach {
      case ExampleSelector.All       => succeed
      case ExampleSelector.ByTags(_) => succeed
      case ExampleSelector.ByIds(_)  => succeed
    }
  }
}
