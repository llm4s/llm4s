package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.Graph
import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Configuration for multi-document graph extraction.
 *
 * @example
 * {{{
 * val config = ExtractionConfig(
 *   schema = Some(ExtractionSchema.simple(Seq("Person", "Org"), Seq("WORKS_FOR"))),
 *   enableCoreference = true,
 *   enableEntityLinking = true
 * )
 * }}}
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
 * A multi-document extraction and what schema validation found in each document (#2971).
 *
 * @param graph       The merged, linked graph, as [[MultiDocumentGraphBuilder.extractDocuments]] returns it
 * @param validations Each newly extracted document's [[ValidationResult]], keyed by its source id; empty when no schema
 *                    is configured. Its node ids are the document's own, before they are namespaced.
 */
final case class GraphExtraction(graph: SourceTrackedGraph, validations: Map[String, ValidationResult]) {

  /** Every violation, with the id of the document it came from. */
  def violations: Seq[(String, SchemaViolation)] =
    validations.toSeq.sortBy(_._1).flatMap { case (doc, v) => v.violations.map(doc -> _) }
}

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
 * @example
 * {{{
 * val builder = new MultiDocumentGraphBuilder(llmClient, ExtractionConfig(
 *   schema = Some(ExtractionSchema.simple(Seq("Person", "Org"), Seq("WORKS_FOR")))
 * ))
 * val docs = Seq(
 *   ("Alice works at Acme.", DocumentSource("doc1", "Report 1")),
 *   ("Bob works at Acme.", DocumentSource("doc2", "Report 2"))
 * )
 * val result = builder.extractDocuments(docs)
 * }}}
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
  private val freeFormExtractor   = new FreeFormExtractor(llmClient)
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
   * @param source The document source metadata; a blank id is a `Left`
   * @return A SourceTrackedGraph containing entities from this document
   */
  def extractDocument(text: String, source: DocumentSource): Result[SourceTrackedGraph] =
    extractValidated(text, source).map { case (graph, _) => SourceTrackedGraph.fromGraph(graph, source) }

  /** One document's valid graph and, when a schema is configured, its validation result. */
  private def extractValidated(text: String, source: DocumentSource): Result[(Graph, Option[ValidationResult])] =
    for {
      _            <- DocumentSource.validateIds(Seq(source))
      resolvedText <- resolveCoref(text)
      rawGraph     <- extractGraph(resolvedText)
    } yield validateGraph(rawGraph)

  /**
   * Extracts knowledge graphs from multiple documents and merges them into a single graph.
   *
   * Runs the full pipeline per document, merges all results, then applies entity linking.
   * Supports incremental building via the `existingGraph` parameter.
   *
   * Fails fast: if any document's extraction fails, the method returns immediately with
   * that error and subsequent documents are not processed.
   *
   * Each source id namespaces its document's node ids, so before any extraction the call returns a `Left` when an id
   * is blank, two documents share one, or a document's id is already a source of `existingGraph`; re-extracting a
   * document into a graph that already holds it would mix its old and new entities.
   *
   * @param documents Sequence of (text, source) pairs to extract from
   * @param existingGraph Optional existing graph to build upon incrementally
   * @return A SourceTrackedGraph combining all documents, or the first extraction error
   */
  def extractDocuments(
    documents: Seq[(String, DocumentSource)],
    existingGraph: Option[SourceTrackedGraph] = None
  ): Result[SourceTrackedGraph] = extractDocumentsWithValidation(documents, existingGraph).map(_.graph)

  /**
   * [[extractDocuments]], also returning each document's schema validation result, so a caller can see what the
   * schema dropped or flagged rather than only a log line (#2971).
   */
  def extractDocumentsWithValidation(
    documents: Seq[(String, DocumentSource)],
    existingGraph: Option[SourceTrackedGraph] = None
  ): Result[GraphExtraction] = {
    val base = existingGraph.getOrElse(SourceTrackedGraph.empty)

    // Extract each document and fold into the accumulator
    val validated = DocumentSource
      .validateIds(base.sources ++ documents.map(_._2))
      .map(_ => (base, Map.empty[String, ValidationResult]))
    val merged = documents.foldLeft[Result[(SourceTrackedGraph, Map[String, ValidationResult])]](validated) {
      case (accResult, (text, source)) =>
        accResult.flatMap { case (acc, validations) =>
          extractValidated(text, source).map { case (graph, validation) =>
            (acc.addDocument(graph, source), validations ++ validation.map(source.id -> _))
          }
        }
    }

    // Apply entity linking on the merged result
    merged.flatMap { case (tracked, validations) =>
      linked(tracked, base).map(GraphExtraction(_, validations))
    }
  }

  private def linked(tracked: SourceTrackedGraph, base: SourceTrackedGraph): Result[SourceTrackedGraph] =
    if (config.enableEntityLinking) {
      // The existing graph's ids stay canonical, so adding documents never renames a node already built (#2967)
      entityLinkerInstance.linkTracked(tracked, preferredIds = base.graph.nodes.keySet)
    } else {
      Right(tracked)
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
   * Validates the extracted graph against the schema if one is configured, returning the valid graph and the result.
   * When no schema is configured, the graph passes through unchanged.
   */
  private def validateGraph(graph: Graph): (Graph, Option[ValidationResult]) =
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
        (result.validGraph, Some(result))
      case None => (graph, None)
    }
}
