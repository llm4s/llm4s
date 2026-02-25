package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.Graph
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

/**
 * Extracts a Knowledge Graph from text using schema-constrained LLM prompts.
 *
 * Unlike the free-form `KnowledgeGraphGenerator`, this extractor guides the LLM
 * by listing the allowed entity types, relationship types, and expected properties
 * from an `ExtractionSchema`. The LLM output is still parsed as JSON but the prompt
 * strongly constrains what types are produced.
 *
 * @param llmClient The LLM client to use for extraction
 */
class SchemaGuidedExtractor(llmClient: LLMClient) {

  /**
   * Extracts entities and relationships from the given text, guided by the schema.
   *
   * @param text The text to analyze
   * @param schema The extraction schema defining expected entity/relationship types
   * @return A Graph containing the extracted nodes and edges
   */
  def extract(text: String, schema: ExtractionSchema): Result[Graph] = {
    val prompt = buildSchemaPrompt(text, schema)
    val conversation = Conversation(
      messages = List(
        SystemMessage("You are a helpful assistant that extracts knowledge graphs from text."),
        UserMessage(prompt)
      )
    )

    llmClient
      .complete(conversation, CompletionOptions(temperature = 0.0))
      .flatMap(completion => GraphJsonParser.parse(completion.content, "schema_guided_extraction"))
  }

  private def buildSchemaPrompt(text: String, schema: ExtractionSchema): String = {
    val entitySection = if (schema.entityTypes.nonEmpty) {
      val lines = schema.entityTypes.map { et =>
        val propsStr = if (et.properties.nonEmpty) {
          val propsList = et.properties.map { p =>
            val reqStr  = if (p.required) " (required)" else ""
            val descStr = if (p.description.nonEmpty) s" - ${p.description}" else ""
            s"    - ${p.name}$reqStr$descStr"
          }
          s"\n  Properties:\n${propsList.mkString("\n")}"
        } else ""
        val descStr = if (et.description.nonEmpty) s" - ${et.description}" else ""
        s"- ${et.name}$descStr$propsStr"
      }
      s"""Entity types to extract:
         |${lines.mkString("\n")}""".stripMargin
    } else ""

    val relationshipSection = if (schema.relationshipTypes.nonEmpty) {
      val lines = schema.relationshipTypes.map { rt =>
        val descStr = if (rt.description.nonEmpty) s" - ${rt.description}" else ""
        val constraintParts = List(
          if (rt.sourceTypes.nonEmpty) s"from: ${rt.sourceTypes.mkString(", ")}" else "",
          if (rt.targetTypes.nonEmpty) s"to: ${rt.targetTypes.mkString(", ")}" else ""
        ).filter(_.nonEmpty)
        val constraintStr = if (constraintParts.nonEmpty) s" (${constraintParts.mkString("; ")})" else ""
        s"- ${rt.name}$descStr$constraintStr"
      }
      s"""Relationship types to extract:
         |${lines.mkString("\n")}""".stripMargin
    } else ""

    val outOfSchemaNote = if (schema.allowOutOfSchema) {
      "If you find important entities or relationships outside the schema, include them with appropriate types."
    } else {
      "Only extract entities and relationships matching the types listed above. Ignore everything else."
    }

    s"""Analyze the following text and extract a knowledge graph containing entities (nodes) and relationships (edges).
       |
       |$entitySection
       |
       |$relationshipSection
       |
       |$outOfSchemaNote
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

}
