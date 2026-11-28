package org.llm4s.knowledgegraph.query

/**
 * Configuration for the graph-guided question answering pipeline.
 *
 * @example
 * {{{
 * val config = GraphQAConfig(
 *   maxHops = 3,
 *   useRanking = true,
 *   rankingAlgorithm = RankingAlgorithm.PageRank,
 *   includeCitations = true
 * )
 * val pipeline = new GraphQAPipeline(llmClient, graphStore, config)
 * }}}
 *
 * @param maxHops Maximum number of hops for graph traversal during context gathering
 * @param maxContextNodes Maximum number of nodes to include in LLM context
 * @param maxContextEdges Maximum number of edges to include in LLM context, whether or not ranking is used
 * @param useRanking Whether to use graph ranking algorithms to prioritize entities
 * @param rankingAlgorithm Which ranking algorithm to use (when useRanking is true)
 * @param includeCitations Whether to track and return source citations
 * @param temperature LLM temperature for answer generation (lower = more deterministic)
 * @param maxQueryDepth The ceiling on any depth or hop count, from the model's query, `maxHops` or a tool call (#2974)
 * @param maxCompositeSteps The most steps a model's composite query runs
 * @param maxPaths The most paths a path query returns, and the most the context shows
 * @param maxEntities The most entities identified from a question, the best matches first (#2975)
 * @param maxPropertyChars The longest property value the context shows; a longer one is cut and marked
 * @param maxContextTokens An optional budget for the whole context, counted with the `cl100k_base` tokenizer; lines
 *                         past it are left out
 */
case class GraphQAConfig(
  maxHops: Int = 3,
  maxContextNodes: Int = 50,
  maxContextEdges: Int = 100,
  useRanking: Boolean = true,
  rankingAlgorithm: RankingAlgorithm = RankingAlgorithm.PageRank,
  includeCitations: Boolean = true,
  temperature: Double = 0.2,
  maxQueryDepth: Int = GraphQueryExecutor.DefaultMaxDepth,
  maxCompositeSteps: Int = GraphQueryExecutor.DefaultMaxCompositeSteps,
  maxPaths: Int = GraphQueryExecutor.DefaultMaxPaths,
  maxEntities: Int = 10,
  maxPropertyChars: Int = 500,
  maxContextTokens: Option[Int] = None
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
 * @param confidence Matching confidence score (0.0 to 1.0): 1.0 for an exact name match, less for a whole-word one;
 *                   entities are ranked by it
 */
case class IdentifiedEntity(
  mention: String,
  nodeId: String,
  nodeLabel: String,
  confidence: Double = 1.0
)
