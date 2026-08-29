package org.llm4s.knowledgegraph.graphrag

import org.llm4s.error.{ ConfigurationError, ProcessingError }
import org.llm4s.knowledgegraph.storage.GraphStore
import org.llm4s.knowledgegraph.{ Graph, Node }
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
  hits: Seq[GraphHybridHit]
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
 */
final class GraphRAG(
  graphStore: GraphStore,
  llmClient: LLMClient,
  config: GraphRAGConfig = GraphRAGConfig()
) {

  @volatile private var hierarchyCache: Option[GraphCommunityHierarchy] = None
  @volatile private var graphCache: Option[Graph]                       = None

  def detectCommunities(forceRefresh: Boolean = false): Result[GraphCommunityHierarchy] =
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

  def summarizeCommunities(forceRefresh: Boolean = false): Result[GraphCommunityHierarchy] =
    hierarchyCache match {
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
          _ <- persistCommunityNodes(summarized)
        } yield {
          hierarchyCache = Some(summarized)
          graphCache = None // Invalidate graph cache since community nodes were persisted
          summarized
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
      traversal = GraphRAG.traverseFromSeeds(graph, seeds, maxDepth)
      _ <-
        if (traversal.nonEmpty) Right(())
        else
          Left(ProcessingError("local_search_no_traversal", "No traversable neighborhood found for local search seeds"))
      supportingCommunities = hierarchy.allCommunities
        .filter(c => traversal.nonEmpty && c.nodeIds.exists(traversal.contains))
        .sortBy(c => -c.nodeIds.count(traversal.contains))
        .take(config.globalTopCommunities)
      answer <- GraphRAG.answerLocal(query, graph, seeds, traversal.toSeq, supportingCommunities, llmClient)
    } yield GraphLocalSearchResult(
      query = query,
      answer = answer,
      seedNodeIds = seeds,
      visitedNodeIds = traversal.toSeq.sorted,
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
      communities = hierarchy.allCommunities
        .filter(c => hits.exists(h => c.nodeIds.contains(h.node.id)))
        .sortBy(c => -hits.count(h => c.nodeIds.contains(h.node.id)))
        .take(config.globalTopCommunities)
      answer <- GraphRAG.answerHybrid(query, hits, communities, llmClient)
    } yield GraphHybridSearchResult(query, answer, seedIds.toSeq.sorted, hits)

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
          communityIds = Seq.empty,
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
        graphStore.loadAll().map { raw =>
          val filtered = GraphRAG.filterCommunityArtifacts(raw)
          graphCache = Some(filtered)
          filtered
        }
    }

  private def persistCommunityNodes(hierarchy: GraphCommunityHierarchy): Result[Unit] =
    for {
      existingGraph <- graphStore.loadAll()
      existingCommunityIds = existingGraph.nodes
        .collect {
          case (id, node) if id.startsWith(GraphRAG.CommunityIdPrefix) || node.label == GraphRAG.CommunityLabel =>
            id
        }
        .toSeq
        .sorted
      _ <- existingCommunityIds.foldLeft[Result[Unit]](Right(())) { (acc, nodeId) =>
        acc.flatMap(_ => graphStore.deleteNode(nodeId))
      }
      _ <- hierarchy.allCommunities.foldLeft[Result[Unit]](Right(())) { (acc, community) =>
        acc.flatMap { _ =>
          val summaryText = community.summary.getOrElse("")
          val communityNode = Node(
            id = s"community:${community.id}",
            label = "Community",
            properties = Map(
              "communityId" -> ujson.Str(community.id),
              "level"       -> ujson.Num(community.level),
              "summary"     -> ujson.Str(summaryText),
              "edgeCount"   -> ujson.Num(community.edgeCount)
            )
          )

          for {
            _ <- graphStore.upsertNode(communityNode)
            _ <- community.nodeIds.toSeq.sorted.foldLeft[Result[Unit]](Right(())) { (edgeAcc, nodeId) =>
              edgeAcc.flatMap(_ =>
                graphStore.upsertEdge(
                  org.llm4s.knowledgegraph.Edge(
                    source = communityNode.id,
                    target = nodeId,
                    relationship = "CONTAINS"
                  )
                )
              )
            }
          } yield ()
        }
      }
    } yield ()
}

private[graphrag] object GraphRAG {

  private[graphrag] val CommunityLabel    = "Community"
  private[graphrag] val CommunityIdPrefix = "community:"

  def hasSummaries(hierarchy: GraphCommunityHierarchy): Boolean =
    hierarchy.allCommunities.forall(_.summary.exists(_.trim.nonEmpty))

  private val SummaryModelTemp       = CompletionOptions(temperature = 0.0, maxTokens = Some(512))
  private val AnswerModelTemp        = CompletionOptions(temperature = 0.0, maxTokens = Some(1024))
  private val NamePropertyKeys       = Seq("name", "title", "entity", "id")
  private val ProvenancePropertyKeys = Seq("source", "docId", "documentId", "path")

  def filterCommunityArtifacts(graph: Graph): Graph = {
    val filteredNodes = graph.nodes.filterNot { case (id, node) =>
      id.startsWith(CommunityIdPrefix) || node.label == CommunityLabel
    }
    val filteredEdges = graph.edges.filter(e => filteredNodes.contains(e.source) && filteredNodes.contains(e.target))
    Graph(filteredNodes, filteredEdges)
  }

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
                summary <- summarizeCommunity(graph, community, llmClient)
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
    visitedNodeIds: Seq[String],
    communities: Seq[GraphCommunity],
    llmClient: LLMClient
  ): Result[String] = {
    val nodeLines = visitedNodeIds.flatMap(graph.nodes.get).take(30).map(renderNode).mkString("\n")
    val edgeLines = graph.edges
      .filter(e => visitedNodeIds.contains(e.source) && visitedNodeIds.contains(e.target))
      .take(40)
      .map(e => s"${e.source} -[${e.relationship}]-> ${e.target}")
      .mkString("\n")
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
    hits: Seq[GraphHybridHit],
    communities: Seq[GraphCommunity],
    llmClient: LLMClient
  ): Result[String] = {
    val topHits = hits
      .take(10)
      .map { hit =>
        f"${hit.node.id}%-20s score=${hit.score}%.4f vector=${hit.vectorScore}%.4f graph=${hit.graphScore}%.4f"
      }
      .mkString("\n")
    val summaries = communities.flatMap(_.summary).take(8).mkString("\n\n")

    val convo = Conversation(
      Seq(
        SystemMessage("Answer the question using hybrid vector and graph evidence."),
        UserMessage(
          s"""Question: $query
             |
             |Top hybrid hits:
             |$topHits
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
      val idScore = if (tokens.exists(t => node.id.toLowerCase.contains(t))) 2 else 0
      val nameScore = NamePropertyKeys
        .flatMap(node.properties.get)
        .collect {
          case ujson.Str(s) if tokens.exists(t => s.toLowerCase.contains(t)) => 3
        }
        .sum
      val propScore = node.properties.values.collect {
        case ujson.Str(s) if tokens.exists(t => s.toLowerCase.contains(t)) => 1
      }.sum
      val score = idScore + nameScore + propScore
      if (score > 0) Some(node -> score) else None
    }

    scored.sortBy { case (node, score) => (-score, node.id) }.map(_._1)
  }

  def traverseFromSeeds(graph: Graph, seedIds: Seq[String], maxDepth: Int): Set[String] =
    traverseWithDepth(graph, seedIds.toSet, maxDepth).keySet

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
        Seq("entityId", "entity", "entity_id", "nodeId").flatMap(metadata.get)
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
    val vectorSignal = vectorResults.map(_.score).sum / math.max(vectorResults.size, 1)
    val maxDepthSeen = math.max(nodeDepths.values.foldLeft(0)(math.max), 1)
    val totalWeight  = config.hybridVectorWeight + config.hybridGraphWeight

    nodeDepths.toSeq
      .flatMap { case (nodeId, depth) =>
        graph.nodes.get(nodeId).map { node =>
          val graphScore  = 1.0 - (depth.toDouble / (maxDepthSeen + 1).toDouble)
          val combinedRaw = config.hybridVectorWeight * vectorSignal + config.hybridGraphWeight * graphScore
          val combined    = combinedRaw / totalWeight
          GraphHybridHit(node = node, score = combined, vectorScore = vectorSignal, graphScore = graphScore)
        }
      }
      .sortBy(hit => (-hit.score, hit.node.id))
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

  private def tokenize(input: String): Set[String] =
    input.toLowerCase
      .split("[^a-z0-9]+")
      .map(_.trim)
      .filter(_.nonEmpty)
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
    val merged = if (mergedSmallNodeIds.nonEmpty) {
      large + ("__small_merged__" -> (large.getOrElse("__small_merged__", Set.empty[String]) ++ mergedSmallNodeIds))
    } else large

    merged.toSeq
      .sortBy(_._1)
      .zipWithIndex
      .map { case ((_, nodeIds), idx) =>
        val edgeCount = graph.edges.count(e => nodeIds.contains(e.source) && nodeIds.contains(e.target))
        GraphCommunity(
          id = s"L$level-C$idx",
          level = level,
          nodeIds = nodeIds,
          edgeCount = edgeCount
        )
      }
      .toVector
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

  private def renderNode(node: Node): String = {
    val displayName =
      NamePropertyKeys
        .flatMap(node.properties.get)
        .collectFirst { case ujson.Str(s) if s.trim.nonEmpty => s.trim }
        .getOrElse(node.id)
    s"${node.id} [${node.label}] $displayName"
  }
}
