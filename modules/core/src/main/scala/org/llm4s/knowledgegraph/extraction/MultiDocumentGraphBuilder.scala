package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.Graph
import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Configuration for multi-document graph extraction.
 *
 * @param schema Optional schema to constrain extraction. When absent, free-form extraction is used.
 * @param enableCoreference Whether to run coreference resolution on each document before extraction
 * @param enableEntityLinking Whether to run entity linking (deduplication) after merging documents
 * @param llmDisambiguation Whether to use LLM-assisted disambiguation during entity linking
 */
case class ExtractionConfig(
  schema: Option[ExtractionSchema] = None,
  enableCoreference: Boolean = true,
  enableEntityLinking: Boolean = true,
  llmDisambiguation: Boolean = false
)

/**
 * Orchestrates multi-document knowledge graph extraction.
 *
 * Composes the extraction pipeline:
 *   1. Per document: coreference resolution → schema-guided (or free-form) extraction → schema validation
 *   2. After all documents: merge graphs → entity linking → final SourceTrackedGraph
 *
 * Supports incremental building: pass an existing `SourceTrackedGraph` to add new documents
 * without re-extracting from previously processed ones.
 *
 * @param llmClient The LLM client used by all pipeline components
 * @param config Configuration controlling which pipeline stages are enabled
 */
class MultiDocumentGraphBuilder(
  llmClient: LLMClient,
  config: ExtractionConfig = ExtractionConfig()
) {
  private val logger = LoggerFactory.getLogger(getClass)

  private val coreferenceResolver = new CoreferenceResolver(llmClient)
  private val schemaExtractor     = new SchemaGuidedExtractor(llmClient)
  private val freeFormExtractor   = new KnowledgeGraphGenerator(llmClient)
  private val entityLinkerInstance = if (config.llmDisambiguation) {
    new EntityLinker(Some(llmClient))
  } else {
    new EntityLinker(None)
  }

  /**
   * Extracts a knowledge graph from a single document with source provenance.
   *
   * Runs the full per-document pipeline: coreference → extraction → validation.
   *
   * @param text The document text to extract from
   * @param source The document source metadata
   * @return A SourceTrackedGraph containing entities from this document
   */
  def extractDocument(text: String, source: DocumentSource): Result[SourceTrackedGraph] =
    for {
      resolvedText <- resolveCoref(text)
      rawGraph     <- extractGraph(resolvedText)
      validGraph = validateGraph(rawGraph)
    } yield SourceTrackedGraph.fromGraph(validGraph, source)

  /**
   * Extracts knowledge graphs from multiple documents and merges them into a single graph.
   *
   * Runs the full pipeline per document, merges all results, then applies entity linking.
   * Supports incremental building via the `existingGraph` parameter.
   *
   * Fails fast: if any document's extraction fails, the method returns immediately with
   * that error and subsequent documents are not processed.
   *
   * @param documents Sequence of (text, source) pairs to extract from
   * @param existingGraph Optional existing graph to build upon incrementally
   * @return A SourceTrackedGraph combining all documents, or the first extraction error
   */
  def extractDocuments(
    documents: Seq[(String, DocumentSource)],
    existingGraph: Option[SourceTrackedGraph] = None
  ): Result[SourceTrackedGraph] = {
    val base = existingGraph.getOrElse(SourceTrackedGraph.empty)

    // Extract each document and fold into the accumulator
    val merged = documents.foldLeft[Result[SourceTrackedGraph]](Right(base)) { case (accResult, (text, source)) =>
      accResult.flatMap { acc =>
        extractDocument(text, source).map(docTracked => acc.addDocument(docTracked.graph, source))
      }
    }

    // Apply entity linking on the merged result
    merged.flatMap { tracked =>
      if (config.enableEntityLinking) {
        entityLinkerInstance.linkTracked(tracked)
      } else {
        Right(tracked)
      }
    }
  }

  /**
   * Runs coreference resolution if enabled.
   */
  private def resolveCoref(text: String): Result[String] =
    if (config.enableCoreference) {
      coreferenceResolver.resolve(text)
    } else {
      Right(text)
    }

  /**
   * Runs schema-guided or free-form extraction based on configuration.
   */
  private def extractGraph(text: String): Result[Graph] =
    config.schema match {
      case Some(schema) => schemaExtractor.extract(text, schema)
      case None         => freeFormExtractor.extract(text)
    }

  /**
   * Validates the extracted graph against the schema if one is configured.
   * Returns the valid graph from the validation result. When no schema is
   * configured, the graph passes through unchanged.
   */
  private def validateGraph(graph: Graph): Graph =
    config.schema match {
      case Some(schema) =>
        val validator = new SchemaValidator(schema)
        val result    = validator.validate(graph)
        if (result.violations.nonEmpty) {
          logger.warn(
            s"Schema validation found ${result.violations.size} violation(s): " +
              result.violations.take(3).map(_.description).mkString("; ")
          )
        }
        if (result.outOfSchemaNodes.nonEmpty) {
          logger.info(
            s"${result.outOfSchemaNodes.size} out-of-schema node(s) " +
              s"${if (schema.allowOutOfSchema) "kept" else "dropped"}"
          )
        }
        result.validGraph
      case None => graph
    }
}
