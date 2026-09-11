package org.llm4s.algorithms.lancedb

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VectorDBSpec extends AnyFlatSpec with Matchers:

  private def vec(values: Float*): Array[Float] = values.toArray

  // ---- Distance Metrics ----

  "DistanceMetric.l2Squared" should "compute squared Euclidean distance" in {
    val a = vec(1.0f, 0.0f, 0.0f)
    val b = vec(0.0f, 1.0f, 0.0f)
    DistanceMetric.l2Squared(a, b) shouldBe 2.0f
  }

  it should "return 0 for identical vectors" in {
    val a = vec(3.0f, 4.0f)
    DistanceMetric.l2Squared(a, a) shouldBe 0.0f
  }

  "DistanceMetric.cosineDistance" should "return 0 for identical directions" in {
    val a = vec(1.0f, 0.0f)
    val b = vec(2.0f, 0.0f)
    DistanceMetric.cosineDistance(a, b) shouldBe (0.0f +- 1e-6f)
  }

  it should "return 1 for orthogonal vectors" in {
    val a = vec(1.0f, 0.0f)
    val b = vec(0.0f, 1.0f)
    DistanceMetric.cosineDistance(a, b) shouldBe (1.0f +- 1e-6f)
  }

  it should "return 1 for zero vector" in {
    val a = vec(0.0f, 0.0f)
    val b = vec(1.0f, 0.0f)
    DistanceMetric.cosineDistance(a, b) shouldBe 1.0f
  }

  "DistanceMetric.negativeDot" should "return negated dot product" in {
    val a = vec(1.0f, 2.0f, 3.0f)
    val b = vec(4.0f, 5.0f, 6.0f)
    DistanceMetric.negativeDot(a, b) shouldBe -32.0f
  }

  "DistanceMetric.compute" should "dispatch to the correct metric" in {
    val a = vec(1.0f, 0.0f)
    val b = vec(0.0f, 1.0f)
    DistanceMetric.compute(DistanceMetric.L2, a, b) shouldBe 2.0f
    DistanceMetric.compute(DistanceMetric.Cosine, a, b) shouldBe (1.0f +- 1e-6f)
    DistanceMetric.compute(DistanceMetric.Dot, a, b) shouldBe 0.0f
  }

  it should "reject dimension mismatches" in {
    an[IllegalArgumentException] should be thrownBy {
      DistanceMetric.compute(DistanceMetric.L2, vec(1.0f), vec(1.0f, 2.0f))
    }
  }

  // ---- VectorDB ----

  "VectorDB" should "create and open tables" in {
    val db = VectorDB.open("test")
    val table = db.createTable("vectors", Schema(4))
    db.tableNames should contain("vectors")
    db.openTable("vectors") shouldBe Some(table)
    db.openTable("nonexistent") shouldBe None
  }

  it should "reject duplicate table names" in {
    val db = VectorDB.open("test")
    db.createTable("t1", Schema(4))
    an[IllegalArgumentException] should be thrownBy {
      db.createTable("t1", Schema(4))
    }
  }

  it should "drop tables" in {
    val db = VectorDB.open("test")
    db.createTable("t1", Schema(4))
    db.dropTable("t1") shouldBe true
    db.dropTable("t1") shouldBe false
    db.tableNames shouldBe empty
  }

  // ---- VectorTable CRUD ----

  "VectorTable" should "add and retrieve records" in {
    val table = new VectorTable("test", Schema(3))
    val ids = table.add(Seq(
      (vec(1, 0, 0), Map("label" -> MetadataValue.StringVal("a"))),
      (vec(0, 1, 0), Map("label" -> MetadataValue.StringVal("b")))
    ))
    ids shouldBe Seq(0L, 1L)
    table.size shouldBe 2
    table.get(0L).map(_.metadata("label")) shouldBe Some(MetadataValue.StringVal("a"))
    table.get(1L).map(_.metadata("label")) shouldBe Some(MetadataValue.StringVal("b"))
  }

  it should "add vectors without metadata" in {
    val table = new VectorTable("test", Schema(3))
    val ids = table.addVectors(Seq(vec(1, 0, 0), vec(0, 1, 0)))
    ids shouldBe Seq(0L, 1L)
    table.get(0L).map(_.metadata) shouldBe Some(Map.empty)
  }

  it should "reject wrong dimension" in {
    val table = new VectorTable("test", Schema(3))
    an[IllegalArgumentException] should be thrownBy {
      table.add(Seq((vec(1, 0, 0, 0), Map.empty)))
    }
  }

  it should "delete records by id" in {
    val table = new VectorTable("test", Schema(3))
    table.addVectors(Seq(vec(1, 0, 0), vec(0, 1, 0), vec(0, 0, 1)))
    table.delete(Set(1L)) shouldBe 1
    table.size shouldBe 2
    table.get(1L) shouldBe None
    table.get(0L) shouldBe defined
    table.get(2L) shouldBe defined
  }

  // ---- Brute-force Search ----

  "Brute-force search" should "return nearest neighbors in L2" in {
    val table = new VectorTable("test", Schema(4))
    table.addVectors(Seq(
      vec(1, 0, 0, 0),
      vec(0, 1, 0, 0),
      vec(0, 0, 1, 0),
      vec(0.9f, 0.1f, 0, 0)
    ))
    val results = table.search(vec(1, 0, 0, 0)).limit(2).execute()
    results.size shouldBe 2
    results.head.record.id shouldBe 0L
    results.head.distance shouldBe 0.0f
    results(1).record.id shouldBe 3L
  }

  it should "return all results when limit exceeds table size" in {
    val table = new VectorTable("test", Schema(2))
    table.addVectors(Seq(vec(1, 0), vec(0, 1)))
    val results = table.search(vec(1, 0)).limit(100).execute()
    results.size shouldBe 2
  }

  it should "return empty for empty table" in {
    val table = new VectorTable("test", Schema(2))
    val results = table.search(vec(1, 0)).limit(10).execute()
    results shouldBe empty
  }

  it should "reject query with wrong dimension" in {
    val table = new VectorTable("test", Schema(3))
    an[IllegalArgumentException] should be thrownBy {
      table.search(vec(1, 0))
    }
  }

  // ---- Metadata Filtering ----

  "Metadata filtering" should "filter by equality" in {
    val table = new VectorTable("test", Schema(3))
    table.add(Seq(
      (vec(1, 0, 0), Map("cat" -> MetadataValue.StringVal("A"))),
      (vec(0.9f, 0.1f, 0), Map("cat" -> MetadataValue.StringVal("B"))),
      (vec(0.8f, 0.2f, 0), Map("cat" -> MetadataValue.StringVal("A")))
    ))
    val results = table.search(vec(1, 0, 0))
      .where(MetadataFilter.eq("cat", MetadataValue.StringVal("A")))
      .limit(10)
      .execute()
    results.size shouldBe 2
    results.foreach(_.record.metadata("cat") shouldBe MetadataValue.StringVal("A"))
  }

  it should "filter with compound predicates" in {
    val table = new VectorTable("test", Schema(2))
    table.add(Seq(
      (vec(1, 0), Map("x" -> MetadataValue.IntVal(1), "y" -> MetadataValue.StringVal("a"))),
      (vec(0, 1), Map("x" -> MetadataValue.IntVal(2), "y" -> MetadataValue.StringVal("a"))),
      (vec(1, 1), Map("x" -> MetadataValue.IntVal(1), "y" -> MetadataValue.StringVal("b")))
    ))
    val results = table.search(vec(1, 0))
      .where(MetadataFilter.and(
        MetadataFilter.eq("x", MetadataValue.IntVal(1)),
        MetadataFilter.eq("y", MetadataValue.StringVal("a"))
      ))
      .limit(10)
      .execute()
    results.size shouldBe 1
    results.head.record.id shouldBe 0L
  }

  it should "support negation" in {
    val table = new VectorTable("test", Schema(2))
    table.add(Seq(
      (vec(1, 0), Map("cat" -> MetadataValue.StringVal("A"))),
      (vec(0, 1), Map("cat" -> MetadataValue.StringVal("B")))
    ))
    val results = table.search(vec(1, 0))
      .where(MetadataFilter.not(MetadataFilter.eq("cat", MetadataValue.StringVal("A"))))
      .limit(10)
      .execute()
    results.size shouldBe 1
    results.head.record.metadata("cat") shouldBe MetadataValue.StringVal("B")
  }

  // ---- Cosine & Dot Metrics ----

  "Cosine search" should "rank by cosine similarity" in {
    val table = new VectorTable("test", Schema(3, DistanceMetric.Cosine))
    table.addVectors(Seq(
      vec(1, 0, 0),
      vec(0, 1, 0),
      vec(0.7f, 0.7f, 0)
    ))
    val results = table.search(vec(1, 0, 0)).limit(3).execute()
    results.head.record.id shouldBe 0L
    results.head.distance shouldBe (0.0f +- 1e-5f)
  }

  "Dot product search" should "rank by dot product" in {
    val table = new VectorTable("test", Schema(3, DistanceMetric.Dot))
    table.addVectors(Seq(
      vec(1, 0, 0),
      vec(10, 0, 0),
      vec(0, 1, 0)
    ))
    val results = table.search(vec(1, 0, 0)).limit(3).execute()
    results.head.record.id shouldBe 1L
    results.head.distance shouldBe -10.0f
  }

  // ---- IVF Index ----

  "IVF index" should "return approximate nearest neighbors" in {
    val rng = new scala.util.Random(42)
    val d = 16
    val table = new VectorTable("test", Schema(d))

    val clusterCenters = Array(
      Array.fill(d)(0.0f),
      Array.tabulate(d)(i => if i == 0 then 10.0f else 0.0f),
      Array.tabulate(d)(i => if i == 1 then 10.0f else 0.0f),
      Array.tabulate(d)(i => if i == 2 then 10.0f else 0.0f)
    )

    val vecs = (0 until 1000).map: i =>
      val center = clusterCenters(i % 4)
      Array.tabulate(d)(j => center(j) + rng.nextGaussian().toFloat * 0.5f)
    table.addVectors(vecs)

    table.createIndex(IVFConfig(nPartitions = 8, nProbes = 2))
    table.hasIndex shouldBe true

    val query = Array.fill(d)(0.0f)
    val results = table.search(query).limit(10).execute()
    results.size shouldBe 10
    results.foreach(_.distance should be < 20.0f)
  }

  it should "find the exact nearest neighbor with enough probes" in {
    val table = new VectorTable("test", Schema(4))
    table.addVectors(Seq(
      vec(1, 0, 0, 0),
      vec(0, 1, 0, 0),
      vec(0, 0, 1, 0),
      vec(0, 0, 0, 1),
      vec(0.5f, 0.5f, 0, 0),
      vec(0.9f, 0.1f, 0, 0)
    ))
    table.createIndex(IVFConfig(nPartitions = 2, nProbes = 2))

    val results = table.search(vec(1, 0, 0, 0)).limit(1).execute()
    results.head.record.id shouldBe 0L
    results.head.distance shouldBe 0.0f
  }

  it should "work with metadata filtering post-index" in {
    val rng = new scala.util.Random(123)
    val d = 8
    val table = new VectorTable("test", Schema(d))

    val vecs = (0 until 200).map: i =>
      val v = Array.tabulate(d)(_ => rng.nextGaussian().toFloat)
      (v, Map("parity" -> MetadataValue.StringVal(if i % 2 == 0 then "even" else "odd")))
    table.add(vecs)
    table.createIndex(IVFConfig(nPartitions = 4, nProbes = 4))

    val query = Array.fill(d)(0.0f)
    val results = table.search(query)
      .where(MetadataFilter.eq("parity", MetadataValue.StringVal("even")))
      .limit(5)
      .execute()
    results.size shouldBe 5
    results.foreach(_.record.metadata("parity") shouldBe MetadataValue.StringVal("even"))
  }

  // ---- End-to-end ----

  "End-to-end workflow" should "support create, add, index, search, delete" in {
    val db = VectorDB.open("e2e-test")
    val table = db.createTable("embeddings", Schema(dimension = 4, metric = DistanceMetric.Cosine))

    val ids = table.add(Seq(
      (vec(1, 0, 0, 0), Map("text" -> MetadataValue.StringVal("north"))),
      (vec(0, 1, 0, 0), Map("text" -> MetadataValue.StringVal("east"))),
      (vec(-1, 0, 0, 0), Map("text" -> MetadataValue.StringVal("south"))),
      (vec(0, -1, 0, 0), Map("text" -> MetadataValue.StringVal("west"))),
      (vec(0.7f, 0.7f, 0, 0), Map("text" -> MetadataValue.StringVal("northeast")))
    ))
    ids.size shouldBe 5

    val results = table.search(vec(1, 0, 0, 0)).limit(2).execute()
    results.head.record.metadata("text") shouldBe MetadataValue.StringVal("north")
    results(1).record.metadata("text") shouldBe MetadataValue.StringVal("northeast")

    table.delete(Set(ids.head))
    table.size shouldBe 4
    table.get(ids.head) shouldBe None

    val afterDelete = table.search(vec(1, 0, 0, 0)).limit(1).execute()
    afterDelete.head.record.metadata("text") shouldBe MetadataValue.StringVal("northeast")
  }

  // ---- MetadataFilter combinators ----

  "MetadataFilter" should "support in-set filter" in {
    val filter = MetadataFilter.in("x", Set(MetadataValue.IntVal(1), MetadataValue.IntVal(2)))
    filter(Map("x" -> MetadataValue.IntVal(1))) shouldBe true
    filter(Map("x" -> MetadataValue.IntVal(3))) shouldBe false
    filter(Map.empty) shouldBe false
  }

  it should "support or combinator" in {
    val filter = MetadataFilter.or(
      MetadataFilter.eq("a", MetadataValue.IntVal(1)),
      MetadataFilter.eq("b", MetadataValue.IntVal(2))
    )
    filter(Map("a" -> MetadataValue.IntVal(1))) shouldBe true
    filter(Map("b" -> MetadataValue.IntVal(2))) shouldBe true
    filter(Map("a" -> MetadataValue.IntVal(99))) shouldBe false
  }

  it should "support neq filter" in {
    val filter = MetadataFilter.neq("x", MetadataValue.IntVal(1))
    filter(Map("x" -> MetadataValue.IntVal(1))) shouldBe false
    filter(Map("x" -> MetadataValue.IntVal(2))) shouldBe true
    filter(Map.empty) shouldBe true
  }
