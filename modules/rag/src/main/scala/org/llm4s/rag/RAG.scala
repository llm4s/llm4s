package org.llm4s.rag

import org.llm4s.chunking.{ ChunkerFactory, DocumentChunk, DocumentChunker }
import org.llm4s.error.{ ConfigurationError, LLMError, ProcessingError }
import org.llm4s.knowledgegraph.graphrag.{ GraphRAG, GraphRAGAnswer, GraphRAGMode }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, ProviderRegistry }
import org.llm4s.model.ModelRegistryService
import org.llm4s.extract.TikaDocumentExtractor
import org.llm4s.rag.loader.*
import org.llm4s.rag.permissions.*
import org.llm4s.rag.transform.QueryTransformer
import org.llm4s.reranker.{ RerankProviderConfig, Reranker, RerankerFactory }
import org.llm4s.trace.Tracing
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.vectorstore.*

import org.slf4j.LoggerFactory

import java.io.{ Closeable, File }
import java.nio.file.Path
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ ExecutionContext, Future }

/**
 * High-level RAG (Retrieval-Augmented Generation) pipeline.
 *
 * Provides a unified interface for:
 * - Document ingestion (from files, directories, or raw text)
 * - Semantic search with hybrid fusion
 * - Answer generation with retrieved context
 *
 * @example
 * {{{
 * // Create pipeline
 * val rag = RAG.builder()
 *   .withEmbeddings("openai")
 *   .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
 *   .build()
 *   .toOption.get
 *
 * // Ingest documents
 * rag.ingest("./docs")
 *
 * // Search
 * val results = rag.query("What is X?")
 *
 * // With answer generation (requires LLM client)
 * val ragWithLLM = RAG.builder()
 *   .withEmbeddings("openai")
 *   .withLLM(llmClient)
 *   .build()
 *   .toOption.get
 *
 * val answer = ragWithLLM.queryWithAnswer("What is X?")
 * }}}
 */
final class RAG private (
  val config: RAGConfig,
  private val embeddingClient: EmbeddingClient,
  private val embeddingModelConfig: EmbeddingModelConfig,
  private val chunker: DocumentChunker,
  private val hybridSearcher: HybridSearcher,
  private val reranker: Option[Reranker],
  private val llmClient: Option[LLMClient],
  private val tracer: Option[Tracing],
  private val registry: DocumentRegistry,
  private val graphRAG: Option[GraphRAG]
) extends Closeable {

  // Statistics tracking: the chunk count of each document this instance has indexed. Keyed by
  // document so that re-ingesting one replaces its count rather than adding to it.
  private val indexedChunkCounts = TrieMap.empty[String, Int]

  // Traced embedding client
  private lazy val tracedEmbeddingClient: EmbeddingClient = tracer match {
    case Some(t) => embeddingClient.withTracing(t).withOperation("rag")
    case None    => embeddingClient
  }

  // ========== Ingestion API ==========

  /**
   * Ingest a document from a file path.
   *
   * Supports: .txt, .md, .pdf, .docx and other text-like formats.
   *
   * @param path Path to file or directory
   * @param metadata Additional metadata to attach to all chunks
   * @return Number of chunks created
   */
  def ingest(path: String, metadata: Map[String, String] = Map.empty): Result[Int] =
    ingestPath(new File(path).toPath, metadata)

  /**
   * Ingest a document from a Path.
   */
  def ingest(path: Path): Result[Int] =
    ingestPath(path, Map.empty)

  /**
   * Ingest a document from a Path with metadata.
   */
  def ingestPath(path: Path, metadata: Map[String, String]): Result[Int] = {
    val file = path.toFile
    if (file.isDirectory) {
      ingestDirectory(file, metadata)
    } else {
      ingestFile(file, metadata)
    }
  }

  /**
   * Ingest raw text content.
   *
   * Ingesting an id that is already indexed replaces what is indexed under it. Replacing one document is not
   * atomic across the two stores, so concurrent re-ingests of the SAME document id are not supported: serialise
   * them in the caller. Different documents may be ingested concurrently.
   *
   * @param content The text content to ingest
   * @param documentId Unique identifier for this document
   * @param metadata Additional metadata
   * @return Number of chunks created
   */
  def ingestText(
    content: String,
    documentId: String,
    metadata: Map[String, String] = Map.empty
  ): Result[Int] = {
    val chunks = chunker.chunk(content, config.chunkingConfig)
    indexChunks(documentId, chunks, metadata)
  }

  /**
   * Ingest pre-chunked content (for advanced use cases).
   */
  def ingestChunks(
    documentId: String,
    chunks: Seq[String],
    metadata: Map[String, String] = Map.empty
  ): Result[Int] = {
    val docChunks = chunks.zipWithIndex.map { case (content, idx) =>
      DocumentChunk(content, idx)
    }
    indexChunks(documentId, docChunks, metadata)
  }

  // ========== Byte-Based Ingestion API ==========

  /**
   * Ingest a document from raw bytes.
   *
   * This enables source-agnostic document ingestion - the same extraction
   * logic works for documents from S3, HTTP responses, databases, etc.
   *
   * Supported formats: PDF, DOCX, plain text, and anything Apache Tika can handle.
   *
   * @param content Raw document bytes
   * @param filename Filename for format detection (e.g., "report.pdf")
   * @param documentId Unique identifier for this document
   * @param metadata Additional metadata
   * @return Number of chunks created
   */
  def ingestBytes(
    content: Array[Byte],
    filename: String,
    documentId: String,
    metadata: Map[String, String] = Map.empty
  ): Result[Int] =
    TikaDocumentExtractor.extract(content, filename).flatMap { extracted =>
      val enrichedMetadata = metadata ++ extracted.metadata + ("format" -> extracted.format.name)
      ingestText(extracted.text, documentId, enrichedMetadata)
    }

  /**
   * Ingest multiple documents from raw bytes.
   *
   * @param documents Iterator of (content, filename, documentId, metadata) tuples
   * @return Loading statistics with success/failure counts
   */
  def ingestBytesMultiple(
    documents: Iterator[(Array[Byte], String, String, Map[String, String])]
  ): Result[LoadStats] = {
    var successful = 0
    var failed     = 0
    val errors     = scala.collection.mutable.ListBuffer[(String, org.llm4s.error.LLMError)]()

    documents.foreach { case (content, filename, docId, metadata) =>
      ingestBytes(content, filename, docId, metadata) match {
        case Right(_) =>
          successful += 1
        case Left(err) =>
          failed += 1
          errors += ((docId, err))
      }
    }

    Right(
      LoadStats(
        totalAttempted = successful + failed,
        successful = successful,
        failed = failed,
        skipped = 0,
        errors = errors.toSeq
      )
    )
  }

  // ========== Document Loader API ==========

  /**
   * Ingest documents from a DocumentLoader.
   *
   * A document that fails to load or ingest is counted in the returned [[LoadStats]] (or, with
   * `failFast`, returned as the `Left`). A [[LoadResult.ListingFailure]] - the loader could not
   * enumerate its documents at all - is always returned as the `Left`.
   *
   * @param loader The document loader to ingest from
   * @return Loading statistics with success/failure counts
   */
  def ingest(loader: DocumentLoader): Result[LoadStats] = ingestResults(loader.load())

  private def ingestResults(results: Iterator[LoadResult]): Result[LoadStats] = {
    case class State(
      totalAttempted: Int = 0,
      successful: Int = 0,
      failed: Int = 0,
      skipped: Int = 0,
      errors: Seq[(String, org.llm4s.error.LLMError)] = Seq.empty,
      abortError: Option[org.llm4s.error.LLMError] = None
    )

    val finalState = results.foldLeft(State()) { (state, result) =>
      if (state.abortError.isDefined) {
        state // Already failed, skip remaining
      } else {
        val newTotal = state.totalAttempted + 1
        result match {
          case LoadResult.Success(doc) =>
            if (config.loadingConfig.skipEmptyDocuments && doc.content.trim.isEmpty) {
              state.copy(totalAttempted = newTotal, skipped = state.skipped + 1)
            } else {
              ingestDocument(doc) match {
                case Right(_) =>
                  state.copy(totalAttempted = newTotal, successful = state.successful + 1)
                case Left(error) =>
                  val newState = state.copy(
                    totalAttempted = newTotal,
                    failed = state.failed + 1,
                    errors = state.errors :+ (doc.id, error)
                  )
                  if (config.loadingConfig.failFast) {
                    newState.copy(abortError = Some(error))
                  } else {
                    newState
                  }
              }
            }

          case LoadResult.Failure(source, error, _, _) =>
            val newState = state.copy(
              totalAttempted = newTotal,
              failed = state.failed + 1,
              errors = state.errors :+ (source, error)
            )
            if (config.loadingConfig.failFast) {
              newState.copy(abortError = Some(error))
            } else {
              newState
            }

          case LoadResult.Skipped(_, _) =>
            state.copy(totalAttempted = newTotal, skipped = state.skipped + 1)

          case LoadResult.ListingFailure(_, error) =>
            // Not one failed document: what the loader holds is unknown, so ingest fails
            // whatever `failFast` says, rather than report success over a partial listing.
            state.copy(abortError = Some(error))
        }
      }
    }

    finalState.abortError match {
      case Some(error) => Left(error)
      case None =>
        Right(
          LoadStats(
            finalState.totalAttempted,
            finalState.successful,
            finalState.failed,
            finalState.skipped,
            finalState.errors
          )
        )
    }
  }

  /**
   * Sync with a loader - only process changed documents.
   *
   * Compares document versions to detect:
   * - New documents (added)
   * - Changed documents (updated - old chunks removed, new chunks added)
   * - Deleted documents (removed from source)
   * - Unchanged documents (skipped)
   *
   * Sync streams the loader, holding one document at a time plus the ids it has seen. Its
   * deletion pass removes only documents the source no longer lists, never ones it could not
   * read:
   *
   *  - If the loader reports a [[LoadResult.ListingFailure]] - it could not enumerate its
   *    documents, e.g. an S3 listing with no credentials or a missing bucket - sync stops there
   *    and returns that error, and deletes nothing: an unlistable source is not an empty one.
   *    Documents synced before the failure (earlier S3 pages) stay synced.
   *  - A document that is listed but whose read fails - a [[LoadResult.Failure]] with a
   *    `documentId`, e.g. a transient S3 `GetObject` error or a failed extraction - keeps its
   *    indexed version. It is not deleted, and not counted in the returned [[SyncStats]]: it was
   *    neither verified unchanged nor updated. The next sync that reads it picks up any change.
   *  - A [[LoadResult.Failure]] with no `documentId` could be any document, so sync cannot tell
   *    which unlisted documents are really gone: it skips the deletion pass for that run, and
   *    logs a warning. Adds and updates still apply.
   *
   * Read failures are logged at WARN.
   *
   * @param loader The document loader to sync with
   * @return Sync statistics, or the listing error
   */
  def sync(loader: DocumentLoader): Result[SyncStats] = {
    var added                          = 0
    var updated                        = 0
    var unchanged                      = 0
    val seenIds                        = scala.collection.mutable.Set[String]()
    val readFailures                   = Vector.newBuilder[LoadResult.Failure]
    var listingError: Option[LLMError] = None
    val results                        = loader.load()

    // Process documents from the loader, stopping at a listing failure
    while (listingError.isEmpty && results.hasNext) results.next() match {
      case LoadResult.ListingFailure(_, error) =>
        listingError = Some(error)

      case LoadResult.Success(doc) =>
        if (config.loadingConfig.skipEmptyDocuments && doc.content.trim.isEmpty) {
          // Skip empty
        } else {
          seenIds += doc.id
          val docVersion = doc.version.getOrElse(DocumentVersion.fromContent(doc.content))

          registry.getVersion(doc.id) match {
            case Right(None) =>
              // New document
              ingestDocument(doc) match {
                case Right(_) =>
                  registry.register(doc.id, docVersion)
                  added += 1
                case Left(_) =>
                // Failed to ingest - skip
              }

            case Right(Some(existingVersion)) if existingVersion.contentHash != docVersion.contentHash =>
              // Changed document - re-ingest, which replaces its chunks. No delete first: a document
              // that fails to re-ingest keeps its previous version.
              ingestDocument(doc) match {
                case Right(_) =>
                  registry.register(doc.id, docVersion)
                  updated += 1
                case Left(_) =>
                // Failed to ingest - skip
              }

            case Right(Some(_)) =>
              // Unchanged
              unchanged += 1

            case Left(_) =>
            // Registry error - skip
          }
        }

      case failure: LoadResult.Failure =>
        // Listed but unreadable: not ingested, and not deleted by the deletion pass
        readFailures += failure

      case LoadResult.Skipped(_, _) =>
      // Skip skipped documents
    }

    listingError match {
      case Some(error) =>
        // seenIds is incomplete, so the deletion pass would remove documents that still exist
        Left(error)

      case None =>
        val deleted = deleteUnlisted(seenIds.toSet, readFailures.result(), "sync")
        Right(SyncStats(added, updated, deleted, unchanged))
    }
  }

  /**
   * The deletion pass shared by [[sync]] and [[syncAsync]]: remove each registered document the
   * loader did not list. A document whose read failed counts as listed. A read failure that does
   * not name its document could be any unlisted one, so then nothing is deleted.
   *
   * @return the number of documents deleted
   */
  private def deleteUnlisted(seenIds: Set[String], readFailures: Seq[LoadResult.Failure], operation: String): Int = {
    val (named, unnamed) = readFailures.partition(_.documentId.isDefined)
    val keptIds          = named.flatMap(_.documentId).toSet

    if (named.nonEmpty) {
      RAG.logger.warn(
        s"$operation: ${named.size} listed document(s) could not be read, and keep their indexed version: " +
          RAG.describeFailures(named)
      )
    }

    if (unnamed.nonEmpty) {
      RAG.logger.warn(
        s"$operation: skipping the deletion pass: ${unnamed.size} read failure(s) do not name their document, " +
          s"so any unlisted document could be one of them: ${RAG.describeFailures(unnamed)}"
      )
      0
    } else {
      registry.allDocumentIds() match {
        case Right(registeredIds) =>
          val deletedIds = registeredIds -- seenIds -- keptIds
          deletedIds.foreach { id =>
            deleteDocumentChunks(id)
            registry.unregister(id)
          }
          deletedIds.size

        case Left(_) =>
          // Registry error - skip deletion check
          0
      }
    }
  }

  /**
   * Full refresh - re-process all documents.
   *
   * Clears the registry and the index, then re-ingests every document from the loader. Use this
   * when you want to ensure a clean slate.
   *
   * '''The index is cleared only after the loader has been read in full.''' If the loader
   * reports a [[LoadResult.ListingFailure]] (at any point, including a later S3 page), or - with
   * `failFast` - any [[LoadResult.Failure]], refresh returns that error as the `Left` and leaves
   * the existing index and registry untouched. Without `failFast`, a document that fails to read
   * is left out of the rebuilt index, as a clean slate implies, and logged at WARN; use [[sync]]
   * to keep a document's indexed version while its source is unreadable.
   *
   * '''Memory:''' to give that guarantee, refresh holds every loaded [[Document]] - its extracted
   * text and metadata, not the raw bytes - in memory before it clears anything. For a source too
   * large for that, use [[sync]], which streams one document at a time.
   *
   * The guarantee covers reading the loader, not ingesting: an embedding or store error after the
   * index is cleared counts that document as failed (or, with `failFast`, is returned as the
   * `Left`), and the index then holds what was ingested before it.
   *
   * @param loader The document loader to refresh from
   * @return Sync statistics (all as "added"), or the loader's error with the index untouched
   */
  def refresh(loader: DocumentLoader): Result[SyncStats] =
    for {
      results <- readForRefresh(loader)
      _       <- registry.clear()
      _       <- clear()
      stats   <- ingestResults(results.iterator)
    } yield SyncStats(added = stats.successful, updated = 0, deleted = 0, unchanged = 0)

  /**
   * Read the whole loader before [[refresh]] clears anything, stopping at the first result that
   * must abort the refresh: a listing failure, or any read failure under `failFast`.
   */
  private def readForRefresh(loader: DocumentLoader): Result[Vector[LoadResult]] = {
    val results                 = loader.load()
    val read                    = Vector.newBuilder[LoadResult]
    var abort: Option[LLMError] = None

    while (abort.isEmpty && results.hasNext) results.next() match {
      case LoadResult.ListingFailure(_, error)                          => abort = Some(error)
      case failure: LoadResult.Failure if config.loadingConfig.failFast => abort = Some(failure.error)
      case result                                                       => read += result
    }

    abort match {
      case Some(error) => Left(error)
      case None =>
        val loaded   = read.result()
        val failures = loaded.collect { case failure: LoadResult.Failure => failure }
        if (failures.nonEmpty) {
          RAG.logger.warn(
            s"refresh: ${failures.size} document(s) could not be read, and will not be in the rebuilt index: " +
              RAG.describeFailures(failures)
          )
        }
        Right(loaded)
    }
  }

  /**
   * Delete a specific document and its chunks.
   *
   * Limitation: a document's chunks are named `<docId>-chunk-<n>` and the stores delete by prefix, so deleting
   * `a` also deletes the chunks of a document whose id itself looks like `a-chunk-<n>` (for example `a-chunk-1`,
   * whose chunks are `a-chunk-1-chunk-0`, ...). Avoid document ids of that shape. Re-ingesting a document is not
   * affected: it replaces chunks by their exact ids.
   *
   * @param docId Document ID to delete
   * @return Unit on success
   */
  def deleteDocument(docId: String): Result[Unit] =
    for {
      _ <- deleteDocumentChunks(docId)
      _ <- registry.unregister(docId)
    } yield ()

  /**
   * Check if a document needs updating based on version.
   *
   * @param doc Document to check
   * @return true if document is new or changed
   */
  def needsUpdate(doc: Document): Result[Boolean] = {
    val docVersion = doc.version.getOrElse(DocumentVersion.fromContent(doc.content))
    registry.getVersion(doc.id).map {
      case None                                                             => true
      case Some(existing) if existing.contentHash != docVersion.contentHash => true
      case _                                                                => false
    }
  }

  // ========== Async Document Loader API ==========

  /**
   * Async version of ingest with parallel document processing.
   *
   * Processes documents in batches with configurable parallelism.
   * Uses the parallelism and batchSize settings from LoadingConfig.
   *
   * As with [[ingest]], a [[LoadResult.ListingFailure]] is returned as the `Left`; as the
   * results are collected first, nothing is ingested in that case.
   *
   * @param loader The document loader to ingest from
   * @param ec Execution context for async operations
   * @return Future with loading statistics
   */
  def ingestAsync(loader: DocumentLoader)(implicit ec: ExecutionContext): Future[Result[LoadStats]] = {
    // Collect all results to enable parallel processing
    val results = loader.load().toSeq
    firstListingError(results) match {
      case Some(error) => Future.successful(Left(error))
      case None        => ingestResultsAsync(results)
    }
  }

  private def firstListingError(results: Seq[LoadResult]): Option[LLMError] =
    results.collectFirst { case LoadResult.ListingFailure(_, error) => error }

  private def ingestResultsAsync(
    results: Seq[LoadResult]
  )(implicit ec: ExecutionContext): Future[Result[LoadStats]] = {
    val batchSize = config.loadingConfig.batchSize

    // Process each result asynchronously
    val futureOutcomes: Seq[Future[(String, Option[(String, org.llm4s.error.LLMError)])]] =
      results.map { result =>
        Future {
          result match {
            case LoadResult.Success(doc) =>
              if (config.loadingConfig.skipEmptyDocuments && doc.content.trim.isEmpty) {
                ("skipped", None)
              } else {
                ingestDocument(doc) match {
                  case Right(_)    => ("success", None)
                  case Left(error) => ("failed", Some((doc.id, error)))
                }
              }
            case LoadResult.Failure(source, error, _, _) =>
              ("failed", Some((source, error)))
            case LoadResult.ListingFailure(source, error) => // ingestAsync returns these as the Left first
              ("failed", Some((source, error)))
            case LoadResult.Skipped(_, _) =>
              ("skipped", None)
          }
        }.recover { case ex =>
          ("failed", Some(("unknown", org.llm4s.error.ThrowableOps.RichThrowable(ex).toLLMError)))
        }
      }

    // Process in batches to control parallelism
    val batches = futureOutcomes.grouped(batchSize).toSeq

    // Aggregate batch results sequentially
    batches
      .foldLeft(Future.successful((0, 0, 0, Seq.empty[(String, org.llm4s.error.LLMError)]))) {
        case (accFuture, batchFutures) =>
          for {
            (successful, failed, skipped, errors) <- accFuture
            batchResults                          <- Future.sequence(batchFutures)
          } yield {
            var s = successful
            var f = failed
            var k = skipped
            var e = errors

            batchResults.foreach {
              case ("success", _)             => s += 1
              case ("failed", Some(errTuple)) => f += 1; e = e :+ errTuple
              case ("skipped", _)             => k += 1
              case _                          => ()
            }

            (s, f, k, e)
          }
      }
      .map { case (successful, failed, skipped, errors) =>
        if (config.loadingConfig.failFast && errors.nonEmpty) {
          Left(errors.head._2)
        } else {
          Right(
            LoadStats(
              results.size,
              successful,
              failed,
              skipped,
              errors
            )
          )
        }
      }
  }

  /**
   * Async sync with parallel change detection.
   *
   * Performs change detection in parallel, but applies updates sequentially
   * to avoid conflicts in the vector store.
   *
   * As with [[sync]], a [[LoadResult.ListingFailure]] is returned as the `Left`; as the
   * results are collected first, nothing is added, updated or deleted in that case. A document
   * whose read fails keeps its indexed version, and a read failure that does not name its
   * document skips the deletion pass, as in [[sync]]. Unlike [[sync]], it holds every loaded
   * document in memory.
   *
   * @param loader The document loader to sync with
   * @param ec Execution context for async operations
   * @return Future with sync statistics
   */
  def syncAsync(loader: DocumentLoader)(implicit ec: ExecutionContext): Future[Result[SyncStats]] = {
    val results = loader.load().toSeq
    firstListingError(results) match {
      case Some(error) => Future.successful(Left(error))
      case None =>
        syncDocumentsAsync(
          results.collect { case LoadResult.Success(d) => d },
          results.collect { case failure: LoadResult.Failure => failure }
        )
    }
  }

  private def syncDocumentsAsync(
    docs: Seq[Document],
    readFailures: Seq[LoadResult.Failure]
  )(implicit ec: ExecutionContext): Future[Result[SyncStats]] = {
    val batchSize = config.loadingConfig.batchSize

    // Sealed trait for change detection results
    sealed trait ChangeType
    case object NewDoc       extends ChangeType
    case object UpdatedDoc   extends ChangeType
    case object UnchangedDoc extends ChangeType
    case object KeptDoc      extends ChangeType // listed, but its version could not be checked
    case class ProcessDoc(doc: Document, change: ChangeType)

    // Parallel change detection using Futures
    val changeDetectionFutures: Seq[Future[Option[ProcessDoc]]] = docs.map { doc =>
      Future {
        if (config.loadingConfig.skipEmptyDocuments && doc.content.trim.isEmpty) {
          None
        } else {
          val docVersion = doc.version.getOrElse(DocumentVersion.fromContent(doc.content))
          registry.getVersion(doc.id) match {
            case Right(None) =>
              Some(ProcessDoc(doc, NewDoc))
            case Right(Some(existing)) if existing.contentHash != docVersion.contentHash =>
              Some(ProcessDoc(doc, UpdatedDoc))
            case Right(Some(_)) =>
              Some(ProcessDoc(doc, UnchangedDoc))
            case Left(_) =>
              // Registry error: as in sync, the document was listed, so it is not deleted
              Some(ProcessDoc(doc, KeptDoc))
          }
        }
      }.recover { case _ =>
        // On error, treat as unchanged so the document stays in seenIds
        // and is not incorrectly deleted during the deletion step.
        Some(ProcessDoc(doc, UnchangedDoc))
      }
    }

    // Process change detection in batches
    val batches = changeDetectionFutures.grouped(batchSize).toSeq

    batches
      .foldLeft(Future.successful(Seq.empty[ProcessDoc])) { case (accFuture, batchFutures) =>
        for {
          acc          <- accFuture
          batchResults <- Future.sequence(batchFutures)
        } yield acc ++ batchResults.flatten
      }
      .map { changeResults =>
        // Apply changes sequentially to avoid conflicts
        var added     = 0
        var updated   = 0
        var unchanged = 0
        val seenIds   = scala.collection.mutable.Set[String]()

        changeResults.foreach {
          case ProcessDoc(doc, NewDoc) =>
            seenIds += doc.id
            val docVersion = doc.version.getOrElse(DocumentVersion.fromContent(doc.content))
            ingestDocument(doc) match {
              case Right(_) =>
                registry.register(doc.id, docVersion)
                added += 1
              case Left(_) => // Failed to ingest - skip
            }

          case ProcessDoc(doc, UpdatedDoc) =>
            seenIds += doc.id
            val docVersion = doc.version.getOrElse(DocumentVersion.fromContent(doc.content))
            // Re-ingesting replaces the chunks; a failure keeps the previous version, as in sync.
            ingestDocument(doc) match {
              case Right(_) =>
                registry.register(doc.id, docVersion)
                updated += 1
              case Left(_) => // Failed to ingest - skip
            }

          case ProcessDoc(doc, UnchangedDoc) =>
            seenIds += doc.id
            unchanged += 1

          case ProcessDoc(doc, KeptDoc) =>
            seenIds += doc.id
        }

        val deleted = deleteUnlisted(seenIds.toSet, readFailures, "syncAsync")
        Right(SyncStats(added, updated, deleted, unchanged))
      }
  }

  /**
   * Async full refresh: [[refresh]], run on `ec`.
   *
   * It gives the same guarantee: the loader is read in full before the index is cleared, so a
   * [[LoadResult.ListingFailure]] (or, with `failFast`, any read failure) is returned as the
   * `Left` with the index untouched - and it holds every loaded document in memory to do so.
   *
   * @param loader The document loader to refresh from
   * @param ec Execution context for async operations
   * @return Future with sync statistics
   */
  def refreshAsync(loader: DocumentLoader)(implicit ec: ExecutionContext): Future[Result[SyncStats]] =
    Future(refresh(loader)).recover { case ex => Left(org.llm4s.error.ThrowableOps.RichThrowable(ex).toLLMError) }

  private def ingestDocument(doc: Document): Result[Int] = {
    // Choose chunker based on hints if configured
    val effectiveChunker = if (config.loadingConfig.useHints) {
      doc.hints.flatMap(_.chunkingStrategy) match {
        case Some(strategy) => ChunkerFactory.create(strategy)
        case None           => chunker
      }
    } else {
      chunker
    }

    val effectiveConfig = if (config.loadingConfig.useHints) {
      doc.hints.flatMap(_.chunkingConfig).getOrElse(config.chunkingConfig)
    } else {
      config.chunkingConfig
    }

    val chunks = effectiveChunker.chunk(doc.content, effectiveConfig)
    val result = indexChunks(doc.id, chunks, doc.metadata)

    // Register version if enabled - only once it is indexed: a document that failed keeps its previous
    // version, and registering the new one would make the next sync skip it as unchanged.
    if (config.loadingConfig.enableVersioning && result.isRight) {
      val version = doc.version.getOrElse(DocumentVersion.fromContent(doc.content))
      registry.register(doc.id, version)
    }

    result
  }

  private def deleteDocumentChunks(docId: String): Result[Unit] =
    // Chunks are named "docId-chunk-N". The prefix must include the "-chunk-" separator: a bare
    // docId prefix would also match every other document whose id merely starts with it
    // ("doc-1" would take "doc-10" and "doc-1-appendix" with it).
    for {
      _ <- hybridSearcher.vectorStore.deleteByPrefix(s"$docId-chunk-")
      _ <- hybridSearcher.keywordIndex.deleteByPrefix(s"$docId-chunk-")
    } yield {
      indexedChunkCounts.remove(docId)
      ()
    }

  private def chunkId(docId: String, index: Int): String = s"$docId-chunk-$index"

  // ========== Query API ==========

  /**
   * Search for relevant chunks.
   *
   * If query transformers are configured, they are applied sequentially
   * to the query before embedding. The transformed query is used for
   * both vector search and keyword search.
   *
   * @param query The search query
   * @param topK Override default topK (optional)
   * @return Ranked search results
   */
  def query(query: String, topK: Option[Int] = None): Result[Seq[RAGSearchResult]] = {
    val k = topK.getOrElse(config.topK)

    for {
      transformedQuery <- applyQueryTransforms(query)
      queryEmbedding   <- embedQuery(transformedQuery)
      results          <- searchWithStrategy(queryEmbedding, transformedQuery, k)
    } yield results.map(toSearchResult)
  }

  /**
   * Search and generate an answer using LLM.
   *
   * Requires an LLM client to be configured.
   *
   * @param question The question to answer
   * @param topK Override default topK (optional)
   * @return Answer with supporting contexts
   */
  def queryWithAnswer(question: String, topK: Option[Int] = None): Result[RAGAnswerResult] =
    llmClient match {
      case None =>
        Left(
          ConfigurationError(
            "LLM client required for answer generation. Use .withLLM(client) when building RAG."
          )
        )
      case Some(client) =>
        for {
          searchResults <- query(question, topK)
          answer        <- generateAnswer(client, question, searchResults)
        } yield answer
    }

  /**
   * Query through GraphRAG using router-selected mode.
   *
   * The router decides Global/Local/Hybrid first.
   * Vector retrieval is only executed when Hybrid is selected.
   */
  def queryWithGraphRAG(question: String, topK: Option[Int] = None): Result[GraphRAGAnswer] =
    graphRAG match {
      case None =>
        Left(
          ConfigurationError(
            "GraphRAG not configured. Use .withGraphStore(graphStore) and .withLLM(client) when building RAG."
          )
        )
      case Some(engine) =>
        for {
          routed <- engine.route(question)
          answer <- queryWithGraphRAGMode(question, routed, topK)
        } yield answer
    }

  /**
   * Query GraphRAG in a specific mode.
   */
  def queryWithGraphRAGMode(question: String, mode: GraphRAGMode, topK: Option[Int] = None): Result[GraphRAGAnswer] =
    graphRAG match {
      case None =>
        Left(
          ConfigurationError(
            "GraphRAG not configured. Use .withGraphStore(graphStore) and .withLLM(client) when building RAG."
          )
        )
      case Some(engine) =>
        mode match {
          case GraphRAGMode.Global =>
            engine.globalSearch(question, config.graphRAGConfig.globalTopCommunities).map { res =>
              GraphRAGAnswer(
                query = question,
                mode = GraphRAGMode.Global,
                answer = res.answer,
                communityIds = res.rankedCommunities.map(_._1.id),
                nodeIds = Seq.empty
              )
            }
          case GraphRAGMode.Local =>
            engine.localSearch(question, config.graphRAGConfig.localTraversalDepth).map { res =>
              GraphRAGAnswer(
                query = question,
                mode = GraphRAGMode.Local,
                answer = res.answer,
                communityIds = res.supportingCommunities.map(_.id),
                nodeIds = res.visitedNodeIds
              )
            }
          case GraphRAGMode.Hybrid =>
            val k = topK.getOrElse(config.topK)
            for {
              queryEmbedding <- embedQuery(question)
              vectorResults  <- searchWithStrategy(queryEmbedding, question, k)
              hybrid         <- engine.hybridSearch(question, vectorResults, config.graphRAGConfig.localTraversalDepth)
            } yield GraphRAGAnswer(
              query = question,
              mode = GraphRAGMode.Hybrid,
              answer = hybrid.answer,
              communityIds = Seq.empty,
              nodeIds = hybrid.hits.map(_.node.id)
            )
        }
    }

  /** Check if GraphRAG is available for this pipeline. */
  def hasGraphRAG: Boolean = graphRAG.isDefined

  // ========== Permission-Aware API ==========

  /** Access the permission-based search index (if configured) */
  def searchIndex: Option[SearchIndex] = config.searchIndex

  /** Check if permission-based search is available */
  def hasPermissions: Boolean = config.searchIndex.isDefined

  /**
   * Search with permission filtering.
   *
   * Requires a SearchIndex to be configured via .withSearchIndex().
   * Results are filtered based on user authorization:
   * - Collection-level: Only search collections the user can access
   * - Document-level: Only return documents the user can read
   *
   * @param auth User authorization context with principal IDs
   * @param collectionPattern Pattern to filter collections (e.g., "confluence" for all descendants)
   * @param queryText The search query
   * @param topK Maximum number of results (defaults to config.topK)
   * @return Permission-filtered search results
   */
  def queryWithPermissions(
    auth: UserAuthorization,
    collectionPattern: CollectionPattern,
    queryText: String,
    topK: Option[Int] = None
  ): Result[Seq[RAGSearchResult]] =
    config.searchIndex match {
      case None =>
        Left(
          ConfigurationError(
            "SearchIndex required for permission-aware queries. Use .withSearchIndex(index) when building RAG."
          )
        )
      case Some(index) =>
        for {
          queryEmbedding <- embedQuery(queryText)
          results <- index.query(
            auth = auth,
            collectionPattern = collectionPattern,
            queryVector = queryEmbedding,
            topK = topK.getOrElse(config.topK)
          )
        } yield results.map(scoredRecordToSearchResult)
    }

  /**
   * Search with permissions and generate an answer using LLM.
   *
   * Requires both a SearchIndex and LLM client to be configured.
   *
   * @param auth User authorization context
   * @param collectionPattern Pattern to filter collections
   * @param question The question to answer
   * @param topK Maximum number of results
   * @return Answer with permission-filtered supporting contexts
   */
  def queryWithPermissionsAndAnswer(
    auth: UserAuthorization,
    collectionPattern: CollectionPattern,
    question: String,
    topK: Option[Int] = None
  ): Result[RAGAnswerResult] =
    llmClient match {
      case None =>
        Left(
          ConfigurationError(
            "LLM client required for answer generation. Use .withLLM(client) when building RAG."
          )
        )
      case Some(client) =>
        for {
          searchResults <- queryWithPermissions(auth, collectionPattern, question, topK)
          answer        <- generateAnswer(client, question, searchResults)
        } yield answer
    }

  /**
   * Ingest a document into a specific collection with permission control.
   *
   * Requires a SearchIndex to be configured via .withSearchIndex().
   *
   * @param collectionPath Target collection (must be a leaf collection)
   * @param documentId Unique document identifier
   * @param content Document text content
   * @param metadata Additional document metadata
   * @param readableBy Principal IDs that can read this document (empty = inherit from collection)
   * @return Number of chunks indexed
   */
  def ingestWithPermissions(
    collectionPath: CollectionPath,
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty
  ): Result[Int] =
    config.searchIndex match {
      case None =>
        Left(
          ConfigurationError(
            "SearchIndex required for permission-aware ingestion. Use .withSearchIndex(index) when building RAG."
          )
        )
      case Some(index) =>
        val docChunks = chunker.chunk(content, config.chunkingConfig)
        for {
          embeddings <- embedBatch(docChunks.map(_.content))
          chunksWithEmbeddings = docChunks.zip(embeddings).map { case (chunk, embedding) =>
            ChunkWithEmbedding(
              content = chunk.content,
              embedding = embedding,
              chunkIndex = chunk.index,
              metadata = metadata
            )
          }
          count <- index.ingest(
            collectionPath = collectionPath,
            documentId = documentId,
            chunks = chunksWithEmbeddings,
            metadata = metadata,
            readableBy = readableBy
          )
        } yield count
    }

  /**
   * Delete a document from a collection.
   *
   * @param collectionPath The collection containing the document
   * @param documentId The document identifier
   * @return Number of chunks deleted
   */
  def deleteFromCollection(collectionPath: CollectionPath, documentId: String): Result[Long] =
    config.searchIndex match {
      case None =>
        Left(
          ConfigurationError(
            "SearchIndex required for permission-aware deletion. Use .withSearchIndex(index) when building RAG."
          )
        )
      case Some(index) =>
        index.deleteDocument(collectionPath, documentId)
    }

  private def scoredRecordToSearchResult(sr: ScoredRecord): RAGSearchResult =
    RAGSearchResult(
      id = sr.record.id,
      content = sr.record.content.getOrElse(""),
      score = sr.score,
      metadata = sr.record.metadata,
      vectorScore = Some(sr.score),
      keywordScore = None
    )

  // ========== Statistics API ==========

  /**
   * Number of documents this instance has indexed and not since deleted. A re-ingested document
   * counts once; one that was re-ingested with no chunks does not count.
   */
  def documentCount: Int = indexedChunkCounts.size

  /** Number of chunks this instance has indexed and not since deleted: a re-ingest replaces a document's count. */
  def chunkCount: Int = indexedChunkCounts.values.sum

  /** Get store statistics */
  def stats: Result[RAGStats] =
    for {
      vectorCount <- hybridSearcher.vectorStore.count()
    } yield RAGStats(
      documentCount = documentCount,
      chunkCount = chunkCount,
      vectorCount = vectorCount
    )

  // ========== Lifecycle API ==========

  /**
   * Clear all indexed data.
   */
  def clear(): Result[Unit] =
    for {
      _ <- hybridSearcher.vectorStore.clear()
      _ <- hybridSearcher.keywordIndex.clear()
    } yield indexedChunkCounts.clear()

  /**
   * Close resources.
   */
  override def close(): Unit =
    hybridSearcher.close()

  // ========== Private Implementation ==========

  /**
   * Apply configured query transforms sequentially.
   * Returns the original query unchanged if no transforms are configured.
   */
  private def applyQueryTransforms(query: String): Result[String] =
    if (config.queryTransformers.isEmpty) Right(query)
    else QueryTransformer.applyChain(query, config.queryTransformers)

  private val supportedExtensions = Set(".txt", ".md", ".pdf", ".docx", ".json", ".xml", ".html")

  private def ingestDirectory(dir: File, metadata: Map[String, String]): Result[Int] = {
    val files = Option(dir.listFiles())
      .map(_.toSeq)
      .getOrElse(Seq.empty)
      .filter(f => f.isFile && supportedExtensions.exists(ext => f.getName.toLowerCase.endsWith(ext)))

    files.foldLeft[Result[Int]](Right(0)) { (acc, file) =>
      for {
        count <- acc
        added <- ingestFile(file, metadata)
      } yield count + added
    }
  }

  private def ingestFile(file: File, metadata: Map[String, String]): Result[Int] = {
    val docId = file.getName

    TikaDocumentExtractor.extractFromPath(file.getAbsolutePath) match {
      case Left(error) =>
        Left(ProcessingError("ingest", s"Failed to extract ${file.getName}: ${error.message}"))
      case Right(document) =>
        val fullMetadata = metadata + ("source" -> file.getName) + ("path" -> file.getAbsolutePath)
        val chunks       = chunker.chunkWithSource(document.text, file.getName, config.chunkingConfig)
        indexChunks(docId, chunks, fullMetadata)
    }
  }

  private def indexChunks(
    docId: String,
    chunks: Seq[DocumentChunk],
    metadata: Map[String, String]
  ): Result[Int] = {
    // Replace, don't merge (#1318): the stores upsert by chunk id, so a document that now has fewer
    // chunks would otherwise keep its old tail and keep matching queries. Embed, read the previous
    // version, write the new chunks over it, then remove its tail. Nothing is deleted before the write,
    // and the two stores share no transaction, so a write that fails part way is rolled back to the
    // previous version. An empty chunk list still replaces: a document that became empty must not
    // leave its old chunks behind.
    val newIds = chunks.map(chunk => chunkId(docId, chunk.index))

    for {
      embeddings <- if (chunks.isEmpty) Right(Seq.empty[Array[Float]]) else embedBatch(chunks.map(_.content))
      previous   <- storedVersion(docId)
      vectorRecords = chunks.zip(embeddings).map { case (chunk, embedding) =>
        VectorRecord(
          id = chunkId(docId, chunk.index),
          embedding = embedding,
          content = Some(chunk.content),
          metadata = metadata + ("docId" -> docId) + ("chunkIndex" -> chunk.index.toString)
        )
      }
      keywordDocs = chunks.map { chunk =>
        KeywordDocument(
          id = chunkId(docId, chunk.index),
          content = chunk.content,
          metadata = metadata + ("docId" -> docId) + ("chunkIndex" -> chunk.index.toString)
        )
      }
      _ <- replaceVersion(docId, previous, vectorRecords, keywordDocs, newIds)
    } yield {
      if (chunks.isEmpty) indexedChunkCounts.remove(docId) else indexedChunkCounts.update(docId, chunks.size)
      chunks.size
    }
  }

  /**
   * What the stores hold for `docId` before it is re-ingested, kept so a failed write can be undone.
   *
   * Chunk indices run `0 until n` with no gaps, so a document is stored exactly when its chunk 0 is:
   * one lookup by id is all a document that was never indexed costs, rather than a scan of the store
   * by prefix. Only a document that is stored is counted, by `docId`, and read back.
   */
  private def storedVersion(docId: String): Result[RAG.StoredVersion] =
    hybridSearcher.vectorStore.get(chunkId(docId, 0)).flatMap {
      case None => Right(RAG.StoredVersion.empty)
      case Some(_) =>
        for {
          stored <- hybridSearcher.vectorStore.count(Some(MetadataFilter.Equals("docId", docId)))
          ids = (0 until math.max(stored, 1L).toInt).map(chunkId(docId, _))
          vectors  <- hybridSearcher.vectorStore.getBatch(ids)
          keywords <- org.llm4s.Result.traverse(ids.toList)(hybridSearcher.keywordIndex.get)
        } yield RAG.StoredVersion(vectors, keywords.flatten)
    }

  /**
   * Write a document's new chunks over `previous`, then remove the previous version's tail, in both
   * stores. Each step comes with the one that undoes it; when a step fails, the steps before it are
   * undone, newest first, so both stores go back to `previous` rather than describing different
   * versions. A step that fails is taken to have done nothing: every built-in store applies a batch
   * write or delete as one transaction or request. If undoing fails too - the stores are likely still
   * failing - the document may mix versions until it is next ingested, and that is logged at ERROR.
   */
  private def replaceVersion(
    docId: String,
    previous: RAG.StoredVersion,
    vectorRecords: Seq[VectorRecord],
    keywordDocs: Seq[KeywordDocument],
    newIds: Seq[String]
  ): Result[Unit] = {
    val vectors  = hybridSearcher.vectorStore
    val keywords = hybridSearcher.keywordIndex
    val added    = newIds.filterNot(previous.ids.contains)
    val stale    = (previous.ids -- newIds).toSeq

    def unless(skip: Boolean)(op: => Result[Unit]): Result[Unit] = if (skip) Right(()) else op

    val steps: List[(() => Result[Unit], () => Result[Unit])] = List(
      (
        () => unless(vectorRecords.isEmpty)(vectors.upsertBatch(vectorRecords)),
        () =>
          unless(added.isEmpty)(vectors.deleteBatch(added))
            .flatMap(_ => unless(previous.vectors.isEmpty)(vectors.upsertBatch(previous.vectors)))
      ),
      (
        () => unless(keywordDocs.isEmpty)(keywords.indexBatch(keywordDocs)),
        () =>
          unless(added.isEmpty)(keywords.deleteBatch(added))
            .flatMap(_ => unless(previous.keywords.isEmpty)(keywords.indexBatch(previous.keywords)))
      ),
      (
        () => unless(stale.isEmpty)(keywords.deleteBatch(stale)),
        () => {
          val tail = previous.keywords.filter(doc => stale.contains(doc.id))
          unless(tail.isEmpty)(keywords.indexBatch(tail))
        }
      ),
      (() => unless(stale.isEmpty)(vectors.deleteBatch(stale)), () => Right(()))
    )

    @scala.annotation.tailrec
    def run(remaining: List[(() => Result[Unit], () => Result[Unit])], undos: List[() => Result[Unit]]): Result[Unit] =
      remaining match {
        case Nil => Right(())
        case (step, undo) :: rest =>
          step() match {
            case Right(_) => run(rest, undo :: undos)
            case Left(error) =>
              val undoFailures = undos.flatMap(undo => undo().left.toOption)
              if (undoFailures.nonEmpty)
                RAG.logger.error(
                  s"Re-ingesting '$docId' failed (${error.message}) and its previous version could not be restored " +
                    s"(${undoFailures.map(_.message).mkString("; ")}); its chunks may mix versions until it is ingested again"
                )
              Left(error)
          }
      }

    run(steps, Nil)
  }

  private def embedQuery(query: String): Result[Array[Float]] = {
    val request = EmbeddingRequest(Seq(query), embeddingModelConfig)
    tracedEmbeddingClient
      .embed(request)
      .flatMap(
        _.embeddings.headOption
          .toRight(EmbeddingError(None, "Embedding provider returned empty embeddings list", embeddingModelConfig.name))
          .map(_.map(_.toFloat).toArray)
      )
  }

  private def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] = {
    val request = EmbeddingRequest(texts, embeddingModelConfig)
    tracedEmbeddingClient.embed(request).flatMap { response =>
      if (response.embeddings.isEmpty) {
        Left(
          EmbeddingError(
            None,
            "Embedding provider returned empty embeddings list",
            embeddingModelConfig.name
          )
        )
      } else if (response.embeddings.length != texts.length) {
        Left(
          EmbeddingError(
            None,
            s"Embedding provider returned ${response.embeddings.length} ${
                if (response.embeddings.length == 1) "embedding" else "embeddings"
              } for batch of ${texts.length} texts",
            embeddingModelConfig.name
          )
        )
      } else {
        Right(response.embeddings.map(_.map(_.toFloat).toArray))
      }
    }
  }

  private def searchWithStrategy(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int
  ): Result[Seq[HybridSearchResult]] =
    reranker match {
      case Some(r) =>
        hybridSearcher.searchWithReranking(
          queryEmbedding,
          queryText,
          topK,
          config.rerankTopK,
          config.fusionStrategy,
          None,
          Some(r)
        )
      case None =>
        hybridSearcher.search(queryEmbedding, queryText, topK, config.fusionStrategy)
    }

  private def toSearchResult(hsr: HybridSearchResult): RAGSearchResult =
    RAGSearchResult(
      id = hsr.id,
      content = hsr.content,
      score = hsr.score,
      metadata = hsr.metadata,
      vectorScore = hsr.vectorScore,
      keywordScore = hsr.keywordScore
    )

  private def generateAnswer(
    client: LLMClient,
    question: String,
    contexts: Seq[RAGSearchResult]
  ): Result[RAGAnswerResult] = {
    val contextText = contexts.zipWithIndex
      .map { case (ctx, i) => s"[${i + 1}] ${ctx.content}" }
      .mkString("\n\n")

    val systemPrompt = config.systemPrompt.getOrElse(DefaultSystemPrompt)

    val userPrompt = s"""Context:
$contextText

Question: $question

Answer:"""

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    client.complete(conversation, CompletionOptions(temperature = 0.0)).map { completion =>
      RAGAnswerResult(
        answer = completion.content,
        question = question,
        contexts = contexts,
        usage = completion.usage
      )
    }
  }

  private val DefaultSystemPrompt =
    """You are a helpful assistant that answers questions based on the provided context.
      |Use only the information from the context to answer the question.
      |If the context doesn't contain enough information, say so.
      |Be concise and accurate.""".stripMargin
}

object RAG {

  private val logger = LoggerFactory.getLogger(classOf[RAG])

  /** A document's chunks as both stores held them before a re-ingest. */
  final private case class StoredVersion(vectors: Seq[VectorRecord], keywords: Seq[KeywordDocument]) {
    val ids: Set[String] = vectors.map(_.id).toSet ++ keywords.map(_.id)
  }

  private object StoredVersion {
    val empty: StoredVersion = StoredVersion(Seq.empty, Seq.empty)
  }

  /** The first few failures, as `source: message`, for a log line. */
  private def describeFailures(failures: Seq[LoadResult.Failure]): String = {
    val shown = failures.take(5).map(f => s"${f.source}: ${f.error.message}")
    val more  = if (failures.size > 5) Seq(s"... and ${failures.size - 5} more") else Seq.empty
    (shown ++ more).mkString("; ")
  }

  private val missingEmbeddingProviderConfig: String => Result[EmbeddingProviderConfig] = provider =>
    Left(
      ConfigurationError(
        s"Missing embedding provider config for '$provider'. Inject a resolver that returns EmbeddingProviderConfig for the selected provider."
      )
    )

  /**
   * Create a RAG builder with default configuration.
   */
  def builder(): RAGConfig = RAGConfig.default

  /**
   * Build a RAG pipeline from configuration.
   *
   * `config.embeddingProvider` is resolved through the given [[org.llm4s.llmconnect.spi.ProviderRegistry]] -
   * by default every provider on the classpath - so an embedding provider that
   * ships in its own module is usable here with nothing in `llm4s-rag` edited,
   * and one that is absent fails with the registry's "not registered" error.
   *
   * @param resolveEmbeddingProvider supplies the provider config for the resolved, canonical
   *                                 embedding provider id - typically from `Llm4sConfig.embeddings()`.
   */
  def build(
    config: RAGConfig,
    resolveEmbeddingProvider: String => Result[EmbeddingProviderConfig] = missingEmbeddingProviderConfig,
    resolveRerankerConfig: () => Result[Option[RerankProviderConfig]] = () => Right(None)
  )(using ModelRegistryService, ProviderRegistry): Result[RAG] =
    build(config, None, resolveEmbeddingProvider, resolveRerankerConfig, None)

  /**
   * Package-private build method for testing with injected embedding client.
   * This allows tests to use mock embedding providers without needing real HTTP services,
   * and - with `hybridSearcher` - stores of their own instead of the ones `config` describes.
   */
  private[rag] def buildWithClient(
    config: RAGConfig,
    embeddingClient: EmbeddingClient,
    resolveRerankerConfig: () => Result[Option[RerankProviderConfig]] = () => Right(None),
    hybridSearcher: Option[HybridSearcher] = None
  )(using ModelRegistryService, ProviderRegistry): Result[RAG] =
    build(
      config,
      Some(embeddingClient),
      _ => Right(EmbeddingProviderConfig("", "", "")),
      resolveRerankerConfig,
      hybridSearcher
    )

  private def build(
    config: RAGConfig,
    existingClient: Option[EmbeddingClient],
    resolveEmbeddingProvider: String => Result[EmbeddingProviderConfig],
    resolveRerankerConfig: () => Result[Option[RerankProviderConfig]],
    existingSearcher: Option[HybridSearcher]
  )(using ModelRegistryService, ProviderRegistry): Result[RAG] =
    for {
      embeddings <- createEmbeddings(config, existingClient, resolveEmbeddingProvider)
      (embeddingClient, embeddingModelConfig) = embeddings
      chunker                                 = createChunker(config, embeddingClient, embeddingModelConfig)
      hybridSearcher <- existingSearcher.fold(createHybridSearcher(config))(Right(_))
      reranker       <- createReranker(config, resolveRerankerConfig)
      registry       <- createRegistry(config)
      graphRAG       <- createGraphRAG(config)
      rag = new RAG(
        config = config,
        embeddingClient = embeddingClient,
        embeddingModelConfig = embeddingModelConfig,
        chunker = chunker,
        hybridSearcher = hybridSearcher,
        reranker = reranker,
        llmClient = config.llmClient,
        tracer = config.tracer,
        registry = registry,
        graphRAG = graphRAG
      )
      // Process any pre-configured document loaders
      _ <-
        if (config.documentLoaders.nonEmpty) {
          config.documentLoaders.foldLeft[Result[Unit]](Right(())) { (acc, loader) =>
            acc.flatMap(_ => rag.ingest(loader).map(_ => ()))
          }
        } else {
          Right(())
        }
    } yield rag

  private def createRegistry(@scala.annotation.unused config: RAGConfig): Result[DocumentRegistry] =
    // For now, use in-memory registry
    // Future: SQLite registry when vectorStorePath is set
    Right(InMemoryDocumentRegistry())

  private def createGraphRAG(config: RAGConfig): Result[Option[GraphRAG]] =
    config.graphStore match {
      case None => Right(None)
      case Some(store) =>
        config.llmClient match {
          case Some(client) =>
            Right(Some(new GraphRAG(store, client, config.graphRAGConfig)))
          case None =>
            Left(
              ConfigurationError(
                "GraphRAG requires an LLM client for community summaries. Use .withLLM(client) when GraphStore is configured."
              )
            )
        }
    }

  /**
   * Extension method to build from config.
   */
  implicit class RAGConfigOps(private val config: RAGConfig) extends AnyVal {
    def build(using ModelRegistryService, ProviderRegistry): Result[RAG] =
      RAG.build(config)

    def build(
      resolveEmbeddingProvider: String => Result[EmbeddingProviderConfig],
      resolveRerankerConfig: () => Result[Option[RerankProviderConfig]] = () => Right(None)
    )(using ModelRegistryService, ProviderRegistry): Result[RAG] =
      RAG.build(config, resolveEmbeddingProvider, resolveRerankerConfig)
  }

  // ========== Private Builders ==========

  /**
   * Resolves `config.embeddingProvider` through the registry and produces the
   * embedding client together with the model it embeds with.
   *
   * The model is the one the config names, else the one in the resolved provider
   * config, else the provider's own default. Dimensions likewise come from the
   * config, else from the provider's descriptor.
   *
   * @param existingClient an already-built client, used instead of building one; the
   *                       provider is still resolved, since it supplies the model's defaults.
   */
  private def createEmbeddings(
    config: RAGConfig,
    existingClient: Option[EmbeddingClient],
    resolveEmbeddingProvider: String => Result[EmbeddingProviderConfig]
  )(using ModelRegistryService, ProviderRegistry): Result[(EmbeddingClient, EmbeddingModelConfig)] = {
    val registry = summon[ProviderRegistry]
    val id       = registry.canonicalEmbeddingId(config.embeddingProvider.asString)

    for {
      descriptor <- registry.resolveEmbedding(id).left.map(withDefaultProviderHint(id, _))
      clientAndModel <- existingClient match {
        case Some(client) =>
          embeddingModel(config, descriptor, None).map(model => (client, model))
        case None =>
          for {
            providerConfig <- resolveEmbeddingProvider(id.asString)
            model          <- embeddingModel(config, descriptor, Some(providerConfig.model))
            provider       <- descriptor.build(providerConfig.copy(model = model))
          } yield (new EmbeddingClient(provider), model)
      }
      (client, model) = clientAndModel
    } yield (client, createEmbeddingModelConfig(config, descriptor, model))
  }

  /**
   * Names the module to add when the missing provider is RAG's default.
   *
   * `RAGConfig.default` embeds with OpenAI, which ships in `llm4s-openai` rather than
   * `llm4s-rag`. The registry's own error can only say "add the dependency that
   * supplies it"; a pipeline built from the default config would otherwise fail
   * without saying which one.
   */
  private def withDefaultProviderHint(id: ProviderId, error: LLMError): LLMError =
    error match
      case e: ConfigurationError if id == RAGConfig.DefaultEmbeddingProvider =>
        ConfigurationError(
          e.message + s" '${id.asString}' is RAGConfig's default embedding provider and ships in " +
            "llm4s-openai: add \"org.llm4s\" %% \"llm4s-openai\" to your dependencies, or choose a " +
            "registered provider with .withEmbeddings(\"<provider>\", \"<model>\").",
          e.missingKeys
        )
      case other => other

  private def embeddingModel(
    config: RAGConfig,
    descriptor: EmbeddingProviderDescriptor,
    resolvedModel: Option[String]
  ): Result[String] =
    config.embeddingModel
      .orElse(resolvedModel.filter(_.trim.nonEmpty))
      .orElse(descriptor.configSpec.defaultModel)
      .toRight(
        ConfigurationError(
          s"No embedding model for provider '${descriptor.id.asString}'. " +
            s"Name one with .withEmbeddings(\"${descriptor.id.asString}\", \"<model>\") " +
            "or supply it in the resolved EmbeddingProviderConfig."
        )
      )

  private def createEmbeddingModelConfig(
    config: RAGConfig,
    descriptor: EmbeddingProviderDescriptor,
    model: String
  ): EmbeddingModelConfig = {
    // A model the provider does not declare still embeds; 1536 (OpenAI's small model) is the
    // historical fallback for a dimension nothing states.
    val dims = config.embeddingDimensions.orElse(descriptor.dimensionsOf(model)).getOrElse(1536)
    EmbeddingModelConfig(model, dims)
  }

  private def createChunker(
    config: RAGConfig,
    embeddingClient: EmbeddingClient,
    modelConfig: EmbeddingModelConfig
  ): DocumentChunker =
    config.chunkingStrategy match {
      case ChunkerFactory.Strategy.Semantic =>
        ChunkerFactory.semantic(embeddingClient, modelConfig)
      case other =>
        ChunkerFactory.create(other)
    }

  private def createHybridSearcher(config: RAGConfig): Result[HybridSearcher] =
    config.pgVectorConnectionString match {
      case Some(connStr) =>
        val user        = config.pgVectorUser.getOrElse("postgres")
        val password    = config.pgVectorPassword.getOrElse("")
        val vectorTable = config.pgVectorTableName.getOrElse("vectors")

        config.pgKeywordTableName match {
          case Some(keywordTable) =>
            // Full PostgreSQL hybrid: pgvector + PostgreSQL FTS
            HybridSearcher.pgvectorShared(
              connStr,
              user,
              password,
              vectorTable,
              keywordTable,
              config.fusionStrategy
            )
          case None =>
            // pgvector + SQLite keyword index (existing behavior)
            for {
              vectorStore <- PgVectorStore(connStr, user, password, vectorTable)
              keywordIndex <- config.keywordIndexPath match {
                case Some(path) => SQLiteKeywordIndex(path)
                case None       => KeywordIndex.inMemory()
              }
            } yield HybridSearcher(vectorStore, keywordIndex, config.fusionStrategy)
        }
      case None =>
        (config.vectorStorePath, config.keywordIndexPath) match {
          case (Some(vectorPath), Some(keywordPath)) =>
            HybridSearcher.sqlite(vectorPath, keywordPath)
          case (Some(vectorPath), None) =>
            HybridSearcher.sqlite(vectorPath, vectorPath.replace(".db", "-fts.db"))
          case _ =>
            HybridSearcher.inMemory()
        }
    }

  private def createReranker(
    config: RAGConfig,
    resolveRerankerConfig: () => Result[Option[RerankProviderConfig]]
  ): Result[Option[Reranker]] =
    config.rerankingStrategy match {
      case RerankingStrategy.None =>
        Right(None)
      case RerankingStrategy.Cohere(_) =>
        resolveRerankerConfig().flatMap {
          case Some(cfg) =>
            Right(Some(RerankerFactory.cohere(cfg)))
          case None =>
            Left(
              ConfigurationError(
                "Cohere reranking selected but no reranker provider config was supplied. Provide a RerankProviderConfig via resolveRerankerConfig."
              )
            )
        }
      case RerankingStrategy.LLM =>
        config.llmClient match {
          case Some(client) =>
            Right(Some(RerankerFactory.llm(client)))
          case None =>
            Left(
              ConfigurationError(
                "LLM reranking requires an LLM client. Use .withLLM(client) when building RAG."
              )
            )
        }
    }
}
