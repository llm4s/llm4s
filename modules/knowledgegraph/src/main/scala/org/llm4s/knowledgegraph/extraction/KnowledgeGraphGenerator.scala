package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.Graph
import org.llm4s.knowledgegraph.storage.GraphStore
import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

/**
 * Generates a Knowledge Graph from unstructured text using an LLM and writes it to a GraphStore.
 *
 * The model's node ids (usually `1`, `2`, ...) are namespaced with the document's source id before they are written,
 * as `<sourceId>__<id>`, so a second document on the same store adds its entities instead of overwriting the first
 * document's (#2966). Use [[FreeFormExtractor]] for the graph alone, without a store.
 *
 * @example
 * {{{
 * val generator = new KnowledgeGraphGenerator(llmClient, graphStore)
 * val result = generator.extract(
 *   text = "Alice works at Acme Corp in San Francisco.",
 *   source = DocumentSource("report-2024"),
 *   entityTypes = List("Person", "Organization", "Location")
 * )
 * // result: Right(Graph) with nodes report-2024__1 (Alice), report-2024__2 (Acme Corp), ...
 * }}}
 *
 * @param llmClient The LLM client to use for extraction
 * @param graphStore The graph store to write extracted entities and relationships to
 */
class KnowledgeGraphGenerator(llmClient: LLMClient, graphStore: GraphStore) {

  private val extractor = new FreeFormExtractor(llmClient)

  /**
   * Extracts entities and relationships from the given text and writes them to the configured GraphStore.
   *
   * Every node is written before any edge, and no edge is written once a node write has failed. Writes that succeeded
   * before a failure stay in the store.
   *
   * @param text The text to analyze
   * @param source The document the text comes from; its id namespaces the extracted node ids, and must not be blank
   * @param entityTypes Optional list of entity types to focus on (e.g., "Person", "Organization")
   * @param relationTypes Optional list of relationship types to look for
   * @return Right(the graph as written, with namespaced ids), or Left with the first error
   */
  def extract(
    text: String,
    source: DocumentSource,
    entityTypes: List[String] = Nil,
    relationTypes: List[String] = Nil
  ): Result[Graph] =
    for {
      _   <- DocumentSource.validateIds(Seq(source))
      raw <- extractor.extract(text, entityTypes, relationTypes)
      graph = SourceTrackedGraph.namespaced(raw, source.id)
      _ <- writeGraphToStore(graph)
    } yield graph

  private def writeGraphToStore(graph: Graph): Result[Unit] = {
    def writeAll[A](items: Iterable[A])(write: A => Result[Unit]): Result[Unit] =
      items.foldLeft(Right(()): Result[Unit])((acc, item) => acc.flatMap(_ => write(item)))

    // Edges only after every node write succeeded (#2963)
    writeAll(graph.nodes.values)(graphStore.upsertNode).flatMap(_ => writeAll(graph.edges)(graphStore.upsertEdge))
  }
}
