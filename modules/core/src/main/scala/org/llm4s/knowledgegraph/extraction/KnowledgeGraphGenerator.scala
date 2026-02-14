package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.error.ProcessingError
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * Generates a Knowledge Graph from unstructured text using an LLM.
 *
 * @param llmClient The LLM client to use for extraction
 */
class KnowledgeGraphGenerator(llmClient: LLMClient) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Extracts entities and relationships from the given text.
   *
   * @param text The text to analyze
   * @param entityTypes Optional list of entity types to focus on (e.g., "Person", "Organization")
   * @param relationTypes Optional list of relationship types to look for
   * @return A Graph containing the extracted nodes and edges
   */
  def extract(
    text: String,
    entityTypes: List[String] = Nil,
    relationTypes: List[String] = Nil
  ): Result[Graph] = {
    val prompt = buildPrompt(text, entityTypes, relationTypes)
    val conversation = Conversation(
      messages = List(
        SystemMessage("You are a helpful assistant that extracts knowledge graphs from text."),
        UserMessage(prompt)
      )
    )

    llmClient
      .complete(conversation, CompletionOptions(temperature = 0.0))
      .flatMap(completion => parseGraph(completion.content))
  }

  private def buildPrompt(text: String, entityTypes: List[String], relationTypes: List[String]): String = {
    val schemaInstruction = if (entityTypes.nonEmpty || relationTypes.nonEmpty) {
      val et = if (entityTypes.nonEmpty) s"Entity Types: ${entityTypes.mkString(", ")}" else ""
      val rt = if (relationTypes.nonEmpty) s"Relationship Types: ${relationTypes.mkString(", ")}" else ""
      s"""
         |Focus on extracting the following types:
         |$et
         |$rt
         |""".stripMargin
    } else {
      "Extract all relevant entities and relationships."
    }

    s"""
       |Analyze the following text and extract a knowledge graph containing entities (nodes) and relationships (edges).
       |
       |$schemaInstruction
       |
       |Output the result in strict JSON format with the following structure:
       |{
       |  "nodes": [
       |    {"id": "unique_id", "label": "Entity Type", "properties": {"name": "Entity Name", "attr": "value"}}
       |  ],
       |  "edges": [
       |    {"source": "source_id", "target": "target_id", "relationship": "RELATIONSHIP_TYPE", "properties": {"attr": "value"}}
       |  ]
       |}
       |
       |Text to analyze:
       |$text
       |""".stripMargin
  }

  private def parseGraph(jsonStr: String): Result[Graph] = {
    // Strip markdown code blocks if present, handling various formats
    val cleanJson = jsonStr.trim
      .stripPrefix("```json")
      .stripPrefix("```")
      .stripSuffix("```")
      .trim

    Try {
      val json = ujson.read(cleanJson)

      // Validate required fields
      if (!json.obj.contains("nodes") || !json.obj.contains("edges")) {
        throw new IllegalArgumentException("JSON must contain 'nodes' and 'edges' fields")
      }

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

      val graph = Graph(nodes, edges)

      // Validate graph integrity at extraction boundary
      graph.validate().map(_ => graph)
    }.toResult.left.map { error =>
      logger.error(s"Failed to parse graph JSON: $cleanJson", error)
      ProcessingError("knowledge_graph_extraction", s"Failed to parse LLM output as graph: ${error.message}")
    }.flatten
  }
}
