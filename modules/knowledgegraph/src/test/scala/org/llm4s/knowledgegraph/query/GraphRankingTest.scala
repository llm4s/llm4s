package org.llm4s.knowledgegraph.query

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }

class GraphRankingTest extends AnyFunSuite with Matchers {

  // Star graph: center node connected to 4 leaf nodes
  private val starGraph = {
    val nodes = Map(
      "center" -> Node("center", "Hub"),
      "a"      -> Node("a", "Leaf"),
      "b"      -> Node("b", "Leaf"),
      "c"      -> Node("c", "Leaf"),
      "d"      -> Node("d", "Leaf")
    )
    val edges = List(
      Edge("center", "a", "CONNECTS"),
      Edge("center", "b", "CONNECTS"),
      Edge("center", "c", "CONNECTS"),
      Edge("center", "d", "CONNECTS")
    )
    Graph(nodes, edges)
  }

  // Chain graph: A -> B -> C -> D -> E
  private val chainGraph = {
    val nodes = Map(
      "a" -> Node("a", "Node"),
      "b" -> Node("b", "Node"),
      "c" -> Node("c", "Node"),
      "d" -> Node("d", "Node"),
      "e" -> Node("e", "Node")
    )
    val edges = List(
      Edge("a", "b", "NEXT"),
      Edge("b", "c", "NEXT"),
      Edge("c", "d", "NEXT"),
      Edge("d", "e", "NEXT")
    )
    Graph(nodes, edges)
  }

  // Diamond graph: A -> B, A -> C, B -> D, C -> D
  private val diamondGraph = {
    val nodes = Map(
      "a" -> Node("a", "Node"),
      "b" -> Node("b", "Node"),
      "c" -> Node("c", "Node"),
      "d" -> Node("d", "Node")
    )
    val edges = List(
      Edge("a", "b", "REL"),
      Edge("a", "c", "REL"),
      Edge("b", "d", "REL"),
      Edge("c", "d", "REL")
    )
    Graph(nodes, edges)
  }

  // --- PageRank tests ---

  test("PageRank should return empty map for empty graph") {
    val scores = GraphRanking.pageRank(Graph.empty)
    scores shouldBe empty
  }

  test("PageRank should assign scores to all nodes") {
    val scores = GraphRanking.pageRank(starGraph)

    scores should have size 5
    scores.keys should contain theSameElementsAs Seq("center", "a", "b", "c", "d")
  }

  test("PageRank scores should sum to approximately 1.0") {
    val scores = GraphRanking.pageRank(starGraph)

    scores.values.sum shouldBe 1.0 +- 0.01
  }

  test("PageRank leaf nodes should have similar scores in star graph") {
    // In a star graph, leaf nodes receive from center, so they should have similar scores.
    // Center has no incoming edges, so it gets only the base score.
    val scores = GraphRanking.pageRank(starGraph)

    // All leaves should have similar scores since they all receive from center
    val leafScores = Seq("a", "b", "c", "d").map(scores)
    leafScores.max - leafScores.min should be < 0.001
  }

  test("PageRank should converge with different damping factors") {
    val scores085 = GraphRanking.pageRank(diamondGraph, dampingFactor = 0.85)
    val scores050 = GraphRanking.pageRank(diamondGraph, dampingFactor = 0.50)

    scores085 should have size 4
    scores050 should have size 4

    // Both should sum to ~1.0
    scores085.values.sum shouldBe 1.0 +- 0.01
    scores050.values.sum shouldBe 1.0 +- 0.01
  }

  test("PageRank on chain should give higher scores to nodes with incoming links") {
    val scores = GraphRanking.pageRank(chainGraph)

    // Node 'a' has no incoming edges, should have lowest score
    // Nodes further down the chain accumulate PageRank
    scores("a") should be < scores("b")
  }

  // --- Degree centrality tests ---

  test("Degree centrality should return empty map for empty graph") {
    val scores = GraphRanking.degreeCentrality(Graph.empty)
    scores shouldBe empty
  }

  test("Degree centrality should rank hub highest in star graph") {
    val scores = GraphRanking.degreeCentrality(starGraph)

    scores("center") should be > scores("a")
    scores("center") shouldBe 1.0 // connected to all 4 others, max possible = 4
  }

  test("Degree centrality of leaf nodes should be equal in star graph") {
    val scores = GraphRanking.degreeCentrality(starGraph)

    val leafScores = Seq("a", "b", "c", "d").map(scores)
    leafScores.distinct should have size 1
  }

  test("Degree centrality should handle single node graph") {
    val singleNode = Graph(Map("a" -> Node("a", "Node")), List.empty)
    val scores     = GraphRanking.degreeCentrality(singleNode)

    scores should have size 1
    scores("a") shouldBe 0.0
  }

  // --- Betweenness centrality tests ---

  test("Betweenness centrality should return zeros for star graph leaves") {
    val scores = GraphRanking.betweennessCentrality(starGraph)

    // Leaf nodes have no shortest paths passing through them
    scores("a") shouldBe 0.0 +- 0.001
  }

  test("Betweenness centrality should rank center highest in star graph") {
    val scores = GraphRanking.betweennessCentrality(starGraph)

    scores("center") should be > scores("a")
  }

  test("Betweenness centrality of middle node in chain should be highest") {
    val scores = GraphRanking.betweennessCentrality(chainGraph)

    // Middle nodes should have higher betweenness since more shortest paths pass through them
    scores("c") should be >= scores("a")
    scores("c") should be >= scores("e")
  }

  test("Betweenness centrality should return zeros for two-node graph") {
    val twoNodes = Graph(
      Map("a" -> Node("a", "Node"), "b" -> Node("b", "Node")),
      List(Edge("a", "b", "REL"))
    )
    val scores = GraphRanking.betweennessCentrality(twoNodes)

    scores.values.foreach(_ shouldBe 0.0)
  }

  test("Betweenness centrality should return empty for empty graph") {
    val scores = GraphRanking.betweennessCentrality(Graph.empty)
    scores shouldBe empty
  }

  // --- Closeness centrality tests ---

  test("Closeness centrality should return zeros for single node") {
    val singleNode = Graph(Map("a" -> Node("a", "Node")), List.empty)
    val scores     = GraphRanking.closenessCentrality(singleNode)

    scores should have size 1
    scores("a") shouldBe 0.0
  }

  test("Closeness centrality should rank center highest in star graph") {
    val scores = GraphRanking.closenessCentrality(starGraph)

    scores("center") should be > scores("a")
  }

  test("Closeness centrality should return empty for empty graph") {
    val scores = GraphRanking.closenessCentrality(Graph.empty)
    scores shouldBe empty
  }

  test("Closeness centrality of middle node in chain should be highest") {
    val scores = GraphRanking.closenessCentrality(chainGraph)

    // The middle node (c) is closest to all others on average
    scores("c") should be >= scores("a")
    scores("c") should be >= scores("e")
  }

  test("Closeness centrality scores should be between 0 and 1") {
    val scores = GraphRanking.closenessCentrality(diamondGraph)

    scores.values.foreach { score =>
      score should be >= 0.0
      score should be <= 1.0
    }
  }

  // --- Cross-algorithm consistency tests ---

  test("All algorithms should return scores for all nodes") {
    val pageRankScores    = GraphRanking.pageRank(diamondGraph)
    val degreeScores      = GraphRanking.degreeCentrality(diamondGraph)
    val betweennessScores = GraphRanking.betweennessCentrality(diamondGraph)
    val closenessScores   = GraphRanking.closenessCentrality(diamondGraph)

    pageRankScores should have size 4
    degreeScores should have size 4
    betweennessScores should have size 4
    closenessScores should have size 4
  }

  test("All algorithms should handle disconnected graph") {
    val disconnected = Graph(
      Map("a" -> Node("a", "Node"), "b" -> Node("b", "Node"), "c" -> Node("c", "Node")),
      List(Edge("a", "b", "REL")) // c is disconnected
    )

    val pageRankScores    = GraphRanking.pageRank(disconnected)
    val degreeScores      = GraphRanking.degreeCentrality(disconnected)
    val betweennessScores = GraphRanking.betweennessCentrality(disconnected)
    val closenessScores   = GraphRanking.closenessCentrality(disconnected)

    pageRankScores should have size 3
    degreeScores should have size 3
    betweennessScores should have size 3
    closenessScores should have size 3

    // Disconnected node should have low scores
    degreeScores("c") shouldBe 0.0
  }
}
