// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordFinally
package org.llm4s.rag.loader.s3

import org.llm4s.error.{ NetworkError, ProcessingError }
import org.llm4s.rag.loader.{ DocumentRef, DocumentVersion, RawDocument, SyncableSource }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentialsProvider,
  DefaultCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  ListObjectsV2Request,
  S3Object
}

import java.io.InputStream
import java.net.URI
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }

/**
 * Document source for AWS S3.
 *
 * Reads documents from an S3 bucket with support for:
 * - Prefix filtering (e.g., "docs/", "reports/2024/")
 * - File extension filtering
 * - Automatic change detection via ETags
 * - Pagination for large buckets
 *
 * Authentication uses the AWS credential chain by default:
 * 1. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 * 2. System properties
 * 3. AWS credentials file (~/.aws/credentials)
 * 4. IAM role (for EC2/Lambda)
 *
 * Usage:
 * {{{
 * val source = S3DocumentSource("my-bucket", prefix = "docs/")
 * val loader = SourceBackedLoader(source)
 * rag.sync(loader)
 * }}}
 *
 * @param bucket S3 bucket name
 * @param prefix Key prefix to filter objects (e.g., "docs/", "reports/")
 * @param region AWS region (default: us-east-1)
 * @param extensions File extensions to include (empty = all files)
 * @param credentials Optional credentials provider (default: AWS credential chain)
 * @param metadata Additional metadata to attach to all documents
 * @param endpointOverride Optional endpoint override (for LocalStack, MinIO, etc.)
 */
final case class S3DocumentSource(
  bucket: String,
  prefix: String = "",
  region: String = "us-east-1",
  extensions: Set[String] = S3DocumentSource.defaultExtensions,
  credentials: Option[AwsCredentialsProvider] = None,
  metadata: Map[String, String] = Map.empty,
  endpointOverride: Option[String] = None
) extends SyncableSource {

  private val logger = LoggerFactory.getLogger(getClass)

  private lazy val client: S3Client = buildClient()

  private def buildClient(): S3Client = {
    val builder = S3Client
      .builder()
      .region(Region.of(region))
      .credentialsProvider(credentials.getOrElse(DefaultCredentialsProvider.create()))

    endpointOverride.foreach { endpoint =>
      builder.endpointOverride(URI.create(endpoint))
      builder.forcePathStyle(true) // Required for LocalStack/MinIO
    }

    builder.build()
  }

  override def listDocuments(): Iterator[Result[DocumentRef]] = {
    logger.info(s"Listing documents from s3://$bucket/$prefix")

    new S3ObjectIterator(client, bucket, prefix, extensions)
      .map {
        case Right(s3Object) =>
          val key = s3Object.key()
          val ref = toDocumentRef(s3Object)
          logger.debug(s"Found document: $key")
          Right(ref)
        case Left(err) =>
          Left(err)
      }
  }

  override def readDocument(ref: DocumentRef): Result[RawDocument] = {
    logger.debug(s"Reading document: ${ref.path}")

    Try {
      val request = GetObjectRequest
        .builder()
        .bucket(bucket)
        .key(ref.path)
        .build()

      val response: ResponseInputStream[GetObjectResponse] = client.getObject(request)
      try {
        val bytes = response.readAllBytes()
        RawDocument(ref, bytes)
      } finally response.close()
    } match {
      case Success(doc) =>
        logger.debug(s"Read ${doc.length} bytes from ${ref.path}")
        Right(doc)
      case Failure(ex) =>
        logger.error(s"Failed to read ${ref.path}: ${ex.getMessage}", ex)
        Left(NetworkError(s"Failed to read S3 object ${ref.path}: ${ex.getMessage}", Some(ex), "s3"))
    }
  }

  override def readDocumentStream(ref: DocumentRef): Result[InputStream] =
    Try {
      val request = GetObjectRequest
        .builder()
        .bucket(bucket)
        .key(ref.path)
        .build()

      client.getObject(request): InputStream
    } match {
      case Success(stream) => Right(stream)
      case Failure(ex) =>
        logger.error(s"Failed to open stream for ${ref.path}: ${ex.getMessage}", ex)
        Left(NetworkError(s"Failed to open S3 stream for ${ref.path}: ${ex.getMessage}", Some(ex), "s3"))
    }

  override def getVersionInfo(ref: DocumentRef): Result[DocumentVersion] =
    ref.toVersion match {
      case Some(version) => Right(version)
      case None          =>
        // If no ETag in ref, we need to fetch object metadata
        // Use headObject instead of getObject to avoid downloading the body
        // and to prevent resource leaks from unclosed streams
        Try {
          val request = HeadObjectRequest
            .builder()
            .bucket(bucket)
            .key(ref.path)
            .build()

          val response = client.headObject(request)
          DocumentVersion(
            contentHash = response.eTag().replace("\"", ""),
            timestamp = Option(response.lastModified()).map(_.toEpochMilli),
            etag = Some(response.eTag().replace("\"", ""))
          )
        } match {
          case Success(version) => Right(version)
          case Failure(ex) =>
            Left(
              ProcessingError("s3-version", s"Failed to get version info for ${ref.path}: ${ex.getMessage}", Some(ex))
            )
        }
    }

  override def description: String = s"S3(s3://$bucket/$prefix)"

  override def estimatedCount: Option[Int] = None // S3 doesn't provide cheap count

  private def toDocumentRef(s3Object: S3Object): DocumentRef = {
    val key  = s3Object.key()
    val etag = Option(s3Object.eTag()).map(_.replace("\"", ""))

    DocumentRef(
      id = s"s3://$bucket/$key",
      path = key,
      metadata = metadata ++ Map(
        "bucket" -> bucket,
        "region" -> region
      ),
      contentLength = Option(s3Object.size()).map(_.toLong),
      lastModified = Option(s3Object.lastModified()).map(_.toEpochMilli),
      etag = etag
    )
  }

  /**
   * Create a copy with different prefix.
   */
  def withPrefix(newPrefix: String): S3DocumentSource = copy(prefix = newPrefix)

  /**
   * Create a copy with different extensions filter.
   */
  def withExtensions(newExtensions: Set[String]): S3DocumentSource = copy(extensions = newExtensions)

  /**
   * Create a copy with additional metadata.
   */
  def withMetadata(additionalMetadata: Map[String, String]): S3DocumentSource =
    copy(metadata = metadata ++ additionalMetadata)

  /**
   * Create a copy with endpoint override (for LocalStack, MinIO).
   */
  def withEndpoint(endpoint: String): S3DocumentSource = copy(endpointOverride = Some(endpoint))
}

object S3DocumentSource {

  /**
   * Default file extensions to include.
   */
  val defaultExtensions: Set[String] = Set(
    "txt",
    "md",
    "markdown",
    "pdf",
    "docx",
    "doc",
    "json",
    "xml",
    "html",
    "htm",
    "csv",
    "rst"
  )

  /**
   * Create an S3DocumentSource with explicit credentials.
   */
  def withCredentials(
    bucket: String,
    accessKeyId: String,
    secretAccessKey: String,
    region: String = "us-east-1",
    prefix: String = ""
  ): S3DocumentSource =
    S3DocumentSource(
      bucket = bucket,
      prefix = prefix,
      region = region,
      credentials = Some(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
    )

  /**
   * Create an S3DocumentSource for LocalStack testing.
   */
  def forLocalStack(
    bucket: String,
    prefix: String = "",
    port: Int = 4566
  ): S3DocumentSource =
    S3DocumentSource(
      bucket = bucket,
      prefix = prefix,
      region = "us-east-1",
      endpointOverride = Some(s"http://localhost:$port"),
      credentials = Some(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
    )
}

/**
 * Iterator over S3 objects with automatic pagination.
 *
 * Returns Result[S3Object] to handle S3 API failures gracefully instead of
 * throwing exceptions that would abort the entire load/sync operation.
 */
private class S3ObjectIterator(
  client: S3Client,
  bucket: String,
  prefix: String,
  extensions: Set[String]
) extends Iterator[Result[S3Object]] {

  private val logger = LoggerFactory.getLogger(getClass)

  private var continuationToken: Option[String]  = None
  private var currentBatch: Iterator[S3Object]   = Iterator.empty
  private var hasMore: Boolean                   = true
  private var pendingError: Option[NetworkError] = None
  private var errorReturned: Boolean             = false

  override def hasNext: Boolean =
    // If we have a pending error that hasn't been returned yet, we have one more item
    if (pendingError.isDefined && !errorReturned) {
      true
    } else if (currentBatch.hasNext) {
      true
    } else if (hasMore && pendingError.isEmpty) {
      fetchNextBatch()
      // After fetching, check again - either we have items, or we have an error to return
      currentBatch.hasNext || (pendingError.isDefined && !errorReturned)
    } else {
      false
    }

  override def next(): Result[S3Object] = {
    if (!hasNext) throw new NoSuchElementException("No more S3 objects")

    // Return pending error if we have one
    pendingError match {
      case Some(err) if !errorReturned =>
        errorReturned = true
        Left(err)
      case _ =>
        Right(currentBatch.next())
    }
  }

  private def fetchNextBatch(): Unit = {
    val requestBuilder = ListObjectsV2Request
      .builder()
      .bucket(bucket)
      .prefix(prefix)
      .maxKeys(1000)

    continuationToken.foreach(requestBuilder.continuationToken)

    Try(client.listObjectsV2(requestBuilder.build())) match {
      case Success(response) =>
        val objects = response
          .contents()
          .asScala
          .iterator
          .filter(obj => !obj.key().endsWith("/")) // Skip directory markers
          .filter(obj => matchesExtension(obj.key()))

        currentBatch = objects
        continuationToken = Option(response.nextContinuationToken())
        hasMore = response.isTruncated

      case Failure(ex) =>
        // Log the error and store it to return as a Left on next iteration
        logger.error(s"Failed to list S3 objects from s3://$bucket/$prefix: ${ex.getMessage}", ex)
        pendingError = Some(
          NetworkError(
            s"Failed to list S3 objects from s3://$bucket/$prefix: ${ex.getMessage}",
            Some(ex),
            "s3"
          )
        )
        hasMore = false
        currentBatch = Iterator.empty
    }
  }

  private def matchesExtension(key: String): Boolean = {
    if (extensions.isEmpty) return true

    val lastDot = key.lastIndexOf('.')
    if (lastDot < 0) return false

    val ext = key.substring(lastDot + 1).toLowerCase
    extensions.contains(ext)
  }
}
