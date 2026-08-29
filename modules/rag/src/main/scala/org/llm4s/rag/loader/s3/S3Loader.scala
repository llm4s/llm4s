package org.llm4s.rag.loader.s3

import org.llm4s.extract.DocumentExtractor
import org.llm4s.rag.loader.{ DocumentLoader, SourceBackedLoader }
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

/**
 * Convenience factory for loading documents from AWS S3.
 *
 * S3Loader combines S3DocumentSource with SourceBackedLoader to provide
 * a simple API for S3 document ingestion.
 *
 * Usage:
 * {{{
 * // Basic usage with default credentials
 * val loader = S3Loader("my-bucket", prefix = "docs/")
 * rag.sync(loader)
 *
 * // With explicit credentials
 * val loader = S3Loader.withCredentials(
 *   bucket = "my-bucket",
 *   accessKeyId = "...",
 *   secretAccessKey = "..."
 * )
 *
 * // For LocalStack testing
 * val loader = S3Loader.forLocalStack("test-bucket")
 * }}}
 */
object S3Loader {

  /**
   * Create a DocumentLoader for S3.
   *
   * Uses the AWS default credential chain for authentication.
   *
   * @param bucket S3 bucket name
   * @param prefix Key prefix to filter objects (e.g., "docs/")
   * @param region AWS region (default: us-east-1)
   * @param extensions File extensions to include (empty = default set)
   * @param credentials Optional credentials provider
   * @param metadata Additional metadata to attach to all documents
   */
  def apply(
    bucket: String,
    prefix: String = "",
    region: String = "us-east-1",
    extensions: Set[String] = S3DocumentSource.defaultExtensions,
    credentials: Option[AwsCredentialsProvider] = None,
    metadata: Map[String, String] = Map.empty
  ): DocumentLoader = {
    val source = S3DocumentSource(
      bucket = bucket,
      prefix = prefix,
      region = region,
      extensions = extensions,
      credentials = credentials,
      metadata = metadata
    )
    SourceBackedLoader(source)
  }

  /**
   * Create a DocumentLoader for S3 with explicit credentials.
   *
   * @param bucket S3 bucket name
   * @param accessKeyId AWS access key ID
   * @param secretAccessKey AWS secret access key
   * @param region AWS region (default: us-east-1)
   * @param prefix Key prefix to filter objects
   */
  def withCredentials(
    bucket: String,
    accessKeyId: String,
    secretAccessKey: String,
    region: String = "us-east-1",
    prefix: String = ""
  ): DocumentLoader = {
    val source = S3DocumentSource.withCredentials(
      bucket = bucket,
      accessKeyId = accessKeyId,
      secretAccessKey = secretAccessKey,
      region = region,
      prefix = prefix
    )
    SourceBackedLoader(source)
  }

  /**
   * Create a DocumentLoader for LocalStack testing.
   *
   * @param bucket Bucket name (must be created in LocalStack first)
   * @param prefix Key prefix to filter objects
   * @param port LocalStack port (default: 4566)
   */
  def forLocalStack(
    bucket: String,
    prefix: String = "",
    port: Int = 4566
  ): DocumentLoader = {
    val source = S3DocumentSource.forLocalStack(bucket, prefix, port)
    SourceBackedLoader(source)
  }

  /**
   * Create a DocumentLoader with a custom extractor.
   *
   * @param bucket S3 bucket name
   * @param prefix Key prefix
   * @param extractor Custom document extractor
   */
  def withExtractor(
    bucket: String,
    prefix: String = "",
    extractor: DocumentExtractor
  ): DocumentLoader = {
    val source = S3DocumentSource(bucket, prefix)
    SourceBackedLoader(source, extractor)
  }

  /**
   * Get the underlying S3DocumentSource for advanced configuration.
   *
   * Use this when you need to configure S3-specific options like
   * endpoint override, then wrap with SourceBackedLoader.
   *
   * @param bucket S3 bucket name
   * @param prefix Key prefix
   */
  def source(bucket: String, prefix: String = ""): S3DocumentSource =
    S3DocumentSource(bucket, prefix)
}
