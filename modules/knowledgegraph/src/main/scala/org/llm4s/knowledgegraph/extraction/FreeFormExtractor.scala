package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.Graph
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

/**
 * Extracts a Knowledge Graph from unstructured text with a free-form LLM prompt, and returns it without storing it.
 *
 * The node ids in the result are the model's own, usually `1`, `2`, ..., so they collide across documents: give each
 * document's graph a namespace before merging or storing it, as [[SourceTrackedGraph.addDocument]] and
 * [[KnowledgeGraphGenerator.extract]] do.
 *
 * @example
 * {{{
 * val extractor = new FreeFormExtractor(llmClient)
 * val result = extractor.extract(
 *   text = "Alice works at Acme Corp in San Francisco.",
 *   entityTypes = List("Person", "Organization", "Location")
 * )
 * }}}
 *
 * @param llmClient The LLM client to use for extraction
 */
class FreeFormExtractor(llmClient: LLMClient) {

  /**
   * Extracts entities and relationships from the given text.
   *
   * @param text The text to analyze
   * @param entityTypes Optional list of entity types to focus on (e.g., "Person", "Organization")
   * @param relationTypes Optional list of relationship types to look for
   * @return Right(extractedGraph) on success, Left(ProcessingError) on failure
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
      .complete(conversation, CompletionOptions(temperature = Some(0.0)))
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

  private def parseGraph(jsonStr: String): Result[Graph] =
    // Delegate parsing (and extraction validation) to the central parser which
    // guarantees it will return a ProcessingError instead of throwing.
    GraphJsonParser.parse(jsonStr, "knowledge_graph_extraction")
}
