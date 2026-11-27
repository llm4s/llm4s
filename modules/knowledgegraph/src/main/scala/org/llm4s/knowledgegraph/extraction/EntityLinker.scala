package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.syntax.toResult
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson
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
 * How nodes merge (#2967):
 *  - A blank or missing `name` is no name: such a node is keyed by its id, so it merges with nothing by name.
 *  - The surviving (canonical) id is one of `preferredIds` when the group has one (the ids of a graph built earlier, so
 *    adding documents never renames a node that already exists), else the smallest id in the deterministic pass and
 *    the longest name (then the smallest id) in the model-driven one.
 *  - The canonical node's properties win; a property the canonical node lacks is taken from the others, in id order.
 *  - Confirmed model merges are grouped transitively (`A = B` and `B = C` merge all three), and each group is merged
 *    once against the current graph.
 *  - After rewriting, edges are one per (source, target, relationship), their properties merged as nodes' are. A
 *    self-loop the rewrite created is dropped; one that was in the graph is kept.
 *
 * @example
 * {{{
 * // Deterministic linking only
 * val linker = new EntityLinker(None)
 * val deduped = linker.link(graphWithDuplicates)
 *
 * // With LLM-assisted disambiguation
 * val smartLinker = new EntityLinker(Some(llmClient))
 * val result = smartLinker.link(graphWithAmbiguousEntities)
 * }}}
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
  def link(graph: Graph): Result[Graph] = linkWithMapping(graph, Set.empty).map(_._1)

  /**
   * Links entities in a SourceTrackedGraph, updating provenance mappings to reflect merges.
   *
   * Every merge, deterministic or model-driven, moves the removed node's sources to the node it merged into, and an
   * edge's sources are the union over every edge rewritten onto it (#2967, #2962).
   *
   * @param tracked The source-tracked graph to link
   * @param preferredIds Ids that stay canonical when they merge with others, such as the node ids of a graph built
   *                     before; adding documents then never renames a node that already exists
   * @return A new SourceTrackedGraph with entities linked and provenance updated
   */
  def linkTracked(tracked: SourceTrackedGraph, preferredIds: Set[String] = Set.empty): Result[SourceTrackedGraph] =
    linkWithMapping(tracked.graph, preferredIds).map { case (finalGraph, idMapping) =>
      def canonical(id: String): String = idMapping.getOrElse(id, id)

      val newNodeSources: Map[String, Set[String]] =
        tracked.nodeSources.toSeq.groupMapReduce { case (id, _) => canonical(id) }(_._2)(_ ++ _)

      val newEdgeSources: Map[(String, String, String), Set[String]] =
        tracked.edgeSources.toSeq
          .flatMap { case ((src, tgt, rel), srcs) =>
            val key = (canonical(src), canonical(tgt), rel)
            // A self-loop the merge created is no longer an edge of the graph
            if (src != tgt && key._1 == key._2) None else Some(key -> srcs)
          }
          .groupMapReduce(_._1)(_._2)(_ ++ _)

      tracked
        .withGraph(finalGraph)
        .withUpdatedNodeSources(newNodeSources)
        .withUpdatedEdgeSources(newEdgeSources)
    }

  /** Both passes, returning every removed id mapped to the id it ends up merged into. */
  private def linkWithMapping(graph: Graph, preferredIds: Set[String]): Result[(Graph, Map[String, String])] = {
    val (afterDeterministic, deterministic) = deterministicMergeWithMapping(graph, preferredIds)
    val afterModel: Result[(Graph, Map[String, String])] = llmClient match {
      case Some(client) => llmDisambiguate(afterDeterministic, client, preferredIds)
      case None         => Right((afterDeterministic, Map.empty))
    }
    afterModel.map { case (finalGraph, modelMapping) =>
      val composed = deterministic.map { case (old, mid) => old -> modelMapping.getOrElse(mid, mid) } ++
        modelMapping.filterNot { case (id, _) => deterministic.contains(id) }
      (finalGraph, composed)
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
   * The canonical node of a group is a `preferredIds` member when there is one, else the smallest id, so the choice
   * does not depend on Map iteration order.
   *
   * @return (merged graph, Map[oldId -> canonicalId])
   */
  private[extraction] def deterministicMergeWithMapping(
    graph: Graph,
    preferredIds: Set[String] = Set.empty
  ): (Graph, Map[String, String]) = {
    val groups = graph.nodes.values.toList.groupBy(nodeKey).values.toList
    mergeGroups(graph, groups.map(g => chooseCanonical(g, preferredIds, byLongestName = false)))
  }

  /** Orders a group with its canonical node first. */
  private def chooseCanonical(group: List[Node], preferredIds: Set[String], byLongestName: Boolean): List[Node] = {
    val sorted = group.sortBy(_.id)
    val canonical = sorted.filter(n => preferredIds.contains(n.id)) match {
      case preferred if preferred.nonEmpty => preferred.head
      case _ if byLongestName              => sorted.maxBy(n => nodeName(n).length) // maxBy keeps the first maximum
      case _                               => sorted.head
    }
    canonical :: sorted.filterNot(_.id == canonical.id)
  }

  /**
   * Merges each group (canonical node first) into its canonical node, against the current graph, and rewrites the
   * edges. Returns the graph and every removed id mapped to its canonical id.
   */
  private def mergeGroups(graph: Graph, groups: List[List[Node]]): (Graph, Map[String, String]) = {
    val merging                      = groups.filter(_.size > 1)
    val mapping: Map[String, String] = merging.flatMap(g => g.tail.map(_.id -> g.head.id)).toMap
    if (mapping.isEmpty) (graph.copy(edges = EntityLinker.rewriteEdges(graph.edges, Map.empty)), Map.empty)
    else {
      val mergedNodes = merging.map { g =>
        val current = g.flatMap(n => graph.nodes.get(n.id))
        val keep    = current.head
        val props = current.tail.foldLeft(keep.properties) { (acc, other) =>
          val conflicting = other.properties.keySet.filter(k => acc.get(k).exists(_ != other.properties(k)))
          if (conflicting.nonEmpty)
            logger.debug(
              s"Merging ${other.id} into ${keep.id}: kept ${keep.id}'s value of ${conflicting.toSeq.sorted.mkString(", ")}"
            )
          other.properties ++ acc
        }
        keep.id -> keep.copy(properties = props)
      }
      val nodes = (graph.nodes -- mapping.keys) ++ mergedNodes
      (Graph(nodes, EntityLinker.rewriteEdges(graph.edges, mapping)), mapping)
    }
  }

  /**
   * Builds a canonical key for a node: (lowercased label, normalized name property). A node without a non-blank
   * "name" is keyed by its own id, in a separate variant, so it never shares a key with a named node (an id `alice`
   * is not the name "Alice") and merges with nothing (#2967).
   */
  private def nodeKey(node: Node): (String, Either[String, String]) =
    (node.label.toLowerCase, givenName(node).toRight(node.id))

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
  private def llmDisambiguate(
    graph: Graph,
    client: LLMClient,
    preferredIds: Set[String]
  ): Result[(Graph, Map[String, String])] = {
    val candidates = findDisambiguationCandidates(graph)

    if (candidates.isEmpty) {
      Right((graph, Map.empty))
    } else {
      resolveDisambiguationCandidates(graph, candidates, client, preferredIds)
    }
  }

  /**
   * Finds pairs of nodes with the same label that might refer to the same entity.
   * A candidate pair has names where one is a substring of the other.
   */
  private[extraction] def findDisambiguationCandidates(graph: Graph): List[(Node, Node)] = {
    val nodesByLabel = graph.nodes.values.toList.sortBy(_.id).groupBy(_.label.toLowerCase)

    nodesByLabel.toList.sortBy(_._1).flatMap { case (_, nodes) =>
      for {
        i <- nodes.indices
        j <- (i + 1) until nodes.size
        a = nodes(i)
        b = nodes(j)
        if arePotentialDuplicates(a, b)
      } yield (a, b)
    }
  }

  /**
   * Two nodes are potential duplicates if they share a label and one's name
   * is a substring of the other's (after normalization). A node without a non-blank name is never a candidate: its
   * id is no evidence of what it names.
   */
  private def arePotentialDuplicates(a: Node, b: Node): Boolean =
    (givenName(a), givenName(b)) match {
      case (Some(nameA), Some(nameB)) if nameA != nameB => // equal names: handled by the deterministic merge
        nameA.contains(nameB) || nameB.contains(nameA)
      case _ => false
    }

  /** The normalized `name` property, when it is a non-blank string. */
  private def givenName(node: Node): Option[String] =
    node.properties.get("name").collect { case ujson.Str(s) if s.trim.nonEmpty => normalizeName(s) }

  /** The normalized `name` property, or the normalized id when the name is missing, not a string, or blank. */
  private def nodeName(node: Node): String =
    givenName(node).getOrElse(normalizeName(node.id))

  /**
   * Asks the LLM to resolve ambiguous entity pairs and merges confirmed duplicates.
   */
  private def resolveDisambiguationCandidates(
    graph: Graph,
    candidates: List[(Node, Node)],
    client: LLMClient,
    preferredIds: Set[String]
  ): Result[(Graph, Map[String, String])] = {
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
      .complete(conversation, CompletionOptions(temperature = Some(0.0)))
      .map(completion => applyDisambiguationResponse(completion.content, candidates, graph, preferredIds))
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
   * Graceful degradation: a reply that is not a JSON object with a `merges` array leaves the graph as it is, with a
   * warning; within the array, each element is read on its own, so a malformed one is skipped (with a warning) and
   * the others still apply (#2967).
   */
  private def applyDisambiguationResponse(
    jsonStr: String,
    candidates: List[(Node, Node)],
    graph: Graph,
    preferredIds: Set[String]
  ): (Graph, Map[String, String]) = {
    val cleanJson = jsonStr.trim
      .stripPrefix("```json")
      .stripPrefix("```")
      .stripSuffix("```")
      .trim

    // The reply is model output: a document nested more than 512 levels deep is refused before it
    // is parsed, since a value that deep overflows the stack of whatever renders it (#1562)
    val merges = BoundedJson.read(cleanJson).flatMap(json => Try(json("merges").arr.toList).toResult)
    merges match {
      case Left(error) =>
        logger.warn(
          s"Failed to parse disambiguation response, falling back to pre-disambiguation graph: ${error.message}"
        )
        (graph, Map.empty)
      case Right(elements) =>
        val confirmed = elements.flatMap { m =>
          Try((m("pair").num.toInt - 1, m("merge").bool)).toOption match {
            case Some((idx, true)) if idx >= 0 && idx < candidates.size => Some(candidates(idx))
            case Some(_)                                                => None
            case None =>
              logger.warn(s"Skipping a malformed disambiguation element: ${m.toString.take(200)}")
              None
          }
        }
        val groups = EntityLinker.components(confirmed.map { case (a, b) => (a.id, b.id) }).map { ids =>
          chooseCanonical(ids.toList.flatMap(graph.nodes.get), preferredIds, byLongestName = true)
        }
        mergeGroups(graph, groups)
    }
  }
}

private[extraction] object EntityLinker {

  /** The connected components of the pairs, each as a set of ids (union-find with path compression). */
  def components(pairs: List[(String, String)]): List[Set[String]] = {
    val parent = scala.collection.mutable.Map.empty[String, String]
    def find(x: String): String = {
      val p = parent.getOrElseUpdate(x, x)
      if (p == x) x
      else {
        val root = find(p)
        parent(x) = root
        root
      }
    }
    pairs.foreach { case (a, b) =>
      val (ra, rb) = (find(a), find(b))
      if (ra != rb) parent(ra) = rb
    }
    parent.keys.toList.groupBy(find).values.map(_.toSet).toList
  }

  /**
   * Rewrites edge endpoints through `mapping`, drops the self-loops the rewrite created (an edge that already was a
   * self-loop stays), and keeps one edge per (source, target, relationship), in first-seen order, its properties
   * merged with the first edge's winning.
   */
  def rewriteEdges(edges: List[Edge], mapping: Map[String, String]): List[Edge] = {
    val rewritten = edges.flatMap { e =>
      val src = mapping.getOrElse(e.source, e.source)
      val tgt = mapping.getOrElse(e.target, e.target)
      if (e.source != e.target && src == tgt) None else Some(e.copy(source = src, target = tgt))
    }
    val merged = scala.collection.mutable.LinkedHashMap.empty[(String, String, String), Edge]
    rewritten.foreach { e =>
      val key = (e.source, e.target, e.relationship)
      merged.get(key) match {
        case Some(first) => merged(key) = first.copy(properties = e.properties ++ first.properties)
        case None        => merged(key) = e
      }
    }
    merged.values.toList
  }
}
