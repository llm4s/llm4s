package org.llm4s.rag.loader

/**
 * Configuration for document loading behavior.
 *
 * Controls how documents are loaded, processed, and tracked by the RAG pipeline.
 *
 * @param failFast Stop on first error vs continue and collect all errors
 * @param useHints Whether to use loader hints for processing
 * @param skipEmptyDocuments Whether to skip documents with empty content
 * @param enableVersioning Track versions for sync operations
 * @param parallelism Maximum concurrent document processing
 * @param batchSize Documents per embedding batch
 */
final case class LoadingConfig(
  failFast: Boolean = false,
  useHints: Boolean = true,
  skipEmptyDocuments: Boolean = true,
  enableVersioning: Boolean = true,
  parallelism: Int = 4,
  batchSize: Int = 10
) {

  /** Enable fail-fast mode */
  def withFailFast: LoadingConfig = copy(failFast = true)

  /** Disable fail-fast mode (continue on errors) */
  def withContinueOnError: LoadingConfig = copy(failFast = false)

  /** Enable/disable hint usage */
  def withHints(use: Boolean): LoadingConfig = copy(useHints = use)

  /** Enable/disable empty document skipping */
  def withSkipEmpty(skip: Boolean): LoadingConfig = copy(skipEmptyDocuments = skip)

  /** Enable/disable version tracking */
  def withVersioning(enable: Boolean): LoadingConfig = copy(enableVersioning = enable)

  /** Set parallelism level */
  def withParallelism(n: Int): LoadingConfig = copy(parallelism = math.max(1, n))

  /** Set batch size for embeddings */
  def withBatchSize(n: Int): LoadingConfig = copy(batchSize = math.max(1, n))
}

object LoadingConfig {

  /** Default configuration */
  val default: LoadingConfig = LoadingConfig()

  /** Strict mode - fail on any error */
  val strict: LoadingConfig = LoadingConfig(failFast = true)

  /** Lenient mode - skip all errors */
  val lenient: LoadingConfig = LoadingConfig(
    failFast = false,
    skipEmptyDocuments = true
  )

  /** High performance mode - more parallelism */
  val highPerformance: LoadingConfig = LoadingConfig(
    parallelism = 8,
    batchSize = 20
  )

  /** Conservative mode - less parallelism, smaller batches */
  val conservative: LoadingConfig = LoadingConfig(
    parallelism = 2,
    batchSize = 5
  )
}
