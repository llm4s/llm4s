package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.{ Graph, Node }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * Links and deduplicates entities across a knowledge graph.
 *
 * Performs two passes:
 *  1. Deterministic same-name merging: nodes with the same label and normalized name
 *     property are merged, with edges rewritten to point at the surviving node.
 *  2. LLM-assisted disambiguation (optional): ambiguous clusters (e.g., "Jobs" vs
 *     "Steve Jobs") are sent to the LLM to confirm or reject merges.
 *
 * @param llmClient Optional LLM client for disambiguation. If None, only deterministic merging is performed.
 */
class EntityLinker(llmClient: Option[LLMClient] = None) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Links (deduplicates) entities in the given graph.
   *
   * @param graph The graph whose entities should be linked
   * @return A new graph with duplicate entities merged and edges rewritten
   */
  def link(graph: Graph): Result[Graph] = {
    val (afterDeterministic, _) = deterministicMergeWithMapping(graph)

    llmClient match {
      case Some(client) => llmDisambiguate(afterDeterministic, client)
      case None         => Right(afterDeterministic)
    }
  }

  /**
   * Links entities in a SourceTrackedGraph, updating provenance mappings to reflect merges.
   *
   * @param tracked The source-tracked graph to link
   * @return A new SourceTrackedGraph with entities linked and provenance updated
   */
  def linkTracked(tracked: SourceTrackedGraph): Result[SourceTrackedGraph] = {
    val (linkedGraph, idMapping) = deterministicMergeWithMapping(tracked.graph)

    val afterDisambiguation: Result[Graph] = llmClient match {
      case Some(client) => llmDisambiguate(linkedGraph, client)
      case None         => Right(linkedGraph)
    }

    afterDisambiguation.map { finalGraph =>
      // Union node sources: every old ID that maps to a canonical ID contributes its sources
      val newNodeSources: Map[String, Set[String]] = idMapping
        .groupBy(_._2) // group by canonical ID
        .map { case (canonicalId, mappings) =>
          val unioned = mappings.keys.flatMap(oldId => tracked.nodeSources.getOrElse(oldId, Set.empty)).toSet
          canonicalId -> unioned
        }

      // Rewrite edgeSources keys: replace old node IDs with their canonical IDs
      val newEdgeSources: Map[(String, String, String), Set[String]] =
        tracked.edgeSources.map { case ((src, tgt, rel), srcs) =>
          val newKey = (idMapping.getOrElse(src, src), idMapping.getOrElse(tgt, tgt), rel)
          newKey -> srcs
        }

      tracked
        .withGraph(finalGraph)
        .withUpdatedNodeSources(newNodeSources)
        .withUpdatedEdgeSources(newEdgeSources)
    }
  }

  /**
   * Deterministic merge: groups nodes by (label, normalized name) and merges duplicates.
   * Returns the merged graph. Delegates to [[deterministicMergeWithMapping]] internally.
   */
  private[extraction] def deterministicMerge(graph: Graph): Graph =
    deterministicMergeWithMapping(graph)._1

  /**
   * Deterministic merge that also returns the old-ID → canonical-ID mapping.
   *
   * Nodes are sorted by ID before grouping so the canonical node (lowest ID) is
   * chosen deterministically regardless of Map iteration order.
   *
   * @return (merged graph, Map[oldId -> canonicalId])
   */
  private[extraction] def deterministicMergeWithMapping(graph: Graph): (Graph, Map[String, String]) = {
    // Sort by ID first so the canonical node (head) is always the lexicographically smallest ID
    val nodesByKey = graph.nodes.values.toList.sortBy(_.id).groupBy(nodeKey)

    // Build a map from old node ID → canonical node ID (lowest-ID node in group wins)
    val idMapping: Map[String, String] = nodesByKey.flatMap { case (_, groupNodes) =>
      val canonicalId = groupNodes.head.id
      groupNodes.map(n => n.id -> canonicalId)
    }.toMap

    // Merge node properties: later nodes' properties are added (canonical's take precedence on conflict)
    val mergedNodes = nodesByKey
      .map { case (_, groupNodes) =>
        val canonical = groupNodes.head
        val mergedProps = groupNodes.tail.foldLeft(canonical.properties) { (acc, node) =>
          node.properties ++ acc // canonical's properties take precedence
        }
        canonical.copy(properties = mergedProps)
      }
      .map(n => n.id -> n)
      .toMap

    // Rewrite edges to use canonical IDs and deduplicate
    val rewrittenEdges = graph.edges
      .map { edge =>
        edge.copy(
          source = idMapping.getOrElse(edge.source, edge.source),
          target = idMapping.getOrElse(edge.target, edge.target)
        )
      }
      .distinct
      .filterNot(e => e.source == e.target) // Remove self-loops created by merging

    (Graph(mergedNodes, rewrittenEdges), idMapping)
  }

  /**
   * Builds a canonical key for a node: (lowercased label, normalized name property).
   * Nodes without a "name" property use their ID as the name component.
   */
  private def nodeKey(node: Node): (String, String) = {
    val name = node.properties.get("name") match {
      case Some(ujson.Str(s)) => normalizeName(s)
      case _                  => normalizeName(node.id)
    }
    (node.label.toLowerCase, name)
  }

  /**
   * Normalizes a name for comparison: lowercase, trim, collapse whitespace.
   */
  private[extraction] def normalizeName(name: String): String =
    name.trim.toLowerCase.replaceAll("\\s+", " ")

  /**
   * LLM-assisted disambiguation for ambiguous entity clusters.
   *
   * Finds nodes of the same label whose names are similar but not identical after
   * normalization, and asks the LLM whether they refer to the same entity.
   */
  private def llmDisambiguate(graph: Graph, client: LLMClient): Result[Graph] = {
    val candidates = findDisambiguationCandidates(graph)

    if (candidates.isEmpty) {
      Right(graph)
    } else {
      resolveDisambiguationCandidates(graph, candidates, client)
    }
  }

  /**
   * Finds pairs of nodes with the same label that might refer to the same entity.
   * A candidate pair has names where one is a substring of the other.
   */
  private[extraction] def findDisambiguationCandidates(graph: Graph): List[(Node, Node)] = {
    val nodesByLabel = graph.nodes.values.toList.groupBy(_.label.toLowerCase)

    nodesByLabel.values.flatMap { nodes =>
      for {
        i <- nodes.indices
        j <- (i + 1) until nodes.size
        a = nodes(i)
        b = nodes(j)
        if arePotentialDuplicates(a, b)
      } yield (a, b)
    }.toList
  }

  /**
   * Two nodes are potential duplicates if they share a label and one's name
   * is a substring of the other's (after normalization).
   */
  private def arePotentialDuplicates(a: Node, b: Node): Boolean = {
    val nameA = nodeName(a)
    val nameB = nodeName(b)
    if (nameA == nameB) false // Already handled by deterministic merge
    else nameA.contains(nameB) || nameB.contains(nameA)
  }

  private def nodeName(node: Node): String =
    node.properties.get("name") match {
      case Some(ujson.Str(s)) => normalizeName(s)
      case _                  => normalizeName(node.id)
    }

  /**
   * Asks the LLM to resolve ambiguous entity pairs and merges confirmed duplicates.
   */
  private def resolveDisambiguationCandidates(
    graph: Graph,
    candidates: List[(Node, Node)],
    client: LLMClient
  ): Result[Graph] = {
    val prompt = buildDisambiguationPrompt(candidates)
    val conversation = Conversation(
      messages = List(
        SystemMessage(
          "You are an entity disambiguation assistant. Determine whether entity pairs refer to the same real-world entity."
        ),
        UserMessage(prompt)
      )
    )

    client
      .complete(conversation, CompletionOptions(temperature = 0.0))
      .flatMap(completion => parseDisambiguationResponse(completion.content, candidates, graph))
  }

  private def buildDisambiguationPrompt(candidates: List[(Node, Node)]): String = {
    val pairs = candidates.zipWithIndex.map { case ((a, b), idx) =>
      s"""Pair ${idx + 1}:
         |  Entity A: "${nodeName(a)}" (type: ${a.label}, id: ${a.id})
         |  Entity B: "${nodeName(b)}" (type: ${b.label}, id: ${b.id})""".stripMargin
    }

    s"""For each pair of entities below, determine if they refer to the same real-world entity.

Respond in strict JSON format:
{"merges": [{"pair": 1, "merge": true}, {"pair": 2, "merge": false}]}

${pairs.mkString("\n\n")}"""
  }

  /**
   * Parses the LLM disambiguation response and applies confirmed merges.
   *
   * Graceful degradation: if the LLM response cannot be parsed (malformed JSON, unexpected
   * structure, etc.) a warning is logged and the pre-disambiguation graph is returned as
   * `Right`. Callers cannot distinguish a successful no-op from a parse failure; use
   * log output to detect issues in production.
   */
  private def parseDisambiguationResponse(
    jsonStr: String,
    candidates: List[(Node, Node)],
    graph: Graph
  ): Result[Graph] = {
    val cleanJson = jsonStr.trim
      .stripPrefix("```json")
      .stripPrefix("```")
      .stripSuffix("```")
      .trim

    Try {
      val json   = ujson.read(cleanJson)
      val merges = json("merges").arr

      // Collect pairs to merge
      val pairsToMerge = merges.flatMap { m =>
        val pairIdx = m("pair").num.toInt - 1
        val doMerge = m("merge").bool
        if (doMerge && pairIdx >= 0 && pairIdx < candidates.size) {
          Some(candidates(pairIdx))
        } else None
      }.toList

      // Apply merges: for each pair, keep the node with the longer name as canonical
      pairsToMerge.foldLeft(graph) { case (g, (a, b)) =>
        val (keep, remove) = if (nodeName(a).length >= nodeName(b).length) (a, b) else (b, a)
        mergeNodePair(g, keep, remove)
      }
    } match {
      case scala.util.Success(updatedGraph) => Right(updatedGraph)
      case scala.util.Failure(ex) =>
        logger.warn(
          s"Failed to parse disambiguation response, falling back to pre-disambiguation graph: ${Option(ex.getMessage)
              .getOrElse(ex.toString)}"
        )
        Right(graph)
    }
  }

  /**
   * Merges two nodes: keeps the `keep` node, removes the `remove` node,
   * and rewrites all edges referencing `remove` to reference `keep`.
   */
  private def mergeNodePair(graph: Graph, keep: Node, remove: Node): Graph = {
    // Merge properties (keep node's properties take precedence)
    val mergedProps  = remove.properties ++ keep.properties
    val mergedNode   = keep.copy(properties = mergedProps)
    val updatedNodes = (graph.nodes - remove.id) + (keep.id -> mergedNode)

    // Rewrite edges
    val updatedEdges = graph.edges
      .map { edge =>
        val newSource = if (edge.source == remove.id) keep.id else edge.source
        val newTarget = if (edge.target == remove.id) keep.id else edge.target
        edge.copy(source = newSource, target = newTarget)
      }
      .distinct
      .filterNot(e => e.source == e.target)

    Graph(updatedNodes, updatedEdges)
  }
}
