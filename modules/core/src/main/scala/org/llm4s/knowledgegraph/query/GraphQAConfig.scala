package org.llm4s.knowledgegraph.query

/**
 * Configuration for the graph-guided question answering pipeline.
 *
 * @param maxHops Maximum number of hops for graph traversal during context gathering
 * @param maxContextNodes Maximum number of nodes to include in LLM context
 * @param maxContextEdges Maximum number of edges to include in LLM context
 * @param useRanking Whether to use graph ranking algorithms to prioritize entities
 * @param rankingAlgorithm Which ranking algorithm to use (when useRanking is true)
 * @param includeCitations Whether to track and return source citations
 * @param temperature LLM temperature for answer generation (lower = more deterministic)
 */
case class GraphQAConfig(
  maxHops: Int = 3,
  maxContextNodes: Int = 50,
  maxContextEdges: Int = 100,
  useRanking: Boolean = true,
  rankingAlgorithm: RankingAlgorithm = RankingAlgorithm.PageRank,
  includeCitations: Boolean = true,
  temperature: Double = 0.2
)

/**
 * Available graph ranking algorithms for entity prioritization.
 */
sealed trait RankingAlgorithm

object RankingAlgorithm {
  case object PageRank              extends RankingAlgorithm
  case object BetweennessCentrality extends RankingAlgorithm
  case object ClosenessCentrality   extends RankingAlgorithm
  case object DegreeCentrality      extends RankingAlgorithm
}

/**
 * Result of graph-guided question answering.
 *
 * @param answer The generated answer text
 * @param citations Sources that contributed to the answer
 * @param entities Key entities identified in the question and used for context
 * @param queryResult The raw graph query result used for context
 */
case class GraphQAResult(
  answer: String,
  citations: Seq[Citation],
  entities: Seq[IdentifiedEntity],
  queryResult: GraphQueryResult
)

/**
 * A citation tracking which graph element contributed to the answer.
 *
 * @param nodeId The ID of the contributing node
 * @param nodeLabel The label of the contributing node
 * @param relationship Optional relationship that connected this information
 * @param property The specific property that was cited
 * @param value The cited value
 */
case class Citation(
  nodeId: String,
  nodeLabel: String,
  relationship: Option[String] = None,
  property: String = "",
  value: String = ""
)

/**
 * An entity identified from the user's question and matched to a graph node.
 *
 * @param mention The text mention from the question
 * @param nodeId The matched node ID in the graph
 * @param nodeLabel The label of the matched node
 * @param confidence Matching confidence score (0.0 to 1.0)
 */
case class IdentifiedEntity(
  mention: String,
  nodeId: String,
  nodeLabel: String,
  confidence: Double = 1.0
)
