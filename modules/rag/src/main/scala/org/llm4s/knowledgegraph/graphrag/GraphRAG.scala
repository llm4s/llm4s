package org.llm4s.knowledgegraph.graphrag

import org.llm4s.error.{ ConfigurationError, ProcessingError }
import org.llm4s.knowledgegraph.storage.GraphStore
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result
import org.llm4s.vectorstore.HybridSearchResult

import scala.collection.mutable

final case class GraphRAGConfig(
  maxCommunityLevels: Int = 3,
  communityIterations: Int = 8,
  minCommunitySize: Int = 2,
  globalTopCommunities: Int = 4,
  localTraversalDepth: Int = 2,
  hybridVectorWeight: Double = 0.6,
  hybridGraphWeight: Double = 0.4
) {
  require(maxCommunityLevels >= 1, "maxCommunityLevels must be >= 1")
  require(communityIterations >= 1, "communityIterations must be >= 1")
  require(minCommunitySize >= 1, "minCommunitySize must be >= 1")
  require(globalTopCommunities >= 1, "globalTopCommunities must be >= 1")
  require(localTraversalDepth >= 1, "localTraversalDepth must be >= 1")
  require(hybridVectorWeight >= 0.0, "hybridVectorWeight must be non-negative")
  require(hybridGraphWeight >= 0.0, "hybridGraphWeight must be non-negative")
  require(
    hybridVectorWeight + hybridGraphWeight > 0.0,
    "hybridVectorWeight and hybridGraphWeight cannot both be 0"
  )
}

final case class GraphCommunity(
  id: String,
  level: Int,
  nodeIds: Set[String],
  edgeCount: Int,
  parentCommunityId: Option[String] = None,
  summary: Option[String] = None
)

final case class GraphCommunityHierarchy(levels: Vector[Vector[GraphCommunity]]) {
  def allCommunities: Vector[GraphCommunity] = levels.flatten
  def topLevel: Vector[GraphCommunity]       = levels.lastOption.getOrElse(Vector.empty)
}

sealed trait GraphRAGMode
object GraphRAGMode {
  case object Global extends GraphRAGMode
  case object Local  extends GraphRAGMode
  case object Hybrid extends GraphRAGMode
}

final case class GraphGlobalSearchResult(
  query: String,
  answer: String,
  rankedCommunities: Seq[(GraphCommunity, Double)],
  partialAnswers: Seq[(String, String)]
)

final case class GraphLocalSearchResult(
  query: String,
  answer: String,
  seedNodeIds: Seq[String],
  visitedNodeIds: Seq[String],
  supportingCommunities: Seq[GraphCommunity]
)

final case class GraphHybridHit(
  node: Node,
  score: Double,
  vectorScore: Double,
  graphScore: Double
)

final case class GraphHybridSearchResult(
  query: String,
  answer: String,
  seedNodeIds: Seq[String],
  hits: Seq[GraphHybridHit],
  communities: Seq[GraphCommunity] = Seq.empty
)

final case class GraphRAGAnswer(
  query: String,
  mode: GraphRAGMode,
  answer: String,
  communityIds: Seq[String],
  nodeIds: Seq[String]
)

/**
 * GraphRAG pipeline: hierarchical community detection, LLM summaries,
 * global and local graph retrieval, and hybrid vector+graph retrieval.
 *
 * GraphRAG only reads `graphStore`. Communities and their summaries are held by this instance, so every node and edge
 * in the store is the caller's data, whatever its label or id, and other readers of the store see the same graph
 * before and after a query (#2965). Concurrent first queries share one summarisation.
 */
final class GraphRAG(
  graphStore: GraphStore,
  llmClient: LLMClient,
  config: GraphRAGConfig = GraphRAGConfig()
) {

  @volatile private var hierarchyCache: Option[GraphCommunityHierarchy] = None
  @volatile private var graphCache: Option[Graph]                       = None

  /** Held while the graph is loaded or communities are built, so concurrent first queries build them once. */
  private val buildLock = new Object

  def detectCommunities(forceRefresh: Boolean = false): Result[GraphCommunityHierarchy] =
    hierarchyCache match {
      case Some(h) if !forceRefresh => Right(h)
      case _ =>
        buildLock.synchronized {
          hierarchyCache match {
            case Some(h) if !forceRefresh => Right(h)
            case _ =>
              if (forceRefresh) graphCache = None
              loadBaseGraph().map { graph =>
                val hierarchy = GraphRAG.buildHierarchy(graph, config)
                hierarchyCache = Some(hierarchy)
                hierarchy
              }
          }
        }
    }

  def summarizeCommunities(forceRefresh: Boolean = false): Result[GraphCommunityHierarchy] =
    hierarchyCache match {
      case Some(cached) if !forceRefresh && GraphRAG.hasSummaries(cached) =>
        Right(cached)
      case _ =>
        buildLock.synchronized {
          hierarchyCache match {
            // Another caller summarised while this one waited for the lock
            case Some(cached) if !forceRefresh && GraphRAG.hasSummaries(cached) =>
              Right(cached)
            case _ =>
              for {
                hierarchy <- detectCommunities(forceRefresh)
                graph     <- loadBaseGraph()
                summarized <- GraphRAG.summarizeHierarchy(
                  graph = graph,
                  hierarchy = hierarchy,
                  llmClient = llmClient
                )
              } yield {
                hierarchyCache = Some(summarized)
                summarized
              }
          }
        }
    }

  def globalSearch(query: String, topK: Int = config.globalTopCommunities): Result[GraphGlobalSearchResult] =
    for {
      hierarchy <- summarizeCommunities(forceRefresh = false)
      scored = hierarchy.allCommunities
        .filter(_.summary.exists(_.trim.nonEmpty))
        .map(c => c -> GraphRAG.lexicalScore(query, c.summary.getOrElse("")))
        .sortBy { case (_, score) => -score }
      ranked = {
        val positive = scored.filter(_._2 > 0.0).take(topK)
        if (positive.nonEmpty) positive else scored.take(topK)
      }
      _ <-
        if (ranked.nonEmpty) Right(())
        else {
          Left(
            ProcessingError(
              "global_search_no_relevant_communities",
              "No community summaries available for global search"
            )
          )
        }
      partials <- GraphRAG.mapPartials(query, ranked, llmClient)
      answer   <- GraphRAG.reducePartials(query, partials, llmClient)
    } yield GraphGlobalSearchResult(query, answer, ranked, partials)

  def localSearch(query: String, maxDepth: Int = config.localTraversalDepth): Result[GraphLocalSearchResult] =
    for {
      graph     <- loadBaseGraph()
      hierarchy <- summarizeCommunities(forceRefresh = false)
      seeds = GraphRAG.findSeedNodes(query, graph).take(3).map(_.id)
      _ <-
        if (seeds.nonEmpty) Right(())
        else Left(ProcessingError("local_search_no_seeds", "No seed entities were found for local search"))
      depths  = GraphRAG.traverseWithDepth(graph, seeds.toSet, maxDepth)
      ordered = GraphRAG.orderByDepth(depths, seeds)
      _ <-
        if (ordered.nonEmpty) Right(())
        else
          Left(ProcessingError("local_search_no_traversal", "No traversable neighborhood found for local search seeds"))
      traversal = ordered.toSet
      supportingCommunities = hierarchy.allCommunities
        .filter(c => c.nodeIds.exists(traversal.contains))
        .sortBy(c => -c.nodeIds.count(traversal.contains))
        .take(config.globalTopCommunities)
      answer <- GraphRAG.answerLocal(query, graph, seeds, ordered, depths, supportingCommunities, llmClient)
    } yield GraphLocalSearchResult(
      query = query,
      answer = answer,
      seedNodeIds = seeds,
      visitedNodeIds = ordered,
      supportingCommunities = supportingCommunities
    )

  def hybridSearch(
    query: String,
    vectorResults: Seq[HybridSearchResult],
    maxDepth: Int = config.localTraversalDepth
  ): Result[GraphHybridSearchResult] =
    for {
      graph     <- loadBaseGraph()
      hierarchy <- summarizeCommunities(forceRefresh = false)
      seedIds  = GraphRAG.resolveSeedsFromVector(vectorResults, graph)
      expanded = GraphRAG.traverseWithDepth(graph, seedIds, maxDepth)
      hits     = GraphRAG.rankHybridHits(graph, expanded, vectorResults, config)
      hitIds   = hits.map(_.node.id).toSet
      communities = hierarchy.allCommunities
        .filter(c => c.nodeIds.exists(hitIds.contains))
        .sortBy(c => -c.nodeIds.count(hitIds.contains))
        .take(config.globalTopCommunities)
      answer <- GraphRAG.answerHybrid(query, graph, hits, vectorResults, communities, llmClient)
    } yield GraphHybridSearchResult(query, answer, seedIds.toSeq.sorted, hits, communities)

  /**
   * Chooses Global or Local for a query from the graph alone: Global for a broad question ("overall", "themes",
   * "summarize", ...) or one that names no entity of the graph, Local when a whole word of the query matches a node.
   * It never returns Hybrid, which needs vector results: [[answer]] runs Hybrid when it is given them, and
   * `RAG.queryWithGraphRAGMode(question, GraphRAGMode.Hybrid)` runs it explicitly.
   */
  def route(query: String): Result[GraphRAGMode] =
    loadBaseGraph().map { graph =>
      val broadIndicators =
        Set("overall", "themes", "theme", "across", "summarize", "summary", "landscape", "big picture")
      val q              = query.toLowerCase
      val hasBroadIntent = broadIndicators.exists(indicator => q.contains(indicator))
      val hasEntities    = GraphRAG.findSeedNodes(query, graph).nonEmpty
      if (hasBroadIntent) GraphRAGMode.Global
      else if (hasEntities) GraphRAGMode.Local
      else GraphRAGMode.Global
    }

  def answer(
    query: String,
    vectorResults: Seq[HybridSearchResult] = Seq.empty
  ): Result[GraphRAGAnswer] =
    if (vectorResults.nonEmpty) {
      hybridSearch(query, vectorResults).map { hybrid =>
        GraphRAGAnswer(
          query = query,
          mode = GraphRAGMode.Hybrid,
          answer = hybrid.answer,
          communityIds = hybrid.communities.map(_.id),
          nodeIds = hybrid.hits.map(_.node.id)
        )
      }
    } else {
      route(query).flatMap {
        case GraphRAGMode.Global =>
          globalSearch(query).map { global =>
            GraphRAGAnswer(
              query = query,
              mode = GraphRAGMode.Global,
              answer = global.answer,
              communityIds = global.rankedCommunities.map(_._1.id),
              nodeIds = Seq.empty
            )
          }
        case GraphRAGMode.Local =>
          localSearch(query).map { local =>
            GraphRAGAnswer(
              query = query,
              mode = GraphRAGMode.Local,
              answer = local.answer,
              communityIds = local.supportingCommunities.map(_.id),
              nodeIds = local.visitedNodeIds
            )
          }
        case GraphRAGMode.Hybrid =>
          Left(ConfigurationError("Hybrid mode requires vector results"))
      }
    }

  private def loadBaseGraph(): Result[Graph] =
    graphCache match {
      case Some(g) => Right(g)
      case None =>
        buildLock.synchronized {
          graphCache match {
            case Some(g) => Right(g)
            case None =>
              graphStore.loadAll().map { graph =>
                graphCache = Some(graph)
                graph
              }
          }
        }
    }
}

private[graphrag] object GraphRAG {

  def hasSummaries(hierarchy: GraphCommunityHierarchy): Boolean =
    hierarchy.allCommunities.forall(_.summary.exists(_.trim.nonEmpty))

  private val SummaryModelTemp       = CompletionOptions(temperature = Some(0.0), maxTokens = Some(512))
  private val AnswerModelTemp        = CompletionOptions(temperature = Some(0.0), maxTokens = Some(1024))
  private val NamePropertyKeys       = Seq("name", "title", "entity", "id")
  private val ProvenancePropertyKeys = Seq("source", "docId", "documentId", "path")
  private val EntityMetadataKeys     = Seq("entityId", "entity", "entity_id", "nodeId")
  private val UnclusteredKey         = "__small_merged__"

  /** Nodes and relationships sent in a Local or Hybrid prompt. */
  private val MaxPromptNodes = 30
  private val MaxPromptEdges = 40

  /** Hybrid prompt bounds: the hits and passages shown, and the characters of each. */
  private val MaxHybridHits      = 10
  private val MaxHybridPassages  = 10
  private val MaxPassageChars    = 1200
  private val MaxPropertyChars   = 300
  private val MaxNodeLineChars   = 1000
  private val MaxUnclusteredList = 25

  /** Query and node tokens shorter than this never match. */
  private val MinTokenLength = 2

  /** Seed weights: a token of the node's name counts above one of its id, which counts above any other property. */
  private val NameTokenWeight     = 3
  private val IdTokenWeight       = 2
  private val PropertyTokenWeight = 1

  private val Stopwords: Set[String] = (
    "a about above after again against all also am an and any are as at be because been before being " +
      "below between both but by can could did do does doing done down during each else even ever every few " +
      "for from further get give go had has have having he her here hers herself him himself his how i if " +
      "in into is it its itself just know let like many may me might more most much must my myself no nor " +
      "not now of off on once one only or other our ours ourselves out over own please same say shall she " +
      "should show so some such tell than that the their theirs them themselves then there these they thing " +
      "things this those through to too under until up us very was we were what when where which while who " +
      "whom whose why will with would you your yours yourself yourselves"
  ).split(' ').toSet

  def buildHierarchy(graph: Graph, config: GraphRAGConfig): GraphCommunityHierarchy = {
    if (graph.nodes.isEmpty) {
      return GraphCommunityHierarchy(Vector(Vector.empty))
    }

    var levels = Vector.empty[Vector[GraphCommunity]]

    val level0Assignment = labelPropagationPartition(
      nodeIds = graph.nodes.keySet,
      adjacency = buildAdjacency(graph),
      iterations = config.communityIterations
    )

    var currentLevelCommunities = buildCommunitiesForLevel(
      graph = graph,
      level = 0,
      assignment = level0Assignment,
      minCommunitySize = config.minCommunitySize
    )

    levels = levels :+ currentLevelCommunities

    var level = 1
    while (level < config.maxCommunityLevels && currentLevelCommunities.size > 1) {
      val parentAssignmentTemp = labelPropagationPartition(
        nodeIds = currentLevelCommunities.map(_.id).toSet,
        adjacency = buildCommunityAdjacency(graph, currentLevelCommunities),
        iterations = config.communityIterations
      )

      val distinctParents = parentAssignmentTemp.values.toSet
      if (distinctParents.size == currentLevelCommunities.size) {
        level = config.maxCommunityLevels
      } else {
        val parentIdRemap = distinctParents.toSeq.sorted.zipWithIndex.map { case (rawId, idx) =>
          rawId -> s"L$level-C$idx"
        }.toMap

        val updatedPrevLevel = currentLevelCommunities.map { community =>
          community.copy(parentCommunityId = Some(parentIdRemap(parentAssignmentTemp(community.id))))
        }
        levels = levels.dropRight(1) :+ updatedPrevLevel

        val grouped = currentLevelCommunities.groupBy(c => parentIdRemap(parentAssignmentTemp(c.id)))
        val nextLevel = grouped.toSeq
          .sortBy(_._1)
          .map { case (communityId, children) =>
            val nodeIds   = children.flatMap(_.nodeIds).toSet
            val edgeCount = graph.edges.count(e => nodeIds.contains(e.source) && nodeIds.contains(e.target))
            GraphCommunity(
              id = communityId,
              level = level,
              nodeIds = nodeIds,
              edgeCount = edgeCount
            )
          }
          .toVector

        levels = levels :+ nextLevel
        currentLevelCommunities = nextLevel
        level += 1
      }
    }

    GraphCommunityHierarchy(levels)
  }

  def summarizeHierarchy(
    graph: Graph,
    hierarchy: GraphCommunityHierarchy,
    llmClient: LLMClient
  ): Result[GraphCommunityHierarchy] = {
    val summarizedLevels =
      hierarchy.levels.foldLeft[Result[Vector[Vector[GraphCommunity]]]](Right(Vector.empty)) { (acc, level) =>
        for {
          built <- acc
          summarizedLevel <- level.foldLeft[Result[Vector[GraphCommunity]]](Right(Vector.empty)) {
            (levelAcc, community) =>
              for {
                running <- levelAcc
                summary <- community.summary.filter(_.trim.nonEmpty) match {
                  case Some(listed) => Right(listed)
                  case None         => summarizeCommunity(graph, community, llmClient)
                }
              } yield running :+ community.copy(summary = Some(summary))
          }
        } yield built :+ summarizedLevel
      }

    summarizedLevels.map(GraphCommunityHierarchy.apply)
  }

  def mapPartials(
    query: String,
    rankedCommunities: Seq[(GraphCommunity, Double)],
    llmClient: LLMClient
  ): Result[Seq[(String, String)]] =
    rankedCommunities.foldLeft[Result[Seq[(String, String)]]](Right(Seq.empty)) { case (acc, (community, _)) =>
      for {
        done <- acc
        partial <- {
          val summary = community.summary.getOrElse("")
          val convo = Conversation(
            Seq(
              SystemMessage("Answer the question using only the provided community summary."),
              UserMessage(
                s"""Question: $query
                   |
                   |Community summary:
                   |$summary
                   |
                   |Provide a concise partial answer grounded only in this summary.""".stripMargin
              )
            )
          )
          llmClient.complete(convo, AnswerModelTemp).map(_.content)
        }
      } yield done :+ (community.id -> partial)
    }

  def reducePartials(
    query: String,
    partials: Seq[(String, String)],
    llmClient: LLMClient
  ): Result[String] = {
    val combined = partials.map { case (id, partial) => s"[$id] $partial" }.mkString("\n")
    val convo = Conversation(
      Seq(
        SystemMessage("Synthesize a single coherent answer from partial answers."),
        UserMessage(
          s"""Question: $query
             |
             |Partial answers:
             |$combined
             |
             |Return one concise final answer.""".stripMargin
        )
      )
    )
    llmClient.complete(convo, AnswerModelTemp).map(_.content)
  }

  def answerLocal(
    query: String,
    graph: Graph,
    seedIds: Seq[String],
    orderedNodeIds: Seq[String],
    depths: Map[String, Int],
    communities: Seq[GraphCommunity],
    llmClient: LLMClient
  ): Result[String] = {
    // orderedNodeIds is seeds first, then by depth, so the cut keeps the nearest nodes
    val shown          = orderedNodeIds.flatMap(graph.nodes.get).take(MaxPromptNodes)
    val nodeLines      = shown.map(renderNode).mkString("\n")
    val edgeLines      = edgesAmong(graph, shown.map(_.id).toSet, depths).map(renderEdge).mkString("\n")
    val communityLines = communities.flatMap(_.summary).take(8).mkString("\n\n")

    val convo = Conversation(
      Seq(
        SystemMessage("Answer the question using the local graph neighborhood context."),
        UserMessage(
          s"""Question: $query
             |Seed entities: ${seedIds.mkString(", ")}
             |
             |Nodes:
             |$nodeLines
             |
             |Relationships:
             |$edgeLines
             |
             |Community summaries:
             |$communityLines
             |
             |Provide a concise answer grounded in this context.""".stripMargin
        )
      )
    )

    llmClient.complete(convo, AnswerModelTemp).map(_.content)
  }

  def answerHybrid(
    query: String,
    graph: Graph,
    hits: Seq[GraphHybridHit],
    vectorResults: Seq[HybridSearchResult],
    communities: Seq[GraphCommunity],
    llmClient: LLMClient
  ): Result[String] = {
    val passages = vectorResults
      .sortBy(r => -r.score)
      .take(MaxHybridPassages)
      .zipWithIndex
      .map { case (r, i) =>
        f"[${i + 1}] (${r.id}, score=${r.score}%.4f)\n${truncate(r.content.trim, MaxPassageChars)}"
      }
      .mkString("\n\n")
    val topHits = hits.take(MaxHybridHits)
    val nodeLines = topHits
      .map { hit =>
        f"${truncate(renderNodeDetailed(hit.node), MaxNodeLineChars)} (score=${hit.score}%.4f vector=${hit.vectorScore}%.4f graph=${hit.graphScore}%.4f)"
      }
      .mkString("\n")
    val edgeLines = edgesAmong(graph, topHits.map(_.node.id).toSet, Map.empty).map(renderEdge).mkString("\n")
    val summaries = communities.flatMap(_.summary).take(8).mkString("\n\n")

    val convo = Conversation(
      Seq(
        SystemMessage("Answer the question using hybrid vector and graph evidence."),
        UserMessage(
          s"""Question: $query
             |
             |Retrieved passages:
             |$passages
             |
             |Graph nodes:
             |$nodeLines
             |
             |Relationships:
             |$edgeLines
             |
             |Community summaries:
             |$summaries
             |
             |Return a concise answer based on this evidence.""".stripMargin
        )
      )
    )
    llmClient.complete(convo, AnswerModelTemp).map(_.content)
  }

  def findSeedNodes(query: String, graph: Graph): Seq[Node] = {
    val tokens = tokenize(query)
    if (tokens.isEmpty) return Seq.empty

    val scored = graph.nodes.values.toSeq.flatMap { node =>
      val nameTokens =
        NamePropertyKeys.flatMap(node.properties.get).collect { case ujson.Str(s) => tokenize(s) }.flatten.toSet
      val idTokens = tokenize(node.id)
      val propTokens = node.properties
        .collect {
          case (k, ujson.Str(s)) if !NamePropertyKeys.contains(k) => tokenize(s)
        }
        .flatten
        .toSet
      // Each query word counts once per field kind, so a node repeating a word in many properties cannot outrank its name
      val score =
        NameTokenWeight * tokens.count(nameTokens.contains) +
          IdTokenWeight * tokens.count(idTokens.contains) +
          PropertyTokenWeight * tokens.count(propTokens.contains)
      if (score > 0) Some(node -> score) else None
    }

    scored.sortBy { case (node, score) => (-score, node.id) }.map(_._1)
  }

  /** Node ids seeds first, in the given seed order, then by depth and id. */
  def orderByDepth(depths: Map[String, Int], seedIds: Seq[String]): Seq[String] = {
    val seedRank = seedIds.zipWithIndex.toMap
    depths.toSeq
      .sortBy { case (id, depth) => (depth, seedRank.getOrElse(id, Int.MaxValue), id) }
      .map(_._1)
  }

  def traverseWithDepth(graph: Graph, seedIds: Set[String], maxDepth: Int): Map[String, Int] = {
    val adjacency = buildAdjacency(graph)
    val visited   = mutable.Map.empty[String, Int]
    val queue     = mutable.Queue.empty[(String, Int)]

    seedIds.filter(graph.nodes.contains).toSeq.sorted.foreach(id => queue.enqueue((id, 0)))

    while (queue.nonEmpty) {
      val (nodeId, depth) = queue.dequeue()
      if (!visited.contains(nodeId) || depth < visited(nodeId)) {
        visited.update(nodeId, depth)
        if (depth < maxDepth) {
          adjacency.getOrElse(nodeId, Set.empty).toSeq.sorted.foreach { neighbor =>
            if (!visited.contains(neighbor) || depth + 1 < visited(neighbor)) {
              queue.enqueue((neighbor, depth + 1))
            }
          }
        }
      }
    }

    visited.toMap
  }

  def resolveSeedsFromVector(
    vectorResults: Seq[HybridSearchResult],
    graph: Graph
  ): Set[String] = {
    val directIds = vectorResults
      .flatMap { res =>
        val metadata = res.metadata
        EntityMetadataKeys.flatMap(metadata.get)
      }
      .filter(graph.nodes.contains)
      .toSet

    if (directIds.nonEmpty) return directIds

    val provenanceMatches = vectorResults.flatMap { res =>
      val provenanceValues = ProvenancePropertyKeys.flatMap(res.metadata.get).map(_.toLowerCase).toSet
      graph.nodes.values
        .filter { node =>
          val nodeProvenance =
            ProvenancePropertyKeys.flatMap(node.properties.get).collect { case ujson.Str(s) => s.toLowerCase }.toSet
          provenanceValues.intersect(nodeProvenance).nonEmpty
        }
        .map(_.id)
    }.toSet

    if (provenanceMatches.nonEmpty) return provenanceMatches

    val queryText = vectorResults.map(_.content).mkString(" ")
    findSeedNodes(queryText, graph).take(5).map(_.id).toSet
  }

  def rankHybridHits(
    graph: Graph,
    nodeDepths: Map[String, Int],
    vectorResults: Seq[HybridSearchResult],
    config: GraphRAGConfig
  ): Seq[GraphHybridHit] = {
    val maxDepthSeen = math.max(nodeDepths.values.foldLeft(0)(math.max), 1)
    val totalWeight  = config.hybridVectorWeight + config.hybridGraphWeight
    val evidence     = vectorResults.map(r => VectorEvidence(r))

    nodeDepths.toSeq
      .flatMap { case (nodeId, depth) =>
        graph.nodes.get(nodeId).map { node =>
          val vectorScore = nodeVectorScore(node, evidence)
          val graphScore  = 1.0 - (depth.toDouble / (maxDepthSeen + 1).toDouble)
          val combinedRaw = config.hybridVectorWeight * vectorScore + config.hybridGraphWeight * graphScore
          val combined    = combinedRaw / totalWeight
          GraphHybridHit(node = node, score = combined, vectorScore = vectorScore, graphScore = graphScore)
        }
      }
      .sortBy(hit => (-hit.score, hit.node.id))
  }

  /** A vector result's metadata and content, tokenised once for matching against every node. */
  final private case class VectorEvidence(
    score: Double,
    entityIds: Set[String],
    provenance: Set[String],
    contentTokens: Set[String]
  )

  private object VectorEvidence {
    def apply(r: HybridSearchResult): VectorEvidence =
      VectorEvidence(
        score = r.score,
        entityIds = EntityMetadataKeys.flatMap(r.metadata.get).toSet,
        provenance = ProvenancePropertyKeys.flatMap(r.metadata.get).map(_.toLowerCase).toSet,
        contentTokens = tokenize(r.content)
      )
  }

  /**
   * The best score of the vector results that name the node in their metadata, share its provenance, or mention its
   * name as whole words; 0 when none does.
   */
  private def nodeVectorScore(node: Node, evidence: Seq[VectorEvidence]): Double = {
    val provenance =
      ProvenancePropertyKeys.flatMap(node.properties.get).collect { case ujson.Str(s) => s.toLowerCase }.toSet
    val nameTokens = tokenize(displayName(node))
    val linked = evidence.filter { e =>
      e.entityIds.contains(node.id) ||
      e.provenance.exists(provenance.contains) ||
      (nameTokens.nonEmpty && nameTokens.forall(e.contentTokens.contains))
    }
    linked.map(_.score).foldLeft(0.0)(math.max)
  }

  def lexicalScore(query: String, text: String): Double = {
    val q = tokenize(query)
    val t = tokenize(text)
    if (q.isEmpty || t.isEmpty) 0.0
    else {
      val overlap = q.intersect(t).size.toDouble
      overlap / math.sqrt(q.size.toDouble * t.size.toDouble)
    }
  }

  /** Whole words of at least [[MinTokenLength]] characters, lower-cased, without stopwords. */
  private def tokenize(input: String): Set[String] =
    input.toLowerCase
      .split("[^\\p{L}\\p{N}]+")
      .filter(t => t.length >= MinTokenLength && !Stopwords.contains(t))
      .toSet

  private def buildAdjacency(graph: Graph): Map[String, Set[String]] = {
    val map = mutable.Map.empty[String, mutable.Set[String]]
    graph.nodes.keys.foreach(id => map.update(id, mutable.Set.empty[String]))
    graph.edges.foreach { edge =>
      map.getOrElseUpdate(edge.source, mutable.Set.empty).add(edge.target)
      map.getOrElseUpdate(edge.target, mutable.Set.empty).add(edge.source)
    }
    map.view.mapValues(_.toSet).toMap
  }

  private def buildCommunityAdjacency(
    graph: Graph,
    communities: Vector[GraphCommunity]
  ): Map[String, Set[String]] = {
    val nodeToCommunity = communities.flatMap(c => c.nodeIds.map(_ -> c.id)).toMap
    val base            = communities.map(_.id -> mutable.Set.empty[String]).toMap

    graph.edges.foreach { edge =>
      (nodeToCommunity.get(edge.source), nodeToCommunity.get(edge.target)) match {
        case (Some(left), Some(right)) if left != right =>
          base(left).add(right)
          base(right).add(left)
        case _ =>
      }
    }

    base.view.mapValues(_.toSet).toMap
  }

  private def labelPropagationPartition(
    nodeIds: Set[String],
    adjacency: Map[String, Set[String]],
    iterations: Int
  ): Map[String, String] = {
    if (nodeIds.isEmpty) return Map.empty

    val sortedNodes = nodeIds.toSeq.sorted
    val assignment  = mutable.Map(sortedNodes.map(n => n -> n): _*)

    var iter = 0
    while (iter < iterations) {
      var changed = false
      sortedNodes.foreach { node =>
        val neighborCommunities = adjacency
          .getOrElse(node, Set.empty)
          .toSeq
          .flatMap(assignment.get)
          .groupBy(identity)
          .view
          .mapValues(_.size)
          .toMap

        if (neighborCommunities.nonEmpty) {
          val best = neighborCommunities.toSeq
            .sortBy { case (communityId, count) => (-count, communityId) }
            .head
            ._1
          if (best != assignment(node)) {
            assignment.update(node, best)
            changed = true
          }
        }
      }
      if (!changed) {
        iter = iterations
      } else {
        iter += 1
      }
    }

    // Canonicalize IDs for deterministic output.
    val canonical = assignment.toSeq
      .groupBy(_._2)
      .toSeq
      .sortBy(_._1)
      .zipWithIndex
      .map { case ((rawCommunityId, _), idx) => rawCommunityId -> s"C$idx" }
      .toMap

    assignment.toSeq.map { case (node, communityId) => node -> canonical(communityId) }.toMap
  }

  private def buildCommunitiesForLevel(
    graph: Graph,
    level: Int,
    assignment: Map[String, String],
    minCommunitySize: Int
  ): Vector[GraphCommunity] = {
    val grouped = assignment.groupBy(_._2).map { case (cid, entries) =>
      cid -> entries.keySet
    }

    val (large, small)     = grouped.partition { case (_, nodes) => nodes.size >= minCommunitySize }
    val mergedSmallNodeIds = small.values.flatten.toSet
    val merged =
      if (mergedSmallNodeIds.nonEmpty) large + (UnclusteredKey -> mergedSmallNodeIds)
      else large

    merged.toSeq
      .sortBy(_._1)
      .zipWithIndex
      .map { case ((key, nodeIds), idx) =>
        val edgeCount = graph.edges.count(e => nodeIds.contains(e.source) && nodeIds.contains(e.target))
        GraphCommunity(
          id = s"L$level-C$idx",
          level = level,
          nodeIds = nodeIds,
          edgeCount = edgeCount,
          // The lump is no community, so it is listed rather than summarised by the model as if it were coherent
          summary = if (key == UnclusteredKey) Some(unclusteredSummary(graph, nodeIds, minCommunitySize)) else None
        )
      }
      .toVector
  }

  private def unclusteredSummary(graph: Graph, nodeIds: Set[String], minCommunitySize: Int): String = {
    val listed = nodeIds.toSeq.sorted.flatMap(graph.nodes.get).take(MaxUnclusteredList).map(renderNode)
    val more   = nodeIds.size - listed.size
    val tail   = if (more > 0) s"; and $more more" else ""
    s"Unclustered nodes, not summarised: these ${nodeIds.size} nodes are in no community of at least " +
      s"$minCommunitySize nodes. ${listed.mkString("; ")}$tail"
  }

  private def summarizeCommunity(graph: Graph, community: GraphCommunity, llmClient: LLMClient): Result[String] = {
    val nodes = community.nodeIds.toSeq.sorted.flatMap(graph.nodes.get).take(25)
    if (nodes.isEmpty) {
      Left(ProcessingError("community_summary", s"Community ${community.id} has no nodes to summarize"))
    } else {
      val nodeLines = nodes.map(renderNode).mkString("\n")
      val edgeLines = graph.edges
        .filter(e => community.nodeIds.contains(e.source) && community.nodeIds.contains(e.target))
        .take(30)
        .map(e => s"${e.source} -[${e.relationship}]-> ${e.target}")
        .mkString("\n")

      val conversation = Conversation(
        Seq(
          SystemMessage("Summarize the graph community for retrieval. Focus on key themes and relationships."),
          UserMessage(
            s"""Community ID: ${community.id}
               |Hierarchy level: ${community.level}
               |
               |Nodes:
               |$nodeLines
               |
               |Relationships:
               |$edgeLines
               |
               |Write a concise summary (3-6 sentences) describing themes and salient facts.""".stripMargin
          )
        )
      )

      llmClient.complete(conversation, SummaryModelTemp).map(_.content.trim)
    }
  }

  private def displayName(node: Node): String =
    NamePropertyKeys
      .flatMap(node.properties.get)
      .collectFirst { case ujson.Str(s) if s.trim.nonEmpty => s.trim }
      .getOrElse(node.id)

  private def renderNode(node: Node): String =
    s"${node.id} [${node.label}] ${displayName(node)}"

  /** [[renderNode]] plus every other property as `key=value`, each value cut to [[MaxPropertyChars]]. */
  private def renderNodeDetailed(node: Node): String = {
    val name = displayName(node)
    val props = node.properties.toSeq
      .sortBy(_._1)
      .flatMap {
        case (k, ujson.Str(s)) => Option.when(s.trim != name)(s"$k=${truncate(s.trim, MaxPropertyChars)}")
        case (k, v)            => Some(s"$k=${truncate(v.render(), MaxPropertyChars)}")
      }
    if (props.isEmpty) renderNode(node) else s"${renderNode(node)}: ${props.mkString("; ")}"
  }

  private def renderEdge(e: Edge): String =
    s"${e.source} -[${e.relationship}]-> ${e.target}"

  /** Edges with both ends in `nodeIds`, nearest first when depths are known, at most [[MaxPromptEdges]]. */
  private def edgesAmong(
    graph: Graph,
    nodeIds: Set[String],
    depths: Map[String, Int]
  ): Seq[Edge] =
    graph.edges
      .filter(e => nodeIds.contains(e.source) && nodeIds.contains(e.target))
      .sortBy(e => math.max(depths.getOrElse(e.source, 0), depths.getOrElse(e.target, 0)))
      .take(MaxPromptEdges)

  private def truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max) + "..."
}
