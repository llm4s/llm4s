package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.error.ProcessingError
import org.llm4s.util.{ BoundedJson, Redaction }
import org.slf4j.LoggerFactory

/**
 * Shared utility for parsing LLM JSON output into a [[Graph]].
 *
 * Both [[KnowledgeGraphGenerator]] and [[SchemaGuidedExtractor]] produce the same
 * JSON structure from the LLM. This object centralises the parsing and validation
 * logic so it is not duplicated.
 */
private[extraction] object GraphJsonParser {
  private val logger = LoggerFactory.getLogger(getClass)

  /** How much of a reply a failure's ERROR line shows. */
  private val MaxLoggedReplyChars = 512

  /**
   * The reply as a failure logs it. The reply is model output - it can be megabytes, and it echoes the
   * documents the graph was extracted from - so the log line carries its first [[MaxLoggedReplyChars]]
   * and the full length, never the whole reply (#1635).
   */
  private def preview(reply: String): String = Redaction.truncateForLog(reply, MaxLoggedReplyChars)

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

    // Parse JSON and map parsing errors to ProcessingError without throwing. The text is model
    // output, so a document nested more than 512 levels deep is refused before it is parsed: a
    // value that deep overflows the stack of whatever renders it (#1562).
    val parsedJsonResult = BoundedJson.read(cleanJson).left.map { error =>
      logger.error(s"Failed to parse graph JSON: ${preview(cleanJson)}", error)
      ProcessingError(errorCode, s"Failed to parse LLM output as graph: ${error.message}")
    }

    parsedJsonResult.flatMap { json =>
      // Ensure the JSON is an object with the required top-level fields. A model can answer with
      // any JSON value, and `json.obj` throws for all but an object, which the parse's `Try` does
      // not cover, so read it as an `Option`.
      if (!json.objOpt.exists(fields => fields.contains("nodes") && fields.contains("edges"))) {
        Left(ProcessingError(errorCode, "JSON must be an object containing 'nodes' and 'edges' fields"))
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
            // the reply as received, not the parsed value re-rendered: rendering it costs as much as
            // it is long, only to be cut to a preview
            logger.error(s"Failed to extract graph structure from JSON: ${preview(cleanJson)}", error)
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
