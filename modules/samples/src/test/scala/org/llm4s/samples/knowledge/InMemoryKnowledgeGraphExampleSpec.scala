package org.llm4s.samples.knowledge

import org.llm4s.knowledgegraph.Edge
import org.llm4s.types.TryOps
import java.nio.file.Paths
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The sample, run for real: every answer it prints is checked against the graph it builds. */
class InMemoryKnowledgeGraphExampleSpec extends AnyFlatSpec with Matchers with EitherValues {

  private lazy val report = InMemoryKnowledgeGraphExample.run().value

  "InMemoryKnowledgeGraphExample" should "build six nodes and seven edges, the most connected being acme" in {
    report.stats.nodeCount shouldBe 6L
    report.stats.edgeCount shouldBe 7L
    report.stats.averageDegree shouldBe (14.0 / 6.0) +- 1e-9
    report.stats.densestNodeId shouldBe Some("acme")
  }

  it should "find who reports to alice and where she works, by direction and relationship" in {
    report.aliceDirectReports should contain theSameElementsAs Seq("bob", "carol")
    report.aliceEmployer shouldBe Seq("acme")
  }

  it should "limit a traversal to the requested number of hops, start node first" in {
    report.bobWithinOneHop.head shouldBe "bob"
    report.bobWithinOneHop.toSet shouldBe Set("bob", "alice", "acme", "scala")
    report.bobWithinTwoHops.toSet shouldBe Set("bob", "alice", "acme", "scala", "london")
  }

  it should "query a sub-graph by label, then narrow it by property" in {
    report.people.nodes.keySet shouldBe Set("alice", "bob", "carol")
    report.people.edges.map(_.relationship).toSet shouldBe Set("REPORTS_TO")
    report.people.edges should have size 2

    report.engineers.nodes.keySet shouldBe Set("bob", "carol")
    report.engineers.edges shouldBe empty
  }

  it should "read back from the JSON file everything it saved" in {
    report.reloaded.nodeCount shouldBe 6L
    report.reloaded.edgeCount shouldBe 7L
  }

  it should "refuse an edge whose node is missing, with a Left rather than an exception" in {
    val store = InMemoryKnowledgeGraphExample.buildStore().value
    store.upsertEdge(Edge("bob", "nobody", "KNOWS")).isLeft shouldBe true
    store.stats().value.edgeCount shouldBe 7L
  }

  it should "print one line per finding" in {
    val lines = InMemoryKnowledgeGraphExample.render(report)
    lines.head shouldBe "In-memory knowledge graph"
    lines.exists(_.contains("most connected node is acme")) shouldBe true
    lines.exists(_.contains("Average degree: 2.33")) shouldBe true
    lines.exists(_.contains("read back: 6 nodes and 7 edges")) shouldBe true
  }

  it should "return cleanup errors and attempt both paths" in {
    val paths     = Seq(Paths.get("graph.json"), Paths.get("directory"))
    val attempted = ArrayBuffer.empty[java.nio.file.Path]
    val result = InMemoryKnowledgeGraphExample.cleanup(
      Right(42),
      paths,
      path => {
        attempted += path
        throw new java.io.IOException("cannot delete")
      }
    )
    result.isLeft shouldBe true
    attempted.toSeq shouldBe paths
  }

  it should "preserve an existing error even when cleanup also fails" in {
    val original  = Try[Int](throw new java.io.IOException("save failed")).toResult
    val paths     = Seq(Paths.get("graph.json"), Paths.get("directory"))
    val attempted = ArrayBuffer.empty[java.nio.file.Path]
    val result = InMemoryKnowledgeGraphExample.cleanup(
      original,
      paths,
      path => {
        attempted += path
        throw new java.io.IOException("cleanup failed")
      }
    )
    result shouldBe original
    attempted.toSeq shouldBe paths
  }
}
