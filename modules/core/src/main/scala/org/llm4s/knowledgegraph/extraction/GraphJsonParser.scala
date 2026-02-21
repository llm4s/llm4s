package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.error.ProcessingError
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * Shared utility for parsing LLM JSON output into a [[Graph]].
 *
 * Both [[KnowledgeGraphGenerator]] and [[SchemaGuidedExtractor]] produce the same
 * JSON structure from the LLM. This object centralises the parsing and validation
 * logic so it is not duplicated.
 */
private[extraction] object GraphJsonParser {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Parses a JSON string (optionally wrapped in a markdown code fence) into a [[Graph]].
   *
   * Expected JSON structure:
   * {{{
   * {
   *   "nodes": [{"id": "...", "label": "...", "properties": {...}}],
   *   "edges": [{"source": "...", "target": "...", "relationship": "...", "properties": {...}}]
   * }
   * }}}
   *
   * @param jsonStr   Raw string returned by the LLM (may contain ```json fences)
   * @param errorCode Error code used in [[ProcessingError]] on failure
   * @return The parsed and integrity-validated [[Graph]], or a [[ProcessingError]]
   */
  def parse(jsonStr: String, errorCode: String): Result[Graph] = {
    val cleanJson = jsonStr.trim
      .stripPrefix("```json")
      .stripPrefix("```")
      .stripSuffix("```")
      .trim

    // Parse JSON and map parsing errors to ProcessingError without throwing
    val parsedJsonResult = Try(ujson.read(cleanJson)).toResult.left.map { error =>
      logger.error(s"Failed to parse graph JSON: $cleanJson", error)
      ProcessingError(errorCode, s"Failed to parse LLM output as graph: ${error.message}")
    }

    parsedJsonResult.flatMap { json =>
      // Ensure the JSON contains required top-level fields
      if (!json.obj.contains("nodes") || !json.obj.contains("edges")) {
        Left(ProcessingError(errorCode, "JSON must contain 'nodes' and 'edges' fields"))
      } else {
        // Guard the field extraction/construction so we return a ProcessingError
        // instead of throwing exceptions for malformed or missing fields.
        scala.util
          .Try {
            val nodes = json("nodes").arr
              .map { n =>
                val id    = n("id").str
                val label = n("label").str
                val props = if (n.obj.contains("properties")) {
                  n("properties").obj.toMap
                } else {
                  Map.empty[String, ujson.Value]
                }
                Node(id, label, props)
              }
              .map(n => n.id -> n)
              .toMap

            val edges = json("edges").arr.map { e =>
              val source = e("source").str
              val target = e("target").str
              val rel    = e("relationship").str
              val props = if (e.obj.contains("properties")) {
                e("properties").obj.toMap
              } else {
                Map.empty[String, ujson.Value]
              }
              Edge(source, target, rel, props)
            }.toList

            Graph(nodes, edges)
          }
          .toResult
          .left
          .map { error =>
            logger.error(s"Failed to extract graph structure from JSON: ${json}", error)
            ProcessingError(errorCode, s"Failed to extract graph structure: ${error.message}")
          }
          .flatMap { graph =>
            // Validate graph integrity at extraction boundary
            graph.validate().map(_ => graph)
          }
      }
    }
  }
}
