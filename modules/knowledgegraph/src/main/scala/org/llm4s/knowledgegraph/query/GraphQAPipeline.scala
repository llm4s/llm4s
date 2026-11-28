package org.llm4s.knowledgegraph.query

import org.llm4s.context.tokens.{ Tokenizer, TokenizerId }
import org.llm4s.knowledgegraph.{ Graph, Node }
import org.llm4s.knowledgegraph.storage.{ Direction, GraphStore }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Graph-guided question answering pipeline.
 *
 * Uses graph structure to provide rich context for LLM answers. The pipeline follows
 * the pattern: identify entities -> traverse for context -> rank entities -> generate answer.
 *
 * Multi-hop traversal follows relationship chains to gather evidence beyond direct neighbors.
 * Citations track which nodes and edges contributed to the answer.
 *
 * Context selection (#2975): a question's mentions are matched to nodes by name (exactly, or as whole words), the
 * model's guessed label only narrowing those matches; the best `maxEntities` become the identified entities, which
 * the translator is told about (#2973) and which one traversal of `maxHops` expands from. When the context is over
 * `maxContextNodes`, the identified entities are kept first and ranking (or result order) fills the rest; edges
 * touching them come first. Property values are cut at `maxPropertyChars`, paths at `maxPaths`, and the whole context
 * at `maxContextTokens` when set. An empty graph is answered without a model call.
 *
 * @example
 * {{{
 * val pipeline = new GraphQAPipeline(llmClient, graphStore)
 * val result: Result[GraphQAResult] = pipeline.ask("Who does Alice work with?")
 * result.foreach { r =>
 *   println(r.answer)
 *   r.citations.foreach(c => println(s"Source: ${c.nodeLabel} ${c.nodeId}"))
 * }
 * }}}
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

  private val logger = LoggerFactory.getLogger(getClass)

  private val queryExecutor = new GraphQueryExecutor(
    graphStore,
    maxDepth = config.maxQueryDepth,
    maxCompositeSteps = config.maxCompositeSteps,
    maxPaths = config.maxPaths
  )
  private val queryTranslator = new GraphQueryTranslator(llmClient, graphStore)

  /**
   * Answers a natural language question using graph-guided context.
   *
   * Pipeline steps:
   * 1. Identify entities mentioned in the question
   * 2. Translate question to graph query, given those entities' ids
   * 3. Execute query to gather context subgraph, and traverse once from the entities
   * 4. Rank entities by importance (if enabled), keeping the identified ones
   * 5. Generate answer with graph context and citations
   *
   * @param question The natural language question
   * @return Right(result) with answer and citations, Left(error) on failure
   */
  def ask(question: String): Result[GraphQAResult] = answer(question, None)

  /**
   * Answers a question with an explicit pre-built graph query (skipping NL translation).
   *
   * Useful when the caller already knows the appropriate query structure.
   *
   * @param question The natural language question for the answer generation step
   * @param query The pre-built graph query to execute
   * @return Right(result) with answer and citations, Left(error) on failure
   */
  def askWithQuery(question: String, query: GraphQuery): Result[GraphQAResult] = answer(question, Some(query))

  private def answer(question: String, preset: Option[GraphQuery]): Result[GraphQAResult] =
    graphStore.stats().flatMap { stats =>
      if (stats.nodeCount == 0)
        Right(GraphQAResult(GraphQAPipeline.EmptyGraphAnswer, Seq.empty, Seq.empty, GraphQueryResult.empty))
      else
        for {
          entities    <- identifyEntities(question)
          graphQuery  <- preset.fold(queryTranslator.translate(question, entities))(Right(_))
          queryResult <- queryExecutor.execute(graphQuery)
          enriched    <- enrichWithTraversal(queryResult, entities, graphQuery)
          ranked           = applyRanking(enriched, entities)
          (context, shown) = selectContext(ranked)
          answer <- generateAnswer(question, context)
          // Only what the model was shown is cited, after any token budget (#2975)
          citations = if (config.includeCitations) buildCitations(shown) else Seq.empty
        } yield GraphQAResult(
          answer = answer,
          citations = citations,
          entities = entities,
          queryResult = ranked
        )
    }

  /**
   * Identifies entities mentioned in the question and matches them to graph nodes.
   */
  private[query] def identifyEntities(question: String): Result[Seq[IdentifiedEntity]] =
    for {
      schema <- queryTranslator.buildSchemaContext()
      conversation = Conversation(
        messages = List(
          SystemMessage(entityIdentificationSystemPrompt),
          UserMessage(s"$schema\n\nQuestion: $question\n\nExtract entities:")
        )
      )
      completion <- llmClient.complete(conversation, CompletionOptions(temperature = Some(0.0)))
      entities   <- resolveEntitiesToNodes(parseEntityResponse(completion.content))
    } yield entities

  /**
   * Traverses `maxHops` (clamped to `maxQueryDepth`; 0 adds only the entities) from the identified entities the query
   * did not already explore that far, all at once (#2975). A `FindNeighbors` from an entity covers it to the query's
   * depth; any other appearance in the result covers it to depth 0, so an entity the query only found still gets
   * its neighbourhood.
   */
  private def enrichWithTraversal(
    queryResult: GraphQueryResult,
    entities: Seq[IdentifiedEntity],
    query: GraphQuery
  ): Result[GraphQueryResult] = {
    val depth = math.max(0, math.min(config.maxHops, config.maxQueryDepth))
    def covered(q: GraphQuery): Map[String, Int] = q match {
      case GraphQuery.FindNeighbors(id, Direction.Both, None, d) =>
        Map(id -> math.max(1, math.min(d, config.maxQueryDepth)))
      case c: GraphQuery.CompositeQuery =>
        // Only the steps the executor runs explore anything
        queryExecutor.stepsRun(c).map(covered).foldLeft(Map.empty[String, Int]) { (acc, m) =>
          m.foldLeft(acc) { case (a, (id, d)) => a.updated(id, math.max(d, a.getOrElse(id, 0))) }
        }
      case _ => Map.empty
    }
    val coverage = covered(query)
    val present  = queryResult.nodes.map(_.id).toSet
    val pending = entities.map(_.nodeId).distinct.filter { id =>
      val reached = coverage.get(id).orElse(if (present.contains(id)) Some(0) else None)
      reached.forall(_ < depth)
    }
    if (pending.isEmpty) Right(queryResult)
    else
      queryExecutor.neighborhood(pending, depth, Direction.Both, None).map { case (nodes, edges, _) =>
        queryResult.merge(GraphQueryResult(nodes, edges))
      }
  }

  /**
   * Trims the result to `maxContextNodes` nodes, `maxContextEdges` edges and `maxPaths` paths, ranking or not. The
   * identified entities are kept first, so a hub the question names keeps its edges (#2975).
   */
  private def applyRanking(queryResult: GraphQueryResult, entities: Seq[IdentifiedEntity]): GraphQueryResult = {
    val resultIds = queryResult.nodes.map(_.id).toSet
    val pinned    = entities.map(_.nodeId).distinct.filter(resultIds.contains).take(config.maxContextNodes)
    val others    = queryResult.nodes.map(_.id).filterNot(pinned.toSet.contains)
    // Pinned first in every case, so a token budget (which keeps a prefix) never cuts an identified entity
    val keptIds: Seq[String] =
      if (queryResult.nodes.size <= config.maxContextNodes) pinned ++ others
      else {
        val ordered =
          if (!config.useRanking) others
          else {
            val subgraph = Graph(queryResult.nodes.map(n => n.id -> n).toMap, queryResult.edges.toList)
            val scores: Map[String, Double] = config.rankingAlgorithm match {
              case RankingAlgorithm.PageRank              => GraphRanking.pageRank(subgraph)
              case RankingAlgorithm.BetweennessCentrality => GraphRanking.betweennessCentrality(subgraph)
              case RankingAlgorithm.ClosenessCentrality   => GraphRanking.closenessCentrality(subgraph)
              case RankingAlgorithm.DegreeCentrality      => GraphRanking.degreeCentrality(subgraph)
            }
            others.sortBy(id => (-scores.getOrElse(id, 0.0), id))
          }
        pinned ++ ordered.take(config.maxContextNodes - pinned.size)
      }
    val kept             = keptIds.toSet
    val pinnedSet        = pinned.toSet
    val byId             = queryResult.nodes.map(n => n.id -> n).toMap
    val nodes            = keptIds.flatMap(byId.get)
    val candidates       = queryResult.edges.filter(e => kept.contains(e.source) && kept.contains(e.target))
    val (touching, rest) = candidates.partition(e => pinnedSet.contains(e.source) || pinnedSet.contains(e.target))
    queryResult.copy(
      nodes = nodes,
      edges = (touching ++ rest).take(config.maxContextEdges),
      paths = queryResult.paths.take(config.maxPaths)
    )
  }

  private def renderValue(v: ujson.Value): String = {
    val text = v match {
      case ujson.Str(s) => s
      case other        => other.toString
    }
    if (text.length <= config.maxPropertyChars) text
    else text.take(math.max(0, config.maxPropertyChars)) + s"... [${text.length - config.maxPropertyChars} more chars]"
  }

  /**
   * Builds a human-readable context string from the query result for the LLM.
   */
  private[query] def buildContext(queryResult: GraphQueryResult): String = selectContext(queryResult)._1

  /** The context string and the nodes, edges and paths it shows, after the token budget. */
  private def selectContext(queryResult: GraphQueryResult): (String, GraphQueryResult) = {
    val nodeLines = queryResult.nodes.map { node =>
      val propsStr = node.properties.toSeq.sortBy(_._1).map { case (k, v) => s"$k: ${renderValue(v)}" }.mkString(", ")
      s"- [${node.label}] ${node.id}: $propsStr"
    }

    val edgeLines = queryResult.edges.map(edge => s"- ${edge.source} -[${edge.relationship}]-> ${edge.target}")

    val shownPaths = queryResult.paths.take(config.maxPaths)
    val pathLines = shownPaths.zipWithIndex.map { case (path, idx) =>
      val steps = path.map(e => s"${e.source} -[${e.relationship}]-> ${e.target}").mkString(" -> ")
      s"Path ${idx + 1}: $steps"
    }

    val (nodesIn, edgesIn, pathsIn) =
      config.maxContextTokens.flatMap(b => GraphQAPipeline.tokenizer.map(b -> _)) match {
        case None                      => (nodeLines, edgeLines, pathLines)
        case Some((budget, tokenizer)) =>
          // Nodes first, then edges, then paths, each line whole, until the budget is spent
          var left = budget - tokenizer.count("Entities:\n\nRelationships:\n")
          def fit(lines: Seq[String]): Seq[String] = lines.takeWhile { line =>
            val cost = tokenizer.count(line) + 1
            if (cost <= left) { left -= cost; true }
            else { left = 0; false }
          }
          val n = fit(nodeLines)
          val e = fit(edgeLines)
          val p = fit(pathLines)
          (n, e, p)
      }

    val pathsSection = if (pathsIn.nonEmpty) s"\nPaths:\n${pathsIn.mkString("\n")}" else ""

    val context =
      s"""Entities:
         |${nodesIn.mkString("\n")}
         |
         |Relationships:
         |${edgesIn.mkString("\n")}
         |$pathsSection""".stripMargin
    // fit keeps a prefix of each list, so the shown elements are the same-length prefixes
    val shown = queryResult.copy(
      nodes = queryResult.nodes.take(nodesIn.size),
      edges = queryResult.edges.take(edgesIn.size),
      paths = shownPaths.take(pathsIn.size)
    )
    (context, shown)
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
      .complete(conversation, CompletionOptions(temperature = Some(config.temperature)))
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

  /** The reply's mentions; an unreadable reply is no mentions, with a warning (#2975). */
  private def parseEntityResponse(reply: String): Seq[(String, Option[String])] =
    ModelJson.first(reply, '[', "entity identification") match {
      case Left(e) =>
        logger.warn(s"Could not read the entity identification reply, so no entities are used: ${e.message}")
        Seq.empty
      case Right(json) =>
        json.arr.toSeq.flatMap { item =>
          ModelJson.optString(item, "mention", "entity").toOption.flatten.map { mention =>
            mention -> ModelJson.optString(item, "label", "entity").toOption.flatten
          }
        }
    }

  /**
   * Resolves mentions to nodes by name: an exact match on a node's `name` (or `title`) is confidence 1.0, an exact
   * match on another string property 0.8, and the mention as whole words of the name 0.7; a blank mention matches
   * nothing. The guessed label only narrows the matches, when some of them have it. Each node is kept once, at its
   * best confidence, and the best `maxEntities` are returned (#2975).
   */
  private def resolveEntitiesToNodes(mentions: Seq[(String, Option[String])]): Result[Seq[IdentifiedEntity]] =
    if (mentions.isEmpty) Right(Seq.empty)
    else
      graphStore.loadAll().map { graph =>
        val nodes = graph.nodes.values.toSeq.sortBy(_.id)
        mentions
          .flatMap { case (mention, label) =>
            val m = GraphQAPipeline.normalize(mention)
            if (m.isEmpty) Seq.empty
            else {
              val scored   = nodes.flatMap(n => GraphQAPipeline.matchScore(n, m).map(n -> _))
              val labelled = label.toSeq.flatMap(l => scored.filter(_._1.label.equalsIgnoreCase(l)))
              (if (labelled.nonEmpty) labelled else scored).map { case (n, confidence) =>
                IdentifiedEntity(mention = mention, nodeId = n.id, nodeLabel = n.label, confidence = confidence)
              }
            }
          }
          .groupBy(_.nodeId)
          .values
          .map(_.maxBy(_.confidence))
          .toSeq
          .sortBy(e => (-e.confidence, e.nodeId))
          .take(math.max(0, config.maxEntities))
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

private[query] object GraphQAPipeline {

  /** The answer to any question on an empty graph, given without a model call. */
  val EmptyGraphAnswer: String = "The knowledge graph is empty, so it holds nothing to answer from."

  lazy val tokenizer: Option[org.llm4s.context.tokens.StringTokenizer] =
    Tokenizer.lookupStringTokenizer(TokenizerId.CL100K_BASE)

  def normalize(s: String): String = s.trim.toLowerCase.replaceAll("\\s+", " ")

  private val NameKeys = Set("name", "title")

  private def wholeWords(text: String, phrase: String): Boolean =
    s"(^|[^\\p{L}\\p{N}])${java.util.regex.Pattern.quote(phrase)}([^\\p{L}\\p{N}]|$$)".r.findFirstIn(text).isDefined

  def matchScore(node: Node, mention: String): Option[Double] = {
    val strings = node.properties.toSeq.collect { case (k, ujson.Str(v)) => k.toLowerCase -> normalize(v) }
    val names   = strings.collect { case (k, v) if NameKeys.contains(k) => v }
    if (names.contains(mention)) Some(1.0)
    else if (strings.exists(_._2 == mention)) Some(0.8)
    else if (names.exists(n => wholeWords(n, mention))) Some(0.7)
    else None
  }
}
