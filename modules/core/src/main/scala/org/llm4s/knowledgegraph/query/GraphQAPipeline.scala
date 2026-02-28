package org.llm4s.knowledgegraph.query

import org.llm4s.knowledgegraph.Graph
import org.llm4s.knowledgegraph.storage.GraphStore
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

import scala.util.Try

/**
 * Graph-guided question answering pipeline.
 *
 * Uses graph structure to provide rich context for LLM answers. The pipeline follows
 * the pattern: identify entities -> traverse for context -> rank entities -> generate answer.
 *
 * Multi-hop traversal follows relationship chains to gather evidence beyond direct neighbors.
 * Citations track which nodes and edges contributed to the answer.
 *
 * @param llmClient The LLM client for entity identification and answer generation
 * @param graphStore The graph store containing the knowledge graph
 * @param config Pipeline configuration
 */
class GraphQAPipeline(
  llmClient: LLMClient,
  graphStore: GraphStore,
  config: GraphQAConfig = GraphQAConfig()
) {

  private val queryExecutor   = new GraphQueryExecutor(graphStore)
  private val queryTranslator = new GraphQueryTranslator(llmClient, graphStore)

  /**
   * Answers a natural language question using graph-guided context.
   *
   * Pipeline steps:
   * 1. Identify entities mentioned in the question
   * 2. Translate question to graph query
   * 3. Execute query to gather context subgraph
   * 4. Rank entities by importance (if enabled)
   * 5. Generate answer with graph context and citations
   *
   * @param question The natural language question
   * @return Right(result) with answer and citations, Left(error) on failure
   */
  def ask(question: String): Result[GraphQAResult] = for {
    entities    <- identifyEntities(question)
    graphQuery  <- queryTranslator.translate(question)
    queryResult <- queryExecutor.execute(graphQuery)
    enriched    <- enrichWithTraversal(queryResult, entities)
    ranked  = applyRanking(enriched)
    context = buildContext(ranked)
    answer <- generateAnswer(question, context)
    citations = if (config.includeCitations) buildCitations(ranked) else Seq.empty
  } yield GraphQAResult(
    answer = answer,
    citations = citations,
    entities = entities,
    queryResult = ranked
  )

  /**
   * Answers a question with an explicit pre-built graph query (skipping NL translation).
   *
   * Useful when the caller already knows the appropriate query structure.
   *
   * @param question The natural language question for the answer generation step
   * @param query The pre-built graph query to execute
   * @return Right(result) with answer and citations, Left(error) on failure
   */
  def askWithQuery(question: String, query: GraphQuery): Result[GraphQAResult] = for {
    entities    <- identifyEntities(question)
    queryResult <- queryExecutor.execute(query)
    enriched    <- enrichWithTraversal(queryResult, entities)
    ranked  = applyRanking(enriched)
    context = buildContext(ranked)
    answer <- generateAnswer(question, context)
    citations = if (config.includeCitations) buildCitations(ranked) else Seq.empty
  } yield GraphQAResult(
    answer = answer,
    citations = citations,
    entities = entities,
    queryResult = ranked
  )

  /**
   * Identifies entities mentioned in the question and matches them to graph nodes.
   */
  private[query] def identifyEntities(question: String): Result[Seq[IdentifiedEntity]] = {
    val prompt = buildEntityIdentificationPrompt(question)
    val conversation = Conversation(
      messages = List(
        SystemMessage(entityIdentificationSystemPrompt),
        UserMessage(prompt)
      )
    )

    llmClient
      .complete(conversation, CompletionOptions(temperature = 0.0))
      .flatMap(completion => parseEntityResponse(completion.content))
      .flatMap(mentions => resolveEntitiesToNodes(mentions))
  }

  /**
   * Enriches query results with multi-hop traversal from identified entities.
   * Follows relationship chains to gather additional evidence.
   */
  private def enrichWithTraversal(
    queryResult: GraphQueryResult,
    entities: Seq[IdentifiedEntity]
  ): Result[GraphQueryResult] = {
    val entityNodeIds = entities.map(_.nodeId).toSet
    val resultNodeIds = queryResult.nodes.map(_.id).toSet
    val missingIds    = entityNodeIds -- resultNodeIds

    if (missingIds.isEmpty || config.maxHops <= 1) {
      Right(queryResult)
    } else {
      // Traverse from entity nodes not already in the result
      val additionalResults = missingIds.toSeq.foldLeft(Right(GraphQueryResult.empty): Result[GraphQueryResult]) {
        case (acc, nodeId) =>
          acc.flatMap { prev =>
            val neighborQuery = GraphQuery.FindNeighbors(nodeId = nodeId, maxDepth = config.maxHops)
            queryExecutor.execute(neighborQuery).map(prev.merge)
          }
      }

      additionalResults.map(queryResult.merge)
    }
  }

  /**
   * Applies graph ranking to prioritize entities in the result.
   * Trims results to fit within the configured context limits.
   */
  private def applyRanking(queryResult: GraphQueryResult): GraphQueryResult =
    if (!config.useRanking || queryResult.nodes.size <= config.maxContextNodes) {
      queryResult
    } else {
      // Build a subgraph from the query result for ranking
      val subgraph = Graph(
        nodes = queryResult.nodes.map(n => n.id -> n).toMap,
        edges = queryResult.edges.toList
      )

      val scores: Map[String, Double] = config.rankingAlgorithm match {
        case RankingAlgorithm.PageRank              => GraphRanking.pageRank(subgraph)
        case RankingAlgorithm.BetweennessCentrality => GraphRanking.betweennessCentrality(subgraph)
        case RankingAlgorithm.ClosenessCentrality   => GraphRanking.closenessCentrality(subgraph)
        case RankingAlgorithm.DegreeCentrality      => GraphRanking.degreeCentrality(subgraph)
      }

      // Keep the top-ranked nodes within the context limit
      val rankedNodeIds = scores.toSeq
        .sortBy(-_._2)
        .take(config.maxContextNodes)
        .map(_._1)
        .toSet

      val filteredNodes = queryResult.nodes.filter(n => rankedNodeIds.contains(n.id))
      val filteredEdges = queryResult.edges
        .filter(e => rankedNodeIds.contains(e.source) && rankedNodeIds.contains(e.target))
        .take(config.maxContextEdges)

      queryResult.copy(nodes = filteredNodes, edges = filteredEdges)
    }

  /**
   * Builds a human-readable context string from the query result for the LLM.
   */
  private[query] def buildContext(queryResult: GraphQueryResult): String = {
    val nodesSection = queryResult.nodes
      .map { node =>
        val propsStr = node.properties
          .map { case (k, v) =>
            val value = v match {
              case ujson.Str(s) => s
              case other        => other.toString
            }
            s"$k: $value"
          }
          .mkString(", ")
        s"- [${node.label}] ${node.id}: $propsStr"
      }
      .mkString("\n")

    val edgesSection =
      queryResult.edges.map(edge => s"- ${edge.source} -[${edge.relationship}]-> ${edge.target}").mkString("\n")

    val pathsSection = if (queryResult.paths.nonEmpty) {
      val pathStrings = queryResult.paths.zipWithIndex
        .map { case (path, idx) =>
          val steps = path.map(e => s"${e.source} -[${e.relationship}]-> ${e.target}").mkString(" -> ")
          s"Path ${idx + 1}: $steps"
        }
        .mkString("\n")
      s"\nPaths:\n$pathStrings"
    } else ""

    s"""Entities:
       |$nodesSection
       |
       |Relationships:
       |$edgesSection
       |$pathsSection""".stripMargin
  }

  /**
   * Generates the final answer using the LLM with graph context.
   */
  private def generateAnswer(question: String, context: String): Result[String] = {
    val conversation = Conversation(
      messages = List(
        SystemMessage(answerGenerationSystemPrompt),
        UserMessage(
          s"""Knowledge Graph Context:
             |$context
             |
             |Question: $question
             |
             |Answer the question based on the knowledge graph context above. Be specific and cite
             |the entities and relationships that support your answer.""".stripMargin
        )
      )
    )

    llmClient
      .complete(conversation, CompletionOptions(temperature = config.temperature))
      .map(_.content)
  }

  /**
   * Builds citations from the graph context that contributed to the answer.
   */
  private def buildCitations(queryResult: GraphQueryResult): Seq[Citation] = {
    val nodeCitations = queryResult.nodes.flatMap { node =>
      if (node.properties.nonEmpty) {
        node.properties.map { case (key, value) =>
          val valueStr = value match {
            case ujson.Str(s) => s
            case other        => other.toString
          }
          Citation(
            nodeId = node.id,
            nodeLabel = node.label,
            property = key,
            value = valueStr
          )
        }
      } else {
        Seq(Citation(nodeId = node.id, nodeLabel = node.label))
      }
    }

    val nodeMap = queryResult.nodes.map(n => n.id -> n.label).toMap

    val edgeCitations = queryResult.edges.map { edge =>
      val sourceLabel = nodeMap.getOrElse(edge.source, "Unknown")
      val targetLabel = nodeMap.getOrElse(edge.target, "Unknown")
      Citation(
        nodeId = edge.source,
        nodeLabel = sourceLabel,
        relationship = Some(edge.relationship),
        property = "relationship",
        value = s"$sourceLabel(${edge.source}) -> $targetLabel(${edge.target})"
      )
    }

    (nodeCitations ++ edgeCitations).take(config.maxContextNodes + config.maxContextEdges)
  }

  // --- Entity identification ---

  private val entityIdentificationSystemPrompt: String =
    """You are an entity extraction expert. Given a question, identify the key entities
      |that should be looked up in a knowledge graph.
      |
      |Output ONLY a valid JSON array of entity mentions. Each mention is an object with:
      |- "mention": the entity text as it appears in the question
      |- "label": the likely entity type (e.g., "Person", "Organization", "Location", "Concept")
      |
      |Example: [{"mention": "Alice", "label": "Person"}, {"mention": "Acme Corp", "label": "Organization"}]
      |
      |Output ONLY the JSON array, nothing else.""".stripMargin

  private def buildEntityIdentificationPrompt(question: String): String =
    queryTranslator.buildSchemaContext() match {
      case Right(schema) => s"$schema\n\nQuestion: $question\n\nExtract entities:"
      case Left(_)       => s"Question: $question\n\nExtract entities:"
    }

  private def parseEntityResponse(jsonStr: String): Result[Seq[(String, String)]] = {
    val cleaned = jsonStr.trim
      .stripPrefix("```json")
      .stripPrefix("```")
      .stripSuffix("```")
      .trim

    // If entity extraction fails, continue with empty entities rather than failing
    Try {
      val arr = ujson.read(cleaned).arr
      arr.map { item =>
        val mention = item("mention").str
        val label   = item("label").str
        (mention, label)
      }.toSeq
    }.fold(_ => Right(Seq.empty), Right(_))
  }

  /**
   * Resolves entity mentions to actual graph nodes by searching for matching nodes.
   */
  private def resolveEntitiesToNodes(mentions: Seq[(String, String)]): Result[Seq[IdentifiedEntity]] =
    graphStore.loadAll().map { graph =>
      mentions.flatMap { case (mention, label) =>
        // Search by label match first
        val byLabel = graph.findNodesByLabel(label)

        // Then search by property match (name-based)
        val byName = graph.nodes.values.filter { node =>
          node.properties.exists { case (_, v) =>
            v match {
              case ujson.Str(s) => s.toLowerCase.contains(mention.toLowerCase)
              case _            => false
            }
          }
        }.toSeq

        // Combine and score matches
        val candidates = (byLabel ++ byName).distinctBy(_.id)
        candidates.map { node =>
          val confidence = if (byName.exists(_.id == node.id)) 0.9 else 0.5
          IdentifiedEntity(
            mention = mention,
            nodeId = node.id,
            nodeLabel = node.label,
            confidence = confidence
          )
        }
      }
    }

  // --- System prompts ---

  private val answerGenerationSystemPrompt: String =
    """You are a knowledgeable assistant that answers questions based on knowledge graph data.
      |
      |You will be given:
      |1. A knowledge graph context containing entities (nodes) and their relationships (edges)
      |2. A question to answer
      |
      |Guidelines:
      |- Answer ONLY based on the information provided in the knowledge graph context
      |- Be specific and reference the entities and relationships that support your answer
      |- If the context doesn't contain enough information, say so clearly
      |- For multi-hop questions, trace the reasoning path through the relationships
      |- Keep your answer concise but complete""".stripMargin
}
